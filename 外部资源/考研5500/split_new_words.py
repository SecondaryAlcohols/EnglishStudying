#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从补全结果里筛出「手机词库里没有的词」，生成一份可直接导入的新词表 CSV。

    python split_new_words.py --merged 考研5500-merged.csv \
        --phone-db ../../backups/phone-2026-10-25_1010/checked/english_studying.db \
        --deck "考研大纲 L3" --tag-prefix 大纲 --out 考研5500-L3.csv

只读手机库的词表（headword），按小写比对，命中的词一律不进来 ——
这样导入时不会覆盖任何已有词条的释义/标签/进度。
"""

from __future__ import annotations

import argparse
import csv
import sqlite3

COLUMNS = ["word", "pos", "meaning", "phonetic", "example", "derivation",
           "tags", "frequency", "deck", "mnemonic", "root", "synonyms"]


def main() -> int:
    ap = argparse.ArgumentParser(description="筛出手机词库里没有的词，生成新词表 CSV")
    ap.add_argument("--merged", required=True)
    ap.add_argument("--phone-db", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--deck", required=True, help="写进 deck 列的词表名")
    ap.add_argument("--tag-prefix", default="", help="给 tags 前面加一个前缀，如「大纲」")
    args = ap.parse_args()

    conn = sqlite3.connect(f"file:{args.phone_db}?mode=ro", uri=True)
    phone = {r[0].strip().lower() for r in conn.execute("SELECT headword FROM words")}
    conn.close()

    rows = list(csv.DictReader(open(args.merged, encoding="utf-8-sig")))
    new_rows = [r for r in rows if r["word"].strip().lower() not in phone]

    for r in new_rows:
        r["deck"] = args.deck
        if args.tag_prefix:
            tags = [args.tag_prefix] + [t for t in (r.get("tags") or "").split() if t]
            r["tags"] = " ".join(dict.fromkeys(tags))

    with open(args.out, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=COLUMNS, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(new_rows)

    with_audio = sum(1 for r in new_rows if (r.get("phonetic") or "").strip())
    print(f"原表 {len(rows)} 词，手机已有 {len(rows) - len(new_rows)}，新词 {len(new_rows)}")
    print(f"新词里有音标 {with_audio}；词表名「{args.deck}」→ {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
