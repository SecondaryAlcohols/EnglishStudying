package com.alcohol.englishstudying.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** 服务器交互失败的统一异常，`message` 直接拿来显示给用户。 */
class ServerException(message: String, val status: Int? = null) : Exception(message)

object ServerUrls {

    /**
     * 用户只输 `192.168.1.5:8000` 也能用：缺协议补 `http://`，去掉结尾斜杠。
     * 已经写了 `https://` 的原样保留（Ktor 会走 TLS）。
     */
    fun normalize(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return ""
        url = url.trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url
    }
}

/**
 * 服务器的 HTTP 接口。只负责发请求与翻译错误，不碰本地数据库。
 *
 * 超时给得比较宽松（读 30 秒）：一次下载 5000 词的 CSV 约 1.6 MB，
 * 局域网一般几百毫秒，但低端路由 + 大词表时要留余量。
 */
class ServerApi {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    suspend fun health(baseUrl: String): HealthDto =
        client.get("$baseUrl/api/health").readOrThrow()

    suspend fun login(baseUrl: String, username: String, password: String): TokenDto {
        val response = client.post("$baseUrl/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = username, password = password))
        }
        if (!response.status.isSuccess()) {
            // 登录接口的 401 是「账号或密码错」，不能翻译成「登录已过期」
            throw ServerException(
                response.errorMessage(unauthorizedFallback = "用户名或密码错误"),
                response.status.value
            )
        }
        return response.body()
    }

    suspend fun deck(baseUrl: String, token: String, deckId: Long): DeckDto {
        val response = client.get("$baseUrl/api/decks/$deckId") {
            header("Authorization", "Bearer $token")
        }
        return response.readOrThrow()
    }

    /**
     * 一次列全。`scope` = all / official / public / mine，`q` 同时匹配词表名、简介、作者。
     * 服务端按四个页签一次算好，客户端切页签不用重新请求。
     */
    suspend fun decks(
        baseUrl: String,
        token: String,
        scope: String = "all",
        q: String? = null
    ): List<DeckDto> {
        val response = client.get("$baseUrl/api/decks") {
            header("Authorization", "Bearer $token")
            parameter("scope", scope)
            if (!q.isNullOrBlank()) parameter("q", q.trim())
        }
        return response.readOrThrow()
    }

    /** 按分享码直达。码里的横线、小写都无所谓，服务端会归一化。 */
    suspend fun deckByCode(baseUrl: String, token: String, code: String): DeckDto {
        val response = client.get("$baseUrl/api/decks/by-code/$code") {
            header("Authorization", "Bearer $token")
        }
        return response.readOrThrow()
    }

    /** 下载整张词表，返回 CSV 原文（就是 App 的导入格式）。 */
    suspend fun exportCsv(baseUrl: String, token: String, deckId: Long): String {
        val response = client.get("$baseUrl/api/decks/$deckId/export?format=csv") {
            header("Authorization", "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            throw ServerException(response.errorMessage(), response.status.value)
        }
        return response.bodyAsText()
    }


    // ---- 词表管理 ----

    suspend fun createDeck(
        baseUrl: String,
        token: String,
        request: CreateDeckRequest
    ): DeckDto {
        val response = client.post("$baseUrl/api/decks") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return response.readOrThrow()
    }

    /** 改名 / 简介 / 可见性 / 自定义分享码，只发非空字段。 */
    suspend fun updateDeck(
        baseUrl: String,
        token: String,
        deckId: Long,
        request: UpdateDeckRequest
    ): DeckDto {
        val response = client.patch("$baseUrl/api/decks/$deckId") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return response.readOrThrow()
    }

    /** 词表头几个词，展开卡片时用来做预览。 */
    suspend fun deckWords(
        baseUrl: String,
        token: String,
        deckId: Long,
        limit: Int = 6
    ): WordPageDto {
        val response = client.get("$baseUrl/api/decks/$deckId/words") {
            header("Authorization", "Bearer $token")
            parameter("limit", limit)
        }
        return response.readOrThrow()
    }

    suspend fun resetShareCode(baseUrl: String, token: String, deckId: Long): DeckDto {
        val response = client.post("$baseUrl/api/decks/$deckId/share-code/reset") {
            header("Authorization", "Bearer $token")
        }
        return response.readOrThrow()
    }

    /** 把 sourceId 的词挂到 deckId 上（只挂载）。mode = append / replace。 */
    suspend fun importFrom(
        baseUrl: String,
        token: String,
        deckId: Long,
        sourceId: Long,
        mode: String
    ): ImportFromResult {
        val response = client.post("$baseUrl/api/decks/$deckId/import-from") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(ImportFromRequest(sourceId = sourceId, mode = mode))
        }
        return response.readOrThrow()
    }

