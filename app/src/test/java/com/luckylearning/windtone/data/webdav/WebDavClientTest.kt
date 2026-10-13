package com.luckylearning.windtone.data.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WebDAV URL 拼接测试。
 *
 * 这是云端播放正确性的关键一环：文件 URL 带了尾斜杠会被部分服务器按目录
 * 处理直接 404；中文/空格文件名不编码则请求直接失败。
 */
class WebDavClientTest {

    @Test
    fun `root dir keeps trailing slash`() {
        assertEquals("https://nas.local:5244/dav/", WebDavClient.joinUrl("https://nas.local:5244/dav", ""))
        assertEquals("https://nas.local:5244/dav/", WebDavClient.joinUrl("https://nas.local:5244/dav/", ""))
    }

    @Test
    fun `dir url ends with slash`() {
        assertEquals(
            "https://nas.local:5244/dav/Music/%E5%91%A8%E6%9D%B0%E4%BC%A6/",
            WebDavClient.joinUrl("https://nas.local:5244/dav", "/Music/周杰伦/"),
        )
    }

    @Test
    fun `file url has no trailing slash`() {
        assertEquals(
            "https://nas.local:5244/dav/Music/song.mp3",
            WebDavClient.joinUrl("https://nas.local:5244/dav", "/Music/song.mp3", trailingSlash = false),
        )
    }

    @Test
    fun `spaces and plus are percent-encoded`() {
        assertEquals(
            "https://dav/My%20Music/a%2Bb.flac",
            WebDavClient.joinUrl("https://dav", "/My Music/a+b.flac", trailingSlash = false),
        )
    }

    @Test
    fun `redundant slashes are collapsed`() {
        assertEquals(
            "https://dav/a/b/c.mp3",
            WebDavClient.joinUrl("https://dav", "//a//b/c.mp3", trailingSlash = false),
        )
    }
}
