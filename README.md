# 考研背单词（EnglishStudying）

一个围绕**巧记 / 词根词缀 / 变形**三个维度设计的安卓背单词应用，主打**高度自定义**：卡片上显示什么、按什么顺序、记忆算法怎么算，全部由你决定。

词库为考研英语，**应用不内置任何词条** —— 词库由你自己准备，通过 CSV 或 JSON 导入。

---

## 验证状态（2026-09-12）

| 项 | 状态 |
|---|---|
| Kotlin 编译（57 个源文件） | ✅ 通过，产出 519 个 class |
| Room KSP 注解处理 | ✅ 通过，生成 4 个 `*_Impl.java` —— **所有 `@Query` 的 SQL 都经 Room 校验** |
| 单元测试（69 个用例） | ✅ **69 通过 / 0 失败 / 0 错误 / 0 跳过** |
| `assembleDebug` 出 APK | ✅ `app/build/outputs/apk/debug/app-debug.apk`（18 MB，16 个 dex） |
| 真机安装运行 | ✅ 一加 Ace 3（Android 15）实机跑通 |
| 导入 → 学习 完整闭环 | ✅ 导入 `docs/sample-vocab.csv`（10 词）→ 卡片正常渲染 |

**真机上验证到的行为**：卡片正面只显示单词和音标；点「显示答案」后巧记 / 词根词缀 /
变形 / 例句四个字段以折叠态出现（只露标题和提示语）；四档评分按钮上的下次间隔预览
与算法默认值一致（忘记 1 分钟 / 模糊 1 分钟 / 认识 10 分钟 / 太简单 4 天）。

**实机运行中发现并修掉的两个 bug**（都是静态审查看不出来的）：

1. `StudyViewModel.startSession()` 无条件把 `libraryEmpty` 置为 `false`，把「词库是空的，
   去导入」这条引导整个顶掉了 —— 对一个不内置词库的应用来说那是唯一的入口。
2. 上一个问题修好后暴露出第二个：`libraryEmpty` 是一次性算出来的布尔值，而 ViewModel 会被
   `saveState/restoreState` 保留。用户导入完词库回到学习页，页面仍说「词库是空的」，
   而那个空状态**只有「去导入词库」一个按钮、没有刷新入口**，用户会卡死在里面。
   改成跟随 `wordRepository.observeCount()` 的可观察流，并在 0 → N 时自动重建会话。

写这套代码的环境没有 Android SDK、Gradle 和 JDK，所以它**长期未经编译**，
只做过静态审查（那次审查在数据层找出并修掉了 2 处必定编译失败的错误）。
后来环境补齐，通过 IntelliJ IDEA 的 MCP 接口触发了构建，才拿到上面这些结果。

**尚未被验证的是界面部分** —— 编译通过不等于跑起来不崩。首次在设备上运行时
仍需留意：Compose 的预览/初始化、SAF 文件选择、以及 DataStore 的首次读写。

---

## 构建步骤

### 本机环境现状（2026-09-12 实测，依据 Gradle daemon 日志）

| 项 | 位置 / 状态 |
|---|---|
| Android SDK | `D:\1Softwares\AndroidSDK` |
| 已装平台 | `android-35` ✅（AGP 自动下载的）、`android-36` |
| 已装 build-tools | `34.0.0` ✅（AGP 8.7.3 要的就是这个）、`36.1.0`、`37.0.0` |
| `local.properties` | 已存在并指向正确的 SDK，无需改动 |
| SDK 许可证 | `android-sdk-license` 已接受 —— **自动下载已验证可用** |
| JDK | `C:\Users\Alcohol\.jdks\jdk-21.0.12.1+1`（IDEA 管理的 JDK 21） |
| Gradle | 8.9 发行版已缓存在 `~/.gradle/wrapper/dists/gradle-8.9-bin/` |
| wrapper jar | ⚠️ **仍缺**（见下） |
| Android Studio | 未安装（只有 IntelliJ 系） |

**已经验证通过的部分**：一次 Gradle 同步已经成功解析出 **AGP 8.7.3** 和
**KSP 2.0.21-1.0.28** 并存入缓存 —— 这两个是原本风险最高的坐标
（KSP 与 Kotlin 必须严格配对），现在可以确认它们真实存在且能被解析。
同一次同步还自动补装了缺失的 platform 35 和 build-tools 34.0.0。

