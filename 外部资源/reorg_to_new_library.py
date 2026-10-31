#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把原词库（L0/L1/L2）的词条数据统一成 5500 新词表，并把派生词单独拆到「派生词」词表。

两件事：
  1) 统一字段。对「原词库 ∩ 新词表」的每个词，用新词表的 pos / 释义 / 音标 / 变形 / 标签 /
     词频覆盖；新词表里为空的字段（例句、巧记、词根、近义词等原词库特有的内容）**保留旧值**。
     覆盖后把层级（L0/L1/L2）作为额外标签补回去 —— 旧的「高频/次高频/中频」和词表本身
     是同一件事，统一后不再需要。
  2) 派生词归拢。原词库里 ECDICT 标记为屈折形（过去式/分词/复数/比较级…）且**不在新词表**
     里的词，从 L0/L1/L2 移到新建的「派生词」词表。判据要求原形也在库内，避开 ECDICT 的
     专名误判（james→jame、Thomas→thoma 之类）。

只动 words / deck_words / decks 三张表，不碰 review_states 与 review_logs —— 复习进度原样保留。

用法：
    python reorg_to_new_library.py --db <db 路径>              # 预览
    python reorg_to_new_library.py --db <db 路径> --apply      # 写入
"""

from __future__ import annotations

import argparse
import csv
import re
import sqlite3
import sys
import time

LEVEL_RE = re.compile(r"L(\d+)\s*$")
LEMMA_RE = re.compile(r"(?:^|/)0:([^/]+)")
OLD_LEVEL_TAGS = {"高频", "次高频", "中频"}

# 词表字段 -> CSV 列
FIELD_MAP = [
    ("phonetic", "phonetic"),
    ("part_of_speech", "pos"),
    ("meanings", "meaning"),
    ("examples", "example"),
    ("derivations", "derivation"),
    ("mnemonic", "mnemonic"),
    ("roots", "root"),
    ("synonyms", "synonyms"),
]


def split_tags(text: str) -> list[str]:
    """标签既可能是空格分隔（旧导入脚本），也可能是逗号分隔，统一拆开。"""
    return [t for t in re.split(r"[,，\s]+", (text or "").strip()) if t]


def join_tags(tokens: list[str]) -> str:
    seen, out = set(), []
    for t in tokens:
        key = t.lower()
        if key not in seen:
            seen.add(key)
            out.append(t)
    return ", ".join(out)


def load_csv(path: str) -> dict[str, dict]:
    with open(path, encoding="utf-8-sig", newline="") as f:
        return {
            r["word"].strip().lower(): r
            for r in csv.DictReader(f)
            if (r.get("word") or "").strip()
        }


def load_lemma_index(ecdict_path: str, words: set[str]) -> dict[str, str]:
    """只为库里这批词查原形，避免全表扫描。"""
    con = sqlite3.connect(ecdict_path)
    out: dict[str, str] = {}
    batch = sorted(w.lower() for w in words)
    try:
        for i in range(0, len(batch), 400):
            chunk = batch[i:i + 400]
            marks = ",".join("?" * len(chunk))
            for word, exchange in con.execute(
                f"SELECT word, exchange FROM stardict WHERE word IN ({marks})", chunk
            ):
                m = LEMMA_RE.search(exchange or "")
                if m:
                    out[word.lower()] = m.group(1).strip()
    finally:
        con.close()
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--csv", default="外部资源/考研5500/考研5500-merged.csv")
    ap.add_argument("--ecdict", default="外部资源/ecdict/stardict.db")
    ap.add_argument("--deck", default="派生词")
    ap.add_argument("--report", default="")
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    con = sqlite3.connect(args.db)
    con.row_factory = sqlite3.Row

    new_words = load_csv(args.csv)
    print(f"新词表 {len(new_words)} 词")

    level_of_deck = {}
    for r in con.execute("SELECT id, name FROM decks"):
        m = LEVEL_RE.search(r["name"])
        if m:
            level_of_deck[r["id"]] = f"L{m.group(1)}"

    word_level: dict[int, str] = {}
    for r in con.execute("SELECT wordId, deckId FROM deck_words"):
        lv = level_of_deck.get(r["deckId"])
        if lv:
            word_level.setdefault(r["wordId"], lv)

    words = {
        r["id"]: r
        for r in con.execute(
            "SELECT id, headword, phonetic, part_of_speech, meanings, examples, derivations,"
            " tags, mnemonic, roots, synonyms, frequency FROM words"
        )
    }

    original = {wid: lv for wid, lv in word_level.items() if lv in ("L0", "L1", "L2")}
    print(
        f"原词库 L0-L2 {len(original)} 词，"
        f"L3 {sum(1 for lv in word_level.values() if lv == 'L3')} 词"
    )

    # ---------- 1) 字段统一 ----------
    updates: list[tuple[list[str], list[object], int]] = []
    touched: dict[str, int] = {}
    kept_old: dict[str, int] = {}
    relabeled_only = 0
    overlap = 0

    for wid, level in original.items():
        row = words[wid]
        src = new_words.get(row["headword"].strip().lower())

        if src is None:
            # 不在新词表：没有可统一的数据，只把「高频/次高频/中频」换成层级标签
            tags = split_tags(row["tags"])
            rest = [t for t in tags if t not in OLD_LEVEL_TAGS]
            if rest != tags or not tags:
                updates.append((["tags"], [join_tags(rest + [level])], wid))
                touched["tags"] = touched.get("tags", 0) + 1
                relabeled_only += 1
            continue

        overlap += 1
        cols: list[str] = []
        vals: list[object] = []

        for col, csv_key in FIELD_MAP:
            new_val = (src.get(csv_key) or "").strip()
            old_val = (row[col] or "").strip()
            if not new_val:
                if old_val:
                    kept_old[col] = kept_old.get(col, 0) + 1
                continue
            if new_val != old_val:
                cols.append(col)
                vals.append(new_val)
                touched[col] = touched.get(col, 0) + 1

        freq_raw = (src.get("frequency") or "").strip()
        if freq_raw:
            try:
                freq = int(freq_raw)
            except ValueError:
                freq = None
            if freq is not None and freq != row["frequency"]:
                cols.append("frequency")
                vals.append(freq)
                touched["frequency"] = touched.get("frequency", 0) + 1

        tags_new = join_tags(split_tags(src.get("tags", "")) + [level])
        if tags_new != (row["tags"] or "").strip():
            cols.append("tags")
            vals.append(tags_new)
            touched["tags"] = touched.get("tags", 0) + 1

        if cols:
            updates.append((cols, vals, wid))

    print(f"\n[1] 字段统一：与新词表重叠 {overlap} 词，待更新 {len(updates)} 行")
    for col in ("phonetic", "part_of_speech", "meanings", "examples", "derivations",
                "tags", "mnemonic", "roots", "synonyms", "frequency"):
        if touched.get(col):
            print(f"      {col:16s} 改 {touched[col]}")
    if relabeled_only:
        print(f"      仅换层级标签（不在新词表）{relabeled_only} 词")
    if kept_old:
        print("      新表为空 → 保留旧值：" + "，".join(f"{k} {v}" for k, v in kept_old.items()))

    # ---------- 2) 派生词归拢 ----------
    not_in_new = {
        wid: lv
        for wid, lv in original.items()
        if words[wid]["headword"].strip().lower() not in new_words
    }
    lemma_of = load_lemma_index(args.ecdict, {words[wid]["headword"] for wid in not_in_new})
    lower_in_lib = {r["headword"].strip().lower() for r in words.values()}

    derived = []
    skipped = []
    for wid, lv in not_in_new.items():
        hw = words[wid]["headword"]
        lemma = lemma_of.get(hw.lower())
        if lemma and lemma.lower() in lower_in_lib:
            derived.append((wid, hw, lemma, lv, (words[wid]["meanings"] or "").split("\n")[0]))
        elif lemma:
            skipped.append((hw, lemma))

    print(f"\n[2] 派生词：不在新词表 {len(not_in_new)} 词，其中派生形且原形在库 {len(derived)}")
    by_level: dict[str, int] = {}
    for item in derived:
        by_level[item[3]] = by_level.get(item[3], 0) + 1
    print("      来源：" + "，".join(f"{k} {v}" for k, v in sorted(by_level.items())))
    if skipped:
        print(f"      原形不在库、跳过 {len(skipped)}：" + "，".join(f"{a}→{b}" for a, b in skipped[:8]))

    report_path = args.report
    if report_path:
        with open(report_path, "w", encoding="utf-8-sig", newline="") as f:
            w = csv.writer(f)
            w.writerow(["word", "lemma", "from_level", "meaning"])
            for _wid, hw, lemma, lv, meaning in sorted(derived, key=lambda x: x[1].lower()):
                w.writerow([hw, lemma, lv, meaning])
        print(f"      清单已写到 {report_path}")

    if not args.apply:
        print("\n（预览完成，未写库。确认后加 --apply）")
        return 0

    # ---------- 写入 ----------
    now_ms = int(time.time() * 1000)
    with con:
        for cols, vals, wid in updates:
            sets = ", ".join(f"{c} = ?" for c in cols)
            con.execute(f"UPDATE words SET {sets} WHERE id = ?", (*vals, wid))

        deck_row = con.execute("SELECT id FROM decks WHERE name = ?", (args.deck,)).fetchone()
        if deck_row:
            deck_id = deck_row["id"]
            print(f"\n词表「{args.deck}」已存在，复用 id={deck_id}")
        else:
            cur = con.execute(
                "INSERT INTO decks(name, createdAt) VALUES(?, ?)", (args.deck, now_ms)
            )
            deck_id = cur.lastrowid
            print(f"\n新建词表「{args.deck}」id={deck_id}")

        moved = 0
        for wid, hw, lemma, lv, _m in derived:
            # 从所有非「派生词」的层级词表里摘掉，再挂到派生词
            con.execute(
                "DELETE FROM deck_words WHERE wordId = ? AND deckId <> ?", (wid, deck_id)
            )
            con.execute(
                "INSERT OR IGNORE INTO deck_words(deckId, wordId) VALUES(?, ?)", (deck_id, wid)
            )
            con.execute("UPDATE words SET tags = ? WHERE id = ?", (f"{'派生词'}, {lv}", wid))
            moved += 1
        print(f"已移动 {moved} 个派生词")

        # 全表标签统一成逗号分隔。`WordEntity.tagList` 是按逗号切的，
        # 早期用空格导入的标签在卡片上会挤成一整块，这里一并纠正。
        fixed = 0
        for r in con.execute("SELECT id, tags FROM words WHERE tags IS NOT NULL AND tags <> ''"):
            normalized = join_tags(split_tags(r["tags"]))
            if normalized != r["tags"]:
                con.execute("UPDATE words SET tags = ? WHERE id = ?", (normalized, r["id"]))
                fixed += 1
        print(f"标签分隔符归一（逗号）：{fixed} 行")

    print("\n写入完成。")
    print("integrity:", con.execute("PRAGMA integrity_check").fetchone()[0])
    print("words:", con.execute("SELECT COUNT(*) FROM words").fetchone()[0])
    print("review_states:", con.execute("SELECT COUNT(*) FROM review_states").fetchone()[0])
    print("review_logs:", con.execute("SELECT COUNT(*) FROM review_logs").fetchone()[0])
    print("词表：")
    for r in con.execute(
        "SELECT d.id, d.name, COUNT(dw.wordId) c FROM decks d"
        " LEFT JOIN deck_words dw ON dw.deckId = d.id GROUP BY d.id ORDER BY d.id"
    ):
        print(f"  {r['name']:16s} {r['c']}")
    print("标签样例：")
    for r in con.execute(
        "SELECT headword, tags FROM words WHERE headword IN "
        "('ability','abandon','England','did','ability') ORDER BY headword LIMIT 6"
    ):
        print(f"  {r['headword']:12s} {r['tags']}")
    con.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
