# -*- coding: utf-8 -*-
"""考研词库补全管线：
1. 形态切分自动生成 root / mnemonic 列（只输出词典能确证的切分）；
2. 库内同根词匹配生成 derivation 列；
3. synonyms.txt 词组数据生成 synonyms 列（只保留库内词）；
4. OVERRIDES 手工条目最终覆盖。
输出：k3y-考研词库-补全版.csv + coverage_report.txt
"""
import csv, re, sys, os
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from morphemes import PREFIXES, SUFFIXES, ROOTS, OVERRIDES, IRREGULAR

SRC = os.path.normpath(os.path.join(HERE, "..", "k3y-考研词库-L0-L2.csv"))
OUT = os.path.normpath(os.path.join(HERE, "..", "k3y-考研词库-补全版.csv"))
REPORT = os.path.join(HERE, "coverage_report.txt")
SYNONYM_FILE = os.path.join(HERE, "synonyms.txt")

POS_RE = re.compile(r"^\s*(n|v|vi|vt|adj|adv|prep|conj|pron|art|num|interj|aux)\.\s*")
SILENT_SUFFIXES = {"e", "ue"}

def clean_word(w):
    return re.sub(r"[^a-z]", "", w.lower())

def first_sense(meaning):
    """取释义第一个义项，去掉词性标记。"""
    if not meaning:
        return ""
    first = meaning.split("\n")[0]
    for sep in ("；", ";", "，", ","):
        if sep in first:
            first = first.split(sep)[0]
            break
    first = POS_RE.sub("", first).strip(" 　.。;；,，")
    return first

def suffix_short(suf_mean):
    """名词后缀（性质） -> 性质"""
    if "（" in suf_mean:
        return suf_mean.split("（", 1)[1].rstrip("）")
    return suf_mean.replace("后缀", "").strip()

def strip_inflection(w):
    """屈折还原，返回词干集合。"""
    stems = {w}
    for suf, rep in (("ies", "y"), ("ied", "y"), ("ives", "ive"), ("ers", "er"),
                     ("s", ""), ("es", ""), ("ed", ""), ("d", ""), ("ing", "")):
        if w.endswith(suf) and len(w) - len(suf) >= 3:
            if suf == "s" and w.endswith("ss"):
                continue
            stem = w[:-len(suf)] + rep
            stems.add(stem)
            # 双写辅音还原：running -> runn -> run
            if len(stem) >= 4 and stem[-1] == stem[-2] and stem[-1] not in "aeiouwxy":
                stems.add(stem[:-1])
    if w in IRREGULAR:
        stems.add(IRREGULAR[w])
    return stems

# ---------------------------------------------------------------- 形态切分
# 后缀链可切性缓存
suffix_memo = {}

def suffix_chain(rest, depth=0):
    """rest 能否完全由后缀构成，返回所有切法 [("s", 后缀)...]。"""
    if rest == "":
        return [[]]
    if depth > 3:
        return []
    if rest in suffix_memo:
        return suffix_memo[rest]
    out = []
    for suf in sorted(SUFFIXES, key=len, reverse=True):
        if rest.endswith(suf) and len(rest) - len(suf) >= 0:
            for tail in suffix_chain(rest[: -len(suf)], depth + 1):
                out.append(tail + [("s", suf)])
    suffix_memo[rest] = out
    return out

def word_variants(lw):
    """同一词的候选形态：原形、去复数、双写辅音折叠。"""
    variants = [lw]
    if lw.endswith("ies") and len(lw) > 4:
        variants.append(lw[:-3] + "y")
    elif lw.endswith("s") and not lw.endswith("ss") and not lw.endswith("us") and len(lw) > 3:
        variants.append(lw[:-1])
        if lw.endswith("es") and len(lw) > 4:
            variants.append(lw[:-2])
    # 双写辅音折叠：current -> curent（供 curr/cur 匹配）
    import re as _re
    for m in _re.finditer(r"(.)\1", lw):
        if m.group(1) not in "aeiou":
            variants.append(lw[:m.start() + 1] + lw[m.end():])
    # 剥 ed/ing 后再补哑音 e：influenced -> influence
    extra = []
    for v in variants:
        for suf in ("ed", "ing", "er", "est"):
            if v.endswith(suf) and len(v) - len(suf) >= 3:
                extra.append(v[: -len(suf)])
                extra.append(v[: -len(suf)] + "e")
    variants.extend(extra)
    return variants

