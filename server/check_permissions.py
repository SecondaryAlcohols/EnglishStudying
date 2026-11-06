"""权限 / 私有词条 / 同步隔离的端到端自测。

跑之前先起服务，并准备一个管理员账号：

    uv run python -m app.cli create-user t_admin t_admin_123 --admin
    uv run python -m app.cli serve --port 8000
    uv run python check_permissions.py

管理员账号可以用环境变量覆盖：VS_ADMIN_USER / VS_ADMIN_PASS。
脚本会自己注册一个临时普通用户，跑完把它删掉（连带它名下的词表与进度），
不会碰你手动注册的账号。
"""

from __future__ import annotations

import json
import os
import random
import string
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8000"
ADMIN_USER = os.environ.get("VS_ADMIN_USER", "t_admin")
ADMIN_PASS = os.environ.get("VS_ADMIN_PASS", "t_admin_123")

results: list[tuple[bool, str]] = []


def call(method: str, path: str, token: str | None = None, body: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    if data:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request) as response:
            raw = response.read().decode()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        raw = error.read().decode()
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, raw


def call_raw(method: str, path: str, token: str | None = None) -> tuple[int, str]:
    request = urllib.request.Request(BASE + path, method=method)
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, response.read().decode("utf-8-sig")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def check(name: str, condition: bool, detail: str = ""):
    results.append((condition, name))
    print(("  ✓ " if condition else "  ✗ ") + name + (f"  [{detail}]" if detail and not condition else ""))


def login(username: str, password: str) -> str:
    status, data = call(
        "POST", "/api/auth/login", body={"username": username, "password": password}
    )
    if status != 200:
        raise SystemExit(
            f"登录 {username} 失败（{status}）：{data}\n"
            f"先建号：uv run python -m app.cli create-user {username} {password} --admin"
        )
    return data["accessToken"]


def rand(n: int = 6) -> str:
    return "".join(random.choices(string.ascii_lowercase + string.digits, k=n))


suffix = rand()
admin_token = login(ADMIN_USER, ADMIN_PASS)
created_user_id: str | None = None
created_deck_ids: list[int] = []

print("== 未登录")
status, _ = call("GET", "/api/decks")
check("未登录访问词表被拒（401）", status == 401, str(status))
status, _ = call("GET", "/api/health")
check("健康检查不需要登录", status == 200, str(status))

print("== 注册与登录")
username = f"t_user_{suffix}"
status, data = call(
    "POST", "/api/auth/register", body={"username": username, "password": "user123"}
)
check("自助注册成功", status == 201, f"{status} {data}")
if status != 201:
    raise SystemExit("注册失败，后续用例无法继续")
created_user_id = data["user"]["id"]
user_token = data["accessToken"]
check("非首个账号是普通用户", data["user"]["role"] == "user", str(data["user"]))

status, _ = call("POST", "/api/auth/login", body={"username": username, "password": "wrong"})
check("密码错误被拒（401）", status == 401, str(status))

status, data = call("GET", "/api/auth/me", user_token)
check("/me 返回当前账号", status == 200 and data["username"] == username, str(data))

status, _ = call("GET", "/api/users", user_token)
check("普通用户看不到账号列表（403）", status == 403, str(status))

print("== 词表可见性")
status, decks = call("GET", "/api/decks", user_token)
official = [d for d in decks if d["official"]]
check("普通用户能看到官方词表", len(official) >= 2, str(len(official)))
check("官方词表对普通用户不可编辑", all(not d["editable"] for d in official))

deck_name = f"测试表_{suffix}"
status, admin_deck = call("POST", "/api/decks", admin_token, {"name": deck_name})
check("管理员建表成功", status == 201, f"{status} {admin_deck}")
admin_deck_id = admin_deck["id"]
created_deck_ids.append(admin_deck_id)

