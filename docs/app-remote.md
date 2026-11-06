# App 端接入计划（服务器 · 词表 · 进度）

把 `server/` 的能力接进 App：登录、下词表、私有词条、学习进度同步。
分四期，每期能独立交付、单独回退。

## 目标 / 非目标

**要做**：填服务器地址 → 登录 → 浏览并下载词表 → 私有词条 → 进度备份与恢复。
**暂不做**：多服务器切换、HTTPS 证书固定、离线同步队列（放最后）。

## 现状盘点

| 能力 | 现状 | 接入方式 |
|---|---|---|
| 导入解析 | `ImportParser.parse(text)` → `ImportPreview.rows()` → `WordImporter.import()` | 服务端返回的 CSV **直接喂进同一条链路**，跳过向导 |
| 设置存储 | `SettingsRepository`（DataStore Preferences） | 加几个 key |
| 依赖注入 | `AppContainer` | 加 `remote` 仓库 |
| 数据库 | Room **v4** | 同步需要 v5，加两列 |
| 网络 | **没有** INTERNET 权限、没有 HTTP 客户端、没有 JSON 库 | 全新引入 |

关键判断：**下载不需要新写导入逻辑**。服务器导出的就是 App 的导入格式，
`ImportParser.parse` 认得出表头，`WordImporter.import` 自带「重复词走更新、空值不覆盖」，
所以重复下载不会重置复习进度。

## 架构

```
ui/remote/
  ServerScreen.kt      服务器地址 / 登录 / 测试连接 / 退出
  RemoteDeckDialog.kt  词表列表（底部弹窗）：名字、词数、官方 or 我的
data/remote/
  ApiClient.kt         Ktor HttpClient（OkHttp 引擎）+ Bearer 注入 + 错误翻译
  RemoteDto.kt         与 server/app/schemas.py 一一对应的 @Serializable
  RemoteRepository.kt  登录、列词表、下载并导入、同步
data/prefs/SettingsRepository.kt   新增 serverUrl / accessToken / userName / syncRevision
di/AppContainer.kt                 新增 remote
```

## P0 · 打通与下载（✅ 已完成）

**改动**

1. `AndroidManifest.xml`：`INTERNET` 权限 + `networkSecurityConfig`（允许局域网明文）。
2. `gradle/libs.versions.toml` + `app/build.gradle.kts`：
   `ktor-client-core` / `ktor-client-okhttp` / `ktor-client-content-negotiation` /
   `ktor-serialization-kotlinx-json`，外加 `kotlin-serialization` 插件。
3. `data/remote/`：`ApiClient`、`RemoteDto`、`RemoteRepository`。
4. `SettingsRepository`：`SERVER_URL`、`ACCESS_TOKEN`、`USER_NAME` 三个 key。
5. `ui/remote/ServerScreen`：地址（`192.168.1.5:8000` 这种就够，自动补 `http://`）、
   账号密码、**测试连接**（`GET /api/health`）、登录、退出。
6. `LibraryScreen` 顶栏加一个云图标 → 词表列表弹窗 → 点一张开始下载。
7. 下载流程：`GET /api/decks/{id}/export?format=csv` → `ImportParser.parse` →
   `preview.rows()` → `importer.import(defaultDeckName = 服务器词表名)` → `relations.rebuild()`。
   结果复用现有 `ImportResult` 面板（新建 / 更新 / 跳过）。

**验收**

- 同一 Wi-Fi 下填 `电脑IP:8000`，测试连接成功；
- 用账号登录，看到「考研5500」「考研大纲 L3」；
- 下载 3461 词的词表，词库词数增加、词表名与服务器一致，耗时期间有进度提示；
- **再点一次下载**：全部落到「更新 / 未变」，不重复建词、复习进度不丢；
- 断网、端口写错、密码错：都给中文提示，不崩。

**风险**：Android 9+ 默认禁止明文 HTTP（必须配 `networkSecurityConfig`）；
5000 行 CSV 解析放 `Dispatchers.Default`，别卡主线程。

**实现备注（落地时的事实）**

