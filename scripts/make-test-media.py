#!/usr/bin/env python3
"""生成用于真机 / 模拟器冒烟验证的测试曲库（音频 + 歌词）。

用法:
    python scripts/make-test-media.py [输出目录]
    # 默认输出 build/testmedia/，随后：
    adb push build/testmedia/Music /sdcard/Music

设计要点（每一条都对应一个待验证项）：
- 文件名一律 ASCII。Windows 侧 adb push 对非 ASCII 路径不稳定，中文只放
  ID3 标签与歌词正文里；这样顺带验证「标签优先、文件名兜底」的取值链。
- 除 06_short 外所有曲目时长 >= 65s，绕过默认 60s 短音频过滤（Prefs 的
  DEFAULT_MIN_DURATION_SEC）；06_short 故意 20s，用来验证该过滤确实生效。
- 05_same / 05_same_copy 同 title + 同 artist + 同时长、但码率不同，
  用于验证「重复歌曲清理」的指纹识别与保留最优打分。
- 01_tide.lrc 是普通 LRC（只有行时间轴）→ 验证加载层的逐字时间轴派生。
- 02_ebb.lrc 是增强 LRC（行内带 <mm:ss.xx> 字级标签）且以 GBK 编码存盘
  → 同时验证 KrcParser 与 TextCodec 的编码嗅探。
- 01_night_flight 内嵌 USLT 歌词 → 验证内嵌歌词通道。
- 07_untagged 不带任何标签 → 验证未知艺术家 / 未知专辑的兜底分组。
"""

import shutil
import subprocess
import sys
from pathlib import Path

PLAYLIST = [
    # dir, filename, title, artist, album, track, duration_s, bitrate, freq
    ("Album_NightFlight", "01_night_flight.mp3", "夜航", "林深", "夜航", 1, 70, "128k", 392),
    ("Album_NightFlight", "02_homeward.mp3", "归途", "林深", "夜航", 2, 80, "192k", 440),
    ("Album_Tide", "01_tide.mp3", "潮汐", "陈屿", "潮汐", 1, 95, "128k", 494),
    ("Album_Tide", "02_ebb.mp3", "退潮", "陈屿", "潮汐", 2, 110, "128k", 523),
    ("Dup_Test", "05_same.mp3", "同名", "测试歌手", "重复测试", 1, 65, "128k", 349),
    ("Dup_Test", "05_same_copy.mp3", "同名", "测试歌手", "重复测试", 1, 65, "320k", 349),
    ("Recordings", "06_short.mp3", "短录音", "测试歌手", "杂项", 1, 20, "128k", 262),
    ("Recordings", "07_untagged.mp3", None, None, None, None, 65, "128k", 294),
]

COVER_COLORS = ["0x2E5A88", "0x8A4B2E", "0x4B8A5F", "0x6B4B8A"]

# 普通 LRC：只有行时间轴，没有字级标签。
PLAIN_LINES = [
    "夜色沉入海面",
    "灯塔还在转",
    "我把船靠向你",
    "潮水退得很慢",
    "一句话没说出口",
    "就被风带走了",
    "天亮之前",
    "我还会在这里",
]

# 增强 LRC：每行是 (文本, 相对行首毫秒) 序列。解析器按「标签到下一个标签之间」
# 取词的正文，所以两个时间标签之间写几个字就得到几个字的词 —— 逐字与分词两种粒度
# 都在这里覆盖到。
WORD_LINES = [
    [("退", 0), ("潮", 320), ("的", 700), ("声", 1050), ("音", 1400)],
    [("很", 0), ("轻", 350), ("很", 700), ("远", 1050)],
    [("沙", 0), ("滩", 400), ("留下", 800), ("痕迹", 1500)],
    [("我", 0), ("数着", 400), ("贝壳", 1100)],
    [("一颗", 0), ("一颗", 700)],
    [("直到", 0), ("潮水", 700), ("回来", 1450)],
    [("把", 0), ("脚印", 500), ("抹平", 1400)],
    [("就像", 0), ("从", 700), ("未", 1100), ("来", 1500), ("过", 1900)],
]


def stamp(ms: int) -> str:
    m, rem = divmod(ms, 60_000)
    s, cs = divmod(rem, 1000)
    return f"{m:02d}:{s:02d}.{cs // 10:02d}"


def build_plain_lrc() -> str:
    out = ["[ti:潮汐]", "[ar:陈屿]", "[al:潮汐]", "[offset:0]", ""]
    for i, text in enumerate(PLAIN_LINES):
        out.append(f"[{stamp(2000 + i * 4200)}]{text}")
    return "\n".join(out) + "\n"


