package com.alcohol.englishstudying.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 与 `server/app/schemas.py` 一一对应的传输对象。
 *
 * 全部字段给默认值 + `Json { ignoreUnknownKeys = true }`：服务端以后加字段，
 * 旧版 App 不会因为「多了个不认识的键」直接解析失败。
 */

@Serializable
data class HealthDto(
    val status: String = "",
    val version: String = "",
    val database: String = "",
    val deckCount: Int = 0,
    val wordCount: Int = 0
)

@Serializable
data class UserDto(
    val id: String = "",
    val username: String = "",
    val displayName: String? = null,
    val role: String = "user"
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class TokenDto(
    val accessToken: String = "",
    val tokenType: String = "bearer",
    val user: UserDto = UserDto()
)

@Serializable
data class DeckDto(
    val id: Long,
    val name: String,
    val description: String? = null,
    val wordCount: Int = 0,
    val updatedAt: String? = null,
    /** 官方词表：所有人可读，只有管理员能改 */
    val official: Boolean = false,
    /** 这张表是不是我的（服务端算好的，不要用 ownerName 比 —— 它可能没带回来） */
    val mine: Boolean = false,
    /** private / public；官方词表恒为 public */
    val visibility: String = "private",
    /** 分享码：8 位大写字母数字（服务端不带横线，展示时 4-4 分段） */
    val shareCode: String? = null,
    val ownerName: String? = null,
    val editable: Boolean = false
) {
    val isPublic: Boolean get() = official || visibility == "public"

    /** `K7Q29F3M` → `K7Q2-9F3M`；自定义码不强行分组。 */
    val prettyShareCode: String
        get() {
            val code = shareCode ?: return ""
            return if (code.length == 8) code.substring(0, 4) + "-" + code.substring(4) else code
        }
}

/** 新建词表。`seedFromDeckId` 只是把源表的词**挂**过来，不复制词条。 */
@Serializable
data class CreateDeckRequest(
    val name: String,
    val description: String? = null,
    val visibility: String = "private",
    val seedFromDeckId: Long? = null
)

@Serializable
data class UpdateDeckRequest(
    val name: String? = null,
    val description: String? = null,
    val visibility: String? = null,
    val shareCode: String? = null
)

@Serializable
data class ImportFromRequest(val sourceId: Long, val mode: String = "append")

@Serializable
data class ImportFromResult(
    val deckId: Long = 0,
    val sourceId: Long = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val skippedPrivate: Int = 0,
    val total: Int = 0
)

/** 展开一张词表时看到的头几个词。 */
@Serializable
data class WordBriefDto(
    val id: Long = 0,
    val source: String = "global",
    val headword: String = "",
    @SerialName("pos") val pos: String? = null,
    @SerialName("meaning") val meaning: String? = null
)

@Serializable
data class WordPageDto(
    val deckId: Long = 0,
    val name: String = "",
    val total: Int = 0,
    val items: List<WordBriefDto> = emptyList()
)

/** 服务端出错时统一返回 `{"detail": "一句中文"}`。 */
@Serializable
data class ErrorDto(val detail: String? = null)

/** `/api/words/search` 的一行：可能来自公共词库，也可能是自己的私有词。 */
@Serializable
data class WordSearchHitDto(
    val id: Long = 0,
    val source: String = "global",
    val headword: String = "",
    val phonetic: String? = null,
    val pos: String? = null,
    val meaning: String? = null,
    val frequency: Int = 0,
    /** 请求时带了 deckId 才有意义：是否已经在张词表里 */
    val inDeck: Boolean = false
)

@Serializable
data class WordSearchPageDto(
    val total: Int = 0,
    val items: List<WordSearchHitDto> = emptyList()
)

@Serializable
data class DeckWordsResultDto(
    val deckId: Long = 0,
    val added: Int = 0,
    val already: Int = 0,
    val removed: Int = 0,
    val notInLibrary: List<String> = emptyList()
)

/** 上传 CSV 到私有词库的结果。 */
@Serializable
data class PrivateImportResultDto(
    val deckId: Long = 0,
    val name: String = "",
    val totalRows: Int = 0,
    val created: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val linked: Int = 0,
    val duplicateLines: List<Int> = emptyList(),
    val issues: List<String> = emptyList()
)

@Serializable
data class DeckNameRequest(val name: String)

@Serializable
data class HeadwordsRequest(val headwords: List<String>)