- Ktor 用 **3.0.3**：3.1+ 是用 Kotlin 2.1/2.2 编译的，metadata 版本高于本项目的
  Kotlin 2.0.21，编译期就会报错。升 Ktor 之前得先升 Kotlin，那是另一件事。
- `network-security-config` 的 `<domain>` **不支持网段**，而服务器 IP 运行时才知道，
  所以只能整体放开明文（`base-config cleartextTrafficPermitted="true"`）。
- 手机没连 Wi-Fi 时可以用 USB 调试做联调：
  `adb reverse tcp:8000 tcp:8000`，App 里地址填 `127.0.0.1:8000`。
- 词表名用**服务器上的名字**，不用文件名，重复下载才会落回同一张本地词表。

## P1 · 词表管理与私有词条（✅ 已完成，随 P0 一起补）

- 我的词表：列表 / 新建 / 改名 / 删除（`POST` `PATCH` `DELETE /api/decks`）。
- 词表详情：搜索（`/api/words/search`，公共词库 + 自己的私有词）→ 勾选 → `POST /api/decks/{id}/words`。
- 私有词条：`/api/my-words` 的**增删改全套**（表单复用 `WordEditScreen` 的字段布局）。
  入口放在「我的词表」详情里：搜不到的词可以直接当场建一条私有词条再挂上去。
- 官方词表在我的界面里只读（`editable=false`）。

**验收**：建一张空词表 → 搜 10 个词加进去 → 下载到本地 → 词库出现新词表且词对得上。

**实现备注**

- 服务端新增 `POST /api/decks/{id}/private-words/import`：上传的 CSV 写进
  `user_words` 并挂到该词表，**普通用户也能用**，永远不碰公共 `words`。
- `/api/words/search` 支持 `?deckId=`，返回每行 `inDeck`，App 才能画「已加入」的对勾。
  （查询参数不走 camelCase 别名，必须显式 `Query(alias="deckId")`，否则会被静默忽略。）
- 账号入口：设置页「服务器与账号」+ 词表弹窗顶部「账号：xxx ｜ 切换」，解决登录后再也进不去服务器页的问题。

## P2 · 学习进度同步

**Room v4 → v5**

| 表 | 变更 |
|---|---|
| `review_states` | 加 `updatedAt INTEGER NOT NULL DEFAULT 0`，每次写状态时置为 `System.currentTimeMillis()` |
| `review_logs` | 加 `clientId TEXT` + 唯一索引（SQLite 里 NULL 互不相等，普通唯一索引就够，不用部分索引） |

迁移在 `Migration.migrate()` 里做：`updatedAt = IFNULL(lastReviewedAt, 0)`；
`clientId` 用 Kotlin 游标逐行写 `UUID.randomUUID()` —— 比在 SQL 里拼 uuid 干净，
几千行也就几毫秒。

**同步逻辑**

- `push`：读六组数据 → `POST /api/sync/push`。
  - 状态按 `updatedAt > lastPushedAt` 增量推；日志按 `clientId` 幂等追加。
  - 记录 `lastPushedAt`，避免每次都推 6000 行。
- `pull`：`GET /api/sync/pull?since=revision`，按 `headword_key` 映射回本地 `wordId`，
  upsert `review_states`；日志按 `clientId` 去重插入；标签 / 优先学 / 设置同理。
- `revision` 存 DataStore，成功后推进。
- **触发时机**：App 进前台、离开学习页、设置页手动点「立即同步」。
  不在评分瞬间发请求，避免学习中卡顿；最坏情况丢最后几张卡的进度，下一页离开时补上。
- 恢复：设置页「从服务器恢复进度」= 清空本地 states/logs → `pull?since=0`。

**验收**：手机学 10 个词 → 同步 → 服务器 `sync/status` 有数 →
清应用数据 → 重新登录 → 拉取 → 词库状态与离开时一致。

## P3 · 打磨

- 断网重试、超时提示、失败队列。
- Token 从明文 DataStore 改成 Keystore（AES/GCM）加密。
- 多服务器配置、HTTPS / 自签证书。
- 同步冲突提示（同一词两台设备都改过时显示「以较新的为准」）。