    suspend fun deleteDeck(baseUrl: String, token: String, deckId: Long) {
        val response = client.delete("$baseUrl/api/decks/$deckId") {
            header("Authorization", "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            throw ServerException(response.errorMessage(), response.status.value)
        }
    }

    // ---- 搜词与挂载 ----

    suspend fun searchWords(
        baseUrl: String,
        token: String,
        keyword: String,
        deckId: Long?,
        limit: Int = 50
    ): WordSearchPageDto {
        val response = client.get("$baseUrl/api/words/search") {
            header("Authorization", "Bearer $token")
            parameter("q", keyword)
            parameter("limit", limit)
            if (deckId != null) parameter("deckId", deckId)
        }
        return response.readOrThrow()
    }

    suspend fun addWords(
        baseUrl: String, token: String, deckId: Long, headwords: List<String>
    ): DeckWordsResultDto {
        val response = client.post("$baseUrl/api/decks/$deckId/words") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(HeadwordsRequest(headwords))
        }
        return response.readOrThrow()
    }

    suspend fun removeWords(
        baseUrl: String, token: String, deckId: Long, headwords: List<String>
    ): DeckWordsResultDto {
        val response = client.post("$baseUrl/api/decks/$deckId/words/remove") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(HeadwordsRequest(headwords))
        }
        return response.readOrThrow()
    }

    /** 上传 CSV/JSON 到自己的私有词库（服务端写 user_words，不碰公共 words）。 */
    suspend fun uploadPrivateWords(
        baseUrl: String,
        token: String,
        deckId: Long,
        fileName: String,
        bytes: ByteArray
    ): PrivateImportResultDto {
        val response = client.submitFormWithBinaryData(
            url = "$baseUrl/api/decks/$deckId/private-words/import",
            formData = formData {
                append(
                    "file",
                    bytes,
                    Headers.build {
                        append(HttpHeaders.ContentType, "text/csv")
                        append(
                            HttpHeaders.ContentDisposition,
                            "filename=\"${fileName.ifBlank { "upload.csv" }}\""
                        )
                    }
                )
            }
        ) {
            header("Authorization", "Bearer $token")
        }
        return response.readOrThrow()
    }

    private suspend inline fun <reified T> HttpResponse.readOrThrow(): T {
        if (status.isSuccess()) return body()
        throw ServerException(errorMessage(), status.value)
    }

    /**
     * 服务端的 4xx 都带一句中文 `detail`，优先用它。
     * 401 在「已登录的请求」里意味着令牌失效，登录接口则意味着账号密码错 ——
     * 由调用方通过 [unauthorizedFallback] 决定兜底话术。
     */
    private suspend fun HttpResponse.errorMessage(
        unauthorizedFallback: String = "登录已过期，请重新登录"
    ): String {
        val raw = runCatching { bodyAsText() }.getOrNull().orEmpty()
        val detail = runCatching { json.decodeFromString<ErrorDto>(raw).detail }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (status == HttpStatusCode.Unauthorized) return detail ?: unauthorizedFallback
        return detail ?: "服务器返回 ${status.value}"
    }
}
