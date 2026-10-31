#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 isdict-api 批量补全词条，输出可直接导入 App 的 CSV。

    python enrich_with_isdict.py --base-url http://localhost:8080 \
        --words 考研5500词表.csv --out 考研5500-isdict.csv

只依赖标准库。默认按 100 个词一批调用 POST /api/v1/words/batch，
输出列名对齐 App 的导入字段（word/pos/meaning/phonetic/example/derivation/tags/frequency/deck）。

支持断点续跑：--out 已存在时，里面出现过的词会被跳过。
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.request

# isdict 的 pos 代码 → 常见词性缩写（和 k3y 词库的写法一致）
POS_ABBR = {
    "noun": "n.", "verb": "v.", "adjective": "a.", "adverb": "ad.",
    "pronoun": "pron.", "preposition": "prep.", "conjunction": "conj.",
    "article": "art.", "interjection": "int.", "determiner": "det.",
    "numeral": "num.", "modal": "aux.v.", "auxiliary": "aux.v.",
    "particle": "part.", "phrasal_verb": "phr.v.", "idiom": "idiom",
    "abbreviation": "abbr.", "contraction": "contr.", "affix": "affix",
}

FORM_TYPE_ZH = {
    "past": "过去式", "past_participle": "过去分词", "present_3rd": "第三人称单数",
    "gerund": "现在分词", "infinitive": "不定式", "plural": "复数",
    "possessive": "所有格", "comparative": "比较级", "superlative": "最高级",
}

SCHOOL_LEVEL_ZH = {1: "初中", 2: "高中", 3: "大学"}

COLUMNS = ["word", "pos", "meaning", "phonetic", "example", "derivation", "tags", "frequency", "deck"]


def load_words(path: str) -> list[str]:
    """从 CSV（取 word 列）或纯文本（每行一个词）读词，保持原顺序并去重。"""
    words: list[str] = []
    seen: set[str] = set()
    if path.lower().endswith(".csv"):
        with open(path, encoding="utf-8-sig", newline="") as f:
            for row in csv.DictReader(f):
                w = (row.get("word") or "").strip()
                if w and w.lower() not in seen:
                    seen.add(w.lower())
                    words.append(w)
    else:
        with open(path, encoding="utf-8-sig") as f:
            for line in f:
                w = line.strip()
                if w and w.lower() not in seen:
                    seen.add(w.lower())
                    words.append(w)
    return words


def already_done(path: str) -> set[str]:
    if not os.path.exists(path):
        return set()
    with open(path, encoding="utf-8-sig", newline="") as f:
        return {(row.get("word") or "").strip().lower() for row in csv.DictReader(f) if row.get("word")}


def opener_for(base_url: str) -> urllib.request.OpenerDirector:
    """本机地址绕过系统代理 —— Windows 上常见的 http_proxy 会把 127.0.0.1 也劫走。"""
    host = base_url.split("//", 1)[-1].split("/", 1)[0].split(":")[0].lower()
    if host in ("localhost", "127.0.0.1", "::1"):
        return urllib.request.build_opener(urllib.request.ProxyHandler({}))
    return urllib.request.build_opener()


def post_json(opener, url: str, payload: dict, timeout: int) -> dict:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json", "Accept": "application/json"}
    )
    with opener.open(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def with_retry(fn, retries: int, wait: float):
    last: Exception | None = None
    for attempt in range(retries + 1):
        try:
            return fn()
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, json.JSONDecodeError) as exc:
            last = exc
            if attempt < retries:
                time.sleep(wait * (attempt + 1))
    raise last  # type: ignore[misc]


def pick_phonetic(item: dict, accent: str | None) -> str:
    prons = item.get("pronunciations") or []
    if not prons:
        return ""
    chosen = None
    if accent:
        same = [p for p in prons if (p.get("accent") or "").lower() == accent]
        chosen = next((p for p in same if p.get("is_primary")), same[0] if same else None)
    if chosen is None:
        chosen = next((p for p in prons if p.get("is_primary")), prons[0])
    ipa = (chosen.get("ipa") or "").strip()
    if not ipa:
        return ""
    return ipa if ipa.startswith(("/", "[")) else f"/{ipa}/"


def build_meaning(item: dict) -> str:
    lines: list[str] = []
    for sense in item.get("senses") or []:
        zh = [g.get("text_zh_hans", "").strip() for g in sense.get("definitions_zh") or []]
        zh = [t for t in zh if t]
        if not zh:
            en = [g.get("text_en", "").strip() for g in sense.get("definitions_en") or []]
            zh = [t for t in en if t]
        if not zh:
            continue
        abbr = POS_ABBR.get((sense.get("pos") or "").lower(), "")
        line = f"{abbr} {'；'.join(zh)}".strip()
        if line not in lines:
            lines.append(line)

    if not lines:
        # 没有义项时退回词条级翻译 / 扁平释义
        if item.get("translation_zh"):
            lines.append(item["translation_zh"].strip())
        else:
            flat = [d.get("text_zh_hans", "").strip() for d in item.get("entry_definitions") or []]
            flat = [t for t in flat if t]
            if flat:
                lines.append("；".join(dict.fromkeys(flat)))
    return "\n".join(lines)


