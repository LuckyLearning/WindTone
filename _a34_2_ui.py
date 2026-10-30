# -*- coding: utf-8 -*-
from pathlib import Path
import re
ROOT = Path(r"E:\workspace\Android\WindTone")

def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def write(rel, text):
    (ROOT / rel).write_text(text, encoding="utf-8", newline="\n")
    print("wrote", rel)

# Extract QueueSheet from backup
backup = read("_SheetsPlayback.full.kt")
m_b = re.search(r"@Composable\r?\nfun QueueSheet\(.*?\r?\n\}\r?\n\r?\n// =+", backup, re.S)
if not m_b:
    raise SystemExit("backup QS not found")
# Keep through closing brace only; restore marker from current
qs_body = m_b.group(0)
# strip trailing // ===...
qs_body = re.sub(r"\r?\n// =+\s*$", "\n", qs_body)

sp = read("app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt")
m_cur = re.search(r"@Composable\r?\nfun QueueSheet\(.*?\r?\n\}\r?\n\r?\n// =+\s*睡眠", sp, re.S)
if not m_cur:
    raise SystemExit("current QS not found")
# find full marker line after match end - m_cur includes start of sleep marker
# Better: replace only fun QueueSheet ... closing }
m_cur2 = re.search(r"@Composable\r?\nfun QueueSheet\(.*?\r?\n\}\r?\n(?=\r?\n// =+\s*睡眠)", sp, re.S)
if not m_cur2:
    raise SystemExit("current QS2 not found")
sp2 = sp[:m_cur2.start()] + qs_body + sp[m_cur2.end():]
write("app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt", sp2)
sp3 = read("app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt")
assert "打乱顺序" in sp3
assert "onEndOfQueue" in sp3
print("merged QS; custom sleep:", "自定义" in sp3)

# MainScreen
ms = read("app/src/main/java/com/luckylearning/windtone/ui/MainScreen.kt")
if "onClear = { playerVm.clearQueue() }" not in ms:
    old = """                QueueSheet(
                    queue = player.queue,
                    onClose = { showQueue = false },
                    onJump = { playerVm.jumpToQueue(it) },
                    onRemove = { playerVm.removeQueueItem(it) },
                    onMove = { from, to -> playerVm.moveQueueItem(from, to) },
                    onSaveAsPlaylist = {
                        showQueue = false
                        saveQueueAsPlaylist = true
                    },
                )
"""
    new = """                QueueSheet(
                    queue = player.queue,
                    onClose = { showQueue = false },
                    onJump = { playerVm.jumpToQueue(it) },
                    onRemove = { playerVm.removeQueueItem(it) },
                    onMove = { from, to -> playerVm.moveQueueItem(from, to) },
                    onSaveAsPlaylist = {
                        showQueue = false
                        saveQueueAsPlaylist = true
                    },
                    onClear = { playerVm.clearQueue() },
                    onShuffleOrder = { playerVm.shuffleQueueOrder() },
                )
"""
    if old not in ms:
        raise SystemExit("MainScreen block missing")
    write("app/src/main/java/com/luckylearning/windtone/ui/MainScreen.kt", ms.replace(old, new))
else:
    print("MainScreen ok")

# 功能说明
feat = read("docs/功能说明.md")
m = re.search(r"- \*\*播放队列抽屉\*\*：[^\n]+", feat)
if m and "打乱顺序" not in m.group(0):
    feat = feat[:m.start()] + "- **播放队列抽屉**：查看队列、点击跳转、上移 / 下移排序、移除单曲、**打乱顺序**（改物理序，非随机模式）、**清空队列**（二次确认）、存为歌单" + feat[m.end():]
    write("docs/功能说明.md", feat)

print("ui ok")
