---
layout: default
title: 发版规范：Release 说明与多平台产物
---

# 发版规范：Release 说明与多平台产物（审查稿）

> 状态：**方案 B 已选定并落地**（gradle / CI / 应用内更新 / 设置页 / 效果图 / 单测，见第六节）。
> 仍待你审查的是**第二节的描述模板文本**——确认后我会补 `.github/release-notes/v1.0.6.md` 并把已发布的
> v1.0.6 Release 正文替换为模板格式（也可以只从下个版本 1.0.7 开始用）。
> 参考对象：https://github.com/FongMi/Release/releases/tag/v5.7.1

---

## 一、参考对象的拆解（FongMi v5.7.1）

**描述正文**（= Release body，去掉 GitHub 自动展示的 Assets 列表后其实很短）：

```
5.7.1 是供舊版本使用者升級用的過渡版本。安裝完成後，請在 App 內再次檢查更新，升級至 5.7.2 正式版。

# 電視版 5.7.1

- 提供舊版本過渡升級。
- 安裝完成後，請再次檢查更新並升級至 5.7.2。

# 手機版 5.7.1

- 提供舊版本過渡升級。
- 安裝完成後，請再次檢查更新並升級至 5.7.2。
```

**结构规律**：
1. 首段一句话说明「这个版本是什么、要用户做什么」（无标题，直接正文）；
2. 按**包的类型**分一级标题：`# 电视版 X.Y.Z` / `# 手机版 X.Y.Z`；
3. 每节下是极简 bullet（2 条左右），只讲用户可感知的变化；
4. **不在正文里手写** 文件大小 / sha256 / 时间 —— 那些由 GitHub 的 Assets 区自动显示；
5. 每个资产的命名即「平台-ABI.apk」：`mobile-arm64_v8a.apk` `mobile-armeabi_v7a.apk` `leanback-arm64_v8a.apk` …
   另有 `-base-` 变体（同平台同 ABI 的另一条产品线）。

**我们当前的做法（对比）**：

| 项目 | FongMi | 听风 WindTone 现状 |
|---|---|---|
| 描述正文 | 首段摘要 + 按包分节 + 要点 bullet | 固定的四段模板（版本号 / 安装包名 / SHA-256 / 安装方式）——没有「本次改了什么」 |
| 自动生成说明 | 不需要（正文手写） | CI 里同时开了 `generate_release_notes: true`，但 `body` 已给出，实际被覆盖 → 等于白开 |
| 产物数量 | 多平台 × 多 ABI × 多产品线（11 个） | 1 个：`WindTone-v1.0.6.apk`（2.9 MB，全 ABI 通用） |
| 命名 | `{平台}-{ABI}.apk` | `WindTone-v{版本}.apk` |

---

## 二、Release 描述：固定模板（已采用）

**决定：不再每个版本手写说明。** 正文由固定模板渲染，发版时只替换占位符，
人不需要动任何文案 —— 这就是 FongMi 那条「提供旧版本过渡升级…」正文的实现方式。

- 模板文件：`.github/release-notes/template.md`（唯一需要维护的文案）
- 占位符：`{version}` / `{version_code}` / `{date}`（CI 自动替换）
- 特例覆盖：`.github/release-notes/v{版本}.md` 存在则用它、忽略模板（平时不建）

渲染出来长这样（以 1.0.6 为例）：

```markdown
**听风 WindTone 1.0.6** —— 本地音乐播放器：无广告、无账号，曲库、歌单、收藏、播放统计与歌词全部只保存在本机。

# 手机版 / 车机版 1.0.6

- 本版包含若干功能调整与问题修复。
- 覆盖升级后歌单、收藏、播放统计、歌词设置全部保留，曲库缓存自动复用，无需重新扫描。

**适用设备**：Android 7.0（API 24）及以上；手机与车机通用（车机横屏界面自动切换）。

**安装**：按设备架构下载对应的包，直接覆盖安装即可：

| 安装包 | 适用设备 |
| --- | --- |
| `WindTone-v1.0.6-arm64-v8a.apk` | 绝大多数手机 / 车机 |
| `WindTone-v1.0.6-armeabi-v7a.apk` | 较老的 32 位设备 |
| `WindTone-v1.0.6-x86_64.apk` | 模拟器 / ChromeOS |
| `WindTone-v1.0.6-universal.apk` | 不确定架构时选它 |

**升级**：也可以在应用内「设置 → 更新 → 检查更新」一键升级（检查更新只访问 GitHub 官方地址）。

**隐私**：音频与歌词只在本机读写；联网只有两处——检查/下载更新（GitHub Releases）和你自己配置的 WebDAV 服务器。不采集、不上传任何信息。

<sub>1.0.6（versionCode 8）· 构建于 2026-10-14</sub>
```

**生效范围**：模板里的安装包名是 ABI 分包命名，1.0.6 是单包（`WindTone-v1.0.6.apk`），
所以**固定模板从 1.0.7 起生效**；1.0.6 页面保持原样（其资产与模板里的包名不符）。

---

## 三、多平台 / 多变体产物

### 3.0 实测数据（决定了方案取舍）

| 事实 | 实测值 | 含义 |
|---|---|---|
| 包内 native 库 | 只有 `libandroidx.graphics.path.so`，4 个 ABI 合计 **37 KB** | ABI 拆分**几乎不减小体积** |
| universal 包 | 3.07 MB | — |
| arm64-v8a 包 | 3.02 MB（−50 KB） | 省下的就是其它三个 ABI 的无用 so |
| armeabi-v7a 包 | 3.02 MB | 老设备/部分车机可用 |
| x86_64 包 | 3.02 MB | 模拟器 / ChromeOS / 部分平板 |

