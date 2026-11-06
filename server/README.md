# 词表服务（server/）

给 Android 客户端用的后端：**分发词表** + **备份/同步学习进度**。
局域网自用，不面向公网。

技术栈：Python 3.13 · FastAPI · SQLAlchemy 2.1（async）· psycopg 3（async）· PostgreSQL 18 · Alembic · uv · Argon2 + JWT

---

## 快速开始

```bash
cd server
cp .env.example .env          # 填 PostgreSQL 密码 + JWT_SECRET
uv sync                       # 建 .venv 并装依赖
uv run alembic upgrade head   # 建表
uv run python -m app.cli serve --port 8000
```

首次使用：浏览器打开首页 **注册第一个账号，它会自动成为管理员**
（之后注册的都是普通用户）。也可以命令行建号：

```bash
uv run python -m app.cli create-user alice 密码 --admin
uv run python -m app.cli users
```

浏览器打开 `http://127.0.0.1:8000` 能看到词表列表和下载按钮；
`/docs` 是自动生成的 OpenAPI 交互文档。

手机要访问，得用电脑的**局域网 IP**（首页会直接印出来），并且放行端口：

```powershell
netsh advfirewall firewall add rule name="vocab-server" dir=in action=allow protocol=TCP localport=8000
```

## 导入词表（管理员）

导入会写公共词库 `words`，所以只有管理员能执行（命令行不校验角色，因为它直接连库）。

```bash
# 按文件里的 deck 列拆成多张词表
uv run python -m app.cli import-csv ../外部资源/考研5500/考研5500-L3.csv --use-deck-column

# 指定词表名 + 覆盖已有挂载
uv run python -m app.cli import-csv ../docs/sample-vocab.csv --deck 示例 --replace

uv run python -m app.cli list
```

CSV / JSON 的列名规则和 App 的导入器完全一致（见 [docs/import-format.md](../docs/import-format.md)），
App 导出的文件可以直接传上来。

## 账号与权限

密码用 **Argon2** 哈希（`pwdlib`），登录换取 **JWT**（HS256，默认 30 天，密钥在 `.env` 的
`JWT_SECRET`）。两级角色：

| 角色 | 能做什么 |
|---|---|
| `user` | 读官方词表 + 别人**公开**的词表；建/改/删**自己名下**的词表（含公开、自定义分享码）；维护自己的**私有词条**并挂到词表；读写自己的学习进度 |
| `admin` | 以上全部 + 修改官方词表 + **导入 CSV**（唯一会写公共词库 `words` 的入口）+ 管理账号（增删、指定角色） |

`words`（公共词库）**没有任何面向普通用户的写接口**。导入接口只认管理员，删除词表也
不会碰 `words` 里的词条。用户想加自己的词，写的是另一张表 `user_words`——
和公共词库完全隔离，同名也不冲突，别人看不见也改不了。

**只挂载 + 改词另存**：建表和「导入其他词表」都只写 `deck_words` / `deck_user_words`
挂载行，不复制词条；真的要改某个词时（`PATCH /api/decks/{id}/words/{wordId}`），
服务端才把公共词复制成这个人的 `user_words`，并把**这张表**的挂载换过去。
公共词库和别人的词表都不受影响。

越权在数据层兜底：所有进度 SQL 都带 `user_id` 条件，主键是 `(user_id, ...)` 复合键，
别人的数据连查都查不到；访问别人的私有词表一律返回 404（不泄漏存在性）。

`ALLOW_REGISTRATION=false` 可以关掉自助注册（第一个账号永远能注册）。

## 接口

