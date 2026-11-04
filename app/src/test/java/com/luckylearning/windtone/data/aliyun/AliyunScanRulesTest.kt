package com.luckylearning.windtone.data.aliyun

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云盘目录递归扫描成歌单的编排规则（层深 2 层 / 过滤 / 排序 / 跳过）。
 *
 * [AliyunScanRules.scan] 注入列表函数即可离线跑，全部断言不需要真网。
 * 层深是用户拍板的固定语义（根 + 2 层，第三层不进），这是这里最重要的回归。
 */
class AliyunScanRulesTest {

    private fun dir(id: String, name: String) = AliyunEntry(id, name, isDir = true, sizeBytes = 0L)
    private fun audio(id: String, name: String) = AliyunEntry(id, name, isDir = false, sizeBytes = 1L)

    /** 目录索引：fileId → 该目录的内容 */
    private fun listing(vararg dirs: Pair<String, List<AliyunEntry>>): suspend (String) -> List<AliyunEntry> {
        val map = dirs.toMap()
        return { map[it] ?: error("没有这个目录：$it") }
    }

    @Test
    fun depthTwoDescendsTwoLevelsOnly() = runBlocking {
        val listDir = listing(
            "root" to listOf(dir("d1", "歌手"), audio("f0", "根目录.mp3")),
            "d1" to listOf(dir("d2", "专辑"), audio("f1", "一层.mp3")),
            "d2" to listOf(dir("d3", "再深一层"), audio("f2", "二层.mp3")),
            // 第三层：扫到了也不该收（canDescend(2) = false，d3 根本不会被列）
            "d3" to listOf(audio("f3", "三层.mp3")),
        )
        val outcome = AliyunScanRules.scan("root", listDir = listDir)

        assertEquals(listOf("根目录.mp3", "一层.mp3", "二层.mp3"), outcome.files.map { it.name })
        // 路径记录：根下文件路径为空，其余是目录名链
        assertEquals("", outcome.files[0].parentPath)
        assertEquals("歌手", outcome.files[1].parentPath)
        assertEquals("歌手/专辑", outcome.files[2].parentPath)
        assertTrue(outcome.skipped.isEmpty())
    }

    @Test
    fun resultsSortedByDirectoryThenFileName() = runBlocking {
        val listDir = listing(
            "root" to listOf(
                dir("b", "B专辑"),
                dir("a", "A专辑"),
                audio("z", "散落.wav"),
            ),
            "a" to listOf(audio("a2", "b歌.flac"), audio("a1", "a歌.flac")),
            "b" to listOf(audio("b1", "单曲.opus")),
        )
        val outcome = AliyunScanRules.scan("root", listDir = listDir)

        // 目录路径字母序（根目录路径为空串排最前），目录内按文件名
        assertEquals(
            listOf("散落.wav", "a歌.flac", "b歌.flac", "单曲.opus"),
            outcome.files.map { it.name },
        )
        assertEquals(listOf("", "A专辑", "A专辑", "B专辑"), outcome.files.map { it.parentPath })
    }

    @Test
    fun nonPlayableAudioIsExcluded() = runBlocking {
        val listDir = listing(
            "root" to listOf(
                audio("f1", " song.mp3"),
                audio("f2", "cover.jpg"),
                audio("f3", "无损.ape"), // 播放器解不了（没接 ffmpeg），不该进歌单
                audio("f4", "文档.pdf"),
                dir("d", "文件夹.mp3"), // 名字带音频后缀但它是目录
            ),
        )
        val outcome = AliyunScanRules.scan("root", listDir = listDir)

        assertEquals(listOf(" song.mp3"), outcome.files.map { it.name })
    }

    @Test
    fun failedDirectoryIsSkippedAndReported() = runBlocking {
        val listDir = listing(
            "root" to listOf(dir("bad", "坏目录"), audio("ok", "好的.mp3")),
            // "bad" 不在索引里 → listDir 抛异常
        )
        val outcome = AliyunScanRules.scan("root", listDir = listDir)

        assertEquals(listOf("好的.mp3"), outcome.files.map { it.name })
        assertEquals(1, outcome.skipped.size)
        assertTrue(outcome.skipped[0].contains("坏目录"))
    }

