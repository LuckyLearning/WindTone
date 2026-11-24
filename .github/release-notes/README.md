# Release 说明（按版本实际改动编写）

**从 v1.3.1 起，发版说明按本版实际改动编写**，不再用一份固定模板套所有版本。

```
.github/release-notes/v{版本}.md   ← 每版一份，写本版真实更新内容
        │  发版时由 scripts/render-release-notes.sh 只替换占位符
        ▼
   Release 正文
```

`.github/release-notes/template.md` 仅作**兜底**：本地 `release.sh` 发现缺 `v{版本}.md` 时会**直接报错**（不会悄悄发出兜底文案），只有显式加 `--allow-template` 才会用它；CI 侧若缺失则回退兜底，保证不漏发。

渲染脚本 `scripts/render-release-notes.sh` 由 **CI 和本地 `release.sh` 共用同一份**。

## 写 v{版本}.md 的要求

1. 内容必须是本版**真实改动**：参考 `git log <上个 tag>..HEAD --oneline` 与 `git diff --stat`，不要编造功能。
2. 动笔前先确认版本范围：

   ```bash
   git log v1.2.61..HEAD --oneline
   ```

3. 结构参照历史文件（如 `v1.2.16.md`、`v1.3.1.md`）：首行简介 → `# 手机版 / 车机版 {version}` → 本版更新（分节，写清用户能感知的变化）→ 覆盖升级说明 → 适用设备 / 安装表格 / 升级 / 隐私 → 页脚。
4. 安装包名沿用占位符 `WindTone-v{version}-<abi>.apk`，不要手写版本号（写死易 404）。
5. **不要在正文里手写**文件大小 / SHA-256 —— GitHub 的 Assets 区会自动展示。
6. 正文用简体中文；涉及隐私/联网描述必须与本版真实行为一致（例如加入在线音乐后，「联网只有一处」就不再成立）。

## 本地预览

```bash
bash scripts/render-release-notes.sh                 # 按当前 build.gradle.kts 的版本渲染并打印
bash scripts/render-release-notes.sh 1.0.7 9 out.md  # 指定版本 / versionCode / 输出文件
```

跑 `bash scripts/release.sh <版本>` 时也会自动打印一份预览；缺本版说明会被直接拦下。

## 占位符（脚本自动替换，不用手改）

| 占位符 | 替换成 | 示例 |
|---|---|---|
| `{version}` | `appVersionName` | `1.0.7` |
| `{version_code}` | `appVersionCode` | `9` |
| `{date}` | 构建当天（UTC） | `2026-10-14` |

## 改兜底模板的注意点

1. 模板里的安装包名必须与 gradle 的命名契约一致
   （`WindTone-v{version}-arm64-v8a.apk` / `-armeabi-v7a` / `-x86_64` / `-universal`），
   否则用户照着下会 404；命名契约见 `docs/AI开发指南.md` §9.1.1。
2. **不要在正文里手写** 文件大小 / SHA-256 —— GitHub 的 Assets 区会自动展示。
3. 正文用简体中文；模板改动属于产品文案，改完最好在预览里过一遍。