除 `/api/health` 和 `/api/auth/*` 外都要 `Authorization: Bearer <token>`。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/health` | 公开 | 连通性 + 词表/词条数 |
| POST | `/api/auth/register` | 公开 | 注册，返回令牌；第一个账号是管理员 |
| POST | `/api/auth/login` | 公开 | 登录 |
| GET | `/api/auth/me` | 登录 | 当前账号 |
| POST | `/api/auth/password` | 登录 | 改密码 |
| GET | `/api/decks?scope=all\|official\|public\|mine&q=` | 登录 | 官方 + 别人公开的 + 自己的（管理员可 `?all=true`） |
| GET | `/api/decks/by-code/{code}` | 登录 | **分享码直达**；码里的横线、小写都会被归一化 |
| GET | `/api/decks/{id}` | 可见 | 单张词表 |
| GET | `/api/decks/{id}/words?offset&limit&q` | 可见 | 分页预览 / 搜索 |
| GET | `/api/decks/{id}/export?format=csv\|json` | 可见 | **下载词表**，带 ETag，没变返回 304 |
| POST | `/api/decks` | 登录 | 新建；`visibility` + `seedFromDeckId`（只挂载源表的词） |
| PATCH | `/api/decks/{id}` | 所有者/管理员 | 改名、简介、`visibility`、自定义 `shareCode` |
| POST | `/api/decks/{id}/share-code/reset` | 所有者/管理员 | 换一个随机分享码，旧的立刻失效 |
| POST | `/api/decks/{id}/import-from` | 所有者/管理员 | 把另一张表的词挂过来（`append` / `replace`），不复制词条 |
| DELETE | `/api/decks/{id}` | 所有者/管理员 | 删词表（不删 `words` 里的词条） |
| POST | `/api/decks/{id}/words` | 所有者/管理员 | 挂载词：先找自己的私有词，再找公共词库 |
| GET | `/api/words/search?q=` | 登录 | 「往词表加词」用的搜索：公共词库 + 自己的私有词条 |
| GET | `/api/my-words` | 登录 | 自己的私有词条（可 `?q=` 搜） |
| POST | `/api/my-words` | 登录 | 新建私有词条（不碰公共词库） |
| PATCH | `/api/my-words/{id}` | 本人 | 改私有词条 |
| DELETE | `/api/my-words/{id}` | 本人 | 删私有词条（挂载级联消失） |
| GET | `/api/users` | 管理员 | 账号列表 |
| POST | `/api/users` | 管理员 | 建号并指定角色 |
| DELETE | `/api/users/{id}` | 管理员 | 删号（连带词表、私有词条、进度） |
| POST | `/api/decks/{id}/words/remove` | 所有者/管理员 | 摘掉挂载 |
| PATCH | `/api/decks/{id}/words/{wordId}?source=global\|private` | 所有者/管理员 | 改词表里的词。**改公共词会复制一份私有词**（copy-on-write）再把这张表的挂载换过去 |
| POST | `/api/decks/{id}/import` | **管理员** | 上传 CSV/JSON，会写公共词库 |
| POST | `/api/sync/push` | 登录 | 推送进度增量 |
| GET | `/api/sync/pull?since=…` | 登录 | 拉取进度增量 |
| GET | `/api/sync/status` | 登录 | 自己的行数与设备列表 |

首页 `/` 是一个小登录页：登录后列出可见词表，可直接下载 CSV/JSON（令牌存在 localStorage，
下载走 fetch + blob）。`/docs` 是 OpenAPI 交互文档。

## 学习进度同步

### 为什么用词头当键

手机上的 `words.id` 和服务端的 `words.id` 各自自增，对不上；拿 id 同步，
换一台设备或者重建词表就全乱了。所以进度表的主键是 `headword_key = lower(headword)`，
连「本地导入、服务端没有的词」也能一起备份。

### 同步协议

1. 客户端把本地变更打包成 `states / logs / tags / wordTags / flags / settings` 六组，
   POST 到 `/api/sync/push`，每条都带**客户端的毫秒时间戳** `updatedAt`。
2. 服务端按主键 upsert：
   - 状态类字段走 **LWW**，`updatedAt` 更新才覆盖，**相同则不动**（重复推送不会产生新版本）；
   - `review_logs` 只追加，靠 `clientLogId` 唯一约束去重，`ON CONFLICT DO NOTHING`。
3. 任何一次真实写入都会 `nextval('sync_revision')`，所有表共用一个序列。
4. 客户端保存返回的 `revision`，下次 `GET /api/sync/pull?since=<revision>` 拿增量；
   `hasMore=true` 就接着拉，直到 `revision` 不再前进。

```jsonc
// POST /api/sync/push
{
  "deviceId": "0190f0e0-0000-7000-8000-000000000001",
  "deviceName": "一加",
  "platform": "android",
  "states": [{
    "headword": "absent", "updatedAt": 1762390000000,
    "state": "REVIEW", "easeFactor": 2.5, "intervalDays": 3.0,
    "repetitions": 2, "learningStep": 0, "dueAtMinutes": 29880000,
    "lastReviewedAt": 1762390000000, "lapses": 0
  }],
  "logs": [{
    "clientLogId": "0190f0e0-0000-7000-8000-000000000002",
    "headword": "absent", "rating": "GOOD",
    "reviewedAt": 1762390000000, "elapsedMs": 3000, "intervalDaysAfter": 3.0
  }],
  "tags":      [{ "name": "写作", "updatedAt": 1762390000000, "deleted": false }],
  "wordTags":  [{ "headword": "absent", "tagName": "写作", "updatedAt": 1762390000000, "deleted": false }],
  "flags":     [{ "headword": "absent", "updatedAt": 1762390000000, "priority": true }],
  "settings":  [{ "key": "dailyNewLimit", "value": "50", "updatedAt": 1762390000000 }]
}
```

删除靠 `deleted: true` 的墓碑，而不是删行 —— 否则另一台设备下次上传会把它复活。

### 时钟

LWW 比的是**客户端时钟**。同一台手机自增没问题；多设备时如果手表慢了几分钟，
慢的那台修改会被当成旧数据。手机自动对时一般够用，真要严格可以后续改成
服务端单调版本 + 向量时钟。

## 表结构

```
users                                   账号（Argon2 哈希 + 角色）
decks ──< deck_words >── words          词表与公共词库；decks.owner_id 空 = 官方
      └─< deck_user_words >── user_words 私有词条（按 user_id 隔离，和 words 互不影响）
