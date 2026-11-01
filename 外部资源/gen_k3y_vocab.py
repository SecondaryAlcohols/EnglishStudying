# -*- coding: utf-8 -*-
"""
把 k3y 的 MySQL dump 转成 EnglishStudying 能直接导入的 CSV。

数据源：外部资源/k3y-master/database/k3y.sql
  word(id, word, count, level, mean, mp3)     6246 行
  sentence(id, year, sentence, translate)     3457 行

范围：level 0-2（level 与真题词频强相关：L0 平均出现 26 次，L3 只有 2.3 次）
例句：原项目没有单词↔句子关联表，靠 ' word ' 子串匹配现算；这里沿用同一规则并加过滤
"""
import os, re, csv, io, collections

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "k3y-master", "database", "k3y.sql")
DST = os.path.join(HERE, "k3y-考研词库-L0-L2.csv")

# ---- MySQL dump 解析 ----

def split_values(s):
    vals, buf, i, in_str = [], [], 0, False
    while i < len(s):
        c = s[i]
        if in_str:
            if c == "\\":
                nxt = s[i + 1] if i + 1 < len(s) else ""
                buf.append({'n': '\n', 't': '\t', 'r': '\r', '0': '\0'}.get(nxt, nxt))
                i += 2
                continue
            if c == "'":
                if i + 1 < len(s) and s[i + 1] == "'":
                    buf.append("'"); i += 2; continue
                in_str = False; i += 1; continue
            buf.append(c)
        else:
            if c == "'":
                in_str = True
            elif c == ",":
                vals.append("".join(buf)); buf = []
            elif not c.isspace():
                buf.append(c)
        i += 1
    vals.append("".join(buf))
    return vals


def rows(table, text):
    out = []
    for line in text.splitlines():
        if not line.startswith("INSERT INTO `%s` VALUES " % table):
            continue
        body = line[line.index("VALUES ") + 7:].rstrip().rstrip(";")
        for m in re.finditer(r"\(((?:[^'\\]|\\.|'(?:[^'\\]|\\.)*')*)\)", body):
            out.append(split_values(m.group(1)))
    return out


# ---- 词性归一化 ----

# 长词优先，否则「及物动词」会被「动词」先吃掉
POS_MAP = [
    ("及物动词", "vt."), ("不及物动词", "vi."),
    ("形容词", "adj."), ("副词", "adv."), ("名词", "n."), ("动词", "v."),
    ("介系词", "prep."), ("介词", "prep."), ("代名词", "pron."), ("代词", "pron."),
    ("连接词", "conj."), ("连词", "conj."), ("数词", "num."), ("冠词", "art."),
    ("感叹词", "int."), ("关系词", "rel."), ("助动词", "aux."),
    ("aux.", "aux."), ("abbr.", "abbr."), ("prep.", "prep."), ("conj.", "conj."),
    ("pron.", "pron."), ("adj.", "adj."), ("adv.", "adv."),
]
POS_RE = re.compile("^(" + "|".join(re.escape(p) for p, _ in POS_MAP) + r")\s*[，,]?\s*")
SEP_RE = re.compile(r"^\s*[&、,，/]\s*")


def parse_mean(mean):
    """'及物动词， （使）加快; | 副词 大约;' -> [(['vt.'], '（使）加快'), (['adv.'], '大约')]"""
    groups = []
    for raw in mean.split("|"):
        g = raw.strip()
        if not g:
            continue
        tags = []
        while True:
            m = POS_RE.match(g)
            if not m:
                break
            tags.append(dict(POS_MAP)[m.group(1)])
            g = g[m.end():]
            g = SEP_RE.sub("", g)
        senses = [s.strip() for s in g.split(";")]
        senses = [s for s in senses if s]
        if senses or tags:
            groups.append((tags, "；".join(senses)))
    return groups


# ---- 例句挑选 ----

