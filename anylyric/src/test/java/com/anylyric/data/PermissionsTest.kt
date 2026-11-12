package com.anylyric.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统保存的通知使用权列表解析。
 *
 * 这一层判错的后果很具体：权限明明勾着，界面说「未授予」，用户只能去系统设置
 * 里关掉再打开一次 —— 真机反馈的现象。
 */
class PermissionsTest {

    private val pkg = "com.anylyric"
    private val cls = "com.anylyric.service.MediaWatchService"

    @Test
    fun findsOwnComponentAmongOthers() {
        val flat = "com.example.player/com.example.player.Listener:" +
            "$pkg/$cls:" +
            "com.other/.other.Service"
        assertTrue(Permissions.matches(flat, pkg, cls))
    }

    @Test
    fun expandsRelativeClassName() {
        // 系统与部分 ROM 会把类名写成相对形式，不展开就会漏判
        assertTrue(Permissions.matches("$pkg/.service.MediaWatchService", pkg, cls))
    }

    @Test
    fun ignoresSamePackageDifferentService() {
        assertFalse(Permissions.matches("$pkg/.service.OtherService", pkg, cls))
    }

    @Test
    fun ignoresSameServiceNameDifferentPackage() {
        assertFalse(Permissions.matches("com.other/$cls", pkg, cls))
    }

    @Test
    fun emptyOrNullListMeansNotGranted() {
        assertFalse(Permissions.matches(null, pkg, cls))
        assertFalse(Permissions.matches("", pkg, cls))
    }

    @Test
    fun ignoresMalformedEntries() {
        assertFalse(Permissions.matches("$pkg:$pkg", pkg, cls))
        assertFalse(Permissions.matches("$pkg/", pkg, cls))
        assertFalse(Permissions.matches("/$cls", pkg, cls))
    }

    @Test
    fun trailingSeparatorIsTolerated() {
        // 系统在关闭最后一项后可能留下尾部分隔符，不应影响其它项的判定
        assertTrue(Permissions.matches("$pkg/$cls:", pkg, cls))
    }
}
