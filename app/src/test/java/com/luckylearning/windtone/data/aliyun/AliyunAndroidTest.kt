package com.luckylearning.windtone.data.aliyun

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 依赖 android.net.Uri / org.json 的用例（Robolectric）。
 *
 * 工程的单测默认是纯 JVM 跑的，这两个类在那套环境里都是「不可调用的 stub」，
 * 一调就抛「not mocked」—— 所以单独挂 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
class AliyunAndroidTest {

    @Test
    fun `错误响应两种字段名都认`() {
        assertEquals(
            "登录状态已过期，请重新登录",
            AliyunErrors.hint(JSONObject("""{"code":"AccessTokenExpired"}""")),
        )
        assertEquals(
            "刷新令牌无效或已失效，请重新获取",
            AliyunErrors.hint(JSONObject("""{"error":"invalid_grant"}""")),
        )
        assertEquals("网络超时", AliyunErrors.hint(JSONObject("""{"message":"网络超时"}""")))
    }

    @Test
    fun `BadRequest 要说明是参数问题而不是登录问题`() {
        // 真机排障教训：drive_id 为空时发 getDownloadUrl，阿里回 {"code":"BadRequest"}，
        // 旧文案「阿里云盘返回错误：BadRequest」让人以为是账号/服务端问题
        assertEquals(
            "请求参数无效（多为 drive_id / file_id 为空或不匹配，不是登录问题）",
            AliyunErrors.hint(JSONObject("""{"code":"BadRequest"}""")),
        )
    }

    @Test
    fun `网页线盘信息解析认 personal_drive_info 并回退根层级`() {
        val info = JSONObject(
            """{"personal_drive_info":{"resource_drive_id":"res-1","backup_drive_id":"bak-1"}}""",
        )
        val parsed = AliyunDrive.parseWebPersonalInfo(info)
        assertEquals("res-1", parsed.resourceDriveId)
        assertEquals("bak-1", parsed.backupDriveId)
        assertEquals("res-1", parsed.preferredDriveId()) // 无 default 时退资源库
    }

    @Test
    fun `网页线昵称从 user_get 的多种字段名里取`() {
        val info = JSONObject("""{"personal_drive_info":{"resource_drive_id":"res-1"}}""")
        for (key in listOf("user_name", "nick_name", "nickname", "name")) {
            val user = JSONObject("""{"$key":"张三"}""")
            assertEquals(key, "张三", AliyunDrive.parseWebPersonalInfo(info, user).nickname)
        }
        // 没有 user 信息也不炸，给默认名
        assertEquals("阿里云盘用户", AliyunDrive.parseWebPersonalInfo(info).nickname)
    }

    @Test
    fun `网页线一个盘 id 都没有时给出明确错误`() {
        val info = JSONObject("""{"personal_drive_info":{}}""")
        val err = runCatching { AliyunDrive.parseWebPersonalInfo(info) }.exceptionOrNull()
        assertEquals("账号没有可用的盘（响应里没有任何 drive_id）", err?.message)
    }

    @Test
    fun `OpenList 在线接口的成功响应形态能解析出令牌`() {
        // 字段名与 OpenList 服务端一致：access_token / refresh_token / text(错误时才非空)
        val json = JSONObject(
            """{"access_token":"AT","refresh_token":"RT","text":""}""",
        )
        assertEquals("AT", json.optString("access_token"))
        assertEquals("RT", json.optString("refresh_token"))
        assertEquals("", json.optString("text"))
    }

    @Test
    fun `队列歌曲的 uri fragment 带稳定缓存键`() {
        // 命名参数强制走主构造（次构造是 AliyunAuth 版本，需要 Context）
        val drive = AliyunDrive(tokenProvider = { null })
        val song = drive.toSong(
            AliyunEntry("6603f2e2abcd", "晴天.flac", false, 28_000_000),
            "https://preview.alipan.com/abc?sign=xyz",
        )
        assertEquals("wt=6603f2e2abcd", song.uri.fragment)
        // fragment 不进 HTTP 请求：uri 去掉 fragment 后就是原始直链
        assertEquals(
            "https://preview.alipan.com/abc?sign=xyz",
            song.uri.buildUpon().fragment(null).build().toString(),
        )
        assertEquals("阿里云盘", song.album)
        assertEquals("晴天", song.title)
        assertTrue(song.id < 0)
        assertEquals(28_000_000, song.sizeBytes)
    }

    @Test
    fun `Uri 解析不改变直链本身`() {
        val raw = "https://cn-beijing-data.aliyundrive.net/abc%2Fdef?x=1&y=2"
        assertEquals(raw, Uri.parse(raw).toString())
    }
}
