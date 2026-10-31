#!/usr/bin/env python3
"""把词库里 ECDICT 的短写词性归一成长写。

只动两处，都是「词性标记」而不是释义正文：

1. `words.part_of_speech` 列：`a.` → `adj.`、`ad.` → `adv.`、`int.` → `interj.`
   （含 `vt. / vi.` 这种用 `/` 连写的复合值，逐段归一）。
2. `words.meanings` **每行行首**的词性标记：同样的映射，正文一个字不改。

`adj.`/`adv.`/`v.`/`n.` 等已经是长写的保持原样；`aux.v.` 不动。
默认只预览，加 `--apply` 才写库。

    python 外部资源/normalize_pos.py --db backups/xxx/english_studying.db
    python 外部资源/normalize_pos.py --db backups/xxx/english_studying.db --apply
"""

import argparse
import re
import sqlite3
import sys

# 短写 → 长写。只列真的同义、且库里出现过的。
ALIAS = {"a": "adj", "ad": "adv", "int": "interj"}

# 行首词性：与 MeaningParser.EN 同一份清单
POS = re.compile(
    r"^(\s*)(interj|phrase|abbr|conj|pron|prep|adv|adj|aux|art|int|pref|suff|vi|vt|num|pl|phr|n|v|ad|a)(\s*[.．])",
    re.IGNORECASE,
)

# 词性列：整段就是一个词性写法（可能带 `/` 复合），不是释义
POS_CELL = re.compile(
    r"^(interj|phrase|abbr|conj|pron|prep|adv|adj|aux|art|int|pref|suff|vi|vt|num|pl|phr|n|v|ad|a)([.．]?)$",
    re.IGNORECASE,
)


def normalize_code(code: str) -> str:
    code = code.lower()
    return ALIAS.get(code, code)


def normalize_cell(value: str) -> str:
    """`a.` → `adj.`；`vt. / vi.` 逐段归一；认不出的原样返回。"""
    parts = [p.strip() for p in value.split("/")]
    out = []
    for part in parts:
        m = POS_CELL.match(part)
        if not m:
            return value
        out.append(normalize_code(m.group(1)) + ".")
    return " / ".join(out)


def normalize_meanings(text: str) -> tuple[str, int]:
    """逐行处理：只改行首那个词性标记。"""
    changed = 0
    lines = []
    for line in text.split("\n"):
        m = POS.match(line)
        if not m:
            lines.append(line)
            continue
        code = normalize_code(m.group(2))
        # 只替换缩写本身，点号留在原地（line[m.end(2):] 从缩写结束处开始，带着那个 `.`）
        new_line = m.group(1) + code + line[m.end(2):]
        if new_line != line:
            changed += 1
        lines.append(new_line)
    return "\n".join(lines), changed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--samples", type=int, default=8)
    args = ap.parse_args()

    con = sqlite3.connect(args.db)
    con.row_factory = sqlite3.Row

    total = con.execute("SELECT COUNT(*) FROM words").fetchone()[0]

    # ---- 词性列 ----
    cell_rows = con.execute(
        "SELECT id, headword, part_of_speech FROM words "
        "WHERE part_of_speech IS NOT NULL AND part_of_speech <> ''"
    ).fetchall()
    cell_changes = []
    for row in cell_rows:
        new = normalize_cell(row["part_of_speech"])
        if new != row["part_of_speech"]:
            cell_changes.append((row["id"], row["headword"], row["part_of_speech"], new))

    # ---- 释义行首 ----
    meaning_rows = con.execute(
        "SELECT id, headword, meanings FROM words WHERE meanings IS NOT NULL AND meanings <> ''"
    ).fetchall()
    meaning_changes = []
    line_total = 0
    for row in meaning_rows:
        new_text, n = normalize_meanings(row["meanings"])
        if n:
            line_total += n
            meaning_changes.append((row["id"], row["headword"], row["meanings"], new_text, n))

    print(f"词条总数 {total}")
    print(f"词性列需改 {len(cell_changes)} 条")
    for _, head, old, new in cell_changes[: args.samples]:
        print(f"   {head:<16} {old!r} -> {new!r}")
    print(f"释义行首需改 {line_total} 行 / {len(meaning_changes)} 个词条")
    for _, head, old, new, n in meaning_changes[: args.samples]:
        o = next((l for l in old.split("\n") if l not in new.split("\n")), "")
        w = next((l for l in new.split("\n") if l not in old.split("\n")), "")
        print(f"   {head:<16} {o!r} -> {w!r}")

    if not args.apply:
        print("\n预览模式，未写库。加 --apply 执行。")
        return 0

    with con:
        con.executemany(
            "UPDATE words SET part_of_speech = ? WHERE id = ?",
            [(new, wid) for wid, _, _, new in cell_changes],
        )
        con.executemany(
            "UPDATE words SET meanings = ? WHERE id = ?",
            [(new, wid) for wid, _, _, new, _ in meaning_changes],
        )
    con.commit()
    con.close()
    print(f"\n已写库：词性列 {len(cell_changes)} 条，释义 {line_total} 行。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