**依赖已全部下齐**（compose / room / lifecycle / navigation / datastore /
material-icons-extended），缓存 group 从 112 增至 148。首次构建的下载已完成，
后续构建不需要再等。

### 依赖从哪来

不用手动下载。Gradle 会自动从 `settings.gradle.kts` 里声明的仓库拉取。
实测本机三条线的响应速度：

| 仓库 | 响应时间 |
|---|---|
| `maven.aliyun.com`（阿里云镜像） | 0.4 s |
| `dl.google.com`（Google 官方） | 0.6 s |
| `repo1.maven.org`（Maven Central） | 3.7 s |

慢的是 Maven Central，而首次构建主要就是从它拉 Kotlin / Compose 那一堆。
所以 `settings.gradle.kts` 里已经把**阿里云镜像配成首选源**，官方源留作回落。
如果你换了网络环境或镜像失效，把那几行 `maven("https://maven.aliyun.com/...")`
删掉即可回到纯官方源。

### 1. Gradle JVM 必须选真正的 JDK

IDEA 的 Gradle JVM 下拉里会把 **Android SDK 的伪条目**和真 JDK 混在一起。
如果误选了 `Android API 36.0, extension level 17 Platform`，IDEA 会报
「找到无效的 Gradle JDK 配置」—— 那个条目的类型是 `Android SDK` 而不是
`JavaSDK`，Gradle 无法用它启动。

正确选择：**`21`**（对应 `~/.jdks/jdk-21.0.12.1+1`）。

- ⚠️ **不要选 `26`**：Gradle 8.9 最高支持到 Java 22，JDK 26 起不来。
- `openjdk-23` 这个名字虽然是 23，实际也指向同一个 JDK 21 目录，可以用但名字有误导。

设置位置：`Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM`。

### 2. 关于缺失的 gradle wrapper jar

仓库里没有 `gradle-wrapper.jar`（二进制文件，无法用文本生成），
且本机直连 GitHub 超时，我无法替你下载。不影响 IDEA 构建：

- **用 IntelliJ IDEA 打开项目**，在 Gradle 工具窗口点刷新，让它用缓存里的
  Gradle 8.9 发行版即可。
- 想在终端用 `./gradlew`，就让 IDEA 生成 wrapper：
  Gradle 工具窗口里执行 `wrapper --gradle-version 8.9` 任务。

### 3. 构建与测试

```bash
# 先跑纯逻辑单测，不需要模拟器，最快暴露问题
./gradlew :app:testDebugUnitTest

# 编译 APK
./gradlew :app:assembleDebug

# 装到设备或模拟器
./gradlew :app:installDebug
```

---

## 导入你的词库

这是使用这个应用的第一步，也是最关键的一步。

### 格式总览

支持 **CSV** 和 **JSON** 两种格式。列名**大小写不敏感**，空格、下划线、连字符都会被忽略（`Part Of Speech`、`part_of_speech`、`partofspeech` 是同一列）。认不出的列会被忽略；缺失的列留空即可。

### CSV 格式

首行是表头。支持 Excel 导出的 UTF-8 BOM、`\r\n` 换行，以及引号内的逗号和换行。分隔符会自动识别（逗号 / 制表符 / 分号 / 竖线）—— 从 Excel 另存为「文本(制表符分隔)」也能直接导入。

```csv
word,phonetic,pos,meaning,mnemonic,root,derivation,example,tags,frequency
absent,/ˈæbsənt/,adj.,"缺席的；心不在焉的","ab(离开)+sent(存在)，人不在场→缺席","ab- 离开 + -sent- 存在","absence n. 缺席; absent-minded adj. 心不在焉的","He was absent from class. 他缺课了。",高频,90
```

完整示例见 [`docs/sample-vocab.csv`](docs/sample-vocab.csv)，可以直接照它的列名准备你的词库。

**编码**：UTF-8 和 GBK/GB18030 都能自动识别。Excel 在中文 Windows 上默认存成 GBK，直接导入不会乱码。

### JSON 格式

对象数组。键名规则与 CSV 列名相同，另外支持 `deck` 字段直接指定词表名。