devices                                 同步过的设备（按 user_id 隔离）
word_states                             每词一条记忆状态（键 = lower(headword)）
review_logs                             作答流水（client_log_id 唯一）
user_tags / word_user_tags / word_flags  用户标签、挂载、优先学
settings                                DataStore 设置项
```

所有同步表都有 `revision bigint default nextval('sync_revision')`，并建了索引。

## 备份

数据库就是普通 PostgreSQL，直接用 `pg_dump`：

```bash
pg_dump -h 127.0.0.1 -U postgres -d english_vocab -Fc -f vocab-$(date +%F).dump
```

恢复：`pg_restore -d english_vocab --clean vocab-2026-11-06.dump`。

## 目录

```
server/
├── app/
│   ├── config.py     .env 配置
│   ├── db.py         异步引擎与会话
│   ├── models.py     全部表
│   ├── schemas.py    请求/响应模型（camelCase）
│   ├── importer.py   CSV/JSON 解析与写库
│   ├── sync.py       push / pull / status
│   ├── main.py       路由
│   └── cli.py        命令行
├── migrations/       Alembic（异步模板）
├── pyproject.toml / uv.lock
└── .env.example
```

## 已知限制

- 没有找回密码 / 邮箱验证：忘了密码就用 `create-user` 另建一个，或直接改库。
- 令牌是长期 JWT，没有刷新与吊销列表；丢手机的场景只能改密码（旧令牌仍有效到过期）。
- 只同步学习进度，不同步「本机导入的词表」——词表从 `/api/decks` 重新下即可。
- 私有词条不进同步协议：它本来就存在服务器上，换设备登录同一个账号就能看到。
- `settings` 按字符串存，改动结构时记得兼容旧值。
