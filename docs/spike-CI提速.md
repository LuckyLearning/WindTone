# Spike：CI 提速

> 日期：2026-10-31（Asia/Shanghai）  
> 范围：`.github/workflows/android-ci.yml` / `android-release.yml` / `pages.yml`  
> 状态：**结论文档 + 已落地小改（path filter / 合并 Gradle 调用 / cache-cleanup）**；**不打 tag、不发版**  
> 数据来源：GitHub Actions API（额度耗尽前最近若干次成功跑次）

## 1. 问题

主干每次 push 都跑 Android CI；发版再跑 Release。规划写「目标 10 分钟以内」，但：

- 成功 CI 常见 **约 8～14 分钟**（中位约 9 分钟）
- **纯 docs 提交也会跑满一轮 Android CI**（浪费分钟，尤其额度紧张时）
- 同一 job 内 **三次独立 `./gradlew`**，重复配置与部分编译

问：瓶颈在哪、哪些改动能安全落地、预估能省多少。

## 2. 结论（摘要）

| 项 | 判定 |
| --- | --- |
| 总判 | **值得做**；优先「少触发」+「少启 Gradle」 |
| 已落地小改 | paths-ignore；合并 test/lint/assemble 为一次 Gradle；cache-cleanup: on-success |
| 发版 / tag | **不做**（额度已满） |
| 硬障碍 | 当前账户 Actions 分钟/消费额度已满 → 优化合入后需等额度恢复才能实测收益 |

预估（额度恢复后验证）：

| 手段 | 预估收益 | 风险 |
| --- | --- | --- |
| docs / 根 README 等 paths-ignore | **单次 docs 提交省整轮 8～14 分钟**（触发次数下降） | 低：漏改业务代码不会被 ignore |
| 合并三次 Gradle → 一次 | **约 1～3 分钟 / 次**（少配置、复用编译） | 低：门禁顺序与产物不变 |
| cache-cleanup: on-success | 间接：缓存更干净、偶发命中更好 | 低 |
| SDK 目录 actions/cache（未做） | 约 0.5～1 分钟 | 中：键失效/路径漂移 |
| Release 去掉重复单测（未做） | 约 3 分钟 / 发版 | 中：tag 直推未过 CI 时少一道闸 |
| 配置缓存 configuration-cache（未开） | 不定；本仓曾关 | 中高：偶发配置问题 |

## 3. 现状盘点

### 3.1 工作流一览