```json
[
  {
    "word": "absent",
    "phonetic": "/ˈæbsənt/",
    "pos": "adj.",
    "meaning": "缺席的；心不在焉的",
    "mnemonic": "ab(离开)+sent(存在)，人不在场→缺席",
    "root": "ab- 离开 + -sent- 存在",
    "derivation": "absence n. 缺席; absent-minded adj. 心不在焉的",
    "example": "He was absent from class. 他缺课了。",
    "tags": "高频",
    "frequency": 90
  }
]
```

不同对象的键可以不一致，取并集处理。

### 字段对照表

| 应用字段 | 常见列名（自动识别） | 说明 |
|---|---|---|
| 单词 | `word` `headword` `term` `spelling` `单词` `词汇` `英文` | **必填** |
| 音标 | `phonetic` `pronunciation` `ipa` `音标` `发音` | |
| 词性 | `pos` `part of speech` `词性` `词类` | |
| 释义 | `meaning` `definition` `translation` `cn` `释义` `中文` `翻译` `意思` | 多条用换行分隔，卡片上会按 1. 2. 编号 |
| **巧记** | `mnemonic` `memory` `tip` `巧记` `助记` `记忆` `联想` | |
| **词根词缀** | `root` `roots` `etymology` `affix` `词根` `词根词缀` `词缀` `构词` | |
| **变形** | `derivation` `forms` `inflection` `变形` `词形变化` `派生` | |
| 例句 | `example` `sentence` `例句` `真题例句` | 多条用换行分隔 |
| 标签 | `tag` `label` `category` `标签` `分类` | 英文逗号分隔 |
| 重要度 | `frequency` `rank` `weight` `重要度` `词频` `权重` | 数字，越大越靠前 |
| 词表 | `deck` `list` `book` `词表` `分组` `来源` | 不填则用导入时指定的词表 |

**列名对不上怎么办？** 导入向导的第二步会列出源文件的每一列，让你用下拉菜单手动指定它对应哪个字段。猜不中的列默认是「忽略」。

### 导入行为

- **重复的词走更新，不是覆盖。** 重新导入修订版词库时，该词的**复习进度和作答记录不会丢失**。
- **空单元格不会清空已有内容。** 导入文件里某列为空表示「这次没提供」，你在应用里手工补的巧记不会被清掉。
- 同一个文件里重复的单词会被跳过并在结果页报出行号。
- 校验失败的行不会入库，结果页会列出**行号 + 原因**（行号与你用 Excel 打开时看到的一致，中间夹空行也不会错位）。

---

## 两处自定义

### 一、卡片内容与顺序

**设置 → 卡片显示 → 卡片字段与顺序**

九个字段：单词、音标、词性、释义、巧记、词根词缀、变形、例句、标签。每个字段可以：

- **显示 / 隐藏** —— 关掉的字段不出现在卡片上
- **上移 / 下移** —— 决定卡片上的上下顺序
- **默认展开 / 折叠** —— 巧记、词根、变形默认折叠，先自己回忆再点开看提示

页面顶部有一张**实时预览卡片**，改任何一项立刻能看到效果。

另一个开关「作答前只显示单词和音标」控制揭晓前是否把释义、巧记等一并藏起来。

### 二、记忆算法与每日计划

**设置 → 每日计划 / 学习步与毕业 / 算法参数**

| 参数 | 默认值 | 作用 |
|---|---|---|
| 每日新词上限 | 20 | 每天引入多少没学过的词，设 0 表示只复习 |
| 每日复习上限 | 200 | 每天最多复习多少张到期卡 |
| 新词抽取顺序 | 按重要度 | 按重要度 / 按入库顺序 / 随机 |
| 学习步（分钟） | `1,10` | 新词在学习阶段的重考间隔，走完最后一步才毕业 |
| 毕业间隔 | 1 天 | 学习步走完后第一次天级复习的间隔 |
| 「太简单」间隔 | 4 天 | 新词阶段直接按「太简单」跳过的天数 |
| 初始难度系数 | 2.5 | 每个新词的起始 ease |
| 「太简单」奖励倍数 | 1.3 | 按「太简单」时间隔额外乘的系数 |
| 「模糊」间隔倍数 | 1.2 | 按「模糊」时间隔乘的系数 |
| 全局间隔修正 | 1.0 | 所有天级间隔统一乘这个数，觉得复习太频繁就调到 0.8 |
| 最小间隔 | 1 天 | 两次复习之间的下限 |
| 最大间隔 | 365 天 | 间隔上限，防止一个词被排到几年后 |
| 挂起阈值 | 8 | 累计「忘记」多少次后自动移出队列 |

