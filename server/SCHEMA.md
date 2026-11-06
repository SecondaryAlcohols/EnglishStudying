# 数据库表结构

从运行中的 PostgreSQL 实库导出，与 `server/migrations/` 里的 Alembic 迁移保持一致。

PostgreSQL 18.6 · 库 `english_vocab` · 迁移版本 `33daab117685`

## 关系

```
users 1 ──< decks ──< deck_words >────── words        公共词库（管理员写，所有人读）
  │            └────< deck_user_words >── user_words   私有词条（谁的归谁）
  ├──< devices
  ├──< word_states        ┐
  ├──< review_logs        │
  ├──< user_tags          ├─ 学习进度，全部按 user_id 分租户
  ├──< word_user_tags     │
  ├──< word_flags         │
  └──< settings           ┘
```

## 通用约定

- **主键**：内容表（`words` / `user_words`）用 `bigint identity`；与账号绑定的表用 `uuid`（`users.id` 是 PG 18 的 `uuidv7()`，时间有序）。
- **时间**：库内事件时间用 `timestamptz default now()`；**学习进度里的时间戳是客户端毫秒数**（`bigint`），因为调度以手机本地为准。
- **`headword_key`**：`lower(headword)`。手机和服务端的词 id 各自自增对不上，进度只认词头。
- **`revision`**：所有同步表共享序列 `sync_revision`，每次真实写入 +1；客户端存一个游标就能增量拉取。
- **删除**：用户可见的删除一律走数据库 `ON DELETE CASCADE`；标签、标签挂载用 `deleted` 墓碑，避免另一台设备把它复活。
- **归属**：`decks.owner_id IS NULL` = 官方词表，非空 = 某个用户的私有词表。

## 表一览

| 表 | 行数 | 用途 |
|---|---:|---|
| `deck_user_words` | 0 | 词表↔私有词 挂载 |
| `deck_words` | 8702 | 词表↔公共词 挂载 |
| `decks` | 2 | 词表（官方 / 用户私有） |
| `devices` | 0 | 同步过的设备 |
| `review_logs` | 0 | 作答流水（只追加） |
| `settings` | 0 | DataStore 设置项 |
| `user_tags` | 0 | 用户自定义标签 |
| `user_words` | 0 | 用户私有词条 |
| `users` | 2 | 账号 |
| `word_flags` | 0 | 单词级开关（优先学） |
| `word_states` | 0 | 每词一条记忆状态 |
| `word_user_tags` | 0 | 词↔标签 挂载 |
| `words` | 5241 | 公共词库 |

## `deck_user_words`

| 列 | 类型 | 可空 | 默认 | 备注 |
|---|---|---|---|---|
| `deck_id` | bigint | 否 |  | PK |
| `user_word_id` | bigint | 否 |  | PK |

- 约束 `deck_user_words_deck_id_fkey`：`FOREIGN KEY (deck_id) REFERENCES decks(id) ON DELETE CASCADE`
- 约束 `deck_user_words_user_word_id_fkey`：`FOREIGN KEY (user_word_id) REFERENCES user_words(id) ON DELETE CASCADE`
- 索引 `deck_user_words_pkey`：`btree (deck_id, user_word_id)`
- 索引 `ix_deck_user_words_user_word_id`：`btree (user_word_id)`

## `deck_words`

| 列 | 类型 | 可空 | 默认 | 备注 |
|---|---|---|---|---|
| `deck_id` | bigint | 否 |  | PK |
| `word_id` | bigint | 否 |  | PK |

- 约束 `deck_words_deck_id_fkey`：`FOREIGN KEY (deck_id) REFERENCES decks(id) ON DELETE CASCADE`
- 约束 `deck_words_word_id_fkey`：`FOREIGN KEY (word_id) REFERENCES words(id) ON DELETE CASCADE`
- 索引 `deck_words_pkey`：`btree (deck_id, word_id)`
- 索引 `ix_deck_words_word_id`：`btree (word_id)`

## `decks`

| 列 | 类型 | 可空 | 默认 | 备注 |
|---|---|---|---|---|
| `id` | bigint | 否 | identity | PK |
| `name` | varchar(120) | 否 |  |  |
| `description` | text | 是 |  |  |
| `created_at` | timestamptz | 否 | now() |  |
| `updated_at` | timestamptz | 否 | now() |  |
| `owner_id` | uuid | 是 |  | 空 = 官方词表 |
| `visibility` | varchar(16) | 否 | 'private' | private / public；官方表不看这列 |
| `share_code` | varchar(32) | 是 |  | 分享码，大写只留字母数字 |