**结论**：分包的价值不在体积，而在「Releases 页面按设备可选 + 车机/老设备有明确包 + 模拟器有包 + universal 兜底」。

### 3.1 已采用的方案 B：ABI 分包 + 通用包

- 产物（4 个）：
  - `WindTone-v1.0.6-arm64-v8a.apk`
  - `WindTone-v1.0.6-armeabi-v7a.apk`
  - `WindTone-v1.0.6-x86_64.apk`
  - `WindTone-v1.0.6-universal.apk`
- 构建：`-PabiSplits=true`（**只有 CI 传这个参数**，本地 dev 默认关闭 → `assembleDebug` 仍只出一个包）；
  `splits.abi { include("arm64-v8a","armeabi-v7a","x86_64"); isUniversalApk = true }`；
  命名统一由 `androidComponents.onVariants` 处理（ABI 过滤 → `-<abi>`；无过滤 → `-universal`）。
- 应用内更新（必须同步改造，否则会 404）：
  1. `apkNameCandidates(version, abis)` 按顺序给出候选名：
     **本机 ABI → 其它 ABI → universal → 历史单包命名**（最后一条保证 1.0.6 及以前的老版本能升级上来）；
  2. Atom 通道按候选名逐个推导直链，逐个尝试；API 通道按 `assets[].name` 匹配本机 ABI，找不到再退 universal；
  3. 下载时把「包名维度 × 线路维度」展开成候选列表，逐条尝试；
  4. 设置页「关于」新增 **CPU 架构** 行，用户可对照 Releases 里的包名。
- 代价/收益：改动集中在 `UpdateChecker` + CI，无产品行为变化。

### 3.2 未采用的方案（留档）

- **方案 A（只改描述）**：你不予选；若只想最小改动可回退至此。
- **方案 C（手机版 / 车机版两条产品线）**：最接近 FongMi 的 `mobile` / `leanback`。
  代价是新增 productFlavor + 代码按 `BuildConfig.FLAVOR` 分支 + 两套默认行为维护，与现有
  「一个 App 自适应车机」的架构冲突。若后续确实要独立的车机安装包再评估。

### 3.3 命名对照表

| 维度 | FongMi | 听风 WindTone |
|---|---|---|
| 平台 | `mobile` / `leanback` | 暂不分（手机与车机同一个包） |
| ABI | `arm64_v8a` / `armeabi_v7a` | `arm64-v8a` / `armeabi-v7a` / `x86_64` |
| 通用 | 无 | `universal` |
| 示例 | `mobile-arm64_v8a.apk` | `WindTone-v1.0.6-arm64-v8a.apk` |

---

## 四、落地情况

### 4.1 已落地（本轮，尚未发版；随下个版本 1.0.7 生效）

| 位置 | 改动 |
|---|---|
| `app/build.gradle.kts` | `-PabiSplits=true` 开关 + `splits.abi`（arm64-v8a / armeabi-v7a / x86_64 + universal）；产物命名支持 `-<abi>` / `-universal`；新增 `APK_ASSET_TEMPLATE_ABI` / `APK_ASSET_TEMPLATE_UNIVERSAL` |
| `.github/workflows/android-release.yml` | 构建传 `-PabiSplits=true`；收集全部 .apk 到 `dist/` 并输出校验表到 Step Summary；新增「组装 Release 说明」步骤：读固定模板 `template.md` 替换 `{version}` / `{version_code}` / `{date}`（`v{版本}.md` 存在则覆盖模板）；发布改用 `body_path`（去掉与 body 冲突的 `generate_release_notes`） |
| `.github/release-notes/template.md` | 新增：**固定说明模板**（唯一需要维护的文案），八个版本只换版本号 |
| `.github/release-notes/README.md` | 新增：占位符说明、特例覆盖规则、改模板的注意点 |
| `data/UpdateChecker.kt` | `apkNameCandidates()` / `deviceAbis()` / `primaryAbiLabel()`；Atom 与 API 两条通道都按本机 ABI 选包；下载候选 = 包名 × 线路展开；`UpdateInfo.apkUrlFallbacks` |
| `ui/SettingsScreen.kt` | 「关于」新增 **CPU 架构** 行 |
| `docs/ui/index.html` | ㉚ 关于段加 CPU 架构行 + 说明（共 44 屏，自查通过） |
| `UpdateCheckerTest.kt` | 新增 ABI 候选顺序 / 未知架构退化 / 多包 × 多线路展开 / 兼容历史命名 等用例（56 项全绿） |

本地实测（`assembleRelease -PabiSplits=true`）：4 个产物命名与 ABI 目录均正确
（arm64 包只含 `lib/arm64-v8a`，universal 含 4 个 ABI）。

### 4.2 下一步

1. **1.0.6 页面不动**（它是单包，与模板里的 ABI 包名不符）；
2. 推送本轮改动，用 **1.0.7** 验证整条链路：4 个包 + 固定模板正文 + 应用内更新的 ABI 选包；
3. 之后每次发版只需 `bash scripts/release.sh 1.0.x`，**不需要写任何文案**。

---

## 五、待你拍板

1. 固定模板的文案（第二节）是否照此？特别是「本版包含若干功能调整与问题修复」这句要不要换成别的说法；
2. 下一版号用 **1.0.7**（1.0.6 已发，分包改造需要新版本号验证）——确认后我就推送并发布。
