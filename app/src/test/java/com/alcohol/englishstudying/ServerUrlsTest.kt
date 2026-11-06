package com.alcohol.englishstudying

import com.alcohol.englishstudying.data.remote.ServerUrls
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用户只会输 `192.168.1.5:8000` 这种，协议、结尾斜杠都要能容忍。
 * 这是纯字符串逻辑，放在这一层测最省事。
 */
class ServerUrlsTest {

    @Test
    fun `只有主机和端口时补 http`() {
        assertEquals("http://192.168.1.5:8000", ServerUrls.normalize("192.168.1.5:8000"))
    }

    @Test
    fun `已经带协议就保留`() {
        assertEquals("http://a:8000", ServerUrls.normalize("http://a:8000"))
        assertEquals("https://example.com", ServerUrls.normalize("https://example.com"))
    }

    @Test
    fun `去掉结尾斜杠`() {
        assertEquals("http://192.168.1.5:8000", ServerUrls.normalize("192.168.1.5:8000/"))
        assertEquals("https://example.com", ServerUrls.normalize("https://example.com///"))
    }

    @Test
    fun `前后空格被忽略`() {
        assertEquals("http://10.0.0.2:8000", ServerUrls.normalize("  10.0.0.2:8000  "))
    }

    @Test
    fun `空串保持空`() {
        assertEquals("", ServerUrls.normalize(""))
        assertEquals("", ServerUrls.normalize("   "))
    }
}
