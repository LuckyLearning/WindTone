# 无缝播放诚实化（A7）

> 落地日：2026-10-31（Asia/Shanghai）
> 版本：随 **v1.2.24** 发版

## 结论

**诚实降级，不接假开关。** Media3 / ExoPlayer **没有**「关闭无缝」的公开 API；听风本来就用**单个 ExoPlayer + 连续 MediaItem 队列**，分轨带 encoder delay/padding 时平台侧即无缝。C1 加的偏好开关只改 Prefs、不改内核（关与开听感相同）——属于假开关，A7 移除。

未做「真接线关无缝」：关断只能人为在曲间插静音，产品拒绝。

## 干了什么

1. **设置 → 播放**：`SwitchRow` 换成只读 `ActionRow`（trailing「始终开启」）；点击弹窗说明能力与平台限制。
2. **拆除接线**：`onGaplessPlayback` / `PlayerActions.setGaplessPlayback` / `SettingsRepository.setGaplessPlayback` / `PlayerUiState.gaplessPlayback` 删除。
3. **Prefs**：`gapless_playback` 留作死键（与双语歌词等同惯例）；诊断包仍可读历史开/关，正文标明「平台默认开启」。
4. **文案**：`gapless_playback*` 进 `values` + `values-zh`。
5. **单测**：`SettingsCollapsibleTest` 增「只读说明无开关」。

## 怎么验

1. 设置 → 播放：见「无缝播放 / 始终开启」，**没有开关**。
2. 点该行：弹窗说明 Media3 无关闭 API、不做插静音。
3. 诊断包「无缝播放」行含「平台默认开启」。
4. `./gradlew :app:testDebugUnitTest --tests com.luckylearning.windtone.ui.SettingsCollapsibleTest`

## 不做什么

- 不在曲间插入静音来「关闭无缝」。
- 不清老用户 SharedPreferences 死键。
- 不改 ExoPlayer 创建方式（仍单实例 + 连续队列）。
