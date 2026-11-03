# 设计 · UI v2：手机 / 车机一体化重构

> 立项：2026-11-03。状态：**阶段一（设计系统地基）已落地**，阶段二起按本文矩阵逐屏推进。
> 本文件是「全面审查 + 重新设计」的书面结论；视觉真源见 [`ui-v2/index.html`](ui-v2/index.html)。
> 旧 `docs/ui/`（10 章 / DOM 实算 93 屏）保留为历史归档，**不再是重构后的真源**。

---

## 一、审查范围与量化结论

四路只读审查覆盖：`app/src/main/java/.../ui/` 全部 Kotlin、`app/src/main/res` 全部资源、`docs/ui/` 视觉真源、`docs/设计-*` 全部设计文档。

| 维度 | 现状数字 |
|---|---|
| UI 层 Kotlin | **81 个文件**（根级 62 平铺 + `library/` 17 逻辑 + `theme/` 2） |
| Activity / 导航 | 1 个 `MainActivity`；**无导航框架**，20+ 个 `mutableStateOf` 布尔 + 6 个 `AnimatedVisibility` 层叠；`BackHandler` 靠注册顺序定优先级 |
| 巨型文件 | 6 个 >1100 行：`SettingsOverlayPage`(1482) / `PlayerScreen`(1415) / `AliyunPanPage`(1300) / `MainScreen`(1195) / `SettingsSystemPages`(1121) / `EqSheet`(1117) |
| 弹层 | 13 个 `ModalBottomSheet`，**只有 5 个**走 `sheetMaxHeight()`；约 45 个 `AlertDialog`，限高 240/320/340/360/380dp 手工散落 |
| 设置页 | 13 个子页、`SettingsScreen` 约 **100 个回调参数**逐条绑 VM |
| 令牌来源 | **6 套**：`Theme.kt` / `DesignTokens.kt` / `ui.css` / `CarMetrics.kt` / `LyricsColors` / `CarLibraryScreen` 局部 `CAR_*` |
| 硬编码 | `.dp` 分布于 44 个 UI 文件、22 种间距值混用；30 个文件 60+ 处手写 `RoundedCornerShape`（2/3/4/7/8/12/16/24dp，事实标准 12dp 不在五档内）；60+ 行硬编码 `.sp`；5 类魔法动效时长（220/320/1400/2600/24000ms） |
| 缺失的基础层 | **无 Typography / 无 Spacing-Dimens / 无 IconSize / 无统一 Elevation**；`MusicPlayerTheme` 未传 typography |
| 形态判定 | **3 套并行**：`carMode`（手拨）/ `screenWidthDp>=840`（真实宽度）/ `isCarHeadUnit`（设备识别），互不联动 |
| 双形态组件 | 3 对**各自独立自绘**：`CarNavRail`↔`LibraryDrawer`、`CarLibraryHeader`↔`LibraryHeader`、`CarPlayerBar`↔`MiniPlayer`；`PlayerScreen` 内 20+ 处 `if (carMode)` |
| 视觉真源 | 不是「57 屏 / 7 章」——现行 **10 章 / DOM 实算 93 屏**（官方手写 94）；**双端并排对照屏 = 0**；`ui.css` 927 行含约 140 行死代码；1521 处内联 `style=`；08 章缺 `ui.js`；圈码撞号 15 组 |

**一句话**：功能与状态其实已经是「一套」（同一 `PlayerUiState` / 同一 VM / 同一 `PlayerScreen`），割裂发生在**外壳组件、尺寸体系、形态判据、功能裁剪**四层。

---

## 二、两端体验割裂清单（重构要消除的）

### 2.1 同一功能，两端长得完全不一样

| # | 功能 | 手机 | 车机 |
|---|---|---|---|
| A1 | 一级导航 | 280dp 抽屉 + 汉堡 + 右滑手势 | 左常驻 Rail（语义色 + 焦点环） |
| A2 | 曲库头部 | 15 个回调：排序/筛选/⋮ 8 工具/语音搜索 | 3 个参数：标题 + 曲数 + 就地搜索 |
| A3 | 现在播放条 | 46dp 浮动玻璃卡、无收藏、无时间 | 84/112dp 实底、80dp 封面、收藏、双时间、88dp 主键 |
| A4 | 播放页中部 | 歌词 ↔ 封面二选一 | 侧动作列 + 左歌词 + 右黑胶三块同场 |
| A5 | 歌名位置 | 进度上方常显、红心贴右 | 完整态顶栏居中、沉浸态才落歌名行 |
| A6 | 队列/设置/收藏入口 | 散在顶栏 ⋮、控制行右槽、歌名行 | 收进 `CarSideActions` 竖排 + 底条 |
| A7 | 手势语义 | 左右 = 切视图 | 左右 = 切歌（**语义相反**） |
| A8 | 歌曲行 | 48dp 封面、无焦点环 | 64dp 封面、3dp 焦点环、⋮ 56dp |
| A9 | 歌词字号 | 基准 | ×1.5 |
| A10 | 设置页 | 720dp 限宽 / 18dp padding | 1100dp / 32dp / 底 84dp + 左下角按钮 |
| A11 | 悬浮歌词预设 | 4 套 | 7 套（+3 车机专属） |

