#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给考研 5500 词表补发音到 app/src/main/assets/pron/。

源是爱词霸的 TTS：`http://res-tts.iciba.com/{md5前三位分层}/{md5}.mp3`，
和仓库里原有的 3246 个音频同源，所以音色一致。k3y.sql 里存的那些 URL
就是同一个规则算出来的，这里按词现算，不依赖那份 dump。

    python download_pron.py --csv 考研5500-merged.csv --assets ../../app/src/main/assets/pron

已存在的文件（<词>.mp3 或 <词小写>.mp3）会跳过，可反复跑。
"""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
import hashlib
import os
import time
import urllib.request

UA = {"User-Agent": "Mozilla/5.0"}
BAD_NAME = set('/\\:*?"<>|')


def url_of(word: str) -> str:
    h = hashlib.md5(word.encode("utf-8")).hexdigest()
    return f"http://res-tts.iciba.com/{h[0]}/{h[1]}/{h[2]}/{h}.mp3"


def is_mp3(data: bytes) -> bool:
    return len(data) > 500 and (
        data[:3] == b"ID3" or (data[0] == 0xFF and (data[1] & 0xE0) == 0xE0)
    )


def fetch(item: tuple[str, str]) -> tuple[str, str]:
    word, out_dir = item
    if any(c in BAD_NAME for c in word):
        return word, "bad-name"
    exact = os.path.join(out_dir, word + ".mp3")
    lower = os.path.join(out_dir, word.lower() + ".mp3")
    for path in (exact, lower):
        if os.path.exists(path) and os.path.getsize(path) > 500:
            return word, "cached"

    for attempt in range(3):
        try:
            req = urllib.request.Request(url_of(word), headers=UA)
            with urllib.request.urlopen(req, timeout=20) as resp:
                data = resp.read()
            if not is_mp3(data):
                return word, f"bad-content:{len(data)}"
            with open(exact, "wb") as f:
                f.write(data)
            return word, "ok"
        except Exception as exc:
            if attempt == 2:
                return word, f"fail:{type(exc).__name__}"
            time.sleep(1 + attempt)
    return word, "fail"


def main() -> int:
    ap = argparse.ArgumentParser(description="按词表补发音到 assets/pron")
    ap.add_argument("--csv", required=True, help="词表 CSV（取 word 列）")
    ap.add_argument("--assets", required=True, help="assets/pron 目录")
    ap.add_argument("--workers", type=int, default=16)
    args = ap.parse_args()

    os.makedirs(args.assets, exist_ok=True)
    words = [r["word"].strip() for r in csv.DictReader(open(args.csv, encoding="utf-8-sig")) if r.get("word")]
    print(f"词表 {len(words)} 个，目标目录 {args.assets}")

    stats: dict[str, int] = {}
    fails: list[str] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as ex:
        for i, (word, status) in enumerate(ex.map(fetch, [(w, args.assets) for w in words]), 1):
            stats[status] = stats.get(status, 0) + 1
            if status.startswith(("fail", "bad")):
                fails.append(f"{word}({status})")
            if i % 500 == 0:
                print(f"  进度 {i}/{len(words)}")

    files = [f for f in os.listdir(args.assets) if f.endswith(".mp3")]
    total = sum(os.path.getsize(os.path.join(args.assets, f)) for f in files)
    print("\n结果:", ", ".join(f"{k}={v}" for k, v in sorted(stats.items())))
    print(f"目录现有 {len(files)} 个文件，共 {total / 1048576:.1f} MB")
    if fails:
        print(f"失败 {len(fails)} 个（前 10）: {fails[:10]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
