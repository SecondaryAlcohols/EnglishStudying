#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 ECDICT 补全结果和 k3y 词库合并：例句、巧记、词根、近义词从 k3y 补。

ECDICT 只有释义没有例句，而 k3y 那份 CSV 里带双语例句和巧记/词根/近义词。
两边按词（大小写不敏感）对齐，只**填空白**、不覆盖已有值。

    python merge_k3y.py --main 考研5500-isdict.csv \
        --k3y ../k3y-考研词库-补全版.csv --out 考研5500-merged.csv
"""

from __future__ import annotations

import argparse
import csv

# 主表已有的列 + 从 k3y 借来的列
COLUMNS = ["word", "pos", "meaning", "phonetic", "example", "derivation",
           "tags", "frequency", "deck", "mnemonic", "root", "synonyms"]

# k3y 列 → 主表列（同名列直接补，其余按这里映射）
BORROW = {"example": "example", "mnemonic": "mnemonic", "root": "root",
          "derivation": "derivation", "synonyms": "synonyms"}


def read_csv(path: str) -> list[dict]:
    with open(path, encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def main() -> int:
    ap = argparse.ArgumentParser(description="合并 ECDICT 补全结果与 k3y 词库")
    ap.add_argument("--main", required=True, help="ECDICT 补全后的 CSV")
    ap.add_argument("--k3y", required=True, help="k3y 词库 CSV")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    main_rows = read_csv(args.main)
    k3y_rows = {r["word"].strip().lower(): r for r in read_csv(args.k3y) if r.get("word")}

    filled = {"example": 0, "mnemonic": 0, "root": 0, "synonyms": 0, "derivation": 0}
    for row in main_rows:
        src = k3y_rows.get(row["word"].strip().lower())
        if not src:
            continue
        for src_key, dst_key in BORROW.items():
            if not (row.get(dst_key) or "").strip() and (src.get(src_key) or "").strip():
                row[dst_key] = src[src_key]
                filled[src_key] += 1

    with open(args.out, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=COLUMNS, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(main_rows)

    total = len(main_rows)
    print(f"合并 {total} 行 → {args.out}")
    for k, v in filled.items():
        print(f"  从 k3y 补上 {k:11s}: {v}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
