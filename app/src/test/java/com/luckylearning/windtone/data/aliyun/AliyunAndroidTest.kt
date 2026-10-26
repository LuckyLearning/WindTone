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
            "登录状态已过期。下一步：到设置 → 在线服务重新扫码授权",
            AliyunErrors.hint(JSONObject("""{"code":"AccessTokenExpired"}""")),
        )
        assertEquals(
            "刷新令牌无效或已失效。下一步：到设置 → 在线服务重新扫码授权",
            AliyunErrors.hint(JSONObject("""{"error":"invalid_grant"}""")),
        )
        assertEquals("网络超时", AliyunErrors.hint(JSONObject("""{"message":"网络超时"}""")))
    }

    @Test
    fun `BadRequest 要说明是参数问题而不是登录问题`() {
        // 真机排障教训：drive_id 为空时发 getDownloadUrl，阿里回 {"code":"BadRequest"}，
        // 旧文案「阿里云盘返回错误：BadRequest」让人以为是账号/服务端问题
        assertEquals(
            "请求参数无效（多为 drive_id / file_id 为空或不匹配，不是登录问题）。下一步：重新进入云盘目录再试",
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
    fun `盘 id 在 user_get 根上，get_personal_info 没有也不报没有盘`() {
        // 现行网页接口：/v2/user/get 带 default/resource/backup_drive_id，
        // get_personal_info 往往只有容量。1.1.67 只解析后者，登录成功却报「没有任何 drive_id」。
        val personal = JSONObject("""{"personal_space_info":{"used_size":1,"total_size":2}}""")
        val user = JSONObject(
            """{"nick_name":"张三","default_drive_id":"def-1","resource_drive_id":"res-1","backup_drive_id":"bak-1"}""",
        )
        val parsed = AliyunDrive.parseWebPersonalInfo(personal, user)
        assertEquals("def-1", parsed.defaultDriveId)
        assertEquals("res-1", parsed.resourceDriveId)
        assertEquals("bak-1", parsed.backupDriveId)
        assertEquals("res-1", parsed.preferredDriveId())
        assertEquals("张三", parsed.nickname)
    }

    @Test
    fun `user_get 只有 default_drive_id 也能进盘`() {
        val user = JSONObject("""{"user_name":"李四","default_drive_id":"only-default"}""")
        val parsed = AliyunDrive.parseWebPersonalInfo(JSONObject(), user)
        assertEquals("only-default", parsed.preferredDriveId())
        assertEquals("李四", parsed.nickname)
    }

    @Test
    fun `JSON null 的 default_drive_id 不当成盘 id`() {
        val user = JSONObject().apply {
            put("default_drive_id", JSONObject.NULL)
            put("resource_drive_id", "res-1")
        }
        val parsed = AliyunDrive.parseWebPersonalInfo(JSONObject(), user)
        assertEquals("", parsed.defaultDriveId)
        assertEquals("res-1", parsed.preferredDriveId())
    }

    @Test
    fun `只有 drive_id 时把它当作默认盘`() {
        val user = JSONObject("""{"drive_id":"plain-1"}""")
        assertEquals("plain-1", AliyunDrive.parseWebPersonalInfo(JSONObject(), user).preferredDriveId())
    }

    @Test
    fun `两份 user_get 合并时用有盘 id 的那份补空`() {
        val nicknameOnly = JSONObject("""{"nick_name":"张三","default_drive_id":null}""")
        val withDrive = JSONObject("""{"resource_drive_id":"res-9","backup_drive_id":"bak-9"}""")
        val merged = AliyunDrive.mergeUserJson(nicknameOnly, withDrive)
        val parsed = AliyunDrive.parseWebPersonalInfo(JSONObject(), merged)
        assertEquals("张三", parsed.nickname)
        assertEquals("res-9", parsed.resourceDriveId)
        assertEquals("bak-9", parsed.backupDriveId)
        assertEquals("res-9", parsed.preferredDriveId())
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
            AliyunEntry("6603f2e2abcd", "周杰伦 - 晴天.flac", false, 28_000_000),
            "https://preview.alipan.com/abc?sign=xyz",
            driveId = "drive-1",
            folderName = "范特西",
        )
        assertEquals(com.luckylearning.windtone.data.model.TrackFragment.of("drive-1", "6603f2e2abcd"), song.uri.fragment)
        assertEquals(
            "https://preview.alipan.com/abc?sign=xyz",
            song.uri.buildUpon().fragment(null).build().toString(),
        )
        assertEquals("范特西", song.album)
        assertEquals("晴天", song.title)
        assertEquals("周杰伦", song.artist)
        assertEquals("aliyun:" + song.uri.fragment, song.stableKey)
        assertTrue(song.id < 0)
        assertEquals(28_000_000, song.sizeBytes)
    }

    @Test
    fun `Uri 解析不改变直链本身`() {
        val raw = "https://cn-beijing-data.aliyundrive.net/abc%2Fdef?x=1&y=2"
        assertEquals(raw, Uri.parse(raw).toString())
    }
}
