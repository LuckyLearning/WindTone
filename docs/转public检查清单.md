---
layout: default
title: 转 public 清单
nav_exclude: false
nav_order: 15
parent: 开发文档
---

# 转 public 检查清单

> **基准**：2026-10-31（Asia/Shanghai）  
> **目的**：源码仓 `LuckyLearning/WindTone` 从 private → public 前的可勾选预检。  
> **本文件不执行**：不改 GitHub 可见性、不打 tag、不做 `filter-repo` / force-push。

## 读前三句

1. **密钥绝不能进公开树**；历史里若曾进过，须先轮换再考虑历史清理（需你确认）。
2. 应用内更新仍走公共分发仓 `LuckyLearning/Release`（与源码是否 public **无关**）。
3. 协议已是 **Apache-2.0**（根目录 `LICENSE`）；转 public 前只需你确认版权署名是否要补 `NOTICE`。

---

## P0 · 已做（本轮已推 / 已核实）

| 项 | 状态 | 说明 |
|---|---|---|
| `.github-token` gitignore | ✅ | 已忽略；`git log --all --full-history -- .github-token` **无历史** |
| `local.properties` / `*.jks` / `keystore.properties` / `secrets.properties` | ✅ | gitignore；历史扫描 **无提交记录** |
| `baiduAppKey` / `SecretKey` / `aliyunClientId` / `aliyunClientSecret` | ✅ | gitignore；历史 **无提交**；工作树根目录当前也无这些文件 |
| 工作树 token 字面量 | ✅ | 已跟踪文件仅有 **示例/文档** 中的 `ghp_` / `github_pat_` 前缀说明，无真实 PAT |
| 私钥 / `BEGIN PRIVATE KEY` | ✅ | 已跟踪内容未发现 |
| 根 README 去「源码私有」口径 | ✅ | 改为「源码公开；APK 仍在 Release 仓」+ 贡献指引 |
| `docs/权限与更新.md` / `UpdateChecker` 注释 | ✅ | 更新链路说明改为与 public 兼容 |
| 本机绝对路径外泄 | ✅ | 文档/脚本中的 `stu_l` / `E:\workspace\...` 等改为 `WINDTONE_JDK` 或「存在才回退」 |
| `.github-token.example` | ✅ | 修正「docs 目录 gitignore」过时表述 |
| 本清单 | ✅ | `docs/转public检查清单.md` |
| 根目录 agent scratch | ✅ | `_write_*` / `_patch_*` / `*.log` 已在 gitignore；本机残留文件已删（不入库） |

---

## P0 · 待你动手（转 public 前建议做完）

