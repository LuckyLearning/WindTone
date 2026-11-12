#!/usr/bin/env python3
"""生成公共分发仓库的 latest.json，并刷新 README 的「软件列表」表格。

由 scripts/publish-release-repo.sh 调用；单独跑也可以（用于补数据/修表）。

用法：
  python3 scripts/gen-release-manifest.py \
      --root build/publish/Release --app-dir WindTone \
      --version 1.1.30 --code 40 --tag v1.1.30 --min-sdk 24 \
      --published 2026-10-19T04:10:00Z --notes /tmp/notes.md
"""
import argparse
import hashlib
import json
import os
import re
import sys

ABI_ORDER = ["arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal"]

# 每个软件的展示名与一句话说明：**唯一出处**，同时用于 latest.json 与 README。
# 新增软件时只在这里加一行，其它全由脚本推导。
APP_DISPLAY = {
    "WindTone": ("听风 WindTone", "Android 本地音乐播放器"),
    "AnyLyric": ("AnyLyric", "车机歌词悬浮窗（旁听媒体会话）"),
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def abi_of(name):
    for abi in ABI_ORDER[:-1]:
        if name.endswith("-" + abi + ".apk"):
            return abi
    return "universal"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--app-dir", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--code", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--published", required=True)
    ap.add_argument("--notes", required=True)
    # 不同软件的最低系统版本不同（WindTone 24 / AnyLyric 31），必须由调用方给出
    ap.add_argument("--min-sdk", type=int, default=24)
    args = ap.parse_args()

    app_root = os.path.join(args.root, args.app_dir)
    vdir = os.path.join(app_root, "v" + args.version)
    if not os.path.isdir(vdir):
        sys.exit("找不到版本目录：%s" % vdir)

    notes = ""
    if os.path.isfile(args.notes):
        with open(args.notes, encoding="utf-8") as f:
            notes = f.read().strip()

    assets = []
    for name in sorted(os.listdir(vdir)):
        if not name.endswith(".apk"):
            continue
        path = os.path.join(vdir, name)
        assets.append(
            {
                "abi": abi_of(name),
                "file": name,
                "size": os.path.getsize(path),
                "sha256": sha256(path),
            }
        )
    assets.sort(key=lambda a: ABI_ORDER.index(a["abi"]) if a["abi"] in ABI_ORDER else 9)
    if not assets:
        sys.exit("版本目录里没有 APK：%s" % vdir)

    manifest = {
        "schema": 1,
        "app": args.app_dir,
        "displayName": APP_DISPLAY.get(args.app_dir, (args.app_dir, ""))[0],
        "version": args.version,
        "versionCode": int(args.code),
        "tag": args.tag,
        "publishedAt": args.published,
        "minSdk": args.min_sdk,
        "notes": notes,
        "assets": assets,
    }
    with open(os.path.join(app_root, "latest.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("latest.json -> %s (code %s, %d 个包)" % (args.version, args.code, len(assets)))

    write_readme(args.root)


README_TEMPLATE = """# Release

本仓库用于分发各软件的安装包（源码仓库为私有）。目录按 **软件名 / 版本号** 组织，每个软件**只保留最近 {keep} 个版本**，更旧的版本在发布新版本时自动清理。

> 本文件由发布流水线自动生成（`scripts/gen-release-manifest.py`），**请勿手工修改** —— 下次发版会被覆盖。

## 目录结构

```
Release/
├─ README.md                       # 本文件（自动生成）
{tree}
```

命名约定（固定，便于机器解析）：

| 项 | 规则 | 示例 |
|---|---|---|
| 软件目录 | 英文名，驼峰 | `WindTone` |
| 版本目录 | `v` + 语义化版本 | `v{example_version}` |
| 安装包 | `<软件>-v<版本>-<abi>.apk` | `WindTone-v{example_version}-arm64-v8a.apk` |
| 元数据 | 每个软件根目录一份 `latest.json` | `WindTone/latest.json` |

## 软件列表

| 软件 | 目录 | 最新版本 | 在库版本 |
|---|---|---|---|
{rows}

## 下载

每个版本目录下按 CPU 架构分 4 个包（轻量应用如 `AnyLyric` 只有一个通用包，不分架构）：

| 安装包 | 适用设备 |
|---|---|
| `*-arm64-v8a.apk` | 绝大多数手机 / 车机（64 位 ARM） |
| `*-armeabi-v7a.apk` | 较老的 32 位 ARM 设备 |
| `*-x86_64.apk` | 模拟器 / x86 设备 |
| `*-universal.apk` | 全架构合一，体积略大；拿不准架构时用它 |

直链格式：

```
https://raw.githubusercontent.com/{owner}/{repo}/main/<软件>/v<版本>/<文件名>
```

## 校验

每个版本目录下的 `checksums.txt` 是各包的 SHA-256：

```bash
cd <软件>/v<版本> && sha256sum -c checksums.txt
```

## 保留策略

每个软件目录下**只保留最近 {keep} 个版本**（按版本号排序）。发布流水线在写入新版本后会自动删除更旧的目录，
并同步刷新本文件与 `latest.json`。
"""


def version_key(name):
    nums = re.findall(r"\d+", name)
    return tuple(int(x) for x in nums)


def write_readme(root, keep=5, repo_slug="LuckyLearning/Release"):
    """全量重写 README —— 内容只来自目录扫描，不夹带任何手写文案。"""
    apps = []
    for name in sorted(os.listdir(root)):
        app_dir = os.path.join(root, name)
        if not os.path.isdir(app_dir) or name.startswith("."):
            continue
        versions = [d for d in os.listdir(app_dir) if d.startswith("v") and os.path.isdir(os.path.join(app_dir, d))]
        if not versions:
            continue
        versions.sort(key=version_key, reverse=True)
        latest = versions[0]
        title, desc = APP_DISPLAY.get(name, (name, ""))
        apps.append((name, title, desc, versions))

    tree_lines = []
    rows = []
    for name, title, desc, versions in apps:
        tree_lines.append("├─ %s/" % name)
        tree_lines.append("│  ├─ latest.json")
        for v in versions:
            tree_lines.append("│  ├─ %s/" % v)
        tree_lines.append("│  └─ …（最多 %d 个版本目录）" % keep)
        label = ("%s（%s）" % (title, desc)) if desc else title
        rows.append(
            "| %s | [`%s/`](%s/) | [%s](%s/%s/) | %s |"
            % (label, name, name, versions[0], name, versions[0], "、".join(versions))
        )

    if not rows:
        rows.append("| — | — | — | — |")
    example_version = apps[0][3][0].lstrip("v") if apps else "1.0.0"
    owner, repo = repo_slug.split("/", 1)

    text = README_TEMPLATE.format(
        keep=keep,
        tree="\n".join(tree_lines) if tree_lines else "└─ （暂无软件）",
        rows="\n".join(rows),
        example_version=example_version,
        owner=owner,
        repo=repo,
    )
    with open(os.path.join(root, "README.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("README.md 已按目录扫描重写（%d 个软件）" % len(apps))


if __name__ == "__main__":
    main()