status, user_deck = call("POST", "/api/decks", user_token, {"name": deck_name})
check("普通用户可以用同名词表名（各自命名空间）", status == 201, f"{status} {user_deck}")
check("同名但不是同一张表", user_deck["id"] != admin_deck_id)
user_deck_id = user_deck["id"]

status, decks = call("GET", "/api/decks", user_token)
check("普通用户看不到别人的私有表", all(d["id"] != admin_deck_id for d in decks))

status, _ = call("GET", f"/api/decks/{admin_deck_id}", user_token)
check("直接访问别人的词表 → 404", status == 404, str(status))

status, _ = call("PATCH", f"/api/decks/{admin_deck_id}", user_token, {"name": "偷改名"})
check("改别人的词表 → 404", status == 404, str(status))

print("== 公共词库只读")
status, _ = call("POST", f"/api/decks/{user_deck_id}/import", user_token)
check("普通用户不能导入（会写 words）", status in (403, 422), str(status))

status, data = call(
    "POST", f"/api/decks/{user_deck_id}/words", user_token,
    {"headwords": ["absent", "abandon", "不存在的词xyz"]},
)
check("普通用户能把词库已有的词挂到自己词表", status == 200 and data["added"] == 2, str(data))
check("词库里没有的词被列出来而不是自动创建", data.get("notInLibrary") == ["不存在的词xyz"], str(data))

status, _ = call(
    "POST", f"/api/decks/{official[0]['id']}/words", user_token, {"headwords": ["absent"]}
)
check("普通用户不能改官方词表的挂载（403）", status == 403, str(status))

status, data = call(
    "POST", f"/api/decks/{user_deck_id}/words/remove", user_token, {"headwords": ["abandon"]}
)
check("普通用户能把自己词表里的词摘掉", status == 200 and data["removed"] == 1, str(data))

print("== 私有词条 user_words")
status, word = call(
    "POST", "/api/my-words", user_token,
    {"headword": "hachimi", "meaning": "n. 测试词", "pos": "n.", "frequency": 99999},
)
check("普通用户能建私有词条", status == 201, f"{status} {word}")
private_id = word["id"] if status == 201 else 0
check("私有词条带 source=private", status == 201 and word["source"] == "private", str(word))

status, _ = call("POST", "/api/my-words", user_token, {"headword": "Hachimi", "meaning": "dup"})
check("同一个人私有库不能重复（忽略大小写）", status == 409, str(status))

status, data = call(
    "POST", f"/api/decks/{user_deck_id}/words", user_token, {"headwords": ["hachimi"]}
)
check("私有词能挂到自己的词表", status == 200 and data["added"] == 1, str(data))

status, page = call("GET", f"/api/decks/{user_deck_id}/words", user_token)
check(
    "词表列表里带 source=private",
    any(item["source"] == "private" and item["headword"] == "hachimi" for item in page["items"]),
    str(page["items"]),
)

status, csv_text = call_raw("GET", f"/api/decks/{user_deck_id}/export?format=csv", user_token)
check("导出包含私有词", "hachimi" in csv_text, csv_text[:120])

status, hits = call("GET", "/api/words/search?q=hachimi", user_token)
check(
    "合并搜索里私有词排在前面",
    bool(hits["items"]) and hits["items"][0]["source"] == "private",
    str(hits.get("items")),
)

status, data = call("GET", "/api/my-words", admin_token)
# 管理员自己也可能有私有词，所以不能断言 total == 0，只能断言「看不到别人那条」
check(
    "管理员看不到别人的私有词条",
    all(item["headword"] != "hachimi" for item in data["items"]),
    str(data["items"])[:200],
)

status, _ = call("PATCH", f"/api/my-words/{private_id}", admin_token, {"meaning": "hack"})
check("管理员也改不了别人的私有词条（404）", status == 404, str(status))

status, data = call("PATCH", f"/api/my-words/{private_id}", user_token, {"meaning": "n. 改过的释义"})
check("本人能改私有词条", status == 200 and data["meaning"] == "n. 改过的释义", str(data))

