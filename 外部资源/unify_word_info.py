#!/usr/bin/env python3
"""把词库统一到新词表的信息，并把 frequency 统一成 COCA 排名。

字段策略：

| 字段 | 策略 | 原因 |
|---|---|---|
| 音标 phonetic | 新表有值就替换 | 手机几乎全空（1770/1780），新表来自 ECDICT |
| 变形 derivations | 取并集 | 手机是「派生词+释义」，新表是「时态形」，不重叠，都留 |
| 词频 frequency | **统一成 COCA 排名** | 数字越小越常用；0 = 查不到，排最后 |
| 例句 examples | 仅补空 | 新表例句本就来自 k3y |
| 词性 part_of_speech | 仅补空 | 手机 vt./adj. 比新表 v./a. 易读 |
| 释义 meanings | 仅补空（默认） | 手机释义更详细 |
| 标签 tags | 仅补空（默认） | 手机存的是 高频/次高频/中频，对应 L0/L1/L2 |
| 巧记/词根/近义词 | 仅补空 | 新表这些列本就来自 k3y |

频率的取法：ECDICT `frq`（COCA 排名）；自己没排名时，用 `exchange` 里的原形
（`0:ability`）去取原形的排名 —— 否则 abilities / accounts 这类屈折形会全部掉成 0。

用法：
    python unify_word_info.py --db <db> --csv 考研5500-merged.csv --ecdict stardict.db
    python unify_word_info.py ... --apply
    python unify_word_info.py ... --apply --refresh-csv 考研5500-merged.csv 考研5500-L3.csv
"""

from __future__ import annotations

import argparse
import csv
import os
import re
import sqlite3
import sys
import time

TOKEN = re.compile(r"[A-Za-z][A-Za-z'’\-]*")
LEMMA = re.compile(r"(?:^|/)0:([^/]+)")

REPLACE = {"phonetic": "phonetic"}
UNION = {"derivation": "derivations"}
FILL_ONLY = {
    "pos": "part_of_speech",
    "meaning": "meanings",
    "example": "examples",
    "tags": "tags",
    "mnemonic": "mnemonic",
    "root": "roots",
    "synonyms": "synonyms",
}


def first_token(text: str) -> str:
    m = TOKEN.search(text)
    return m.group(0).lower() if m else ""


def normalize_phonetic(text: str) -> str:
    """ECDICT 用 ASCII 的 ' 标主重音，手机里已有的音标是 IPA 的 ˈ，统一成后者。"""
    return text.replace("'", "ˈ").replace(",", "ˌ")


def union_derivations(old: str, new: str) -> str:
    """保留旧的派生词，只把旧文里没出现过的变形追加进去。"""
    if not new:
        return old
    if not old:
        return new
    seen = {t.lower() for t in TOKEN.findall(old)}
    parts = [p.strip() for p in re.split(r"[;；]", new) if p.strip()]
    extra = [p for p in parts if first_token(p) and first_token(p) not in seen]
    if not extra:
        return old
    return old.rstrip(";； ") + "；" + "；".join(extra)


def build_ranker(ecdict_path: str):
    con = sqlite3.connect(ecdict_path)
    cache: dict[str, int] = {}

    def rank(word: str) -> int:
        key = word.lower()
        if key in cache:
            return cache[key]
        row = con.execute("SELECT frq, exchange FROM stardict WHERE word = ?", (word,)).fetchone()
        value = 0
        if row:
            frq, exchange = row
            if frq:
                value = int(frq)
            else:
                m = LEMMA.search(exchange or "")
                if m:
                    base = con.execute(
                        "SELECT frq FROM stardict WHERE word = ?", (m.group(1),)
                    ).fetchone()
                    if base and base[0]:
                        value = int(base[0])
        cache[key] = value
        return value

    return rank