- 约束 `decks_owner_id_fkey`：`FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE`
- 索引 `decks_pkey`：`btree (id)`
- 索引 `ix_decks_owner_id`：`btree (owner_id)`
- 索引 `ix_decks_share_code`：`btree (share_code) UNIQUE`
- 索引 `ux_decks_official_name`：`btree (name) WHERE (owner_id IS NULL)`
- 索引 `ux_decks_owner_name`：`btree (owner_id, name) WHERE (owner_id IS NOT NULL)`

## `devices`

| 列 | 类型 | 可空 | 默认 | 备注 |
|---|---|---|---|---|
| `user_id` | uuid | 否 |  | PK |
| `id` | varchar(64) | 否 |  | PK |
| `name` | varchar(120) | 是 |  |  |
| `platform` | varchar(60) | 是 |  |  |
| `created_at` | timestamptz | 否 | now() |  |
| `last_seen_at` | timestamptz | 否 | now() |  |

- 约束 `devices_user_id_fkey`：`FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE`
- 索引 `devices_pkey`：`btree (user_id, id)`

## `review_logs`

| 列 | 类型 | 可空 | 默认 | 备注 |
|---|---|---|---|---|
| `id` | bigint | 否 | identity | PK |
| `user_id` | uuid | 否 |  |  |
| `client_log_id` | uuid | 否 |  |  |
| `headword_key` | varchar(200) | 否 |  |  |
| `headword` | varchar(200) | 否 |  |  |
| `rating` | varchar(16) | 否 |  |  |
| `reviewed_at` | bigint | 否 |  |  |
| `elapsed_ms` | bigint | 否 |  |  |
| `interval_days_after` | double | 否 |  |  |
| `device_id` | varchar(64) | 是 |  |  |
| `created_at` | timestamptz | 否 | now() |  |
| `revision` | bigint | 否 | nextval('sync_revision'::regclass) |  |

- 约束 `review_logs_user_id_fkey`：`FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE`
- 索引 `ix_review_logs_reviewed_at`：`btree (reviewed_at)`
- 索引 `ix_review_logs_revision`：`btree (revision)`
- 索引 `ix_review_logs_user_headword`：`btree (user_id, headword_key)`
- 索引 `review_logs_pkey`：`btree (id)`
- 索引 `ux_review_logs_user_client`：`btree (user_id, client_log_id)`

## `settings`

| 列 | 类型 | 可空 | 默认 | 备注 |
|---|---|---|---|---|
| `user_id` | uuid | 否 |  | PK |
| `key` | varchar(80) | 否 |  | PK |
| `value` | text | 否 |  |  |
| `updated_at` | bigint | 否 |  |  |
| `revision` | bigint | 否 | nextval('sync_revision'::regclass) |  |

- 约束 `settings_user_id_fkey`：`FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE`
- 索引 `ix_settings_revision`：`btree (revision)`
- 索引 `settings_pkey`：`btree (user_id, key)`

## `user_tags`

| 列 | 类型 | 可空 | 默认 | 备注 |
|---|---|---|---|---|
| `user_id` | uuid | 否 |  | PK |
| `name` | varchar(80) | 否 |  | PK |
| `updated_at` | bigint | 否 |  |  |
| `deleted` | boolean | 否 |  |  |
| `revision` | bigint | 否 | nextval('sync_revision'::regclass) |  |

- 约束 `user_tags_user_id_fkey`：`FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE`
- 索引 `ix_user_tags_revision`：`btree (revision)`
- 索引 `user_tags_pkey`：`btree (user_id, name)`

## `user_words`

| 列 | 类型 | 可空 | 默认 | 备注 |
|---|---|---|---|---|
| `id` | bigint | 否 | identity | PK |
| `user_id` | uuid | 否 |  |  |
| `headword` | varchar(200) | 否 |  |  |
| `phonetic` | varchar(200) | 是 |  |  |
| `part_of_speech` | varchar(60) | 是 |  |  |
| `meanings` | text | 是 |  |  |
| `mnemonic` | text | 是 |  |  |
| `roots` | text | 是 |  |  |
| `derivations` | text | 是 |  |  |
| `examples` | text | 是 |  |  |
| `synonyms` | text | 是 |  |  |
| `tags` | text | 是 |  |  |
| `frequency` | int | 否 |  |  |
| `created_at` | timestamptz | 否 | now() |  |
| `updated_at` | timestamptz | 否 | now() |  |