status, _ = call("DELETE", f"/api/my-words/{private_id}", user_token)
check("删除私有词条成功", status == 204, str(status))

status, page = call("GET", f"/api/decks/{user_deck_id}/words", user_token)
check(
    "删词后挂载级联消失",
    all(item["headword"] != "hachimi" for item in page["items"]),
    str(page["items"]),
)

print("== 管理员")
status, data = call(
    "POST", f"/api/decks/{admin_deck_id}/words", admin_token, {"headwords": ["absent"]}
)
check("管理员能改自己（或官方）的词表", status == 200, str(data))

status, data = call("GET", "/api/decks?all=true", admin_token)
check("管理员 ?all=true 能看到别人的词表", any(d["id"] == user_deck_id for d in data), str(len(data)))

print("== 进度同步按账号隔离")
payload = {
    "deviceId": "device-test-0001",
    "deviceName": "测试机",
    "platform": "android",
    "states": [{
        "headword": "absent", "updatedAt": 1762390000000, "state": "REVIEW",
        "easeFactor": 2.5, "intervalDays": 3.0, "repetitions": 2,
        "learningStep": 0, "dueAtMinutes": 29880000, "lastReviewedAt": 1762390000000, "lapses": 0,
    }],
    "logs": [{
        "clientLogId": "0190f0e0-0000-7000-8000-000000000011",
        "headword": "absent", "rating": "GOOD", "reviewedAt": 1762390000000,
        "elapsedMs": 1200, "intervalDaysAfter": 3.0,
    }],
}
status, data = call("POST", "/api/sync/push", user_token, payload)
check("推送成功", status == 200 and data["applied"]["states"] == 1, str(data))

status, data = call("GET", "/api/sync/status", user_token)
check("看到自己的 1 状态 / 1 日志", data["counts"]["states"] == 1 and data["counts"]["logs"] == 1, str(data))

status, data = call("GET", "/api/sync/pull?since=0", user_token)
check("能拉回自己的增量", len(data["states"]) == 1 and len(data["logs"]) == 1, str(data))

status, data = call("GET", "/api/sync/status", admin_token)
check("管理员看不到别人的进度（0 条）", data["counts"]["states"] == 0, str(data))

status, _ = call("POST", "/api/sync/push", None, payload)
check("未登录不能推进度", status == 401, str(status))

print("== 云端词表：公开 / 分享码 / 只挂载 / 改词另存")

# 第二个普通用户，用来验证「公开表别人能读不能改」
status, data = call(
    "POST", "/api/auth/register", body={"username": f"t_user2_{suffix}", "password": "user123"}
)
check("再注册一个普通用户", status == 201, f"{status} {data}")
user2_token = data["accessToken"]
user2_id = data["user"]["id"]

# 用官方表做种子，只挂载不复制
status, official_decks = call("GET", "/api/decks?scope=official", user_token)
seed_id = official_decks[0]["id"]
seed_count = official_decks[0]["wordCount"]

status, pub = call(
    "POST",
    "/api/decks",
    user_token,
    {"name": f"公开表_{suffix}", "visibility": "public", "seedFromDeckId": seed_id},
)
check("建公开表并从官方表挂载", status == 201 and pub["wordCount"] == seed_count, f"{status} {pub}")
pub_id = pub["id"]
created_deck_ids.append(pub_id)
check("公开表自动生成分享码", bool(pub.get("shareCode")) and len(pub["shareCode"]) == 8, str(pub.get("shareCode")))
check("公开之后仍然是「我的」", pub.get("mine") is True, str(pub.get("mine")))
check("公开之后带作者名", pub.get("ownerName") == username, str(pub.get("ownerName")))
check("公开之后仍可编辑", pub.get("editable") is True, str(pub.get("editable")))
check("官方表的词只是挂过来（没复制词条）", pub["wordCount"] == seed_count, f"{pub['wordCount']} vs {seed_count}")

