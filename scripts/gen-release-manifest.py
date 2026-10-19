#!/usr/bin/env python3
"""生成公共分发仓库的 latest.json，并刷新 README 的「软件列表」表格。

由 scripts/publish-release-repo.sh 调用；单独跑也可以（用于补数据/修表）。

用法：
  python3 scripts/gen-release-manifest.py \
      --root build/publish/Release --app-dir WindTone \
      --version 1.1.30 --code 40 --tag v1.1.30 \
      --published 2026-10-19T04:10:00Z --notes /tmp/notes.md
"""
import argparse
import hashlib
import json
import os
import re
import sys

ABI_ORDER = ["arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal"]
DISPLAY_NAMES = {"WindTone": "听风 WindTone"}
DISPLAY_DESC = {"WindTone": "Android 本地音乐播放器"}


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
        "displayName": DISPLAY_NAMES.get(args.app_dir, args.app_dir),
        "version": args.version,
        "versionCode": int(args.code),
        "tag": args.tag,
        "publishedAt": args.published,
        "minSdk": 24,
        "notes": notes,
        "assets": assets,
    }
    with open(os.path.join(app_root, "latest.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("latest.json -> %s (code %s, %d 个包)" % (args.version, args.code, len(assets)))

    readme = os.path.join(args.root, "README.md")
    if os.path.isfile(readme):
        with open(readme, encoding="utf-8") as f:
            text = f.read()
        name = DISPLAY_NAMES.get(args.app_dir, args.app_dir)
        desc = DISPLAY_DESC.get(args.app_dir, "")
        label = ("%s（%s）" % (name, desc)) if desc else name
        row = "| %s | [`%s/`](%s/) | [v%s](%s/v%s/) |" % (
            label, args.app_dir, args.app_dir, args.version, args.app_dir, args.version
        )
        pattern = re.compile(r"^\| .*?%s.*?\|$" % re.escape("[" + "%s/" % args.app_dir + "]"), re.M)
        if pattern.search(text):
            text = pattern.sub(row.replace("\\", "\\\\"), text, count=1)
        else:
            text = text.rstrip("\n") + "\n" + row + "\n"
        with open(readme, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("README 版本行已刷新")


if __name__ == "__main__":
    main()
