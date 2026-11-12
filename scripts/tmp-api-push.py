#!/usr/bin/env python3
"""临时工具：git 协议的 github.com 被代理断开时，用 REST 把本地 HEAD 推到远端分支。

只适用于「远端分支是本地 HEAD 的祖先」的快进场景。用完即删。
"""
import base64
import datetime
import json
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = "C:/esendev/code/temp/WindTone"
SLUG = "LuckyLearning/WindTone"
BRANCH = "main"
HEAD = subprocess.run(
    ["git", "-C", ROOT, "rev-parse", "HEAD"], capture_output=True, text=True, check=True
).stdout.strip()


def git(*args):
    return subprocess.run(
        ["git", "-C", ROOT, *args], capture_output=True, text=True, check=True
    ).stdout


TOKEN = ""
for line in open(f"{ROOT}/.github-token", encoding="utf-8"):
    s = line.strip()
    if s.startswith(("ghp_", "github_pat_")):
        TOKEN = s
        break
assert TOKEN, "没找到 token"


def api(method, path, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        f"https://api.github.com/repos/{SLUG}{path}", data=data, method=method
    )
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Accept", "application/vnd.github+json")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as r:
            body = r.read().decode()
    except urllib.error.HTTPError as e:
        print("HTTP", e.code, e.read().decode()[:400])
        raise
    return json.loads(body) if body.strip() else {}


remote_sha = api("GET", f"/git/ref/heads/{BRANCH}")["object"]["sha"]
print(f"远端 {BRANCH} = {remote_sha[:7]}")

if remote_sha == HEAD:
    print("已经一致，无需推送")
    sys.exit(0)

# 远端必须是本地 HEAD 的祖先（快进），否则拒绝
ancestors = git("rev-list", HEAD).split()
if remote_sha not in ancestors:
    print("!! 不是快进关系，停止")
    sys.exit(1)

changed = [p for p in git("diff", "--name-status", remote_sha, HEAD).splitlines() if p]
base_tree = api("GET", f"/git/commits/{remote_sha}")["tree"]["sha"]
entries = []
for line in changed:
    status, path = line.split("\t")[0], line.split("\t")[-1]
    if status == "D":
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
        print("  删除", path)
        continue
    raw = open(f"{ROOT}/{path}", "rb").read()
    blob = api("POST", "/git/blobs", {"content": base64.b64encode(raw).decode(), "encoding": "base64"})
    entries.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
    print("  改动", path, len(raw), "字节")

tree = api("POST", "/git/trees", {"base_tree": base_tree, "tree": entries})
print("新 tree", tree["sha"][:7], "| 本地", git("rev-parse", HEAD + "^{tree}").strip()[:7])

name, email, ts = git("log", "-1", "--format=%an%x00%ae%x00%at", HEAD).strip().split("\x00")
iso = datetime.datetime.fromtimestamp(int(ts), datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
commit = api(
    "POST",
    "/git/commits",
    {
        "message": git("log", "-1", "--format=%B", HEAD),
        "tree": tree["sha"],
        "parents": [remote_sha],
        "author": {"name": name, "email": email, "date": iso},
        "committer": {"name": name, "email": email, "date": iso},
    },
)
new_sha = commit["sha"]
print(f"新 commit {new_sha[:7]} | 本地 {HEAD[:7]} {'一致' if new_sha == HEAD else '不同'}")

api("PATCH", f"/git/refs/heads/{BRANCH}", {"sha": new_sha, "force": False})
print(f"✓ {BRANCH} -> {new_sha[:7]}")