BOILER = re.compile(
    r"ANSWER SHEET|blackening|Choose the best|mark your answer|corresponding letter"
    r"|Read the (?:passages|following)|write your answer|DIRECTIONS|Write your translation"
    r"|extra choices|following questions|in the brackets",
    re.I,
)
# 完形填空的空格在原始数据里变成了裸数字（"also 13 changes" / "the American 1."）
CLOZE_NUM = re.compile(r"(?<![\w.])\d{1,2}(?![\w])")
# 选择题的选项字母残留（"...Who They Are G."）
TRAILING_OPTION = re.compile(r"\s[A-Z]\.?[\"'”]?$")
# 真正的句子以大写字母开头（允许前面有一个引号）。原始数据里大量条目是从段落中间
# 截断的片段，例如 "have also voiced their concerns at meetings." —— 这条规则把它们挡在外面。
LEAD_OK = re.compile(r"^[\"'“‘]?[A-Z]")
WORDLIKE = re.compile(r"[A-Za-z]{2,}")


def is_clean(eng):
    if not (40 <= len(eng) <= 200):
        return False
    if BOILER.search(eng) or CLOZE_NUM.search(eng) or TRAILING_OPTION.search(eng):
        return False
    if not LEAD_OK.match(eng):
        return False
    # 原始数据里句子常被从引号中间截断，引号数不成对就说明是残句
    if eng.count('"') % 2 or eng.count("“") != eng.count("”"):
        return False
    if not eng.rstrip().endswith((".", "?", "!")):
        return False
    if len(WORDLIKE.findall(eng)) < 6:
        return False
    return True


def pick_example(word, sentences):
    """优先取最短的合适句子：短句更适合当卡片例句。"""
    pat = re.compile(r"\b" + re.escape(word) + r"\b", re.I)
    best = None
    for eng, zh in sentences:
        if not is_clean(eng) or not pat.search(eng):
            continue
        if best is None or len(eng) < len(best[0]):
            best = (eng, zh)
    if best is None:
        return ""
    eng, zh = best
    # 与 sample-vocab.csv 的写法一致：英文句 + 空格 + 中文译文
    return (eng + " " + zh).strip()


# ---- 主流程 ----

LEVEL_DECK = {
    0: ("真题高频 L0", "高频", "真题里出现 21 次以上"),
    1: ("真题次高频 L1", "次高频", "真题里出现 11-20 次"),
    2: ("真题中频 L2", "中频", "真题里出现 4-10 次"),
}

text = open(SRC, encoding="utf-8", errors="replace").read()
words = rows("word", text)
sents = [(s[2].strip(), s[3].strip()) for s in rows("sentence", text) if s[2].strip()]

picked = [w for w in words if int(w[3]) in LEVEL_DECK]
# 同一个词在不同 level 下不会重复出现（已验证无重复 headword）
picked.sort(key=lambda w: (int(w[3]), -int(w[2]), w[1].lower()))

buf = io.StringIO()
wr = csv.writer(buf, lineterminator="\n")
wr.writerow(["word", "pos", "meaning", "tags", "frequency", "example", "deck"])

stats = collections.Counter()
for w in picked:
    headword, freq, level, mean = w[1].strip(), int(w[2]), int(w[3]), w[4]
    deck, tag, _ = LEVEL_DECK[level]
    groups = parse_mean(mean)

    if not groups:
        pos, meaning = "", ""
    else:
        first_tags, first_senses = groups[0]
        pos = " / ".join(first_tags)
        lines = [first_senses] if first_senses else []
        # 其余词性内联进释义行，与 docs/sample-vocab.csv 里 present 的写法一致
        for tags, senses in groups[1:]:
            prefix = " / ".join(tags)
            lines.append((prefix + " " + senses).strip())
        meaning = "\n".join(l for l in lines if l)

    ex = pick_example(headword, sents)
    if ex:
        stats["with_example"] += 1

    wr.writerow([headword, pos, meaning, tag, freq, ex, deck])
    stats["level_%d" % level] += 1

out = buf.getvalue()
open(DST, "w", encoding="utf-8-sig", newline="").write(out)

print("rows written :", len(picked))
print("  level 0    :", stats["level_0"])
print("  level 1    :", stats["level_1"])
print("  level 2    :", stats["level_2"])
print("rows w/ example:", stats["with_example"],
      "(%.0f%%)" % (100.0 * stats["with_example"] / max(1, len(picked))))
print("bytes        :", len(out.encode("utf-8-sig")))
print("->", DST)