def decompose(word):
    """返回 [(prefix_parts, root, [suffixes]), ...]，所有合法切分。prefix 最多叠两层。"""
    results = []
    cand_prefixes = [()]
    for p in PREFIXES:
        if word.startswith(p):
            cand_prefixes.append((p,))
            rest = word[len(p):]
            for p2 in PREFIXES:
                if p2 and rest.startswith(p2):
                    cand_prefixes.append((p, p2))
    cand_prefixes = sorted(set(cand_prefixes), key=lambda t: sum(len(x) for x in t), reverse=True)
    for pre_parts in cand_prefixes:
        stem = word[sum(len(x) for x in pre_parts):]
        if len(stem) < 2:
            continue
        for root in sorted(ROOTS, key=len, reverse=True):
            if not stem.startswith(root) or len(root) < 2:
                continue
            rest = stem[len(root):]
            for chain in suffix_chain(rest):
                results.append((pre_parts, root, chain))
            # 词根+词根 复合：purpose = pur+pose
            for root2 in sorted(ROOTS, key=len, reverse=True):
                if root2 != root and rest.startswith(root2):
                    for chain in suffix_chain(rest[len(root2):]):
                        results.append((pre_parts, root, [("r", root2)] + chain))
    # 去重并排序：后缀越少越好、词根越长越好
    seen, uniq = set(), []
    for r in results:
        key = (r[0], r[1], tuple(r[2]))
        if key not in seen:
            seen.add(key)
            uniq.append(r)
    uniq.sort(key=lambda r: (len(r[2]) + len(r[0]), -len(r[1]), -sum(len(x) for x in r[0])))
    return uniq

def decompose_best(lw):
    """对词的各形态变体尝试切分，返回最优 (prefix_parts, root, chain, variant)。"""
    for v in word_variants(lw):
        decs = decompose(v)
        if decs:
            pre, rt, chain = decs[0]
            return (pre, rt, chain, v)
    return None

def _piece(kind, key):
    """渲染链条上的一项：词根 / 后缀。返回 (root列片段, 巧记片段)。"""
    if kind == "r":
        return f"-{key}- {ROOTS[key]}", f"{key}({ROOTS[key]})"
    if key in SILENT_SUFFIXES:  # 哑音字母不展示
        return None, None
    mean = suffix_short(SUFFIXES[key])
    return f"-{key} {mean}", f"{key}({mean})"

def root_column(pre_parts, root, chain):
    parts = [f"{p}- {PREFIXES[p]}" for p in pre_parts]
    parts.append(f"-{root}- {ROOTS[root]}")
    for kind, key in chain:
        col, _ = _piece(kind, key)
        if col:
            parts.append(col)
    return " + ".join(parts)

def mnemonic_column(pre_parts, root, chain, sense):
    parts = [f"{p}({PREFIXES[p]})" for p in pre_parts]
    parts.append(f"{root}({ROOTS[root]})")
    for kind, key in chain:
        _, mn = _piece(kind, key)
        if mn:
            parts.append(mn)
    return "+".join(parts) + f"→{sense}" if sense else "+".join(parts)

# ---------------------------------------------------------------- derivation
def build_related(words):
    """库内同根/变形词。返回 word -> [(related_word, score)]"""
    # 词干 -> 词集合
    stem_index = defaultdict(set)
    info = {}
    for w in words:
        lw = clean_word(w["word"])
        if not lw:
            continue
        stems = strip_inflection(lw)
        info[lw] = (int(w["frequency"] or 0), w)
        for s in stems:
            stem_index[s].add(lw)
    pairs = defaultdict(set)
    lw_list = list(info.keys())
    stem_list = list(stem_index.keys())
    # 规则1：共享词干（屈折，不进 derivation 列，仅排除）
    inflection = defaultdict(set)
    for s, ws in stem_index.items():
        if len(ws) > 1:
            for a in ws:
                for b in ws:
                    if a != b:
                        inflection[a].add(b)
    # 规则2：stem(u) 是 stem(v) 的前缀（consider ⊂ considerable）
    # 余部必须是像后缀的结尾，防止 count/country 这类假同根
    deriv_tails = set(SUFFIXES) | {"able", "ible", "ment", "ness", "ation", "ition",
                                   "ution", "ity", "ance", "ence", "ancy", "ency",
                                   "ical", "ular", "icular", "hood", "ship", "dom"}
    lw_set = set(lw_list)
    for u in lw_list:
        us = strip_inflection(u)
        for stem in us:
            for L in range(3, len(stem) + 1):
                head = stem[:L]
                if head not in stem_index:
                    continue
                tail = stem[L:]
                tail_ok = tail == "" or tail in deriv_tails
                if not tail_ok:
                    continue
                for v in stem_index[head]:
                    if v != u and v not in inflection[u]:
                        pairs[u].add(v)
                        pairs[v].add(u)
    # 规则3：共同词头≥5，双侧余部≤2 且至少一侧是 e/ce/cy/s 类尾（absent/absence）
    tail_ok = re.compile(r"^(e|es|ce|cy|cy|s|y|ty|ce|t|d|k|ve|al)$")
    lw_sorted = sorted(lw_list)
    for i, u in enumerate(lw_sorted):
        for v in lw_sorted[i + 1:]:
            if v in inflection.get(u, ()):  # 屈折不重复
                continue
            uu, vv = u, v
            if len(uu) > len(vv):
                uu, vv = vv, uu
            # 共同前缀长度
            n = 0
            for a, b in zip(uu, vv):
                if a != b:
                    break
                n += 1
            if n < 5:
                continue
            rem_u, rem_v = uu[n:], vv[n:]
            if len(rem_u) <= 2 and len(rem_v) <= 2 and (tail_ok.match(rem_u) or tail_ok.match(rem_v)):
                pairs[u].add(v)
                pairs[v].add(u)
    return pairs, inflection, info

