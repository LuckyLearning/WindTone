# 睡眠定时：再播 N 首后停（H6）

> 落地日：2026-10-31（Asia/Shanghai）
> 版本：随下一 patch 发版（实现提交在前，versionCode 由 `scripts/release.sh` 抬）

## 干了什么

睡眠定时在「倒计时 / 播完当前曲 / 播完当前专辑 / 播完当前队列 / 自定义分钟」之外，新增 **「再播 N 首后暂停」**：

- **约定：正在播的这一首算第 1 首**。因此「再播 1 首」≡「播完当前曲」；「再播 3 首」= 当前剩余 + 随后两首。
- 时长 = 当前曲剩余 + 队列里随后最多 N−1 首的 `durationMs`（预计算倒计时，与曲 / 专辑 / 队列档同架构）。
- 队列不够 N 首时累加到队尾为止；列表循环不会无限拖长。
- UI：预设 **1 / 3 / 5 / 10** + 自定义输入（合法 **1～100**）；非法只标红，不关 Sheet。
- 控制器不可用 / 索引无效 / 空队列：退化为「播完当前曲」。
- 到点仍走既有 10 秒淡出 + `SleepTimer.fireAction` 暂停。

## 怎么干的

1. **纯函数** `SleepTimer.songsRemainingMs(playlist, index, positionMs, durationMs, count)`：当前算 1，再往后累加 `count-1` 首；`count<=0` 按 1。
2. **夹取** `SleepTimer.clampSongCount`：`coerceIn(1, 100)`。
3. **引擎** `PlaybackEngine.sleepAfterSongs(count)`：读队列与进度 → 纯函数 → `SleepTimer.start`；失败路径 `sleepEndOfSong()`。
4. **接口** `PlayerActions.sleepAfterSongs`；`SleepSheet` 增加「再播 N 首」分区；`PlayerScreen` / `MainScreenParts` 接线。
5. **单测** `SleepTimerTest` 增补边界：N=1、N=3、超过队尾、越界、空队列、count≤0、时长 0、clamp。
6. **文档**：本页 + `功能说明.md` 睡眠定时一条补「再播 N 首」；发版时写入 `版本历史.md`。

## 怎么验

1. 组 ≥4 首队列，播第 1 首中途 → 睡眠定时 →「再播 3 首后暂停」→ 剩余约等于「本曲剩余 + 第 2、3 首时长」。
2. 同场景点「再播 1 首」：应约等于本曲剩余（与「播完当前曲」一致）。
3. 自定义填 `0` / 空 / `999`：标红或夹到 100（UI 挡非法；引擎路径经 clamp）。
4. 到点前约 10 秒淡出，随后暂停。
5. `./gradlew :app:testDebugUnitTest --tests com.luckylearning.windtone.playback.SleepTimerTest`

## 不做什么

- 不做「切歌计数事件」式停播（仍用预计算倒计时，与现有档一致；跳歌 / 插队可能导致实际首数与 N 不完全一致，与专辑/队列档同局限）。
- 不持久化「上次自定义 N」。
- 不改场景化助眠默认（仍是 45 分钟倒计时）。
