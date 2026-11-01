# 词库导入格式

应用**不内置任何词条**，词库由你自己准备，通过 CSV 或 JSON 导入。这一页是完整的字段规范。

示例文件：[`sample-vocab.csv`](sample-vocab.csv)（10 个词，可直接导入验证）。

## 格式总览

支持 **CSV** 与 **JSON**。列名**大小写不敏感**，空格、下划线、连字符都会被忽略——`Part Of Speech`、`part_of_speech`、`partofspeech` 是同一列。认不出的列会被忽略，缺失的列留空即可。

## CSV

首行是表头。支持 Excel 导出的 UTF-8 BOM、`\r\n` 换行，以及引号内的逗号和换行。分隔符自动识别（逗号 / 制表符 / 分号 / 竖线）——从 Excel「另存为文本(制表符分隔)」也能直接导入。

```csv
word,phonetic,pos,meaning,mnemonic,root,derivation,example,tags,frequency
absent,/ˈæbsənt/,adj.,"缺席的；心不在焉的","ab(离开)+sent(存在)，人不在场→缺席","ab- 离开 + -sent- 存在","absence n. 缺席; absent-minded adj. 心不在焉的","He was absent from class. 他缺课了。",高频,90
```

**编码**：UTF-8 与 GBK/GB18030 自动识别。中文 Windows 上 Excel 默认存成 GBK，直接导入不会乱码。

## JSON

对象数组，键名规则与 CSV 相同，另外支持 `deck` 字段直接指定词表名。

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

不同对象的键可以不一致，按并集处理。

## 字段对照表

| 应用字段 | 常见列名（自动识别） | 说明 |
|---|---|---|
| 单词 | `word` `headword` `term` `spelling` `单词` `词汇` `英文` | **必填** |
| 音标 | `phonetic` `pronunciation` `ipa` `音标` `发音` | |
| 词性 | `pos` `part of speech` `词性` `词类` | 不单独显示；释义里一条词性都没有时，兜底挂到第一条释义前 |
| 释义 | `meaning` `definition` `translation` `cn` `释义` `中文` `翻译` `意思` | 多条用换行分隔，不做编号。每条开头可写自己的词性（`n. 礼物`、`形容词，能够的`），渲染时逐条挂到该条释义上 |
| 巧记 | `mnemonic` `memory` `tip` `巧记` `助记` `记忆` `联想` | |
| 词根词缀 | `root` `roots` `etymology` `affix` `词根` `词根词缀` `词缀` `构词` | |
| 变形 | `derivation` `forms` `inflection` `变形` `词形变化` `派生` | 多条用换行分隔；提到的库内词可点击跳转 |
| 例句 | `example` `sentence` `例句` `真题例句` | 多条用换行分隔 |
| 标签 | `tag` `label` `category` `标签` `分类` | 英文逗号分隔 |
| 词频 | `frequency` `rank` `weight` `词频` `权重` | 数字，**越小越常用**（COCA 排名）；0 表示查不到，排在最后 |
| 词表 | `deck` `list` `book` `词表` `分组` `来源` | 不填则用导入时指定的词表 |

释义里的英文词性缩写按 ECDICT 的写法全认：`n. v. a. ad. adj. adv. vt. vi. prep. pron. conj. num. art. int. interj. aux. abbr. pref.`。卡片上统一显示长写：`a.`→`adj.`、`ad.`→`adv.`、`int.`→`interj.`，`aux.v.` 整段算一个词性。

**列名对不上怎么办？** 导入向导的第二步会列出源文件的每一列，用下拉菜单手动指定它对应哪个字段。猜不中的列默认「忽略」。

## 列内容规范（变形 / 词根词缀 / 近义词）

这几列是自由文本，但**写法决定关联功能能不能用**。

### 变形 `derivation`

- 每条写「词形 + 可选词性释义」，多条用分号或换行分隔：`absence n. 缺席；absent-minded adj. 心不在焉的`
- 想让读者能点，就写成**库中存在的确切词形**（大小写无关）：库中有的词渲染成主题色，点击直达详情页；库里没有的保持普通文本
- 另有**自动词形匹配**兜底（不依赖这一列）：`-s / -es / -ed / -ing / -ies / -ied` 结尾的词与其词干（≥3 字母）自动连成变形（answer/answers、study/studied）。刻意**不含** `-ly / -er / -est`，以免把 likely/like 这类同形不同源的词连错
- 这一列里提到的词要建**变形关系**（参与「放在一起背」聚组）额外要求共同词头 ≥4 字母：absent/absence ✓；present/represent ✗（仍可点击）

### 词根词缀 `root`

- 词缀**必须带连字符**，多个用 `+` 连接：`ab- 离开 + -sent- 存在 + -ence 名词后缀`
- 三种合法形态：前缀 `ab-`、词根 `-sent-`、后缀 `-ity`。裸写词干（`sent`）不参与分组——它同时是 sent 的过去式，会误连
- 词缀相同的词自动归为**同根词**，卡片 chip 上印出共享词缀（如 `-fer-`）；一组最多 8 个，超出按词频截断

### 近义词 `synonyms`

- 直接写库中的词，分号或逗号分隔：`important; significant`
- 只连库中存在的，不像变形那样要求词形相近

## 导入行为

- **重复的词走更新，不是覆盖。** 重新导入修订版词库，复习进度与作答记录不会丢
- **空单元格不会清空已有内容。** 某列为空表示「这次没提供」，手工补的巧记不会被清掉
- 同一文件里重复的单词会被跳过，并在结果页报出行号
- 校验失败的行不入库，结果页列出**行号 + 原因**（行号与 Excel 里看到的一致）
- 导入完成后自动重建一次词语关联