| # | 项 | 怎么做 |
|---|---|---|
| U1 | **在 GitHub UI 把仓库改为 Public** | Settings → Danger Zone → Change visibility。**本 agent 不会代点。** |
| U2 | **轮换 GitHub PAT**（`.github-token` 里那枚） | 即使从未入库，private→public 后协作面变大；到 Developer settings 作废旧 PAT、发新 Fine-grained，写回本机 `.github-token`。权限见 `.github-token.example`。 |
| U3 | **确认 GitHub Actions Secrets 仍在** | 公开后 workflow 文件人人可见，**secret 值仍只有维护者可见**。核对：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`、`BAIDU_APP_KEY`、`BAIDU_SECRET_KEY`、`RELEASE_REPO_TOKEN`。缺任一则 Release 流水线会警告/失败（见 `android-release.yml`）。 |
| U4 | **确认 `RELEASE_REPO_TOKEN` 范围** | 该 token 需能写 `LuckyLearning/Release`。公开源码仓后，勿把此 token 写进文档或 issue。 |
| U5 | **签名 keystore 只留本机 / Secrets** | 确认没有任何 `*.jks` 曾通过别的渠道分享到聊天/网盘公开区；证书指纹可公开（文档里已有校验用 SHA-256），**口令与 jks 文件不可公开**。 |

---

## P1 · 历史风险（实证）

| 项 | 结论 | 建议 |
|---|---|---|
| `.github-token` / keystore / `local.properties` / 云盘凭据文件是否进过 git 历史 | **未发现**（`git log --all --full-history` 为空；`diff-filter=A` 仅命中 `.github-token.example` 与文档中的「凭据不入库」叙述） | **不必** `git filter-repo` / force-push |
| 若你**记得**曾误提交过密钥后又 amend/删文件 | 本扫描看不到「从未 push 的本地丢失对象」以外的保证 | 仍建议 **轮换** 相关密钥；若需清历史，另开任务并由你确认 force-push |

---

## P1 · 建议你拍板（可不阻塞转 public）

| # | 项 | 说明 |
|---|---|---|
| B1 | **版权署名 / NOTICE** | `LICENSE` 为标准 Apache-2.0 全文（附录里的 `[yyyy] [name]` 是许可证模板说明，**不应改 LICENSE 正文**）。若要对外署名，可另加根目录 `NOTICE`（例如 Copyright © 年 LuckyLearning / 你的名字）。 |
| B2 | **是否加 `SECURITY.md`** | 可选：说明如何私下报告漏洞（邮箱或 GitHub Security Advisories）。 |
| B3 | **是否加 `CONTRIBUTING.md`** | README 已链到 AI 开发指南；单独 CONTRIBUTING 可更短。 |
| B4 | **文档站「今晚 / 额度」作战语气** | 技术结论已保留（`待发版积压.md`、§0.3）。标题里的「今晚 backlog」等偏内部；可后续再润色，**不挡**转 public。 |
| B5 | **Actions 额度** | 与 public 无关；额度恢复前仍禁止打 tag 空烧。见 `docs/待发版积压.md`。 |

---

## P2 · 工程卫生（已知 / 已处理口径）

| 项 | 说明 |
|---|---|
| CI Secrets 名称（公开可见） | `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`、`BAIDU_APP_KEY`、`BAIDU_SECRET_KEY`、`RELEASE_REPO_TOKEN` |
| `BuildConfig.ALIYUN_CLIENT_*` | 由本机文件或 CI env 注入；未配置时为空字符串，**不是**把 secret 写进源码 |
| Debug 后门 / 硬编码测试账号 | 扫描未发现典型后门；云盘走用户粘贴 refresh_token / OAuth |
| `docs/archive/` | 已有归档惯例；历史规划可继续留 archive |
| agent 根目录 `_*.py` scratch | gitignore 已覆盖 `_write_*` `_patch_*` 等；公开后请勿 `git add -f` |

---

## 转 public 后建议立即做的 5 步

1. 打开仓库主页确认 **Public**。  
2. 浏览 `README` / `LICENSE` / Actions 徽章是否正常。  
3. 轮换 PAT（U2）并本机验证：`bash scripts/push.sh`（dry 或推一个 docs 小改）。  
4. 抽查 Actions → Secrets 列表仍在（值不可见是正常的）。  
5. 额度恢复后按 `待发版积压.md` 补发版（另任务）。

---

## 本轮故意不做

- 不把仓库改为 public（须你在 GitHub UI 操作）  
- 不 `filter-repo` / force-push  
- 不打 tag / 不改 `versionName`  
- 不擅自更换许可证（维持 Apache-2.0）

---

## Actions 触发（公开后同样适用）

见 [`spike-CI提速.md`](spike-CI提速.md) §9：Android CI **仅手动**；发版 `v*` **自动**（含单测门禁）；Pages 随 `docs/**` **自动**。


---

## 文档站 GitHub Pages（2026-10-31 续）

| 项 | 说明 |
|---|---|
| 主题 | `remote_theme: just-the-docs/just-the-docs@v0.10.1`（侧栏 + 搜索 + 浅/深色切换） |
| 旧布局 | 原内联 CSS 布局已挪到 `docs/archive/legacy-default-layout.html` |
| 触发 | 仅 `docs/**` 或 `pages.yml` 变更自动部署；也可手动 Run |
| **你必须开一次** | Settings → Pages → Build and deployment → **Source = GitHub Actions**。未开时必挂：PAT API 403；workflow 里 `enablement: true` 亦被拒（`Resource not accessible by integration`）。**只能网页点一次**；开好后重跑 Deploy docs 即可。 |
| 站点 URL | https://luckylearning.github.io/WindTone/ |

开好 Source 后，再推一笔 `docs/**` 或 Actions 里手动跑 **Deploy docs to GitHub Pages** 即可上线。