def derivation_column(word, pairs, inflection, info, limit=3):
    lw = clean_word(word)
    # 不规则屈折形式（felt/went 等）单独成词义时不进派生列
    cands = [v for v in pairs.get(lw, ()) if v != lw and v not in IRREGULAR]
    cands.sort(key=lambda v: -info[v][0])
    parts = []
    for v in cands[:limit]:
        _, winfo = info[v]
        sense = first_sense(winfo["meaning"]) or winfo["pos"]
        parts.append(f"{v} {winfo['pos']} {sense}".strip())
    return "；".join(parts)

# ---------------------------------------------------------------- synonyms
def build_synonyms(words):
    group_map = {}
    if os.path.exists(SYNONYM_FILE):
        display = {}  # clean -> 原词形
        for w in words:
            cw = clean_word(w["word"])
            if cw:
                display[cw] = w["word"]
        lib = set(display)
        for line in open(SYNONYM_FILE, encoding="utf-8"):
            ws = [clean_word(x) for x in re.split(r"[;；,，]", line) if x.strip()]
            ws = [w for w in ws if w in lib]
            for w in ws:
                group_map[w] = [display[x] for x in ws if x != w]
    return group_map

def override_lookup(lw):
    """手工覆盖表查询：先查原词，再查剥了屈折的词干（experts->expert, ignored->ignore）。"""
    if lw in OVERRIDES:
        return OVERRIDES[lw]
    cands = []
    if lw.endswith("s") and not lw.endswith("ss"):
        cands += [lw[:-1], lw[:-2], lw[:-3] + "y"]
    for suf in ("ed", "ing"):
        if lw.endswith(suf):
            cands += [lw[: -len(suf)], lw[: -len(suf)] + "e"]
    for stem in cands:
        if stem and stem in OVERRIDES:
            return OVERRIDES[stem]
    return None

# ---------------------------------------------------------------- 主流程
def main():
    with open(SRC, encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    fields = list(rows[0].keys())
    for col in ("mnemonic", "root", "derivation", "synonyms"):
        if col not in fields:
            fields.append(col)

    pairs, inflection, info = build_related(rows)
    syn_map = build_synonyms(rows)

    stats = defaultdict(int)
    report = []
    no_morph = []  # 形态切分失败，待手工

    for r in rows:
        w = r["word"]
        lw = clean_word(w)
        root, mnem, deriv, syn = "", "", "", ""

        if lw and override_lookup(lw):
            o = override_lookup(lw)
            root, mnem, deriv, syn = o
            stats["override"] += 1
        else:
            best = decompose_best(lw) if lw else None
            if best:
                pre, rt, chain, _v = best
                root = root_column(pre, rt, chain)
                mnem = mnemonic_column(pre, rt, chain, first_sense(r["meaning"]))
                stats["morph"] += 1
            else:
                no_morph.append((int(r["frequency"] or 0), w))
                stats["no_morph"] += 1

        if not deriv:
            deriv = derivation_column(w, pairs, inflection, info)
            if deriv:
                stats["deriv"] += 1
        if not syn:
            cands = syn_map.get(lw, [])[:3]
            if cands:
                syn = "；".join(cands)
                stats["syn"] += 1

        r["root"], r["mnemonic"], r["derivation"], r["synonyms"] = root, mnem, deriv, syn

    with open(OUT, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    no_morph.sort(reverse=True)
    with open(REPORT, "w", encoding="utf-8") as f:
        f.write("=== 统计 ===\n")
        f.write(f"总词数 {len(rows)}\n")
        for k, v in sorted(stats.items()):
            f.write(f"{k}: {v}\n")
        f.write(f"\n=== 形态切分失败（按重要度排序，前200）===\n")
        for freq, w in no_morph[:200]:
            f.write(f"{w}\t{freq}\n")
        f.write(f"\n=== 切分样例（抽查） ===\n")
        sample = ["predict", "security", "occur", "current", "transport", "return",
                  "visible", "audience", "action", "solar", "rebel", "position",
                  "important", "different", "absent"]
        idx = {clean_word(r["word"]): r for r in rows}
        for s in sample:
            r = idx.get(s)
            if r:
                f.write(f"{s} | root: {r['root']} | mnem: {r['mnemonic']} | deriv: {r['derivation']}\n")
    print(f"完成。统计: {dict(stats)}，切分失败 {len(no_morph)} 个 -> {REPORT}")

if __name__ == "__main__":
    main()