- 约束 `user_words_user_id_fkey`：`FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE`
- 索引 `user_words_pkey`：`btree (id)`
- 索引 `ux_user_words_user_headword`：`btree (user_id, lower((headword)::text))`

## `users`

| 列 | 类型 | 可空 | 默认 | 备注 |
|---|---|---|---|---|
| `id` | uuid | 否 | uuidv7() | PK |
| `username` | varchar(64) | 否 |  |  |
| `password_hash` | text | 否 |  |  |
| `display_name` | varchar(120) | 是 |  |  |
| `role` | varchar(16) | 否 |  |  |
| `disabled` | boolean | 否 |  |  |
| `created_at` | timestamptz | 否 | now() |  |
| `last_login_at` | timestamptz | 是 |  |  |

- 约束 `users_username_key`：`UNIQUE (username)`
- 索引 `users_pkey`：`btree (id)`
- 索引 `users_username_key`：`btree (username)`

## `word_flags`

| 列 | 类型 | 可空 | 默认 | 备注 |
|---|---|---|---|---|
| `user_id` | uuid | 否 |  | PK |
| `headword_key` | varchar(200) | 否 |  | PK |
| `priority` | boolean | 否 |  |  |
| `updated_at` | bigint | 否 |  |  |
| `device_id` | varchar(64) | 是 |  |  |
| `revision` | bigint | 否 | nextval('sync_revision'::regclass) |  |

- 约束 `word_flags_user_id_fkey`：`FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE`
- 索引 `ix_word_flags_revision`：`btree (revision)`
- 索引 `word_flags_pkey`：`btree (user_id, headword_key)`

## `word_states`

| 列 | 类型 | 可空 | 默认 | 备注 |
|---|---|---|---|---|
| `user_id` | uuid | 否 |  | PK |
| `headword_key` | varchar(200) | 否 |  | PK |
| `headword` | varchar(200) | 否 |  |  |
| `state` | varchar(20) | 否 |  |  |
| `ease_factor` | double | 否 |  |  |
| `interval_days` | double | 否 |  |  |
| `repetitions` | int | 否 |  |  |
| `learning_step` | int | 否 |  |  |
| `due_at_minutes` | bigint | 否 |  |  |
| `last_reviewed_at` | bigint | 是 |  |  |
| `lapses` | int | 否 |  |  |
| `updated_at` | bigint | 否 |  |  |
| `device_id` | varchar(64) | 是 |  |  |
| `revision` | bigint | 否 | nextval('sync_revision'::regclass) |  |

- 约束 `word_states_user_id_fkey`：`FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE`
- 索引 `ix_word_states_revision`：`btree (revision)`
- 索引 `ix_word_states_user_due`：`btree (user_id, due_at_minutes)`
- 索引 `word_states_pkey`：`btree (user_id, headword_key)`

## `word_user_tags`

| 列 | 类型 | 可空 | 默认 | 备注 |
|---|---|---|---|---|
| `user_id` | uuid | 否 |  | PK |
| `headword_key` | varchar(200) | 否 |  | PK |
| `tag_name` | varchar(80) | 否 |  | PK |
| `updated_at` | bigint | 否 |  |  |
| `deleted` | boolean | 否 |  |  |
| `revision` | bigint | 否 | nextval('sync_revision'::regclass) |  |

- 约束 `word_user_tags_user_id_fkey`：`FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE`
- 索引 `ix_word_user_tags_revision`：`btree (revision)`
- 索引 `word_user_tags_pkey`：`btree (user_id, headword_key, tag_name)`

## `words`

| 列 | 类型 | 可空 | 默认 | 备注 |
|---|---|---|---|---|
| `id` | bigint | 否 | identity | PK |
| `headword` | varchar(200) | 否 |  |  |
| `phonetic` | varchar(200) | 是 |  |  |
| `part_of_speech` | varchar(60) | 是 |  |  |
| `meanings` | text | 是 |  |  |
| `mnemonic` | text | 是 |  |  |
| `roots` | text | 是 |  |  |
| `derivations` | text | 是 |  |  |
| `examples` | text | 是 |  |  |
| `synonyms` | text | 是 |  |  |
| `tags` | text | 是 |  |  |
| `frequency` | int | 否 |  |  |
| `created_at` | timestamptz | 否 | now() |  |
| `updated_at` | timestamptz | 否 | now() |  |

- 索引 `ix_words_frequency`：`btree (frequency)`
- 索引 `ux_words_headword_lower`：`btree (lower((headword)::text))`
- 索引 `words_pkey`：`btree (id)`

