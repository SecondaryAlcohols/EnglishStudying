package com.alcohol.englishstudying.data.remote

import com.alcohol.englishstudying.data.imports.ImportParser
import com.alcohol.englishstudying.data.imports.ImportResult
import com.alcohol.englishstudying.data.imports.ParseOutcome
import com.alcohol.englishstudying.data.imports.WordImporter
import com.alcohol.englishstudying.data.prefs.SettingsRepository
import com.alcohol.englishstudying.data.repo.RelationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 服务器侧的业务入口：登录状态、词表列表、下载并导入。
 *
 * 下载后**不新写导入逻辑**——服务端导出的 CSV 就是 App 的导入格式，
 * 直接走 `ImportParser` + `WordImporter`，于是「重复词走更新、空值不覆盖」
 * 这些既有语义自动生效，重复下载不会重置复习进度。
 */
class RemoteRepository(
    private val api: ServerApi,
    private val settings: SettingsRepository,
    private val importer: WordImporter,
    private val relations: RelationRepository
) {

    data class Session(
        val url: String = "",
        val token: String = "",
        val userName: String = ""
    ) {
        val loggedIn: Boolean get() = token.isNotBlank()
    }

    val session: Flow<Session> = combine(
        settings.serverUrl,
        settings.accessToken,
        settings.serverUserName
    ) { url, token, name -> Session(url, token, name) }

    suspend fun testConnection(rawUrl: String): HealthDto {
        val base = ServerUrls.normalize(rawUrl)
        if (base.isBlank()) throw ServerException("先填服务器地址")
        return api.health(base)
    }

    /** 登录成功后把地址、令牌、用户名一起落盘，下次进 App 不用再填。 */
    suspend fun login(rawUrl: String, username: String, password: String): UserDto {
        val base = ServerUrls.normalize(rawUrl)
        if (base.isBlank()) throw ServerException("先填服务器地址")
        if (username.isBlank()) throw ServerException("用户名不能为空")
        val token = api.login(base, username.trim(), password)
        if (token.accessToken.isBlank()) throw ServerException("服务器没有返回令牌")
        settings.setServerSession(base, token.accessToken, token.user.username)
        return token.user
    }

    /** 只清令牌，保留地址 —— 换账号登录时不用重输 IP。 */
    suspend fun logout() = settings.clearServerToken()

    suspend fun decks(scope: String = "all", q: String? = null): List<DeckDto> {
        val base = settings.currentServerUrl()
        val token = settings.currentToken()
        if (base.isBlank()) throw ServerException("还没有设置服务器地址")
        if (token.isBlank()) throw ServerException("还没有登录")
        return api.decks(base, token, scope, q)
    }

    /** 分享码直达。用户可能带着横线、小写粘贴进来，交给服务端归一化。 */
    suspend fun deckByCode(code: String): DeckDto {
        val (base, token) = credentials()
        val cleaned = code.trim()
        if (cleaned.isBlank()) throw ServerException("先输入分享码")
        return api.deckByCode(base, token, cleaned)
    }

    /**
     * 下载词表并导入本地。
     *
     * 词表名用**服务器上的名字**，不用文件名 —— 这样重新下载同一张表会落回同一个本地词表，
     * 而不是每次多出一张「专升本(1)」。
     */
    suspend fun downloadAndImport(deck: DeckDto): ImportResult {
        val base = settings.currentServerUrl()
        val token = settings.currentToken()
        if (base.isBlank() || token.isBlank()) throw ServerException("还没有登录")

        val csv = api.exportCsv(base, token, deck.id)
        if (csv.isBlank()) throw ServerException("服务器返回了空文件")

        val parsed = ImportParser.parse(csv, hasHeaderRow = true)
        val preview = when (parsed) {
            is ParseOutcome.Failure -> throw ServerException("词表解析失败：${parsed.message}")
            is ParseOutcome.Success -> parsed.preview
        }

        val result = importer.import(preview.rows(), defaultDeckName = deck.name)
        // 与手动导入一致：导完立刻重建关联，否则新词的同根/变形要点一次「重建」才生效
        relations.rebuild()
        return result
    }

    // ---- 词表管理与私有词 ----

    private suspend fun credentials(): Pair<String, String> {
        val base = settings.currentServerUrl()
        val token = settings.currentToken()
        if (base.isBlank() || token.isBlank()) throw ServerException("还没有登录")
        return base to token
    }

    /** 只要自己名下的词表（官方表只读，跟着列表一起看就行）。 */
    suspend fun myDecks(): List<DeckDto> = decks("mine").filter { !it.official }

    suspend fun deckDetail(deckId: Long): DeckDto {
        val (base, token) = credentials()
        return api.deck(base, token, deckId)
    }

    /**
     * 新建词表。`seedFromDeckId` 只把源表的词**挂**过来，不复制词条 ——
     * 源表更新这边跟着变，源表删词这边也少。
     */
    suspend fun createDeck(
        name: String,
        description: String? = null,
        visibility: String = "private",
        seedFromDeckId: Long? = null
    ): DeckDto {
        val (base, token) = credentials()
        if (name.isBlank()) throw ServerException("词表名不能为空")
        return api.createDeck(
            base,
            token,
            CreateDeckRequest(
                name = name.trim(),
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                visibility = visibility,
                seedFromDeckId = seedFromDeckId
            )
        )
    }

    /** 只发需要改的字段；`null` 表示「这一项不动」。 */
    suspend fun updateDeck(
        deckId: Long,
        name: String? = null,
        description: String? = null,
        visibility: String? = null,
        shareCode: String? = null
    ): DeckDto {
        val (base, token) = credentials()
        if (name != null && name.isBlank()) throw ServerException("词表名不能为空")
        return api.updateDeck(
            base,
            token,
            deckId,
            UpdateDeckRequest(
                name = name?.trim(),
                description = description?.trim(),
                visibility = visibility,
                shareCode = shareCode?.trim()?.takeIf { it.isNotEmpty() }
            )
        )
    }

    suspend fun deckWords(deckId: Long, limit: Int = 6): WordPageDto {
        val (base, token) = credentials()
        return api.deckWords(base, token, deckId, limit)
    }

    suspend fun resetShareCode(deckId: Long): DeckDto {
        val (base, token) = credentials()
        return api.resetShareCode(base, token, deckId)
    }

    /** 把另一张词表的词挂到这张表上。只动挂载关系，不复制词条。 */
    suspend fun importFrom(deckId: Long, sourceId: Long, mode: String): ImportFromResult {
        val (base, token) = credentials()
        return api.importFrom(base, token, deckId, sourceId, mode)
    }

    suspend fun deleteDeck(deckId: Long) {
        val (base, token) = credentials()
        api.deleteDeck(base, token, deckId)
    }

    suspend fun searchWords(keyword: String, deckId: Long?): List<WordSearchHitDto> {
        val (base, token) = credentials()
        if (keyword.isBlank()) return emptyList()
        return api.searchWords(base, token, keyword.trim(), deckId).items
    }

    suspend fun addWords(deckId: Long, headwords: List<String>): DeckWordsResultDto {
        val (base, token) = credentials()
        return api.addWords(base, token, deckId, headwords)
    }

    suspend fun removeWords(deckId: Long, headwords: List<String>): DeckWordsResultDto {
        val (base, token) = credentials()
        return api.removeWords(base, token, deckId, headwords)
    }

    /** 上传 CSV/JSON：服务端写进 user_words，不碰公共词库。 */
    suspend fun uploadPrivateWords(
        deckId: Long,
        fileName: String,
        bytes: ByteArray
    ): PrivateImportResultDto {
        val (base, token) = credentials()
        return api.uploadPrivateWords(base, token, deckId, fileName, bytes)
    }
}
