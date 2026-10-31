#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本地词典服务：用 ECDICT 的 stardict.db 模拟 isdict-api 的接口形态。

为什么不是 isdict-api 本体：那个仓库只有 10 个词的样本数据，71.5 万词条来自作者
未发布的导入管线，没有 dump 可取 —— 服务和数据总得有一个能拿到，所以数据这边用
ECDICT（isdict 的六个数据源里最大的那个），接口这边照它的信封和字段名仿一份，
`enrich_with_isdict.py` 不用改就能直接跑。

    python local_dict_service.py --db stardict.db --port 8080

支持的端点：
    GET  /health
    GET  /api/v1/health
    GET  /api/v1/words/{headword}
    GET  /api/v1/words/{headword}/pronunciations
    GET  /api/v1/words/{headword}/senses
    POST /api/v1/words/batch        {"words": [...], "include_variants": true, ...}
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sqlite3
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DB_PATH = "stardict.db"
DB_LOCK = threading.Lock()

# ECDICT 的 pos 百分比 → isdict 的 pos 代码
POS_CODE = {
    "n": "noun", "v": "verb", "vt": "verb", "vi": "verb", "adj": "adjective",
    "a": "adjective", "adv": "adverb", "ad": "adverb", "prep": "preposition",
    "conj": "conjunction", "pron": "pronoun", "num": "numeral", "art": "article",
    "int": "interjection", "aux": "auxiliary", "abbr": "abbreviation",
}

# ECDICT 的 exchange 键 → isdict 的 form_type
FORM_TYPE = {
    "p": "past", "d": "past_participle", "i": "gerund",
    "3": "present_3rd", "s": "plural", "r": "comparative", "t": "superlative",
}

# ECDICT 的 tag 代码 → 展示名
TAG_NAME = {
    "zk": "中考", "gk": "高考", "cet4": "CET4", "cet6": "CET6", "ky": "考研",
    "toefl": "托福", "ielts": "雅思", "gre": "GRE",
}

POLLY = re.compile(r"^([a-zA-Z]{1,4}\.)\s*(.*)$")


