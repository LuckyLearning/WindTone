# -*- coding: utf-8 -*-
from pathlib import Path
ROOT = Path(r"E:\workspace\Android\WindTone")

def must_replace(path, old, new, label):
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if new.strip()[:40] in text and old not in text:
        print(f"{label}: already applied")
        return
    if old not in text:
        raise SystemExit(f"{label}: OLD NOT FOUND in {path}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"{label}: OK")

# 1 SleepTimer
must_replace(
"app/src/main/java/com/luckylearning/windtone/playback/SleepTimer.kt",
"""    fun cancel() {
        job?.cancel()
        job = null
        _remainingMs.value = 0L
    }
}
""",
"""    /**
     * 「播完当前队列」要等多久：当前曲剩余 + 队列里**当前索引之后全部**曲目的时长。
     *
     * 纯函数，便于 JVM 单测。与 [albumRemainingMs] 的差别：不按专辑截断，一直累加到队尾。
     * 语义是「这一轮队列还剩多久」——列表循环时也只算到队尾一次，不会无限拖长。
     *
     * 索引越界 / 空队列：退化为「当前曲剩余」（与专辑档兜底一致）。
     */
    fun queueRemainingMs(playlist: List<Song>, index: Int, positionMs: Long, durationMs: Long): Long {
        val current = (durationMs - positionMs).coerceAtLeast(0L)
        if (index < 0 || index >= playlist.size) return current
        var total = current
        var i = index + 1
        while (i < playlist.size) {
            total += playlist[i].durationMs.coerceAtLeast(0L)
            i++
        }
        return total
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remainingMs.value = 0L
    }
}
""",
"SleepTimer")

# 2 PlayerActions
must_replace(
"app/src/main/java/com/luckylearning/windtone/playback/PlayerActions.kt",
"""    /** 播完当前专辑（当前曲剩余 + 队列里后续同专辑曲目） */
    fun sleepEndOfAlbum()
    fun cancelSleep()
""",
"""    /** 播完当前专辑（当前曲剩余 + 队列里后续同专辑曲目） */
    fun sleepEndOfAlbum()

    /** 播完当前队列（当前曲剩余 + 队列里当前索引之后全部曲目） */
    fun sleepEndOfQueue()
    fun cancelSleep()
""",
"PlayerActions")

# 3 PlaybackEngine
must_replace(
"app/src/main/java/com/luckylearning/windtone/playback/PlaybackEngine.kt",
"""        SleepTimer.start(
            SleepTimer.albumRemainingMs(
                playlist = playlist,
                index = idx,
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = c.duration.takeIf { it > 0 } ?: _progress.value.durationMs,
            ),
        )
    }

    override fun cancelSleep() = SleepTimer.cancel()
""",
"""        SleepTimer.start(
            SleepTimer.albumRemainingMs(
                playlist = playlist,
                index = idx,
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = c.duration.takeIf { it > 0 } ?: _progress.value.durationMs,
            ),
        )
    }

    /**
     * 「播完当前队列」：当前曲剩余 + 队列里**当前索引之后全部**曲目时长。
     * 列表循环时也只算到队尾一次（与 SleepTimer.queueRemainingMs 注释一致）。
     * 控制器或索引不可用时退化为「播完当前曲」。
     */
    override fun sleepEndOfQueue() {
        val c = controller
        val playlist = currentQueue
        val idx = c?.currentMediaItemIndex ?: -1
        if (c == null || idx < 0 || playlist.isEmpty()) {
            sleepEndOfSong()
            return
        }
        SleepTimer.start(
            SleepTimer.queueRemainingMs(
                playlist = playlist,
                index = idx,
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = c.duration.takeIf { it > 0 } ?: _progress.value.durationMs,
            ),
        )
    }

    override fun cancelSleep() = SleepTimer.cancel()
""",
"PlaybackEngine")

# 4 SheetsPlayback
must_replace(
"app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt",
"""fun SleepSheet(
    onClose: () -> Unit,
    onSet: (Int) -> Unit,
    onEndOfSong: () -> Unit,
    onEndOfAlbum: () -> Unit,
    onCancel: () -> Unit,
)""",
"""fun SleepSheet(
    onClose: () -> Unit,
    onSet: (Int) -> Unit,
    onEndOfSong: () -> Unit,
    onEndOfAlbum: () -> Unit,
    onEndOfQueue: () -> Unit,
    onCancel: () -> Unit,
)""",
"SleepSheet-sig")

sp = (ROOT / "app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt").read_text(encoding="utf-8")
old_btn = """            Text(
                "播完当前专辑暂停",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onEndOfAlbum() }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (remaining > 0) {"""
new_btn = """            Text(
                "播完当前专辑暂停",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onEndOfAlbum() }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            // A2：整份队列听完再停。与「播完当前专辑」并列；时长按队列顺序累加到队尾一次。
            Text(
                "播完当前队列暂停",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onEndOfQueue() }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (remaining > 0) {"""
if "播完当前队列暂停" not in sp:
    if old_btn not in sp:
        raise SystemExit("SleepSheet button block not found")
    sp = sp.replace(old_btn, new_btn, 1)
    sp = sp.replace(
        "三期规划里承诺过三种模式，此前只落地了两种（倒计时 / 播完当前曲）。",
        "倒计时 / 播完当前曲 / 播完当前专辑之外，再补「播完当前队列」。",
        1,
    )
    (ROOT / "app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt").write_text(sp, encoding="utf-8")
    print("SleepSheet-btn: OK")
else:
    print("SleepSheet-btn: already")

# 5 PlayerScreen
must_replace(
"app/src/main/java/com/luckylearning/windtone/ui/PlayerScreen.kt",
"""    onSleep: (Int) -> Unit,
    onSleepEndOfSong: () -> Unit,
    onSleepEndOfAlbum: () -> Unit,
    onCancelSleep: () -> Unit,""",
"""    onSleep: (Int) -> Unit,
    onSleepEndOfSong: () -> Unit,
    onSleepEndOfAlbum: () -> Unit,
    onSleepEndOfQueue: () -> Unit,
    onCancelSleep: () -> Unit,""",
"PlayerScreen-sig")

must_replace(
"app/src/main/java/com/luckylearning/windtone/ui/PlayerScreen.kt",
"""    if (showSleep) SleepSheet(
        onClose = { showSleep = false },
        onSet = { onSleep(it); showSleep = false },
        onEndOfSong = { onSleepEndOfSong(); showSleep = false },
        onEndOfAlbum = { onSleepEndOfAlbum(); showSleep = false },
        onCancel = { onCancelSleep(); showSleep = false },
    )""",
"""    if (showSleep) SleepSheet(
        onClose = { showSleep = false },
        onSet = { onSleep(it); showSleep = false },
        onEndOfSong = { onSleepEndOfSong(); showSleep = false },
        onEndOfAlbum = { onSleepEndOfAlbum(); showSleep = false },
        onEndOfQueue = { onSleepEndOfQueue(); showSleep = false },
        onCancel = { onCancelSleep(); showSleep = false },
    )""",
"PlayerScreen-call")

# 6 MainScreenParts
must_replace(
"app/src/main/java/com/luckylearning/windtone/ui/MainScreenParts.kt",
"""            onSleep = playerVm::sleepAfter,
            onSleepEndOfSong = playerVm::sleepEndOfSong,
            onSleepEndOfAlbum = playerVm::sleepEndOfAlbum,
            onCancelSleep = playerVm::cancelSleep,""",
"""            onSleep = playerVm::sleepAfter,
            onSleepEndOfSong = playerVm::sleepEndOfSong,
            onSleepEndOfAlbum = playerVm::sleepEndOfAlbum,
            onSleepEndOfQueue = playerVm::sleepEndOfQueue,
            onCancelSleep = playerVm::cancelSleep,""",
"MainScreenParts")

# 7 Tests
tp = ROOT / "app/src/test/java/com/luckylearning/windtone/playback/SleepTimerTest.kt"
tt = tp.read_text(encoding="utf-8")
extra = """

    // ---------- A2：播完当前队列 ----------

    @Test
    fun `队列第一首起播等于整队剩余`() {
        // 剩 180s + 240 + 240 + 300 = 960s
        val ms = SleepTimer.queueRemainingMs(albumQueue(), 0, positionMs = 60_000L, durationMs = 240_000L)
        assertEquals(960_000L, ms)
    }

    @Test
    fun `队列最后一首只等于本曲剩余`() {
        val ms = SleepTimer.queueRemainingMs(albumQueue(), 3, positionMs = 50_000L, durationMs = 300_000L)
        assertEquals(250_000L, ms)
    }

    @Test
    fun `队列中段累加到队尾不按专辑截断`() {
        // 当前 a2：剩 200s + a3 240 + b1 300 = 740s（专辑档只会到 a3）
        val ms = SleepTimer.queueRemainingMs(albumQueue(), 1, positionMs = 40_000L, durationMs = 240_000L)
        assertEquals(740_000L, ms)
    }

    @Test
    fun `队列索引越界退化为当前曲剩余`() {
        val ms = SleepTimer.queueRemainingMs(albumQueue(), index = 99, positionMs = 10_000L, durationMs = 100_000L)
        assertEquals(90_000L, ms)
    }

    @Test
    fun `空队列退化为当前曲剩余`() {
        val ms = SleepTimer.queueRemainingMs(emptyList(), index = 0, positionMs = 20_000L, durationMs = 100_000L)
        assertEquals(80_000L, ms)
    }

    @Test
    fun `后续曲目时长为 0 时只累加已知部分`() {
        val queue = listOf(
            song("a1", "A", 120_000L),
            song("a2", "A", 0L),
            song("a3", "B", 90_000L),
        )
        val ms = SleepTimer.queueRemainingMs(queue, 0, positionMs = 0L, durationMs = 120_000L)
        assertEquals(210_000L, ms)
    }
"""
if "队列第一首起播等于整队剩余" not in tt:
    stripped = tt.rstrip() + "\n"
    if not stripped.endswith("}\n"):
        raise SystemExit("test file end unexpected: " + repr(stripped[-30:]))
    tp.write_text(stripped[:-2] + extra + "}\n", encoding="utf-8")
    print("SleepTimerTest: OK")
else:
    print("SleepTimerTest: already")

# 8 Docs
doc = """# 睡眠定时：播完当前队列（今晚 A2）

> 落地日：2026-10-31（Asia/Shanghai）
> 版本：随下一 patch 发版（实现提交在前，versionCode 由 `scripts/release.sh` 抬）

## 干了什么

睡眠定时在「倒计时 / 播完当前曲 / 播完当前专辑」之外，新增 **「播完当前队列暂停」**：

- 时长 = 当前曲剩余 + 播放队列里**当前索引之后全部**曲目的 `durationMs`
- 列表循环时也**只算到队尾一次**，不会因为循环无限拖长
- 与「播完当前专辑」并列入口；专辑档仍按同专辑截断，本档不按专辑截断
- 控制器不可用 / 索引无效 / 空队列：退化为「播完当前曲」（与专辑档兜底同口径）
- 到点仍走既有 10 秒淡出 + `SleepTimer.fireAction` 暂停，不新开淡出逻辑

## 怎么干的

1. **纯函数** `SleepTimer.queueRemainingMs(playlist, index, positionMs, durationMs)`：与 `albumRemainingMs` 同形，循环累加到 `playlist.size`，不比对 `album`。
2. **引擎** `PlaybackEngine.sleepEndOfQueue()`：读 `currentQueue` + controller 索引/进度/时长，交给上面纯函数再 `SleepTimer.start`；失败路径调 `sleepEndOfSong()`。
3. **接口** `PlayerActions.sleepEndOfQueue()`；UI `SleepSheet` 增加一行「播完当前队列暂停」；`PlayerScreen` / `MainScreenParts` 接线。
4. **单测** `SleepTimerTest` 增补 6 例：整队、队尾、中段（相对专辑不截断）、越界、空队列、时长为 0。
5. **文档**：本页 + `功能说明.md` 睡眠定时一条补第四档；发版时写入 `版本历史.md`。

## 怎么验

1. 组一份 ≥3 首的队列，播到第 1 首中段 → 睡眠定时 →「播完当前队列暂停」→ 剩余时间应约等于「本曲剩余 + 后面所有曲时长」。
2. 同队列改点「播完当前专辑」：若后续跨专辑，队列档应比专辑档更长。
3. 播到最后一首再点队列档：应约等于本曲剩余。
4. 到点前约 10 秒应有淡出，随后暂停。

## 不做什么

- 不做「列表终」事件监听式停播（仍用预计算倒计时，与曲/专辑档一致）
- 不改场景化助眠默认（仍是 45 分钟倒计时）
- 本提交不碰 i18n 字符串抽取（SleepSheet 仍硬编码中文，与现网其它三档一致；错开并行 i18n）
"""
(ROOT / "docs/功能-睡眠定时播完队列.md").write_text(doc, encoding="utf-8")
print("doc: OK")

fn = ROOT / "docs/功能说明.md"
ft = fn.read_text(encoding="utf-8")
old = "- **睡眠定时**：倒计时 / 播完当前曲 / **播完当前专辑**三种模式；到点前 **10 秒淡出渐弱**，不会在听感正好的时候一刀切断"
new = "- **睡眠定时**：倒计时 / 播完当前曲 / 播完当前专辑 / **播完当前队列**四种模式；到点前 **10 秒淡出渐弱**，不会在听感正好的时候一刀切断。队列档 = 当前曲剩余 + 队尾前全部后续曲时长（只算一轮，见 [功能-睡眠定时播完队列](功能-睡眠定时播完队列.html)）"
if old in ft:
    fn.write_text(ft.replace(old, new, 1), encoding="utf-8")
    print("功能说明: OK")
elif "播完当前队列" in ft:
    print("功能说明: already")
else:
    print("功能说明: LINE NOT FOUND")

ai = ROOT / "docs/AI开发指南.md"
at = ai.read_text(encoding="utf-8")
old_ai = "睡眠定时（定时/播完当前曲）"
if old_ai in at:
    ai.write_text(at.replace(old_ai, "睡眠定时（倒计时/播完当前曲/专辑/队列）", 1), encoding="utf-8")
    print("AI开发指南: OK")
else:
    print("AI开发指南: skip")

print("DONE")