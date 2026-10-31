---
title: 功能 · i18n 单曲 Sheet（第 13 批）
---

# i18n 单曲 Sheet（第 13 批）

把 **SheetsSong**（长按操作 / 音频信息 / 编辑歌曲信息）面向用户的硬编码中文收进既有 i18n 体系：

- `res/values/strings.xml` = 英文兜底
- `res/values-zh/strings.xml` = 中文

与第 1–12 批同一套约定，不另造框架。详见 `docs/AI开发指南.md` § i18n 第 13 批。

## 覆盖范围

- 长按操作：分享 / 下一首播放 / 加入歌单 / 查看专辑·歌手 / 编辑… / 删除文件…（`ss_share` / `ss_play_next` / `ss_add_to_playlist` / `ss_view_*` / `ss_edit_info` / `ss_delete_file`）
- 收藏切换复用 `cd_favorite` / `cd_unfavorite`；音频信息标题与操作复用 `po_audio_info`
- 音频信息 Sheet：读取中 / 失败提示 / 各字段标签（`ss_info_*`）
- 编辑弹窗：标题 / 说明 / 标题·歌手·专辑字段（`ss_edit_title` / `ss_edit_hint` / `ss_field_*`）；保存 / 取消复用 `save` / `cancel`

## 刻意未改

- **MainScreen** / MainScreenParts（已第 14 批）/ CarLibrary / ListeningReport / LibraryStats / SheetsScanScope（留给更后批次）
- Aliyun 整包 / SoftEq / SoftEqSpike / 播放内核
- `songDisplayPath` 路径还原逻辑与 versionName / tag / release

## 验证

- `./gradlew :app:compileDebugKotlin --offline`
- 人工扫：`SheetsSong.kt` 用户可见文案无残留中文硬编码（KDoc/注释除外）