#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「派生词-疑似独立词条」里那些词按原层级写回 L0/L1/L2。

判据来自清单里的 from_level 列（当初从哪个词表移出去的）。写回时：
  - deck_words：从「派生词」摘掉，挂回对应层级词表；
  - words.tags：改回只有层级标签（这些词不在 5500 表里，本来就没有 ECDICT 标签可拼）。

用法：
    python restore_disputed.py --db <db> --csv 外部资源/考研5500/派生词-疑似独立词条.csv
    python restore_disputed.py --db <db> --csv ... --apply
"""

from __future__ import annotations

import argparse
import csv
import re
import sqlite3
import sys

LEVEL_RE = re.compile(r"L(\d+)\s*$")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--csv", default="外部资源/考研5500/派生词-疑似独立词条.csv")
    ap.add_argument("--derived-deck", default="派生词")
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    con = sqlite3.connect(args.db)
    con.row_factory = sqlite3.Row

    with open(args.csv, encoding="utf-8-sig", newline="") as f:
        rows = [r for r in csv.DictReader(f) if (r.get("word") or "").strip()]
    print(f"清单 {len(rows)} 词")

    level_deck: dict[str, tuple[int, str]] = {}
    derived_deck_id = None
    for r in con.execute("SELECT id, name FROM decks"):
        m = LEVEL_RE.search(r["name"])
        if m:
            level_deck[f"L{m.group(1)}"] = (r["id"], r["name"])
        if r["name"] == args.derived_deck:
            derived_deck_id = r["id"]
    if derived_deck_id is None:
        print(f"找不到词表「{args.derived_deck}」", file=sys.stderr)
        return 1

    by_lower = {r["headword"].strip().lower(): r for r in con.execute("SELECT id, headword FROM words")}

    plans = []
    missing, already, unknown = [], [], []
    for r in rows:
        word = r["word"].strip()
        level = (r.get("from_level") or "").strip()
        target = level_deck.get(level)
        if target is None:
            unknown.append((word, level))
            continue
        hit = by_lower.get(word.lower())
        if hit is None:
            missing.append(word)
            continue
        deck_ids = {x["deckId"] for x in con.execute(
            "SELECT deckId FROM deck_words WHERE wordId = ?", (hit["id"],))}
        if target[0] in deck_ids and derived_deck_id not in deck_ids:
            already.append(word)
            continue
        plans.append((hit["id"], hit["headword"], level, target[0], target[1],
                      derived_deck_id in deck_ids))

    print(f"待写回 {len(plans)} 词")
    dist: dict[str, int] = {}
    for p in plans:
        dist[p[2]] = dist.get(p[2], 0) + 1
    print("  目标层级：" + "，".join(f"{k} {v}" for k, v in sorted(dist.items())))
    if already:
        print(f"  已在目标词表、跳过 {len(already)}：{'，'.join(already[:6])}")
    if missing:
        print(f"  库中找不到、跳过 {len(missing)}：{'，'.join(missing[:6])}")
    if unknown:
        print(f"  层级无法识别 {len(unknown)}：{unknown[:4]}")

    if not plans:
        print("没有需要改的。")
        return 0
    if not args.apply:
        print("\n（预览完成，未写库。确认后加 --apply）")
        return 0

    now_ms = __import__("time").time()
    with con:
        for wid, headword, level, deck_id, _name, was_derived in plans:
            con.execute(
                "DELETE FROM deck_words WHERE wordId = ? AND deckId <> ?", (wid, deck_id)
            )
            con.execute(
                "INSERT OR IGNORE INTO deck_words(deckId, wordId) VALUES(?, ?)", (deck_id, wid)
            )
            con.execute("UPDATE words SET tags = ? WHERE id = ?", (level, wid))

    print(f"\n已写回 {len(plans)} 词")
    print("integrity:", con.execute("PRAGMA integrity_check").fetchone()[0])
    print("words:", con.execute("SELECT COUNT(*) FROM words").fetchone()[0])
    print("review_states:", con.execute("SELECT COUNT(*) FROM review_states").fetchone()[0])
    print("review_logs:", con.execute("SELECT COUNT(*) FROM review_logs").fetchone()[0])
    print("多词表词数:", con.execute(
        "SELECT COUNT(*) FROM (SELECT wordId FROM deck_words GROUP BY wordId HAVING COUNT(*) > 1)"
    ).fetchone()[0])
    print("词表：")
    for r in con.execute(
        "SELECT d.name, COUNT(dw.wordId) c FROM decks d"
        " LEFT JOIN deck_words dw ON dw.deckId = d.id GROUP BY d.id ORDER BY d.id"
    ):
        print(f"  {r['name']:16s} {r['c']}")
    print("抽查：")
    for r in con.execute(
        "SELECT headword, tags FROM words WHERE headword IN "
        "('added','media','saying','shopping','caused') ORDER BY headword"
    ):
        print(f"  {r['headword']:10s} {r['tags']}")
    con.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
