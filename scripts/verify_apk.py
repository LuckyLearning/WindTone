#!/usr/bin/env python3
# ===================================================================
#  发行包验签：下载 Release 的 universal APK → 校签名证书 → 校包内版本串
#
#  为什么要有这一层（第 ④ 步验证链，别省）：
#   · CI 缺 KEYSTORE_BASE64 时只打 ::warning:: 且 step 仍 success
#     ——「构建成功但装不上」就是这么来的，只看 workflow success 会被骗；
#   · 各版本 zipalign 对齐后**字节数可能完全相同**（1.1.52/53/54 三个 universal
#     都是 3414642B），光看大小判断「是不是新包」会误判成发了旧包。
#     所以要连包内 AndroidManifest 的版本串一起核。
#
#  本机没有 apksigner / Android SDK，故直接解析 APK Signing Block，
#  在其中暴力扫描 X.509 证书 DER（ASN.1 SEQUENCE 0x30 82 xx xx）。
#
#  用法：
#    python scripts/verify_apk.py v1.1.54            # 默认仓库 LuckyLearning/WindTone
#    python scripts/verify_apk.py v1.1.54 Owner/Repo
#    python scripts/verify_apk.py v1.1.54 Owner/Repo arm64-v8a
#    python scripts/verify_apk.py anylyric-v0.2.8    # AnyLyric 同样适用（自动剥离 anylyric-v 前缀取版本串）
#
#  退出码：0 = 全通过；1 = 验签失败 / 版本串不符 / 下载失败
# ===================================================================
import sys, os, re, io, json, zipfile, hashlib, tempfile, urllib.request

GOLDEN = "75eaeccd7cab0db4e30170f52091b3bf80ac0f1d6f89235433ebe2b5876e7398".lower()
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read_token():
    """token 放在仓库根的 .github-token（不入库，脚本只读）。"""
    p = os.path.join(ROOT, ".github-token")
    if not os.path.exists(p):
        sys.exit(f"找不到 token 文件：{p}")
    m = re.search(r"(ghp_|github_pat_)[A-Za-z0-9_]+", open(p, encoding="utf-8").read())
    if not m:
        sys.exit(".github-token 里没找到合法 Token")
    return m.group()


def u32(b, off):
    return int.from_bytes(b[off:off + 4], "little")


def u64(b, off):
    return int.from_bytes(b[off:off + 8], "little")


def download(tag, token, repo, target):
    url = f"https://api.github.com/repos/{repo}/releases/tags/{tag}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    d = json.load(urllib.request.urlopen(req, timeout=60))
    assets = [x for x in d["assets"] if target in x["name"]]
    if not assets:
        sys.exit(f"Release {tag} 里没有名字含 {target} 的资产")
    asset = assets[0]
    # 私有仓库不能用 browser_download_url（404），要走 assets API + octet-stream
    req2 = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/releases/assets/{asset['id']}",
        headers={"Authorization": f"Bearer {token}", "Accept": "application/octet-stream"})
    return asset["name"], urllib.request.urlopen(req2, timeout=300).read()


def find_eocd(data):
    i = len(data) - 22
    while i >= 0:
        if data[i:i + 4] == b"PK\x05\x06":
            clen = int.from_bytes(data[i + 20:i + 22], "little")
            if i + 22 + clen == len(data):
                return i
        i -= 1
    return None


def find_sig_block(data):
    eocd = find_eocd(data)
    assert eocd is not None, "找不到 EOCD"
    cd_offset = u32(data, eocd + 16)
    magic = data[cd_offset - 16: cd_offset]
    assert magic == b"APK Sig Block 42", f"magic 不匹配: {magic}"
    size = u64(data, cd_offset - 24)
    return data[cd_offset - 8 - size: cd_offset]


def find_cert_in_block(block):
    i = 0
    while True:
        i = block.find(b"\x30\x82", i)
        if i < 0:
            return None
        total = 4 + int.from_bytes(block[i + 2:i + 4], "big")
        if 400 <= total <= len(block) - i:
            cand = block[i: i + total]
            if hashlib.sha256(cand).hexdigest().lower() == GOLDEN:
                return True
            i += total
        else:
            i += 2


def manifest_version(data, expect):
    """在解压后的 AndroidManifest.xml 里找版本串（二进制 XML 用 UTF-16LE 存字符串）。"""
    # ⚠️ ZipFile 只吃路径或 file-like，直接喂 bytes 会抛 AttributeError
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        if "AndroidManifest.xml" not in z.namelist():
            return None
        b = z.read("AndroidManifest.xml")
    return expect.encode("utf-16-le") in b


def main():
    if len(sys.argv) < 2:
        sys.exit("用法：python scripts/verify_apk.py <tag> [Owner/Repo] [asset 关键字]")
    tag = sys.argv[1]
    repo = sys.argv[2] if len(sys.argv) > 2 else "LuckyLearning/WindTone"
    target = sys.argv[3] if len(sys.argv) > 3 else "universal"
    # tag 可能是 WindTone 的 v1.2.x，也可能是 AnyLyric 的 anylyric-v0.2.x
    if tag.startswith("anylyric-v"):
        expect = tag[len("anylyric-v"):]
    elif tag.startswith("v"):
        expect = tag[1:]
    else:
        expect = tag

    token = read_token()
    cache = os.path.join(tempfile.gettempdir(), f"wt_{tag}.apk")
    if os.path.exists(cache):
        data = open(cache, "rb").read()
        print(f"读取缓存 {cache}（{len(data)} 字节）")
    else:
        print(f"下载 {tag} 的 {target} 包 ...")
        name, data = download(tag, token, repo, target)
        print(f"  {name}：{len(data)} 字节")
        open(cache, "wb").write(data)

    ok = True
    if find_cert_in_block(find_sig_block(data)):
        print(f"  签名证书 SHA-256 = {GOLDEN.upper()} ✅")
    else:
        print("  签名证书不匹配 golden ❌（可能是调试签名 / 未签名包）")
        ok = False

    if manifest_version(data, expect):
        print(f"  包内版本串 = {expect} ✅")
    else:
        print(f"  包内版本串不是 {expect} ❌（疑似发了旧包 / 版本号没写进去）")
        ok = False

    print("结论：" + ("PASS ✅" if ok else "FAIL ❌"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