def connect(path: str) -> sqlite3.Connection:
    uri = "file:" + urllib.parse.quote(os.path.abspath(path).replace("\\", "/")) + "?mode=ro"
    conn = sqlite3.connect(uri, uri=True, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn


def dominant_pos(raw: str | None) -> str:
    """`n:15/v:85` → `verb`：取占比最高的一档。"""
    if not raw:
        return ""
    best, best_n = "", -1
    for part in raw.split("/"):
        code, _, num = part.partition(":")
        try:
            n = int(num)
        except ValueError:
            n = 0
        if n > best_n:
            best, best_n = code.strip(), n
    return POS_CODE.get(best.lower(), "")


def normalize_ipa(raw: str | None) -> str:
    """ECDICT 里混了西里尔字母 ә，换成 IPA 的 ə；再补上斜杠。"""
    if not raw:
        return ""
    ipa = raw.strip().replace("ә", "ə")
    return ipa if ipa.startswith(("/", "[")) else f"/{ipa}/"


def split_lines(raw: str | None) -> list[str]:
    return [ln.strip() for ln in (raw or "").splitlines() if ln.strip()]


def build_senses(row: sqlite3.Row) -> list[dict]:
    """把 ECDICT 的 translation 拆成义项：一行一个，行首的词性单独拎出来。"""
    fallback = dominant_pos(row["pos"])
    en_lines = split_lines(row["definition"])
    senses: list[dict] = []

    for idx, line in enumerate(split_lines(row["translation"]), start=1):
        m = POLLY.match(line)
        pos_code = POS_CODE.get(m.group(1).rstrip(".").lower(), fallback) if m else fallback
        text = (m.group(2) if m else line).strip()
        if not text:
            continue
        senses.append({
            "sense_id": len(senses) + 1,
            "pos": pos_code,
            "sense_order": len(senses) + 1,
            "definitions_zh": [{"gloss_id": len(senses) + 1, "gloss_order": 1,
                                "text_zh_hans": text, "is_primary": True}],
        })

    # 英文释义挂到第一条义项上（中文释义优先，这里只是补全）
    if senses and en_lines:
        senses[0]["definitions_en"] = [
            {"gloss_id": i + 1, "gloss_order": i + 1,
             "text_en": (POLLY.match(ln).group(2) if POLLY.match(ln) else ln).strip()}
            for i, ln in enumerate(en_lines)
        ]
    elif en_lines:
        senses.append({
            "sense_id": 1, "pos": fallback, "sense_order": 1,
            "definitions_en": [{"gloss_id": i + 1, "gloss_order": i + 1,
                                "text_en": (POLLY.match(ln).group(2) if POLLY.match(ln) else ln).strip()}
                               for i, ln in enumerate(en_lines)],
        })
    return senses


def build_variants(row: sqlite3.Row) -> list[dict]:
    out: list[dict] = []
    seen: set[str] = set()
    for part in (row["exchange"] or "").split("/"):
        key, _, value = part.partition(":")
        form_type = FORM_TYPE.get(key.strip())
        text = value.strip()
        # 同一个词形可能既是过去式又是过去分词（advised），只留第一条
        if not form_type or not text or text.lower() in seen:
            continue
        seen.add(text.lower())
        out.append({"form_text": text, "relation_kind": "form", "form_type": form_type})
    return out


def build_tags(row: sqlite3.Row) -> list[str]:
    codes = (row["tag"] or "").split()
    return [TAG_NAME[c] for c in codes if c in TAG_NAME]


def to_word(row: sqlite3.Row) -> dict:
    senses = build_senses(row)
    # ECDICT 的 pos 字段有 1/4 是空的，退到第一条义项的词性上，
    # 免得导进 App 之后「词性」那栏空着
    pos = dominant_pos(row["pos"]) or (senses[0]["pos"] if senses else "")
    frequency = row["frq"] or row["bnc"] or 0
    return {
        "id": row["id"],
        "headword": row["word"],
        "pos": pos,
        "collins_stars": row["collins"] or 0,
        "oxford_level": 1 if row["oxford"] else 0,
        "frequency_rank": frequency,
        "frequency_count": 0,
        # 把 ECDICT 的考试标签透出成扩展字段，客户端可以自行拼接
        "tags": build_tags(row),
        "pronunciations": [{"accent": "british", "ipa": normalize_ipa(row["phonetic"]),
                            "is_primary": True}] if row["phonetic"] else [],
        "senses": senses,
        "variants": build_variants(row),
        "entry_examples": [],
        "entry_definitions": [],
    }


def lookup(conn: sqlite3.Connection, word: str) -> dict | None:
    """先精确查，再试连字符/空格互换（PDF 里的 air-conditioning 在库里可能是 air conditioning）。"""
    candidates = [word]
    if "-" in word:
        candidates.append(word.replace("-", " "))
    if " " in word:
        candidates.append(word.replace(" ", "-"))
    for cand in candidates:
        with DB_LOCK:
            row = conn.execute(
                "SELECT id, word, phonetic, definition, translation, pos, collins, oxford,"
                " tag, bnc, frq, exchange FROM stardict WHERE word = ? COLLATE NOCASE LIMIT 1",
                (cand,),
            ).fetchone()
        if row:
            return to_word(row)
    return None


class Handler(BaseHTTPRequestHandler):
    server_version = "isdict-local/0.1"

    def _json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _ok(self, data, meta=None, status: int = 200) -> None:
        self._json({"success": True, "data": data, "error": None, "meta": meta}, status)

    def _err(self, code: str, message: str, status: int) -> None:
        self._json({"success": False, "data": None,
                    "error": {"code": code, "message": message}, "meta": None}, status)

    def _path_parts(self) -> list[str]:
        path = urllib.parse.urlsplit(self.path).path
        return [urllib.parse.unquote(p) for p in path.strip("/").split("/") if p]

    def do_GET(self) -> None:  # noqa: N802
        parts = self._path_parts()
        if parts == ["health"] or parts == ["api", "v1", "health"]:
            self._json({"status": "ok", "service": "isdict-local"})
            return
        if len(parts) >= 3 and parts[0] == "api" and parts[1] == "v1" and parts[2] == "words":
            if len(parts) == 3:
                self._err("MISSING_PARAMETER", "headword required", 400)
                return
            headword = parts[3] if len(parts) > 3 else ""
            item = lookup(self.server.conn, headword)  # type: ignore[attr-defined]
            if not item:
                self._err("WORD_NOT_FOUND", f"Word '{headword}' not found in dictionary", 404)
                return
            if len(parts) == 4:
                self._ok(item)
            elif parts[4] == "pronunciations":
                self._ok(item["pronunciations"])
            elif parts[4] == "senses":
                self._ok(item["senses"])
            else:
                self._err("INVALID_PARAMETER", f"unknown sub-resource '{parts[4]}'", 400)
            return
        self._err("INVALID_PARAMETER", f"unknown path '{self.path}'", 404)

    def do_POST(self) -> None:  # noqa: N802
        parts = self._path_parts()
        if parts == ["api", "v1", "words", "batch"]:
            length = int(self.headers.get("Content-Length") or 0)
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except json.JSONDecodeError:
                self._err("INVALID_PARAMETER", "body is not valid JSON", 400)
                return
            words = [str(w).strip() for w in body.get("words") or [] if str(w).strip()]
            if not words:
                self._err("MISSING_PARAMETER", "words required", 400)
                return
            if len(words) > 100:
                self._err("BATCH_LIMIT_EXCEEDED", "batch limit is 100", 400)
                return

            found: list[dict] = []
            not_found: list[str] = []
            for w in dict.fromkeys(words):
                item = lookup(self.server.conn, w)  # type: ignore[attr-defined]
                (found.append(item) if item else not_found.append(w))
            self._ok(found, meta={"requested": len(words), "found": len(found),
                                  "not_found": not_found})
            return
        self._err("INVALID_PARAMETER", f"unknown path '{self.path}'", 404)

    def log_message(self, *args) -> None:  # 静音，进度由调用方打印
        pass


def main() -> int:
    ap = argparse.ArgumentParser(description="ECDICT 版本地词典服务")
    ap.add_argument("--db", default=DB_PATH)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8080)
    args = ap.parse_args()

    if not os.path.exists(args.db):
        print(f"找不到数据库 {args.db}")
        return 1

    conn = connect(args.db)
    total = conn.execute("SELECT COUNT(*) FROM stardict").fetchone()[0]
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.conn = conn  # type: ignore[attr-defined]
    print(f"词典 {args.db}：{total} 词条")
    print(f"监听 http://{args.host}:{args.port}/api/v1")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