code = pub["shareCode"]
status, hit = call("GET", f"/api/decks/by-code/{code.lower()}", user2_token)
check("别人用 8 位码能查到", status == 200 and hit["id"] == pub_id, f"{status} {hit}")

formatted = f"{code[:4].lower()}-{code[4:].lower()}"
status, hit = call("GET", f"/api/decks/by-code/{formatted}", user2_token)
check("小写 + 横线的码也能查到（归一化）", status == 200 and hit["id"] == pub_id, str(status))

custom = f"my-code-{suffix}"
status, data = call("PATCH", f"/api/decks/{pub_id}", user_token, {"shareCode": custom})
check("自定义分享码成功", status == 200 and data["shareCode"] == custom.upper().replace("-", ""), f"{status} {data}")
normalized = custom.upper().replace("-", "")
status, hit = call("GET", f"/api/decks/by-code/{custom}", user2_token)
check("按自定义码（带横线）能查到", status == 200 and hit["id"] == pub_id, str(status))

status, other_admin_deck = call(
    "POST", "/api/decks", admin_token, {"name": f"管理员表_{suffix}"}
)
created_deck_ids.append(other_admin_deck["id"])
status, _ = call(
    "PATCH", f"/api/decks/{other_admin_deck['id']}", admin_token, {"shareCode": normalized}
)
check("管理员也不能抢别人的分享码（409）", status == 409, str(status))

status, decks = call("GET", "/api/decks?scope=public", user2_token)
check("别人在「公开」页签里能看到", any(d["id"] == pub_id for d in decks), str([d["id"] for d in decks]))
status, decks = call("GET", "/api/decks?scope=public", user_token)
check("自己的公开表不进「公开」页签", all(d["id"] != pub_id for d in decks), str([d["id"] for d in decks]))
status, decks = call("GET", "/api/decks?scope=mine", user_token)
check("自己的公开表在「我的」里", any(d["id"] == pub_id for d in decks), str([d["id"] for d in decks]))
status, decks = call("GET", "/api/decks?scope=mine", user2_token)
check("scope=mine 里看不到别人的公开表", all(d["id"] != pub_id for d in decks))
status, decks = call("GET", "/api/decks?q=" + urllib.parse.quote(f"公开表_{suffix}"), user2_token)
check("按名字搜得到", any(d["id"] == pub_id for d in decks), str([d["name"] for d in decks]))

status, data = call("GET", f"/api/decks/{pub_id}", user2_token)
check("别人能读公开表", status == 200 and data["editable"] is False, f"{status} {data}")
status, _ = call("PATCH", f"/api/decks/{pub_id}", user2_token, {"name": "偷改"})
check("别人改公开表 → 403", status == 403, str(status))
status, _ = call("POST", f"/api/decks/{pub_id}/words", user2_token, {"headwords": ["abandon"]})
check("别人往公开表塞词 → 403", status == 403, str(status))

# 挂载导入：空表 ← 公开表
status, blank = call("POST", "/api/decks", user_token, {"name": f"导入目标_{suffix}"})
blank_id = blank["id"]
created_deck_ids.append(blank_id)
status, data = call(
    "POST", f"/api/decks/{blank_id}/import-from", user_token, {"sourceId": pub_id, "mode": "append"}
)
check("从公开表 append 导入", status == 200 and data["added"] == seed_count, f"{status} {data}")
status, data = call(
    "POST", f"/api/decks/{blank_id}/import-from", user_token, {"sourceId": pub_id, "mode": "append"}
)
check("再 append 一次不重复挂（added=0）", status == 200 and data["added"] == 0, str(data))
status, data = call(
    "POST", f"/api/decks/{blank_id}/import-from", user_token, {"sourceId": pub_id, "mode": "replace"}
)
check("replace 先清后挂", status == 200 and data["removed"] == seed_count and data["added"] == seed_count, str(data))
status, _ = call(
    "POST", f"/api/decks/{blank_id}/import-from", user_token, {"sourceId": blank_id, "mode": "append"}
)
check("不能从自己导入自己（400）", status == 400, str(status))