### 2.2 功能互缺（一端有、一端整块删掉）

- **车机被裁掉的 6 项**：长按多选→批量操作、字母索引、±10/15s 快进快退、底部状态行、右滑抽屉、库头部 ⋮ 全部工具（含语音听写）。
- **手机被裁掉的 5 类**：≥56dp 触控目标、可见焦点环、场景芯片、悬浮窗侧钮、车机音效档案 / 蓝牙自动进车机 / 开机自启。

> 一体化的目标不是「把两端画成一样」——竖屏触摸与横屏远距驾驶的物理形态决定了布局必须不同。目标是**同源组件 + 同一套令牌 + 功能对等 + 状态统一**，差异只体现在密度与呈现态参数上。

### 2.3 结构性割裂

- 三套形态判据并行，导致「设备是车机 ≠ 用车载 UI」「车机模式开着 ≠ 所有页面适配」「手机横屏也白让 56dp」。
- 主界面三块「壳」是两套自绘代码，改一个入口要同时维护两份（云盘、扫描范围都曾因「车机没抽屉」补过一遍）。
- 视觉真源里车机只有 1 章、手机 8 章，**零并排对照**，双端关联全靠自然语言注释，机器不可读、不可校验。

---

## 三、「可丢的包袱」与「不可丢的物理约束」

用户要求「不考虑之前的一切兼容和历史包袱」。本重构据此把历史切成两类：

### 3.1 明确丢弃（不再兼容）

1. 旧的 10 章平铺视觉真源结构、圈码编号、手写屏数、`cv3-*` / `cset-*` 整套车机专用 CSS、约 140 行死代码、1521 处内联 `style=`。
2. 「手机一套壳 + 车机一套壳」的两套自绘实现（`CarNavRail` / `CarLibraryHeader` / `CarPlayerBar` / `MiniPlayer` / `LibraryDrawer` 的独立形态）。
3. `carMode` 作为「换一套组件」的开关——降级为「用户偏好的密度/行为策略」。
4. 三套并行尺寸判定与散落硬编码（22 种间距、9 种圆角、13 种图标尺寸、5 类魔法时长、车机两套字号表）。
5. 13 个子页 + 100 个回调的状态驱动导航，改为显式路由。
6. 文档与代码不符的历史描述（`CarSettingsScreen.kt`、「单栏大控件」、`CAR_CONTENT_MAX_WIDTH`、「7 章 / 57 屏」）。

### 3.2 必须保留（不是包袱，是物理/系统约束，推翻会真机崩溃或乱码）

| 约束 | 后果（若推翻） |
|---|---|
| 矢量图标路径一律**绝对坐标**（`z` 后接相对 `m` 在比亚迪车机渲染链画歪） | 真机图标乱码 / 残缺 |
| `PlayerScreen` **不得**加 `navigationBarsPadding`（宿主已让位，双重让位 = 沉浸态悬空） | 车机播放页底部悬空 |
| minSdk 24：禁用 `java.time` / `java.util.stream` | `NoClassDefFoundError` |
| 资源化 i18n：`values/strings.xml` 为英文兜底，每条必须有 | 英文设备 `NotFoundException` |
| `Song.stableKey` = `uri.toString()`，算法永不变 | 曲库/收藏/歌单全量错位 |
| 出厂默认值只在 `SettingsDefaults` 定义，每项可「恢复默认」 | 设置项失控 |
| 车机 Dock 让位 56dp（DiLink 自绘浮层、insets 报 0） | 底部内容被系统 Dock 盖住 |
| 车机 ≥56dp 触控目标 + 可见焦点环（方向盘/旋钮无触摸点） | 行驶中无法操作 |
| 弹层最高屏高 70%、超了内部滚动 | 平板横屏尾项被控制键盖住 |

---

## 四、新设计系统（四层地基，阶段一已落地）

| 层 | 文件 | 职责 |
|---|---|---|
| 形态 | `ui/theme/FormFactor.kt` | `UiFormFactor`（PHONE/CAR/TABLET）；`FormFactor.resolve()` 纯函数统一三套判据：用户意图 > 设备识别 > 尺寸 |
| 密度 | `ui/theme/Density.kt` | `UiDensity` 一张表覆盖全端：手机/平板/车机紧凑/车机常规四档，字段完全相同；紧凑档沿用 `CarMetrics` 已验证真机值 |
| 令牌 | `ui/theme/Tokens.kt` | 补齐 `Spacing` / `Radius` / `IconSize` / `Elevation` / `Duration` 五类；与既有 `AppVisual`（渐变/投影/玻璃/语义色）分工不重叠 |
| 排版 | `ui/theme/Type.kt` | `AppTypography` 显式字阶，唯一出处；第一阶段取值与 M3 默认一致（零视觉回归） |
| 接线 | `ui/theme/Theme.kt` | `AppShapes` 改从 `Radius` 读取；`MaterialTheme` 传入 `AppTypography` |