def refresh_csv(path: str, rank) -> None:
    with open(path, encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        fields = reader.fieldnames or []
        rows = list(reader)
    changed = 0
    for row in rows:
        if "frequency" in fields:
            new = str(rank(row["word"]))
            if (row.get("frequency") or "") != new:
                changed += 1
            row["frequency"] = new
        if "phonetic" in fields:
            row["phonetic"] = normalize_phonetic(row.get("phonetic") or "")
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    try:
        os.replace(tmp, path)
    except PermissionError:
        # Windows 上文件被编辑器打开着时换不了名。退一步：原地写。
        try:
            with open(path, "w", encoding="utf-8-sig", newline="") as f:
                writer = csv.DictWriter(f, fieldnames=fields)
                writer.writeheader()
                writer.writerows(rows)
            os.remove(tmp)
        except PermissionError:
            os.remove(tmp)
            print(f"  {os.path.basename(path)}: 被其他程序占用，跳过（关掉编辑器后重跑）")
            return
    zero = sum(1 for r in rows if r.get("frequency") == "0")
    print(f"  {os.path.basename(path)}: {len(rows)} 行，词频改 {changed} 行，无排名 {zero}")


def main() -> int:
    ap = argparse.ArgumentParser(description="统一词库信息与 COCA 排名")
    ap.add_argument("--db", required=True)
    ap.add_argument("--csv", required=True)
    ap.add_argument("--ecdict", required=True)
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--overwrite-content", action="store_true",
                    help="连释义/词性/标签也用新表覆盖（默认只补空）")
    ap.add_argument("--refresh-csv", nargs="*", default=[],
                    help="同时把这些 CSV 的 frequency 列刷成 COCA 排名（需 --apply）")
    args = ap.parse_args()

    rank = build_ranker(args.ecdict)

    con = sqlite3.connect(args.db)
    con.row_factory = sqlite3.Row

    with open(args.csv, encoding="utf-8-sig", newline="") as f:
        incoming = {}
        for row in csv.DictReader(f):
            word = (row.get("word") or "").strip()
            if word:
                incoming.setdefault(word.lower(), row)

    existing = {}
    for r in con.execute(
        "SELECT id, headword, phonetic, part_of_speech, meanings, examples, "
        "derivations, tags, mnemonic, roots, synonyms, frequency FROM words"
    ):
        existing[r["headword"].lower()] = r

    overlap = sorted(set(existing) & set(incoming))
    changes: dict[str, list[tuple[int, str, str, str]]] = {}
    plan: list[tuple[int, str, object]] = []

    def add(field: str, row_id: int, headword: str, old, new) -> None:
        changes.setdefault(field, []).append((row_id, headword, str(old), str(new)))
        plan.append((row_id, field, new))

    for key in overlap:
        db_row = existing[key]
        src = incoming[key]
        for csv_col, db_col in REPLACE.items():
            new = (src.get(csv_col) or "").strip()
            if csv_col == "phonetic":
                new = normalize_phonetic(new)
            old = (db_row[db_col] or "").strip()
            if new and new != old:
                add(db_col, db_row["id"], db_row["headword"], old, new)
        for csv_col, db_col in UNION.items():
            new = (src.get(csv_col) or "").strip()
            old = (db_row[db_col] or "").strip()
            merged = union_derivations(old, new)
            if merged != old:
                add(db_col, db_row["id"], db_row["headword"], old, merged)
        for csv_col, db_col in FILL_ONLY.items():
            new = (src.get(csv_col) or "").strip()
            old = (db_row[db_col] or "").strip()
            if not new:
                continue
            if (not old or args.overwrite_content) and new != old:
                add(db_col, db_row["id"], db_row["headword"], old, new)

    # 词频：全库统一成 COCA 排名，不只重叠词
    for db_row in existing.values():
        new_rank = rank(db_row["headword"])
        old_rank = int(db_row["frequency"] or 0)
        if new_rank != old_rank:
            add("frequency", db_row["id"], db_row["headword"], old_rank, new_rank)

    print(f"库里 {len(existing)} 词，新表 {len(incoming)} 词，重叠 {len(overlap)}")
    print(f"涉及 {len(plan)} 处修改：")
    for field, items in changes.items():
        print(f"  {field:16s}: {len(items):5d}")
    print("\n样例：")
    for field in ("frequency", "phonetic", "derivations"):
        for _, hw, old, new in changes.get(field, [])[:3]:
            print(f"  {field} · {hw}: {old[:30]!r} -> {new[:60]!r}")

    zero = sum(1 for r in existing.values() if rank(r["headword"]) == 0)
    print(f"\n排名为 0（查不到）的词：{zero}")

    if not plan:
        print("\n数据没有需要改的。")
    elif not args.apply:
        print("\n（只预览，未写库。确认后加 --apply）")
        return 0
    else:
        now = int(time.time() * 1000)
        with con:
            for row_id, field, value in plan:
                con.execute(
                    f"UPDATE words SET {field} = ?, updatedAt = ? WHERE id = ?",
                    (value, now, row_id),
                )

        print("\n已写入。")
        print("integrity:", con.execute("PRAGMA integrity_check").fetchone()[0])
        print(
            "词条数:",
            con.execute("SELECT COUNT(*) FROM words").fetchone()[0],
            "| 大小写重复:",
            con.execute(
                "SELECT COUNT(*) FROM (SELECT LOWER(headword) k FROM words GROUP BY k HAVING COUNT(*)>1)"
            ).fetchone()[0],
        )
        print("最常用的 5 个词:", con.execute(
            "SELECT headword, frequency FROM words WHERE frequency > 0 ORDER BY frequency ASC LIMIT 5"
        ).fetchall())

    if args.apply and args.refresh_csv:
        print("\n刷新 CSV 的 frequency / phonetic 列：")
        for path in args.refresh_csv:
            refresh_csv(path, rank)

    return 0


if __name__ == "__main__":
    sys.exit(main())