    @Test
    fun statusParsingIgnoresCaseAndUnknownMeansWaiting() {
        assertEquals(AliyunQrLogin.ScanStatus.WAITING, AliyunQrLogin.parseStatus("WAITING"))
        assertEquals(AliyunQrLogin.ScanStatus.SCANED, AliyunQrLogin.parseStatus("scaned"))
        assertEquals(AliyunQrLogin.ScanStatus.CONFIRMED, AliyunQrLogin.parseStatus("Confirmed"))
        assertEquals(AliyunQrLogin.ScanStatus.EXPIRED, AliyunQrLogin.parseStatus("expired"))
        // 未来服务端加新状态：按 WAITING 处理，轮询不被打断
        assertEquals(AliyunQrLogin.ScanStatus.WAITING, AliyunQrLogin.parseStatus("SOMETHING_NEW"))
        assertEquals(AliyunQrLogin.ScanStatus.WAITING, AliyunQrLogin.parseStatus(""))
    }

    @Test
    fun onlyConfirmedAndExpiredAreTerminal() {
        assertTrue(AliyunQrLogin.isTerminal(AliyunQrLogin.ScanStatus.CONFIRMED))
        assertTrue(AliyunQrLogin.isTerminal(AliyunQrLogin.ScanStatus.EXPIRED))
        assertTrue(!AliyunQrLogin.isTerminal(AliyunQrLogin.ScanStatus.WAITING))
        assertTrue(!AliyunQrLogin.isTerminal(AliyunQrLogin.ScanStatus.SCANED))
    }

    @Test
    fun umidTokenIs32HexAndVaries() {
        val a = AliyunQrLogin.randomUmidToken()
        val b = AliyunQrLogin.randomUmidToken()
        assertEquals(32, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertTrue("随机串不该两连相等", a != b)
    }

    @Test
    fun scanStopsWhenCancelledMidway() = runBlocking {
        // 第一个目录列完后取消扫描所在的 scope；根下留一个子目录让队列非空，
        // 这样扫描必须走到第二轮循环 —— 在目录之间被 ensureActive 拦下。
        // （不能直接 cancel runBlocking 自己：那会让整个测试协程带病结束。）
        var calls = 0
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val listDir: suspend (String) -> List<AliyunEntry> = { _ ->
            calls++
            if (calls == 1) scope.cancel()
            listOf(dir("d1", "子目录"))
        }
        val job = scope.launch { AliyunScanRules.scan("root", listDir = listDir) }
        job.join()
        assertTrue("取消后扫描任务应当以取消告终", job.isCancelled)
        assertEquals(1, calls)
    }

    @Test
    fun sameLevelDirectoriesAreConcurrentButMergedInQueueOrder() = runBlocking {
        var inFlight = 0
        var peak = 0
        val lock = Any()
        val listDir: suspend (String) -> List<AliyunEntry> = { id ->
            synchronized(lock) {
                inFlight++
                if (inFlight > peak) peak = inFlight
            }
            delay(20)
            synchronized(lock) { inFlight-- }
            if (id == "root") (1..6).map { dir("d$it", "目录$it") }
            else listOf(audio("${id}_f", "$id.mp3"))
        }

        val outcome = AliyunScanRules.scan("root", listDir = listDir)

        assertEquals(6, outcome.files.size)
        // 并发只发生在「取数据」；归并按 BFS 队列顺序，再经 sortCollected 排序，结果可复现
        assertEquals((1..6).map { "d$it.mp3" }, outcome.files.map { it.name })
        assertTrue("同层应真的并发，实测峰值 $peak", peak > 1)
        assertTrue(
            "并发不得超过上限 ${AliyunScanRules.MAX_PARALLEL_DIRS}（实测 $peak）",
            peak <= AliyunScanRules.MAX_PARALLEL_DIRS,
        )
    }
}