## 接口对照

| App 动作 | 接口 | 期 |
|---|---|---|
| 测试连接 | `GET /api/health` | P0 |
| 登录 / 退出 | `POST /api/auth/login`、`GET /api/auth/me` | P0 |
| 词表列表 | `GET /api/decks` | P0 |
| 下载词表 | `GET /api/decks/{id}/export?format=csv` | P0 |
| 建 / 改名 / 删词表 | `POST` `PATCH` `DELETE /api/decks` | P1 |
| 搜词 | `GET /api/words/search?q=` | P1 |
| 往词表加 / 减词 | `POST /api/decks/{id}/words`、`.../words/remove` | P1 |
| 私有词条增删改 | `GET/POST/PATCH/DELETE /api/my-words` | P1 |
| 推 / 拉进度 | `POST /api/sync/push`、`GET /api/sync/pull?since=` | P2 |

## 关键技术点

- **baseUrl**：设置里只存 `host:port`，`ApiClient` 拼 `http://`；改地址即重建 client。
- **错误翻译**：服务端 4xx 的 `detail` 已经是中文单句，直接显示；网络异常转成
  「连不上服务器，检查 IP 和端口」。
- **401**：清 token、跳到服务器登录页，不弹一串红字。
- **DTO 容错**：`Json { ignoreUnknownKeys = true }`，服务端加字段不会让旧 App 崩。
- **下载大小**：5241 行 CSV 约 1.6 MB，`bodyAsText()` 进内存没问题；解析在后台线程。

## 风险

1. **明文 HTTP + 明文 token**：局域网自用可接受；P3 再上 Keystore 与 HTTPS。
2. **时钟**：LWW 比的是手机时间，多设备时快慢会决定谁赢。
3. **Ktor 3 与 Kotlin 2.0.21 / AGP 8.7.3 的兼容**：先用一个最小 screen 验证依赖能拉起来再铺开。
4. **大词表解析**：低端机可能 1–3 秒，必须有进度反馈。

## 已定

| 问题 | 决定 |
|---|---|
| 私有词条的范围 | **App 里完整编辑**：新增、改释义、删除都做（P1） |
| 同步触发时机 | **进前台 / 离开学习页 / 手动**，不在评分瞬间发请求（P2） |

## P1.5 · 云端词表整页（已实现）

云图标不再弹对话框，改成整页 `CloudDecksScreen`（路由 `cloud_decks`）。设计稿见
`docs/ui/cloud-decks.html`。

### 落地的四条规则

1. **必须登录**：所有词表接口都要令牌，分享码只解决「找不到」，不解决「没账号」。
2. **只挂载**：新建时「复制现有词表」和「从其他词表导入」都只写挂载行
   （`deck_words` / `deck_user_words`），不复制词条；源表删词这边跟着少。
   别人的私有词不会被挂过来（数量走 `skippedPrivate`）。
3. **改词才另存**：`PATCH /api/decks/{id}/words/{wordId}?source=global` 会把公共词
   复制成自己的 `user_words`，再把**这张表**的挂载换过去（copy-on-write）。
   公共词库与别人的词表都不受影响；同一个词再改就是原地更新私有词。
4. **分享码可自定义**：4–32 位字母数字，存成大写并去掉横线空格，只校验唯一性。
   默认 8 位 Crockford Base32（去掉 I/L/O/U），显示成 `XXXX-XXXX`。

### 界面

- 顶部：账号条（用户名 / 服务器 / 切换）常驻，退出与换号不再藏进设置。
- 搜索：一次请求同时做关键词搜索与分享码查找；命中时顶部一条「分享码直达」。
- 四段筛选：全部 / 官方 / 公开（别人公开的）/ 我的，计数在本地算。
- 卡片手风琴：同时只展开一张；展开区里改名称、简介、可见性、分享码，
  以及「管理词条」「从其他词表导入」「删除」。
- 「管理词条」进原来的词表详情页（搜词、加词、上传 CSV 都在那里）。
