# E2：UI 测试补关键路径

> 日期：2026-10-31（Asia/Shanghai）  
> 状态：**已合入 main，不打 tag / 不发版**  
> 范围：设置搜索 / 悬浮窗返回栈 / 车机尺码阈值 / 播放页横向切面板判据

## 干了什么

规划 E2「悬浮窗设置页、车机设置页、播放页手势」此前多靠真机肉眼。本批在**现有 JVM / 纯逻辑**风格上补关键路径，**不改** SoftEq / EqSheet 生产代码，不升 `versionName`。

| 用例 | 钉什么 |
| --- | --- |
| `SettingsSearchTest`（+3） | 中文关键词「车载 / 悬浮 / 片头」；trim + 大小写；六分区 + OVERLAY 目录覆盖 |
| `SettingsPageParentTest`（新） | `OVERLAY→LYRICS` 等父链；防悬浮窗返回误跳主页 |
| `CarMetricsTest`（+1） | 760dp 高 / 1400dp 宽分界 |
| `PlayerSwipeTest`（+3） | `resolvePane` 甩速、位移优先、非法宽度 |

场景冷启动 / 睡眠定时 / 队列清空打乱等仓内已有稳测，本批不再重复。

## 怎么验

```text
./gradlew :app:testDebugUnitTest --offline --tests com.luckylearning.windtone.ui.SettingsSearchTest --tests com.luckylearning.windtone.ui.SettingsPageParentTest --tests com.luckylearning.windtone.ui.CarMetricsTest --tests com.luckylearning.windtone.ui.PlayerSwipeTest
```

## 明确不做

- 不引入新的真机 instrumentation 模板
- 不改 SoftEq / EqSheet
- 不发版、不打 tag