**已验证**：`compileDebugKotlin` 通过、`DesignSystemTest` 20 项全绿、既有 1410 项单测未受影响。

---

## 五、一体化组件矩阵（阶段二起逐屏落地）

| 同源组件 | 手机呈现态 | 车机呈现态 | 差异收进 |
|---|---|---|---|
| `AdaptiveNav` | 抽屉（遮罩 + 汉堡 + 右滑） | 常驻侧栏（语义色 + 焦点环） | `UiFormFactor.hasSideNav` / `UiDensity.navWidth` |
| `AdaptiveHeader` | 汉堡 + 标题 + 搜索 + 排序 + ⋮ 工具 | 标题 + 曲数 + 就地搜索 + **同一批工具**（不再裁掉） | 可见性参数，不是两套组件 |
| `NowPlayingBar` | 浮动玻璃卡、46dp 封面、手势切歌 | 实底 84/112dp、收藏键、双时间、大主键 | `UiDensity.nowPlayingBar / coverSmall / mainButton` |
| `SongRow` | 48dp 封面、无焦点环 | 64dp 封面、焦点环、⋮ 56dp | `UiDensity.coverSmall / touchMin / focusRing` |
| `PlayerLayout` | 歌词 ↔ 封面二选一 | 侧动作 + 歌词 + 黑胶三栏 | 同一个 `PlayerScreen`，布局分支由形态查表 |
| `SettingsScaffold` | 720dp / 18dp / 无底部按钮 | 1100dp / 32dp / 底部让位 + 播放页按钮 | `UiDensity.contentMaxWidth / horizontalPadding / dockReserved` |
| `AppSheet` | 统一 70% 限高 + 内部滚动 + 玻璃底 | 同左 | 替换 13 个各自为政的底栏与 45 个弹窗 |

**手势语义统一表**（消除「左右滑在两端含义相反」）：左右滑 = 切歌（全端一致）；上下滑 = 切歌词/封面视图（全端一致）；长按 = 多选（全端开放，车机改为长按大目标而非删除）。

**功能对等表**：把 §2.2 的车机 6 项缺失改为「按形态启用」而不是整块删除；手机的焦点环/场景芯片等按形态启用。所有功能在同一套 `UiState` 上，两端只差呈现。

---

## 六、视觉真源 v2

[`ui-v2/index.html`](ui-v2/index.html) 是重构后的唯一视觉真源，自包含、可明暗切换、可 `file://` 直开。与旧真源的关键差别：

1. **手机 + 车机并排对照**成为每一屏的默认结构（`data-formfactor="phone|car|both"` + `data-pair`），不再是 0 个对照屏；
2. 先「设计系统 → 组件双端变体 → 布局骨架 → 屏稿」，而不是按功能模块平铺 93 屏；
3. 屏数由脚本现算，不手写；不写圈码；样式集中在头部单一 `<style>` 块，屏稿与组件一律走 class（仅令牌色板 / 尺寸样本允许内联覆盖，约 41 处）；
4. 新增**共用 / 差异矩阵**与**功能对等表**两层显式规范。

---

## 七、分阶段路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| 一 | 设计系统四层地基 + 主题接线 + 单测 | ✅ 已完成（本轮） |
| 二 | 视觉真源 v2 + 本规范文档 | ✅ 已完成（本轮） |
| 三 | 统一判据接线：`MainScreen` 改用 `FormFactor.resolve()`，删掉 `>=840` 与三套分支；`carMode` 降级为偏好 | 待开工 |
| 四 | 合并三对壳组件为 `AdaptiveNav` / `AdaptiveHeader` / `NowPlayingBar`，逐处替换 | 待开工 |
| 五 | `PlayerScreen` 20+ 处 `if (carMode)` 改查表；统一手势语义表 | 待开工 |
| 六 | 弹层统一 `AppSheet`（13 底栏 + 45 弹窗）+ 设置页显式路由（替换 100 回调） | 待开工 |
| 七 | 全量硬编码收敛到令牌（44 文件 `.dp` / 60+ `.sp` / 圆角 / 图标 / 时长） | 待开工 |
| 八 | 功能对等补齐（车机 6 项 / 手机 5 类）+ 真机 A/B 验收 | 待开工 |

每阶段固定门禁：`compileDebugKotlin` → `testDebugUnitTest`（0 失败）→ `lintDebug`（Error = 0）→ 提交但不推送、不动版本号。

## 八、治理

- 旧 `docs/ui/` 保留归档，不做静默删除；`docs/DELETIONS.md` / `docs/DOC_INVENTORY.md` 待阶段三起同步口径。
- `ai开发指南.md:291,1303` 与 `版本历史.md:171` 的「7 章 / 57 屏」旧口径，在本重构收口时一并修正。
- 版本号遵循既有红线：**只在用户主动要求发版时才改**，本轮不动 `appVersionName` / `appVersionCode`。