| 工作流 | 触发 | concurrency | 主要步骤 |
| --- | --- | --- | --- |
| **Android CI** | push/PR → main/master；workflow_dispatch | ci 按 ref 分组，**cancel-in-progress: true** | JDK17 → Android SDK → setup-gradle → test → lint 门禁 → assembleDebug → artifacts |
| **Release APK** | v* tag；workflow_dispatch | release 按 ref，不取消 | 同上 SDK/Gradle → 版本校验 → 签名 → **再跑单测** → assembleRelease（abiSplits）→ Release + 公共仓 |
| **Pages** | docs/**、本 workflow | pages，不取消 | 已做 path filter |

已有加分项：

- `ubuntu-24.04` 固定镜像；`gradle/actions/setup-gradle@v4`（依赖缓存）
- 仓库 `gradle.properties`：`org.gradle.parallel=true`、`org.gradle.caching=true`
- artifact 上传 `continue-on-error`（配额满不拖红）
- CI concurrency 可取消旧跑（连推时省分钟）

### 3.2 成功跑次耗时（样本）

**Android CI**（feat(G8) v1.2.33，约 8.9 分钟）分步：

| 步骤 | 秒 |
| --- | --- |
| 配置 Android SDK + 安装组件 | ~15 |
| 配置 Gradle（含依赖缓存） | ~12 |
| **单元测试** | **~222** |
| **Lint（Error 门禁）** | **~196** |
| 编译 Debug 包 | ~47 |
| Post Gradle 缓存写回 | ~34 |
| 其余 | <5 |

**Release**（成功样本约 8～9 分钟）：单测 ~182s + assembleRelease ~237s 占绝对大头。

近期大量跑次 **3～4 秒 failure**：账户 **Actions 额度已满**（拿不到 runner），与 workflow 正文无关。

### 3.3 瓶颈判断

1. **Gradle 任务墙钟**（test ≈ lint ≈ 3～4 分钟级）— 主成本；二次/三次调用放大配置与编译重复。
2. **误触发**：docs-only / 文案类 push 仍跑 Android CI（样本中多次 docs 提交跑满 9～14 分钟）。
3. **Release 与 CI 重复单测**：发版再测一遍合理作保险，但是分钟翻倍来源之一。
4. **SDK 每次 sdkmanager**：相对 Gradle 较小（~15s），缓存收益有限。
5. **矩阵**：当前 **无** OS/JDK/ABI 矩阵膨胀 — 无需「精简矩阵」。

## 4. 建议与落地状态

### P0 — 已落地（本 Spike）

1. **Android CI paths-ignore**  
   忽略 `docs/**`、根 `*.md`、`pages.yml`、`release-notes/**`。业务/`app`/`gradle`/其它 workflow 变更仍跑。  
   Pages 工作流继续负责文档站。

2. **合并 Gradle 调用（CI）**  
   `./gradlew testDebugUnitTest lintDebug assembleDebug` 一次跑完，再解析 lint XML 做 Error 门禁（逻辑不变）。

3. **gradle/actions/setup-gradle@v4 → cache-cleanup: on-success**（CI + Release）  
   成功跑次清理缓存垃圾，利于后续命中与配额。

4. **Release：单测 + assembleRelease 合并为一次 Gradle**  
   仍保留发版前单测，只去掉第二次 JVM/配置开销。

### P1 — 文档建议，暂不改代码

| 建议 | 说明 |
| --- | --- |
| Android SDK actions/cache | 缓存 platforms/build-tools；键绑 API 35 / build-tools 35.0.0；收益中等 |
| PR 只读缓存 / 主干写缓存 | setup-gradle 已大体如此；可再显式 cache-read-only on pull_request |
| Release 可选跳过单测 | 仅当同 commit 已有绿色 CI 时；需额外脚本判断，易踩「只打 tag」路径 |
| 拆 job 并行 test∥lint | 需两份 checkout/SDK/Gradle，**可能更费分钟**；本仓单 module 不优先 |
| 打开 configuration-cache | gradle.properties 现为 false；需单独兼容 spike，勿搭车 |

### P2 — 流程 / 额度

- 额度恢复前：**禁止反复 workflow_dispatch / 打 tag**（与今晚铁律一致）。
- 恢复后：用一次「仅改 Kotlin」与一次「仅改 docs」对照，量 wall time，回写本页「实测」小节。

## 5. 风险与回滚

| 改动 | 风险 | 回滚 |
| --- | --- | --- |
| paths-ignore | 若把业务路径写进 ignore 会漏测 | 删 ignore 段即可 |
| 合并 Gradle | 失败日志稍长；门禁仍看 lint XML | 拆回三步 |
| cache-cleanup | 极低 | 去掉 with 参数 |

**未改**：单测范围、lint Error 门禁、Release 签名/公共仓、Pages 逻辑。

## 6. 怎么验证（额度恢复后）

```text
1. 只改 docs/ 某文件并 push → 应只见 Pages，不见 Android CI
2. 改 app/ 下任意 kt 并 push → Android CI 应跑；看「单元测试/Lint/编译」是否合成一步
3. 对比优化前后 job 总分钟与 Gradle 相关 step 之和
```

本地对齐（不耗 Actions）：

```bat
gradlew.bat testDebugUnitTest lintDebug assembleDebug --stacktrace
```

## 7. 改判 / 后续

- 若合并 Gradle 后 lint 报告路径变化 → 立刻拆回分步。
- 若 docs 目录误放了需编译校验的生成物 → 收紧 ignore，改为只 ignore docs 下 markdown 等。
- Soft EQ / 声道平衡 / 无障碍等并行 WIP **与本 Spike 无代码交叉**。