def build_word_lrc() -> str:
    """增强型 LRC：字级标签与词**交替**出现（`<t1>词1<t2>词2`）。

    这是 LrcParser 的 WORD_TAG 约定 —— 每个词的文本取自该标签结束到下一个
    标签开始之间，所以「先堆所有标签再写正文」会被解析成空词。
    """
    out = ["[ti:退潮]", "[ar:陈屿]", "[al:潮汐]", "[offset:0]", ""]
    for i, line in enumerate(WORD_LINES):
        base = 2000 + i * 4200
        body = "".join(f"<{stamp(base + off)}>{text}" for text, off in line)
        out.append(f"[{stamp(base)}]{body}")
    return "\n".join(out) + "\n"


def build_embedded_lyrics() -> str:
    lines = ["[ti:夜航]", "[ar:林深]", "[al:夜航]", ""]
    for i, text in enumerate(PLAIN_LINES):
        lines.append(f"[{stamp(3000 + i * 4500)}]{text}")
    return "\n".join(lines)


def ffmpeg() -> str:
    found = shutil.which("ffmpeg")
    if found:
        return found
    fallback = (
        Path.home()
        / "AppData/Local/Microsoft/WinGet/Packages"
        / "Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe"
        / "ffmpeg-8.1.1-full_build/bin/ffmpeg.exe"
    )
    if fallback.exists():
        return str(fallback)
    sys.exit("找不到 ffmpeg：请安装后重试，或把它加入 PATH")


def run(cmd: list[str]) -> None:
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        tail = proc.stderr.decode("utf-8", "replace").strip().splitlines()[-6:]
        sys.exit("ffmpeg 失败:\n" + "\n".join(tail))


def make_cover(ff: str, out: Path, color: str) -> None:
    run([
        ff, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", f"color=c={color}:s=500x500:d=1",
        "-frames:v", "1", str(out),
    ])


def make_track(ff: str, out: Path, dur: int, freq: int, bitrate: str,
               meta: dict[str, str], cover: Path | None) -> None:
    cmd = [
        ff, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", f"sine=frequency={freq}:duration={dur}:sample_rate=44100",
        "-f", "lavfi", "-i", f"sine=frequency={freq * 2}:duration={dur}:sample_rate=44100",
    ]
    if cover is not None:
        cmd += ["-i", str(cover)]
    # 基频 + 八度叠加并加轻微颤音，避免纯正弦听感过于刺耳。
    cmd += [
        "-filter_complex",
        "[0:a][1:a]amix=inputs=2:duration=shortest,tremolo=f=4:d=0.35[a]",
        "-map", "[a]",
    ]
    if cover is not None:
        cmd += ["-map", "2:v", "-c:v", "copy", "-disposition:v", "attached_pic"]
    cmd += ["-c:a", "libmp3lame", "-b:a", bitrate, "-id3v2_version", "3"]
    for key, value in meta.items():
        cmd += ["-metadata", f"{key}={value}"]
    cmd += [str(out)]
    run(cmd)


def main() -> None:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build/testmedia")
    music = root / "Music"
    if root.exists():
        shutil.rmtree(root)
    ff = ffmpeg()

    covers: list[Path] = []
    for idx, color in enumerate(COVER_COLORS):
        path = root / f"cover{idx}.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        make_cover(ff, path, color)
        covers.append(path)

    embedded = build_embedded_lyrics()
    made = 0
    for idx, (folder, name, title, artist, album, track, dur,
              bitrate, freq) in enumerate(PLAYLIST):
        target = music / folder
        target.mkdir(parents=True, exist_ok=True)
        meta: dict[str, str] = {"genre": "Test"}
        if title:
            meta["title"] = title
        if artist:
            meta["artist"] = artist
        if album:
            meta["album"] = album
        if track is not None:
            meta["track"] = str(track)
        if name == "01_night_flight.mp3":
            meta["lyrics"] = embedded
        cover = covers[idx % len(covers)] if name != "07_untagged.mp3" else None
        make_track(ff, target / name, dur, freq, bitrate, meta, cover)
        made += 1
        print(f"  生成 {folder}/{name}  ({dur}s / {bitrate})")

    plain = music / "Album_Tide" / "01_tide.lrc"
    plain.write_text(build_plain_lrc(), encoding="utf-8")
    gbk = music / "Album_Tide" / "02_ebb.lrc"
    gbk.write_bytes(build_word_lrc().encode("gbk"))
    print(f"  生成 Album_Tide/01_tide.lrc  (普通 LRC, UTF-8, {len(PLAIN_LINES)} 行)")
    print(f"  生成 Album_Tide/02_ebb.lrc   (增强 LRC, GBK, {len(WORD_LINES)} 行)")

    for path in covers:
        path.unlink()
    print(f"\n完成：{made} 首音频 + 2 份歌词 → {music}")
    print(f"推送： adb push \"{music}\" /sdcard/Music")


if __name__ == "__main__":
    main()
