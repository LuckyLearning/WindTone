# Release 说明（固定模板）

**发版时不需要手写说明。** GitHub Release 的正文由 CI 用固定模板渲染：

```
.github/release-notes/template.md   ← 唯一需要维护的文件
        │  发版时 CI 只替换占位符
        ▼
   Release 正文（每次只有版本号等占位符不同）
```

## 占位符（CI 自动替换，不用手改）

| 占位符 | 替换成 | 示例 |
|---|---|---|
| `{version}` | `appVersionName` | `1.0.7` |
| `{version_code}` | `appVersionCode` | `9` |
| `{date}` | 构建当天（UTC） | `2026-10-14` |

## 特例覆盖（可选）

想给某个版本单独写说明（例如过渡版本、破坏性变更），在
`.github/release-notes/v{版本}.md` 放一个文件即可 —— CI 检测到它就用它、忽略模板。
同样支持上面的占位符。**平时不要创建这个文件**，固定模板就是默认路径。

## 改模板的注意点

1. 模板里的安装包名必须与 gradle 的命名契约一致
   （`WindTone-v{version}-arm64-v8a.apk` / `-armeabi-v7a` / `-x86_64` / `-universal`），
   否则用户照着下会 404；命名契约见 `docs/AI开发指南.md` §9.1.1。
2. **不要在正文里手写** 文件大小 / SHA-256 —— GitHub 的 Assets 区会自动展示。
3. 正文用简体中文；模板改动属于产品文案，改完最好在预览里过一遍。
