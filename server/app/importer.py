"""解析 CSV / JSON 并写库。

列名规则与 App 的导入器保持一致（`ImportField.kt`）：大小写不敏感，空格 / 下划线 /
连字符都会被忽略；认不出的列忽略，缺失的列留空。这样后端导出的文件能原样再导回 App，
App 导出的文件也能直接上传到这里。
"""

from __future__ import annotations

import csv
import io
import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Iterable, Sequence

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from .models import Deck, UserWord, Word, deck_user_words, deck_words

BOM = "\ufeff"
NBSP = "\u00a0"

# 规范字段：headword / phonetic / pos / meaning / mnemonic / root / derivation /
#           example / synonym / tags / frequency / deck
ALIASES: dict[str, str] = {
    "word": "headword", "headword": "headword", "term": "headword",
    "spelling": "headword", "english": "headword", "单词": "headword",
    "词汇": "headword", "英文": "headword",

    "phonetic": "phonetic", "phonetics": "phonetic", "pronunciation": "phonetic",
    "ipa": "phonetic", "音标": "phonetic", "发音": "phonetic",

    "pos": "pos", "partofspeech": "pos", "speech": "pos", "词性": "pos", "词类": "pos",

    "meaning": "meaning", "meanings": "meaning", "definition": "meaning",
    "translation": "meaning", "chinese": "meaning", "cn": "meaning",
    "释义": "meaning", "中文": "meaning", "翻译": "meaning", "意思": "meaning",
    "词义": "meaning",

    "mnemonic": "mnemonic", "mnemonics": "mnemonic", "memory": "mnemonic",
    "memoryaid": "mnemonic", "tip": "mnemonic", "巧记": "mnemonic",
    "助记": "mnemonic", "记忆": "mnemonic", "记忆法": "mnemonic", "联想": "mnemonic",

    "root": "root", "roots": "root", "etymology": "root", "affix": "root",
    "affixes": "root", "词根": "root", "词根词缀": "root", "词缀": "root", "构词": "root",

    "derivation": "derivation", "derivations": "derivation", "forms": "derivation",
    "form": "derivation", "inflection": "derivation", "variants": "derivation",
    "变形": "derivation", "词形变化": "derivation", "派生": "derivation", "派生词": "derivation",

    "example": "example", "examples": "example", "sentence": "example",
    "sentences": "example", "例句": "example", "真题例句": "example", "示例": "example",

    "synonym": "synonym", "synonyms": "synonym", "synonymy": "synonym",
    "near": "synonym", "similar": "synonym", "近义词": "synonym", "同义词": "synonym",
    "近义": "synonym", "同义": "synonym",

    "tag": "tags", "tags": "tags", "label": "tags", "labels": "tags",
    "category": "tags", "标签": "tags", "分类": "tags", "标记": "tags",

    "frequency": "frequency", "freq": "frequency", "rank": "frequency",
    "coca": "frequency", "cocarank": "frequency", "词频": "frequency",
    "频率": "frequency", "序号": "frequency",

    "deck": "deck", "list": "deck", "book": "deck", "source": "deck",
    "词表": "deck", "分组": "deck", "来源": "deck", "书": "deck",
}

COLUMN_ORDER = ["headword", "phonetic", "pos", "meaning", "mnemonic", "root",
                "derivation", "example", "synonym", "tags", "frequency"]

# 导出时用的列名（也是 App 认得的写法）
EXPORT_HEADERS = {
    "headword": "word",
    "phonetic": "phonetic",
    "pos": "pos",
    "meaning": "meaning",
    "mnemonic": "mnemonic",
    "root": "root",
    "derivation": "derivation",
    "example": "example",
    "synonym": "synonym",
    "tags": "tags",
    "frequency": "frequency",
}

MAX_HEADWORD = 200
MAX_TEXT = 20000


def normalize_header(header: str) -> str:
    text = header.strip().lstrip(BOM).strip().lower()
    return "".join(ch for ch in text if ch not in {" ", NBSP, "_", "-"})


def canonical_field(header: str) -> str | None:
    return ALIASES.get(normalize_header(header))


