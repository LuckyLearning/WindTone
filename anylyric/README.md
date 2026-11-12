# AnyLyric

WindTone 的轻量分支：旁听车机上任意播放器**已经发布**的媒体会话，把播放器自己暴露的歌词画到悬浮层。

**它不是播放器** —— 不播放声音、不申请音频焦点、不控制播放、不读文件、不联网。

- 包名：`com.anylyric`（与 `:app` 不同，可同机共存）
- 平台：Android 12+（`minSdk = 31`）
- 运行时依赖：仅 `kotlin-stdlib`

---

## 一期范围（当前实现）

只做「直接获取」，用来验证车机播放器到底有没有把歌词放进可被旁观的系统通道。

| 通道 | 来源 | 判定 |
|---|---|---|
| L0 | `MediaMetadata` 自定义键 | 键名白名单（lyric / lrc / 歌词）+ `[mm:ss.xx]` 内容特征双判，解析出 ≥2 行才采纳 |
| L1 | `TITLE` 逐行推送 | 曲目指纹（包名 / duration / mediaId / artist / 专辑 / 音轨）不变而 `TITLE` 在 8 秒内变化 ≥2 次 → 判定「伪歌名」，切逐行模式 |
| L2 | 媒体通知文本 | 含 LRC 特征则解析；或多行正文且已判定伪歌名时作为逐行流 |

**不实现**：L3 本地 `.lrc`、L4 在线／无障碍。二者只在 `LyricsSource` 接口上预留挂载点，**不写实现、不申请存储与网络权限**。

因此多数普通播放器会显示「暂无歌词」——这是**一期预期结果，不是缺陷**。一期验收标准是通道探测报告，不是歌词覆盖率。

### 首屏延迟的两条硬约束（v0.1.1）

1. **缓冲必须早于判定**：每次 TITLE 变化就进 L1 缓冲，不等判定成立。否则判定前看到的
   第一二句会被永久丢弃，首屏要多等一个歌词周期。判定成立时缓冲整体回填。
2. **判定只要两条证据**：`minChanges = 2`（8 秒内两个不同标题）。要求三条会让首屏
   白等 6~15 秒。两条就够的前提是曲目指纹未变，且「歌名 → 歌名 - 播放中」这类
   后缀变体已单独排除（`TitleTimeline.isTrivialVariant`）。

空窗期的文案也分两种：标题还在动 → 「正在获取歌词…」；标题停住 20 秒仍无行 → 「暂无歌词」。


---

## 构建

```bash
# 必须用标准 OpenJDK 21。GraalVM 的 jlink 会让 JdkImageTransform 失败。
export JAVA_HOME="C:/Users/stu_l/.jdks/openjdk-21.0.2"

./gradlew :anylyric:assembleDebug
./gradlew :anylyric:assembleRelease
./gradlew :anylyric:testDebugUnitTest
./gradlew :anylyric:lintDebug
```

产物：`anylyric/build/outputs/apk/release/AnyLyric-v<版本>.apk`（release 约 100 KB）。

---

## 架构

```
MediaWatchService  (NotificationListenerService，由系统绑定，不自拉活)
   └─ SessionHub          会话发现 / 选主 / 回调注册 / 通知文本采集
        ├─ SessionClock        进度外推 + 行定位（二分）
        ├─ TitleTimeline       伪歌名判定（变化频率 + 曲目指纹）
        ├─ LyricsSourceChain   L0 → L1 → L2，保留全部尝试记录
        └─ OverlayHost         TYPE_APPLICATION_OVERLAY，默认 FLAG_NOT_TOUCHABLE
             └─ LyricRenderer  单 View + Canvas 两行，无封面位图
```

单进程、无常驻前台服务、无数据库、无网络。坐标与字号只存 Int / Float 快照。

**编辑态**才临时打开触摸（拖动定位），退出后立刻回到完全穿透。

---

## 排障：不依赖 adb

设置页 → 通道探测 → 打开调试日志 → 在别的应用里播放 → 回到探测页看结论卡。

导出有三条**设备内**路径：

1. 复制到剪贴板
2. 保存到公共下载目录（车机文件管理器可浏览）
3. 系统分享面板

**不需要连接电脑、不需要开发者选项、不需要 USB 调试。**

自动判因按「权限 → 会话 → 通道 → 时钟 → 窗口」顺序给出第一条结论。其中四条会明确标注为**播放器侧事实**，不当成本应用故障：

- 会话数为 0：播放器未发布 MediaSession
- 三通道全未命中：播放器未暴露任何歌词通道
- 标题仅在暂停时更新：发布端 AVRCP 门控丢更新
- 进度多次小幅回跳：发布端进度不自洽（**仅当歌词带时间轴时才报**；L1 逐行流按最后一行定位，与 progress 无关，不应误伤）

换歌、重播、拖动进度都不计入「进度不稳」：换歌时清零并忽略落到 0 附近的位置，回退超过 5 秒视为用户拖动。

---

## 验证状态

| 项 | 结果 |
|---|---|
| `:anylyric:testDebugUnitTest` | 46 项通过（LRC 解析 10、伪歌名 10、责任链 8、时钟 6、判因 12） |
| `:anylyric:lintDebug` | Error 0 |
| `assembleRelease` | 约 102 KB，运行时依赖仅 `kotlin-stdlib` |
| 真机 | 手机（HONOR / API 37）已验证 L1 可命中；车机 **未验证** |

未验证项：车机播放器的通道命中率、车厂 ROM 对悬浮窗与通知使用权的策略、多屏行为。

## 自测 L1 的发布端

用 WindTone 当发布端时必须先打开 **设置 → 实验室 → 车载蓝牙歌词**（`bt_car_lyric`）。
不开这一项，WindTone 的 `MediaMetadata.TITLE` 只有歌名，探测会如实报 `stable_title` ——
这不是 AnyLyric 的问题。开了之后 TITLE 会被当前歌词行接管，AnyLyric 同机直连
MediaSession 即可读到逐行流（不经 AVRCP 蓝牙栈，绕开其门控）。

