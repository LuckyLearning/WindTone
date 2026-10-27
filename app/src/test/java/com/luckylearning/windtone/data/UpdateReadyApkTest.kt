package com.luckylearning.windtone.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.state.UpdateUiState
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「已下载待装」状态的持久化与恢复（v1.2.8）。
 *
 * ## 为什么值得测
 *
 * 这条链路的每一环坏了症状都一样：**用户切出去回来就得重新下载**——
 *  - 路径没落盘：下载完杀后台，重启后待装状态消失；
 *  - 恢复时不查文件：缓存被系统清了，界面却还挂着「立即安装」，点下去必然失败；
 *  - 装完不清理：升级重启后更新页还残留上一版的安装行，点了等于重装旧包。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateReadyApkTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs
    private lateinit var repo: UpdateRepository

    /** 造一个「已下载完成」的假 APK 文件（内容无所谓，只看存在性） */
    private fun fakeApk(): File =
        File(context.cacheDir, "WindTone-v999.0.0-universal.apk").apply { writeText("PK-fake") }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = Prefs(context)
        // 关掉自动检查，避免 init 里的延迟网络请求干扰断言
        prefs.autoCheckUpdate = false
        prefs.lastUpdateCheckAt = System.currentTimeMillis()
    }

    @After
    fun tearDown() {
        prefs.updateReadyApkPath = ""
        prefs.updateReadyApkVersion = ""
    }

    private fun newRepo(): UpdateRepository =
        UpdateRepository(context as android.app.Application, prefs, MessageBus())

    @Test
    fun downloadPersistsPathAndVersionToPrefs() {
        val apk = fakeApk()
        prefs.updateReadyApkPath = apk.absolutePath
        prefs.updateReadyApkVersion = "999.0.0"

        repo = newRepo()

        assertEquals(apk.absolutePath, prefs.updateReadyApkPath)
        assertEquals("999.0.0", prefs.updateReadyApkVersion)
    }

    @Test
    fun restoreResumesReadyStateWhenFileExistsAndVersionDiffers() {
        val apk = fakeApk()
        prefs.updateReadyApkPath = apk.absolutePath
        prefs.updateReadyApkVersion = "999.0.0"

        repo = newRepo()

        assertNotNull("文件在、版本不同 → 应恢复待装状态", repo.update.value.readyApkPath)
        assertEquals("999.0.0", repo.update.value.readyApkVersion)
    }

    @Test
    fun restoreClearsRecordWhenDownloadedVersionEqualsCurrent() {
        val apk = fakeApk()
        prefs.updateReadyApkPath = apk.absolutePath
        prefs.updateReadyApkVersion = UpdateChecker.currentVersion

        repo = newRepo()

        assertNull("装完重启（版本相同）→ 记录应清掉，不能残留上一版的安装行", repo.update.value.readyApkPath)
        assertEquals("", prefs.updateReadyApkPath)
        assertEquals("", prefs.updateReadyApkVersion)
    }

    @Test
    fun restoreClearsRecordWhenFileWasEvicted() {
        val apk = fakeApk()
        prefs.updateReadyApkPath = apk.absolutePath
        prefs.updateReadyApkVersion = "999.0.0"
        // 模拟系统清缓存：路径落了盘，文件却没了
        apk.delete()

        repo = newRepo()

        assertNull("文件被清 → 不应恢复待装状态", repo.update.value.readyApkPath)
        assertEquals("", prefs.updateReadyApkPath)
    }

    @Test
    fun ignoreUpdateClearsPersistedRecordAndState() {
        val apk = fakeApk()
        prefs.updateReadyApkPath = apk.absolutePath
        prefs.updateReadyApkVersion = "999.0.0"

        repo = newRepo()
        repo.ignoreUpdate()

        assertEquals("", prefs.updateReadyApkPath)
        assertEquals("", prefs.updateReadyApkVersion)
        assertEquals(UpdateUiState().copy(currentVersion = UpdateChecker.currentVersion, route = prefs.updateDownloadRoute), repo.update.value)
    }

    @Test
    fun installWithoutPermissionOpensDialogInsteadOfSilentNoop() {
        val apk = fakeApk()
        prefs.updateReadyApkPath = apk.absolutePath
        prefs.updateReadyApkVersion = "999.0.0"
        repo = newRepo()
        // Robolectric 默认未授予「安装未知应用」→ canInstall=false
        org.junit.Assume.assumeFalse(UpdateChecker.canInstall(context))

        repo.installUpdate()

        assertTrue(
            "从更新页点安装但没授权时，必须把弹窗打开（授权按钮才有着落），不能静默吞掉",
            repo.update.value.dialogVisible,
        )
        assertTrue(repo.update.value.needInstallPermission)
    }
}
