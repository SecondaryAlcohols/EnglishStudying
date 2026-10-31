#!/usr/bin/env python3
"""把被压成小写的专有名词改回原拼写（England / China / December …）。

背景：k3y 词库的 headword 全是小写，导入后 `England` 就变成 `england`；
而 App 的显示层是原样渲染 `words.headword` 的，所以只能从数据里修。
改名是原地 UPDATE，id 不变，复习进度、日志、词表关联、词间关系全部照旧。

用法：
    python fix_headword_case.py --db <english_studying.db>            # 只报告
    python fix_headword_case.py --db <english_studying.db> --apply    # 落库

判定原则：**只改词条本身就是专有名词的词**，依据是它自己的释义，而不是 ECDICT 的拼写。
ECDICT 把 AIMS / CORE / NORTH / STH 这类也写成了大写，盲从会把普通词改坏，
所以这里用白名单，不自动发现。
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
import time

# 词头 -> 正确拼写。只收「释义就是国家/城市/语言/人名/月份/星期」的词条。
FIXES = {
    "america": "America",
    "american": "American",
    "australia": "Australia",
    "boston": "Boston",
    "british": "British",
    "california": "California",
    "canada": "Canada",
    "chicago": "Chicago",
    "china": "China",
    "december": "December",
    "egypt": "Egypt",
    "england": "England",
    "english-chinese": "English-Chinese",
    "france": "France",
    "french": "French",
    "friday": "Friday",
    "friedman": "Friedman",
    "german": "German",
    "greek": "Greek",
    "harvard": "Harvard",
    "japanese": "Japanese",
    "july": "July",
    "june": "June",
    "london": "London",
    "microsoft": "Microsoft",
    "monday": "Monday",
    "nobel": "Nobel",
    "robert": "Robert",
    "saturday": "Saturday",
    "spain": "Spain",
    "thomas": "Thomas",
    "virginia": "Virginia",
    "washington": "Washington",
}

# 故意不改的：它们看着像专有名词，但词条本身是普通名词/缩写。
SKIPPED = {
    "japan": "释义是「漆器 / 涂黑漆」，普通名词 japan",
    "carl": "释义是「村夫 / 奴隶」，古语普通名词",
    "north": "释义是「北方 / 北部」，普通名词",
    "states": "state 的复数",
    "conservative": "形容词「保守的」",
    "republicans": "republican 的复数",
    "core": "普通名词「核心」",
    "aims": "aim 的第三人称单数",
    "com": "缩写，非专有名词",
    "sth": "词典缩写 something",
    "e-mail": "小写是通行写法，E-MAIL 反而怪",
    "dna": "释义对不上（did not attend），先不改，等数据修好",
    "a-g": "医学缩写，含义不明",
    "utopia": "普通名词「乌托邦」，词典通常小写",
}


def main() -> int:
    parser = argparse.ArgumentParser(description="修正词库中专有名词的大小写")
    parser.add_argument("--db", required=True, help="english_studying.db 路径")
    parser.add_argument("--apply", action="store_true", help="真正写库（默认只报告）")
    args = parser.parse_args()

    con = sqlite3.connect(args.db)
    con.row_factory = sqlite3.Row

    total_before = con.execute("SELECT COUNT(*) FROM words").fetchone()[0]
    planned: list[tuple[int, str, str]] = []
    problems: list[str] = []

    for old, new in sorted(FIXES.items()):
        row = con.execute(
            "SELECT id, headword FROM words WHERE headword = ? COLLATE BINARY", (old,)
        ).fetchone()
        if row is None:
            # 已经是正确拼写，或者这个词根本不在这台机器的库里
            already = con.execute(
                "SELECT id FROM words WHERE headword = ? COLLATE BINARY", (new,)
            ).fetchone()
            if already is None:
                problems.append(f"{old}: 库里没有这个词，跳过")
            continue
        clash = con.execute(
            "SELECT id FROM words WHERE headword = ? COLLATE BINARY", (new,)
        ).fetchone()
        if clash is not None:
            problems.append(f"{old} -> {new}: 目标已存在（id={clash['id']}），跳过")
            continue
        planned.append((row["id"], old, new))

    print(f"库内词条 {total_before} 个，计划改名 {len(planned)} 个：")
    for _, old, new in planned:
        print(f"  {old:16s} -> {new}")
    if problems:
        print("\n跳过：")
        for p in problems:
            print("  " + p)

    if not planned:
        print("\n没有需要改的词。")
        return 0

    if not args.apply:
        print("\n（只报告，未写库。确认后加 --apply）")
        return 0

    now = int(time.time() * 1000)
    with con:  # 单事务：任何一条失败都整体回滚
        for word_id, old, new in planned:
            cur = con.execute(
                "UPDATE words SET headword = ?, updatedAt = ? "
                "WHERE id = ? AND headword = ? COLLATE BINARY",
                (new, now, word_id, old),
            )
            if cur.rowcount != 1:
                raise RuntimeError(f"{old} 更新失败（rowcount={cur.rowcount}）")

    total_after = con.execute("SELECT COUNT(*) FROM words").fetchone()[0]
    dup = con.execute(
        "SELECT LOWER(headword) k, COUNT(*) c FROM words GROUP BY k HAVING c > 1"
    ).fetchall()
    print(f"\n已写入。词条数 {total_before} -> {total_after}；大小写重复 {len(dup)} 组")
    if dup:
        print("  重复:", [(r["k"], r["c"]) for r in dup])
        return 1
    print("integrity:", con.execute("PRAGMA integrity_check").fetchone()[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