所有参数都做范围校验，输入越界会被收敛回合法区间而不是让算法算出奇怪的结果。点「恢复默认参数」可以一键还原，不会动词库和学习进度。

### 学习流程

卡片正面只看得到**单词和音标**。点「显示答案」揭晓，然后四档自评：

**忘记 / 模糊 / 认识 / 太简单**

四个按钮上直接印着**按下之后的下次间隔**，不用猜算法会怎么算。按「忘记」的卡会重新排回今天队列的末尾。

---

## 架构

```
app/src/main/java/com/alcohol/englishstudying/
├── di/AppContainer.kt          手写依赖容器（没有引 Hilt）
├── domain/                     纯 Kotlin，不依赖 Android，可单测
│   ├── srs/                    SM-2 调度器、每日计划、参数模型
│   └── model/CardField.kt      可配置的卡片字段枚举
├── data/
│   ├── local/                  Room 实体 / DAO / 数据库
│   ├── prefs/                  DataStore 配置读写
│   ├── imports/                CSV 解析、JSON 解析、列映射、入库
│   └── repo/                   WordRepository / StudyRepository / DeckRepository
└── ui/                         Compose 界面，按页面分包
```

几个刻意的取舍：

- **算法层是纯函数**，时间由调用方以参数传入而非读系统时钟 —— 因此可以直接单测，`app/src/test/` 下有完整覆盖。
- **没有引 Hilt**：依赖图是一棵深度为 2 的树，为此多加一个必须与 Kotlin 版本严格配对的 KSP 处理器不划算。
- **JSON 用 Android 内置的 `org.json`**，不引 kotlinx.serialization，少一个编译器插件。
- **CSV 解析手写**（约 100 行），因为要处理的边界很具体：Excel 的 BOM、GBK 编码、引号内的逗号和换行。

### 数据模型

`words` 一个词条一行，巧记 / 词根 / 变形是三列独立字段。`review_states` 存 SM-2 状态，**新词不预建行** —— 只有第一次作答时才建，否则新词会被到期查询当成到期卡捞出来，每日上限就形同虚设。`review_logs` 留作答痕迹供统计。`decks` + `deck_words` 是词表分组，一个词可以属于多个词表。

时间统一用「距 epoch 的分钟数」表示：天级间隔取当天零点，学习步取精确分钟，两者用同一个字段比较，排序和筛选都在 SQL 里完成。

---

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

覆盖：SM-2 四档评分的间隔演进、难度系数边界、挂起阈值、学习步不被天级间隔钳制、每日计划的数量与排序、CSV 的引号 / BOM / CRLF / 分隔符识别边界、CSV 与 JSON 两条路径的解析等价性、卡片字段配置的编解码与向后兼容。

**没有 UI 测试**，也没有任何在真机上跑过的验证。

---

## 已知风险与待办

1. **依赖版本未经验证。** 版本集中在 `gradle/libs.versions.toml`。如果解析失败，报错会直接指出哪个坐标不存在。最容易出问题的是 KSP 与 Kotlin 的配对（`2.0.21-1.0.28` 必须严格对应），实在解决不了可以把 `app/build.gradle.kts` 里的 `ksp(...)` 换成 `kapt(...)` 并删掉 ksp 插件。
2. **图标资源。** 代码里用了 `Icons.Filled.School`、`Icons.Filled.Style`、`Icons.Filled.Layers`、`Icons.Filled.Undo` 等来自 `material-icons-extended` 的图标。如果某个名字在这个版本里不存在，编译器会直接报错，换成任意一个存在的图标即可，不影响逻辑。
3. **CSV 不支持反斜杠转义引号**（`\"`）。标准 CSV 用的是把引号写两遍（`""`）。如果你的数据源用前者，需要在 `CsvParser` 的 `inQuotes` 分支加一个判断。
4. **导入结果页最多列出 100 条错误**，超出只显示总数。
5. **没有统计图表**，统计页只有数字。
6. **外观不可自定义**：深色/浅色跟随系统，主色调、字号、圆角均未开放。