# copy-on-write：改公共词 → 另存私有词并换挂载
status, page = call("GET", f"/api/decks/{pub_id}/words?limit=1", user_token)
target = page["items"][0]
target_id, target_head = target["id"], target["headword"]
original_meaning = target.get("meaning")

status, edited = call(
    "PATCH",
    f"/api/decks/{pub_id}/words/{target_id}?source=global",
    user_token,
    {"meaning": "n. 我改过的释义 " + suffix},
)
check("改公共词 → 返回私有词条", status == 200 and edited["source"] == "private", f"{status} {edited}")
private_id = edited["id"]
check("私有词是新的一份", private_id != target_id, f"{private_id} vs {target_id}")

status, page = call("GET", f"/api/decks/{pub_id}/words?q={target_head}&limit=5", user_token)
rows = [(r["source"], r["headword"], r["meaning"]) for r in page["items"]]
check(
    "这张表里已经换成私有词，同一个词头不再来自公共库",
    any(src == "private" and suffix in (mean or "") for src, _, mean in rows)
    and not any(src == "global" and head.lower() == target_head.lower() for src, head, _ in rows),
    str(rows),
)

status, hits = call("GET", f"/api/words/search?q={target_head}&limit=5", user2_token)
global_rows = [h for h in hits["items"] if h["source"] == "global" and h["headword"].lower() == target_head.lower()]
check(
    "公共词库原词没被改",
    bool(global_rows) and (global_rows[0].get("meaning") or "") == (original_meaning or ""),
    str(global_rows[:1]),
)

status, again = call(
    "PATCH", f"/api/decks/{pub_id}/words/{private_id}?source=private", user_token,
    {"meaning": "n. 二改 " + suffix},
)
check("再改私有词就在原地更新", status == 200 and again["id"] == private_id, f"{status} {again}")

status, _ = call(
    "PATCH", f"/api/decks/{pub_id}/words/{target_id}?source=global", user2_token, {"meaning": "越权改"}
)
check("别人改不动我的词表里的词", status in (403, 404), str(status))

# 改回私有：码还在，但别人查不到、也读不到
status, data = call("PATCH", f"/api/decks/{pub_id}", user_token, {"visibility": "private"})
check("改回私有保留分享码", status == 200 and data["shareCode"] == normalized, str(data))
check("改回私有后仍标记为「我的」", data.get("mine") is True, str(data.get("mine")))
status, _ = call("GET", f"/api/decks/by-code/{normalized}", user2_token)
check("私有化后按码查 → 404", status == 404, str(status))
status, _ = call("GET", f"/api/decks/{pub_id}", user2_token)
check("私有化后直接读 → 404", status == 404, str(status))
status, data = call("GET", f"/api/decks/by-code/{normalized}", user_token)
check("作者自己还能按码查到", status == 200 and data["id"] == pub_id, str(status))

status, data = call("POST", f"/api/decks/{pub_id}/share-code/reset", user_token)
check("重置分享码", status == 200 and data["shareCode"] != normalized, str(data))
status, _ = call("GET", f"/api/decks/by-code/{normalized}", user_token)
check("旧码立刻失效", status == 404, str(status))

# 删掉第二个测试账号
status, _ = call("DELETE", f"/api/users/{user2_id}", admin_token)
check("清理第二个测试账号", status == 204, str(status))

print("== 清理")
for deck_id in created_deck_ids:
    call("DELETE", f"/api/decks/{deck_id}", admin_token)
if created_user_id:
    status, _ = call("DELETE", f"/api/users/{created_user_id}", admin_token)
    check("管理员能删测试账号（连带词表与进度）", status == 204, str(status))

failed = [name for ok, name in results if not ok]
print()
print(f"{len(results) - len(failed)}/{len(results)} 通过")
if failed:
    print("失败：")
    for name in failed:
        print("  -", name)
    raise SystemExit(1)