def decode_text(data: bytes) -> str:
    """UTF-8（含 BOM）优先，中文 Windows 上的 Excel 存 GBK 也能读。"""
    for encoding in ("utf-8-sig", "gb18030"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def sniff_delimiter(sample: str) -> str:
    try:
        return csv.Sniffer().sniff(sample, delimiters=",;\t|").delimiter
    except csv.Error:
        # 表头常只有一个逗号，Sniffer 会罢工；按出现次数兜底
        first = sample.splitlines()[0] if sample.splitlines() else ""
        counts = {d: first.count(d) for d in (",", ";", "\t", "|")}
        best = max(counts, key=counts.get)
        return best if counts[best] else ","


@dataclass
class ParsedRow:
    values: dict[str, str]
    line: int


@dataclass
class ImportStats:
    total_rows: int = 0
    created: int = 0
    updated: int = 0
    unchanged: int = 0
    linked: int = 0
    duplicate_lines: list[int] = field(default_factory=list)
    issues: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "totalRows": self.total_rows,
            "created": self.created,
            "updated": self.updated,
            "unchanged": self.unchanged,
            "linked": self.linked,
            "duplicateLines": self.duplicate_lines[:100],
            "issues": self.issues[:100],
        }


def parse_csv(data: bytes) -> list[ParsedRow]:
    text = decode_text(data)
    delimiter = sniff_delimiter(text[:4096])
    reader = csv.reader(io.StringIO(text, newline=""), delimiter=delimiter)
    rows = list(reader)
    if not rows:
        return []

    headers = [canonical_field(h) for h in rows[0]]
    if not any(headers):
        raise ValueError("表头里没有一列能被识别（至少要有一列是单词）")

    parsed: list[ParsedRow] = []
    for index, raw in enumerate(rows[1:], start=2):
        if not any(cell.strip() for cell in raw):
            continue
        values: dict[str, str] = {}
        for position, field_name in enumerate(headers):
            if field_name is None or position >= len(raw):
                continue
            value = raw[position].strip().lstrip(BOM)
            if value:
                values[field_name] = value
        if values.get("headword"):
            parsed.append(ParsedRow(values=values, line=index))
    return parsed


def parse_json(data: bytes) -> list[ParsedRow]:
    payload = json.loads(decode_text(data))
    if isinstance(payload, dict):
        # 允许 {"words": [...]} 或 {"data": [...]}
        for key in ("words", "data", "items", "rows"):
            if isinstance(payload.get(key), list):
                payload = payload[key]
                break
        else:
            payload = [payload]
    if not isinstance(payload, list):
        raise ValueError("JSON 顶层必须是对象数组")

    parsed: list[ParsedRow] = []
    for index, item in enumerate(payload, start=1):
        if not isinstance(item, dict):
            continue
        values: dict[str, str] = {}
        for key, value in item.items():
            field_name = canonical_field(str(key))
            if field_name is None or value is None:
                continue
            text = str(value).strip()
            if text:
                values[field_name] = text
        if values.get("headword"):
            parsed.append(ParsedRow(values=values, line=index))
    return parsed


def parse_upload(filename: str, data: bytes) -> list[ParsedRow]:
    lowered = (filename or "").lower()
    if lowered.endswith(".json"):
        return parse_json(data)
    if lowered.endswith((".csv", ".tsv", ".txt")):
        return parse_csv(data)
    # 没后缀就按内容猜
    head = decode_text(data[:64]).lstrip()
    return parse_json(data) if head.startswith(("[", "{")) else parse_csv(data)


def _preferred_casing(old: str, new: str) -> str:
    """大小写是数据不是格式：允许小写→大写升级，不允许反向降级。"""
    if old == new:
        return old
    if old.islower() and not new.islower():
        return new
    return old


def _clean(value: str | None, limit: int = MAX_TEXT) -> str | None:
    if value is None:
        return None
    text = value.strip().lstrip(BOM).strip()
    return text[:limit] if text else None


