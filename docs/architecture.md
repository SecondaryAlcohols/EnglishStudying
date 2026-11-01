# 架构

```
app/src/main/java/com/alcohol/englishstudying/
├── di/AppContainer.kt          手写依赖容器（没有引 Hilt）
├── domain/                     纯 Kotlin，不依赖 Android，可单测
│   ├── srs/                    SM-2 调度器、每日计划、参数模型、今日进度条
│   ├── relations/              词语关联推导、变形链聚组
│   └── model/                  卡片字段枚举、释义词性解析
├── data/
│   ├── local/                  Room 实体 / DAO / 数据库 / 迁移
│   ├── prefs/                  DataStore 配置读写
│   ├── imports/                CSV 解析、JSON 解析、列映射、入库
│   └── repo/                   Word / Study / Deck / UserTag / Relation 五个仓库
└── ui/                         Compose 界面，按页面分包
```

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / UI | Kotlin 2.0.21、Jetpack Compose（Material 3，BOM 2024.10.01） |
| 导航 | Navigation Compose 2.8 |
| 持久化 | Room 2.6.1（KSP）、DataStore Preferences |
| 最低版本 | Android 8.0（minSdk 26），targetSdk 35 |
| 构建 | AGP 8.7.3 + Gradle 8.9 + JDK 17 |

## 几个刻意的取舍

- **算法层是纯函数**，时间由调用方以参数传入而非读系统时钟——因此可以直接单测。
- **没有引 Hilt**：依赖图是一棵深度为 2 的树，为此多一个必须与 Kotlin 版本严格配对的 KSP 处理器不划算。
- **JSON 用 Android 内置的 `org.json`**，不引 kotlinx.serialization，少一个编译器插件。
- **CSV 解析手写**（约 100 行），要处理的边界很具体：Excel 的 BOM、GBK 编码、引号内的逗号与换行。
- **迁移默认不兜底**：`build()` 里刻意不调用 `fallbackToDestructiveMigration()`。缺迁移会启动即崩，但数据原样躺在盘上；静默清库没有任何补救路径。`MigrationChainTest` 会在单测里挡住「改了实体忘了写迁移」。

## 数据模型

| 表 | 作用 |
|---|---|
| `words` | 一个词条一行。巧记 / 词根 / 变形 / 标签是独立字段；`tags` 存**系统标签**（L0–L3、真题高频、考研…） |
| `review_states` | SM-2 状态。**新词不预建行**——只有第一次作答时才建，否则新词会被到期查询当成到期卡捞出来 |
| `review_logs` | 每次作答留痕，供统计与「今天学了多久」 |
| `decks` / `deck_words` | 词表分组，一个词可属多个词表 |
| `word_relations` | 同根词 / 变形 / 近义词三类关系 |
| `user_tags` / `word_user_tags` | **用户自定义标签**（易混、写作…），与系统标签彻底分开；双向 CASCADE |

时间统一用「距 epoch 的分钟数」：天级间隔取当天零点，学习步取精确分钟，两者用同一字段比较。

## 词语关联

三类关系独立建模，不写死在字段文本里：

| 类型 | 来源 | 说明 |
|---|---|---|
| 变形 | 词形匹配 + `变形` 列 | 自动规则覆盖 `-s / -es / -ed / -ing / -ies / -ied`；刻意不含 `-ly / -er / -est` |
| 同根词 | `词根词缀` 列 | 抽带连字符的词缀，同词缀为一组，一组最多 8 个 |
| 近义词 | `近义词` 列 | 只连库中存在的词 |

导入后自动重建一次；设置页可手动「重建词语关联」。手工添加的关系标记为 `MANUAL`，重建时不会被清掉。

已知行为：手动删掉的自动关联会在重建时被找回来——要彻底去掉，得清空对应列后重建。

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

覆盖：SM-2 四档评分的间隔演进、ease 边界、挂起阈值、学习步不被天级间隔钳制、每日计划的数量与排序、参数钳制、CSV 的引号 / BOM / CRLF / 分隔符识别、CSV 与 JSON 解析等价性、卡片字段配置编解码与向后兼容、词语关联推导（词形匹配命中与误判、词根分组与截断、库外词丢弃）、今日进度条分段、标签配色稳定性、数据库迁移链完整性。

**没有 UI 测试**：分组节奏、沉浸状态这类逻辑住在 ViewModel 里，只有 `domain/` 那层是纯函数、可单测。
