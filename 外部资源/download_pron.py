# 从 k3y.sql 提取 word->mp3 URL 映射，下载与本应用词库匹配的发音到 assets/pron/
import re, sqlite3, os, sys, time
import urllib.request
import concurrent.futures

BASE = r"D:/1ProgramProjects/KotlinProject/EnglishStudying"
SQL = BASE + "/外部资源/k3y-master/database/k3y.sql"
DB = BASE + "/外部资源/esdb/english_studying.db"
OUT = BASE + "/app/src/main/assets/pron"

sql = open(SQL, encoding="utf-8", errors="replace").read()
# 行形状: (id, 'word', count, level, 'mean', 'http://...mp3')
rows = re.findall(r"\(\d+, '([^']*?)', \d+, \d+, '(?:[^'\\]|\\.)*', '(https?://[^']*?)'\)", sql)
print("k3y rows parsed:", len(rows))
word_url = {}
for w, u in rows:
    word_url.setdefault(w.lower(), u)

con = sqlite3.connect(DB)
app_words = [r[0] for r in con.execute("select headword from words")]
con.close()

targets = []
skipped = []
for w in app_words:
    u = word_url.get(w.lower())
    if u is None:
        skipped.append(w)
        continue
    targets.append((w, u))

print("app words:", len(app_words), "| matched:", len(targets), "| no audio:", len(skipped))
os.makedirs(OUT, exist_ok=True)

BAD = set('/\\:*?"<>|')
def fetch(item):
    w, u = item
    if any(c in BAD for c in w):
        return (w, "bad-name")
    dest = os.path.join(OUT, w + ".mp3")
    if os.path.exists(dest) and os.path.getsize(dest) > 500:
        return (w, "cached")
    for attempt in range(3):
        try:
            req = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=20) as r:
                data = r.read()
            if len(data) > 500 and (
                data[:3] == b"ID3" or (data[0] == 0xFF and (data[1] & 0xE0) == 0xE0)
            ):
                with open(dest, "wb") as f:
                    f.write(data)
                return (w, "ok")
            return (w, "bad-content:%d" % len(data))
        except Exception as e:
            if attempt == 2:
                return (w, "fail:%s" % type(e).__name__)
            time.sleep(1 + attempt)

results = []
with concurrent.futures.ThreadPoolExecutor(max_workers=16) as ex:
    for res in ex.map(fetch, targets):
        results.append(res)
        if len(results) % 500 == 0:
            print("progress:", len(results))

fails = [r for r in results if r[1].startswith(("fail", "bad"))]
ok = [r for r in results if r[1] in ("ok", "cached")]
total = sum(os.path.getsize(os.path.join(OUT, f)) for f in os.listdir(OUT))
print("downloaded/cached:", len(ok), "| failed:", len(fails))
print("total size: %.1f MB" % (total / 1048576))
if fails:
    print("failures (first 20):", fails[:20])
if skipped:
    print("no audio for (first 30):", skipped[:30])