def _to_int(value: str | None) -> int:
    if not value:
        return 0
    try:
        number = int(float(value.replace(",", "").strip()))
    except (TypeError, ValueError):
        return 0
    return number if number > 0 else 0


async def _find_existing(session: AsyncSession, keys: Sequence[str]) -> dict[str, Word]:
    found: dict[str, Word] = {}
    chunk = 800
    for start in range(0, len(keys), chunk):
        part = keys[start:start + chunk]
        stmt = select(Word).where(func.lower(Word.headword).in_(part))
        for word in (await session.scalars(stmt)):
            found[word.headword.lower()] = word
    return found


async def import_rows(
    session: AsyncSession,
    deck: Deck,
    rows: Iterable[ParsedRow],
    *,
    replace_links: bool = False,
) -> ImportStats:
    """把解析好的行写进词池并挂到词表上。

    两条与 App 一致的约定：
      1. 已存在的词走 UPDATE，绝不删除重建（否则复习进度会被顺手抹掉）。
      2. 空单元格不覆盖已有内容 —— 空表示「这次没提供」。
    """
    stats = ImportStats()
    rows = list(rows)
    stats.total_rows = len(rows)

    # 同一份文件里大小写不同的同一个词，只留一条
    deduped: list[ParsedRow] = []
    seen: dict[str, int] = {}
    for row in rows:
        key = row.values["headword"].lower()
        if key in seen:
            stats.duplicate_lines.append(row.line)
            continue
        seen[key] = row.line
        deduped.append(row)

    existing = await _find_existing(session, list(seen.keys()))

    words: list[Word] = []
    for row in deduped:
        values = row.values
        headword = _clean(values["headword"], MAX_HEADWORD)
        if not headword:
            stats.issues.append(f"第 {row.line} 行缺少单词，已跳过")
            continue

        key = headword.lower()
        word = existing.get(key)
        payload = {
            "phonetic": _clean(values.get("phonetic"), 200),
            "part_of_speech": _clean(values.get("pos"), 60),
            "meanings": _clean(values.get("meaning")),
            "mnemonic": _clean(values.get("mnemonic")),
            "roots": _clean(values.get("root")),
            "derivations": _clean(values.get("derivation")),
            "examples": _clean(values.get("example")),
            "synonyms": _clean(values.get("synonym")),
            "tags": _clean(values.get("tags")),
            "frequency": _to_int(values.get("frequency")),
        }

        if word is None:
            word = Word(headword=headword, **payload)
            session.add(word)
            existing[key] = word
            words.append(word)
            stats.created += 1
            continue

        changed = False
        preferred = _preferred_casing(word.headword, headword)
        if preferred != word.headword:
            word.headword = preferred
            changed = True
        for attr, new_value in payload.items():
            if attr == "frequency":
                # 词频 0 也表示「没提供」，不覆盖
                if new_value and word.frequency != new_value:
                    word.frequency = new_value
                    changed = True
                continue
            if new_value is not None and getattr(word, attr) != new_value:
                setattr(word, attr, new_value)
                changed = True
        if changed:
            stats.updated += 1
        else:
            stats.unchanged += 1
        words.append(word)

    await session.flush()  # 拿到新建词的 id

    if replace_links:
        await session.execute(deck_words.delete().where(deck_words.c.deck_id == deck.id))

    linked_ids = {wid for (wid,) in await session.execute(
        select(deck_words.c.word_id).where(deck_words.c.deck_id == deck.id)
    )}
    for word in words:
        if word.id in linked_ids:
            continue
        await session.execute(
            deck_words.insert().values(deck_id=deck.id, word_id=word.id)
        )
        stats.linked += 1

    deck.updated_at = datetime.now(timezone.utc)
    await session.flush()
    return stats