def build_examples(item: dict, limit: int = 2) -> str:
    out: list[str] = []
    for row in (item.get("entry_examples") or [])[:limit]:
        en = (row.get("sentence_en") or "").strip()
        zh = (row.get("sentence_zh_hans") or "").strip()
        if en:
            out.append(f"{en}｜{zh}" if zh else en)
    return "\n".join(out)


def build_derivations(item: dict) -> str:
    parts: list[str] = []
    for v in item.get("variants") or []:
        text = (v.get("form_text") or "").strip()
        if not text:
            continue
        label = FORM_TYPE_ZH.get((v.get("form_type") or "").lower(), "")
        parts.append(f"{text}({label})" if label else text)
    return "；".join(dict.fromkeys(parts))


def build_tags(item: dict) -> str:
    tags: list[str] = []
    cet = item.get("cet_level") or 0
    if cet in (4, 6):
        tags.append(f"CET{cet}")
    oxford = item.get("oxford_level") or 0
    if oxford == 1:
        tags.append("牛津3000")
    elif oxford == 2:
        tags.append("牛津5000")
    stars = item.get("collins_stars") or 0
    if stars:
        tags.append(f"柯林斯{stars}星")
    cefr = (item.get("cefr_level_name") or "").strip()
    if cefr:
        tags.append(f"CEFR {cefr}")
    school = item.get("school_level") or 0
    if school in SCHOOL_LEVEL_ZH:
        tags.append(SCHOOL_LEVEL_ZH[school])
    # 非标准扩展：本地服务会把 ECDICT 的考试标签放在这里（考研/托福/雅思…）
    for extra in item.get("tags") or []:
        if extra and extra not in tags:
            tags.append(str(extra))
    return " ".join(tags)


def to_row(item: dict, accent: str | None, deck: str) -> dict:
    frequency = item.get("frequency_rank") or 0
    return {
        "word": item.get("headword", ""),
        "pos": POS_ABBR.get((item.get("pos") or "").lower(), item.get("pos") or ""),
        "meaning": build_meaning(item),
        "phonetic": pick_phonetic(item, accent),
        "example": build_examples(item),
        "derivation": build_derivations(item),
        "tags": build_tags(item),
        "frequency": str(frequency) if frequency else "",
        "deck": deck,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="用 isdict-api 批量补全词条")
    ap.add_argument("--base-url", default="http://localhost:8080", help="isdict-api 根地址")
    ap.add_argument("--words", required=True, help="词表：CSV（取 word 列）或纯文本")
    ap.add_argument("--out", required=True, help="输出 CSV（存在则续跑）")
    ap.add_argument("--batch-size", type=int, default=100, help="每批词数，上限受 API_BATCH_MAX_SIZE 限制")
    ap.add_argument("--accent", default="british", help="音标口音：british/american/...；传 none 则不筛")
    ap.add_argument("--deck", default="考研5500", help="写进 deck 列的词表名")
    ap.add_argument("--timeout", type=int, default=30)
    ap.add_argument("--retries", type=int, default=3)
    ap.add_argument("--limit", type=int, default=0, help="只跑前 N 个词（调试用）")
    args = ap.parse_args()

    accent = None if args.accent.lower() in ("none", "") else args.accent.lower()
    words = load_words(args.words)
    if args.limit:
        words = words[: args.limit]
    done = already_done(args.out)
    todo = [w for w in words if w.lower() not in done]
    print(f"词表 {len(words)} 个，已完成 {len(done)} 个，待处理 {len(todo)} 个")

    if not todo:
        return 0

    opener = opener_for(args.base_url)
    url = args.base_url.rstrip("/") + "/api/v1/words/batch"
    new_file = not os.path.exists(args.out)
    not_found: list[str] = []
    failed = 0

    with open(args.out, "a", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=COLUMNS)
        if new_file:
            writer.writeheader()

        for start in range(0, len(todo), args.batch_size):
            batch = todo[start : start + args.batch_size]
            payload = {
                "words": batch,
                "include_variants": True,
                "include_pronunciations": True,
                "include_senses": True,
            }
            try:
                resp = with_retry(
                    lambda: post_json(opener, url, payload, args.timeout), args.retries, 1.0
                )
            except Exception as exc:  # 单批失败不该让整轮白跑
                failed += len(batch)
                print(f"[{start + 1}-{start + len(batch)}] 请求失败：{exc}", file=sys.stderr)
                continue

            if not resp.get("success"):
                failed += len(batch)
                err = resp.get("error") or {}
                print(f"[{start + 1}-{start + len(batch)}] 接口报错：{err}", file=sys.stderr)
                continue

            for item in resp.get("data") or []:
                writer.writerow(to_row(item, accent, args.deck))
            not_found.extend((resp.get("meta") or {}).get("not_found") or [])
            f.flush()
            print(f"[{min(start + args.batch_size, len(todo))}/{len(todo)}] 写入 {len(resp.get('data') or [])} 条")

    if not_found:
        path = os.path.splitext(args.out)[0] + "-not-found.txt"
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(not_found) + "\n")
        print(f"未收录 {len(not_found)} 个词 → {path}")
    if failed:
        print(f"{failed} 个词因请求失败未处理，重跑同一命令即可续上", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
