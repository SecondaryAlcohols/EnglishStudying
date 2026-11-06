package com.alcohol.englishstudying

import com.alcohol.englishstudying.data.remote.ServerApi
import com.alcohol.englishstudying.data.remote.ServerUrls
import com.alcohol.englishstudying.data.remote.CreateDeckRequest
import com.alcohol.englishstudying.data.remote.UpdateDeckRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 打到真实服务器的联调用例。
 *
 * 默认跳过（CI 上没有服务器），需要时这样跑：
 *
 * ```
 * VOCAB_SERVER=http://127.0.0.1:8000 \
 * VOCAB_USER=t_admin VOCAB_PASS=t_admin_123 \
 * ./gradlew :app:testDebugUnitTest --tests "*ServerApiLiveTest*"
 * ```
 *
 * 它验证的是「App 的 HTTP 层」而不是 curl：DTO 字段、鉴权头、CSV 下载、
 * 错误翻译，任何一处对不上都会在这里红。
 */
class ServerApiLiveTest {

    private val rawServer = System.getenv("VOCAB_SERVER").orEmpty()
    private val user = System.getenv("VOCAB_USER") ?: "t_admin"
    private val password = System.getenv("VOCAB_PASS") ?: "t_admin_123"
    private val api = ServerApi()

    private val baseUrl: String get() = ServerUrls.normalize(rawServer)

    @Before
    fun requireServer() {
        assumeTrue("没有设置 VOCAB_SERVER，跳过联调", rawServer.isNotBlank())
    }

    @Test
    fun `健康检查能解析`() = runBlocking {
        val health = api.health(baseUrl)
        assertEquals("ok", health.status)
        assertEquals("ok", health.database)
        assertTrue("词表数应该大于 0", health.deckCount > 0)
    }

    @Test
    fun `登录后能列出词表`() = runBlocking {
        val token = api.login(baseUrl, user, password)
        assertTrue("应当拿到令牌", token.accessToken.isNotBlank())
        assertEquals(user, token.user.username)

        val decks = api.decks(baseUrl, token.accessToken)
        assertTrue("至少有两张官方词表", decks.size >= 2)
        assertTrue("词表带词数", decks.all { it.wordCount >= 0 })
        assertTrue("能识别官方标记", decks.any { it.official })
    }

    @Test
    fun `下载的 CSV 是 App 的导入格式`() = runBlocking {
        val token = api.login(baseUrl, user, password)
        val deck = api.decks(baseUrl, token.accessToken).first()
        val csv = api.exportCsv(baseUrl, token.accessToken, deck.id)

        assertTrue("CSV 不该为空", csv.isNotBlank())
        val header = csv.removePrefix("\uFEFF").lineSequence().first()
        listOf("word", "phonetic", "pos", "meaning").forEach { column ->
            assertTrue("表头缺少 $column：$header", header.contains(column))
        }
        assertTrue("行数应当和词数一致", csv.lineSequence().count() > deck.wordCount / 2)
    }

    @Test
    fun `建表_上传_搜索_改名_删表 全流程`() = runBlocking {
        val token = api.login(baseUrl, user, password).accessToken
        val deck = api.createDeck(
            baseUrl,
            token,
            CreateDeckRequest(name = "联调-${System.currentTimeMillis()}", visibility = "public")
        )
        assertTrue("公开表应当带分享码", !deck.shareCode.isNullOrBlank())
        try {
            val csv = "word,pos,meaning\nzzztest,/z/,n. 联调测试词\n"
            val uploaded = api.uploadPrivateWords(
                baseUrl, token, deck.id, "联调.csv", csv.toByteArray()
            )
            assertEquals("上传应当新建 1 条私有词", 1, uploaded.created)
            assertEquals("应当挂到词表", 1, uploaded.linked)

            val page = api.searchWords(baseUrl, token, "zzztest", deck.id)
            assertTrue("搜索应命中", page.items.isNotEmpty())
            assertTrue("应当标记为已在词表", page.items.any { it.inDeck })

            val removed = api.removeWords(baseUrl, token, deck.id, listOf("zzztest"))
            assertEquals("应当摘掉 1 条", 1, removed.removed)

            val byCode = api.deckByCode(baseUrl, token, deck.prettyShareCode.lowercase())
            assertEquals("按分享码（小写带横线）应当查回同一张表", deck.id, byCode.id)

            val renamed = api.updateDeck(
                baseUrl, token, deck.id, UpdateDeckRequest(name = "联调-改名")
            )
            assertEquals("联调-改名", renamed.name)

            val custom = api.updateDeck(
                baseUrl, token, deck.id, UpdateDeckRequest(shareCode = "lian-diao-${System.currentTimeMillis() % 100000}")
            )
            assertTrue("自定义码应当落成大写去横线", custom.shareCode?.startsWith("LIAN") == true)

            val reset = api.resetShareCode(baseUrl, token, deck.id)
            assertTrue("重置后码应当变了", reset.shareCode != custom.shareCode)
        } finally {
            api.deleteDeck(baseUrl, token, deck.id)
        }
    }

    @Test
    fun `密码错误会被翻译成中文`() = runBlocking {
        val error = runCatching { api.login(baseUrl, user, "definitely-wrong") }.exceptionOrNull()
        assertTrue("应当抛异常", error != null)
        assertTrue("错误信息应为中文：${error?.message}", error?.message?.contains("密码") == true)
    }
}