def export_rows(words: Iterable[Word]) -> tuple[list[str], list[list[str]]]:
    """导出的表格：表头 + 每行 11 列，顺序与 EXPORT_HEADERS 一致。"""
    headers = [EXPORT_HEADERS[column] for column in COLUMN_ORDER]
    body: list[list[str]] = []
    for word in words:
        line: list[str] = []
        for column in COLUMN_ORDER:
            if column == "headword":
                line.append(word.headword)
            elif column == "pos":
                line.append(word.part_of_speech or "")
            elif column == "meaning":
                line.append(word.meanings or "")
            elif column == "frequency":
                line.append(str(word.frequency or 0))
            else:
                attr = {
                    "phonetic": "phonetic",
                    "mnemonic": "mnemonic",
                    "root": "roots",
                    "derivation": "derivations",
                    "example": "examples",
                    "synonym": "synonyms",
                    "tags": "tags",
                }[column]
                line.append(getattr(word, attr) or "")
        body.append(line)
    return headers, body

async def import_user_words(
    session: AsyncSession,
    user_id,
    deck: Deck,
    rows: Iterable[ParsedRow],
    *,
    replace_links: bool = False,
) -> ImportStats:
    """把 CSV/JSON 里的词写进**用户自己的** user_words，并挂到词表上。

    和 `import_rows`（写公共词库）规则一致：同一个人同名只留一条、空值不覆盖。
    区别只在于写哪张表 —— 普通用户上传永远不会碰到 `words`。
    """
    stats = ImportStats()
    rows = list(rows)
    stats.total_rows = len(rows)

    deduped: list[ParsedRow] = []
    seen: dict[str, int] = {}
    for row in rows:
        key = row.values["headword"].lower()
        if key in seen:
            stats.duplicate_lines.append(row.line)
            continue
        seen[key] = row.line
        deduped.append(row)

    found: dict[str, UserWord] = {}
    keys = list(seen.keys())
    chunk = 800
    for start in range(0, len(keys), chunk):
        part = keys[start:start + chunk]
        stmt = (
            select(UserWord)
            .where(UserWord.user_id == user_id)
            .where(func.lower(UserWord.headword).in_(part))
        )
        for word in await session.scalars(stmt):
            found[word.headword.lower()] = word

    words: list[UserWord] = []
    for row in deduped:
        values = row.values
        headword = _clean(values["headword"], MAX_HEADWORD)
        if not headword:
            stats.issues.append(f"第 {row.line} 行缺少单词，已跳过")
            continue

        key = headword.lower()
        word = found.get(key)
        payload = {
            "phonetic": _clean(values.get("phonetic"), 200),
            "part_of_speech": _clean(values.get("pos"), 60),
            "meanings": _clean(values.get("meaning")),
            "mnemonic": _clean(values.get("mnemonic")),
            "roots": _clean(values.get("root")),
            "derivations": _clean(values.get("derivation")),
            "examples": _clean(values.get("example")),
            "synonyms": _clean(values.get("synonym")),
            "tags": _clean(values.get("tags")),
            "frequency": _to_int(values.get("frequency")),
        }

        if word is None:
            word = UserWord(user_id=user_id, headword=headword, **payload)
            session.add(word)
            found[key] = word
            words.append(word)
            stats.created += 1
            continue

        changed = False
        preferred = _preferred_casing(word.headword, headword)
        if preferred != word.headword:
            word.headword = preferred
            changed = True
        for attr, new_value in payload.items():
            if attr == "frequency":
                if new_value and word.frequency != new_value:
                    word.frequency = new_value
                    changed = True
                continue
            if new_value is not None and getattr(word, attr) != new_value:
                setattr(word, attr, new_value)
                changed = True
        if changed:
            stats.updated += 1
        else:
            stats.unchanged += 1
        words.append(word)

    await session.flush()

    if replace_links:
        await session.execute(
            deck_user_words.delete().where(deck_user_words.c.deck_id == deck.id)
        )

    linked_ids = {wid for (wid,) in await session.execute(
        select(deck_user_words.c.user_word_id).where(deck_user_words.c.deck_id == deck.id)
    )}
    for word in words:
        if word.id in linked_ids:
            continue
        await session.execute(
            deck_user_words.insert().values(deck_id=deck.id, user_word_id=word.id)
        )
        stats.linked += 1

    from datetime import datetime, timezone

    deck.updated_at = datetime.now(timezone.utc)
    await session.flush()
    return stats
