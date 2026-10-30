# -*- coding: utf-8 -*-
from pathlib import Path
ROOT = Path(r"E:\workspace\Android\WindTone")

def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def write(rel, text):
    (ROOT / rel).write_text(text, encoding="utf-8", newline="\n")
    print("wrote", rel)

# ---------- QueueSheet in SheetsPlayback.kt ----------
sp = read("app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt")
old_sig = """fun QueueSheet(
    queue: List<QueueEntry>,
    onClose: () -> Unit,
    onJump: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSaveAsPlaylist: () -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = glassContainerColor()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Icon(AppIcons.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "播放队列 · ${queue.size} 首",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                // 队列是「临时排序」，用户攒出一份满意的队列后往往想存下来长期用
                TextButton(
                    onClick = onSaveAsPlaylist,
                    enabled = queue.isNotEmpty(),
                ) { Text("存为歌单") }
            }
            Spacer(Modifier.height(8.dp))
"""

# The comment text might have encoding variants - find by signature only
import re
m = re.search(
    r"fun QueueSheet\(\s*queue: List<QueueEntry>,.*?onSaveAsPlaylist: \(\) -> Unit = \{\},?\s*\) \{",
    sp,
    re.S,
)
if not m:
    raise SystemExit("QueueSheet signature not found")
print("sig at", m.start(), m.group(0)[:80])

# Find the block from fun QueueSheet through first Spacer after 存为歌单
m2 = re.search(
    r"fun QueueSheet\(.*?Spacer\(Modifier\.height\(8\.dp\)\)\n",
    sp,
    re.S,
)
if not m2:
    raise SystemExit("QueueSheet header block not found")
print("header block len", len(m2.group(0)))

new_header = '''fun QueueSheet(
    queue: List<QueueEntry>,
    onClose: () -> Unit,
    onJump: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSaveAsPlaylist: () -> Unit = {},
    onClear: () -> Unit = {},
    onShuffleOrder: () -> Unit = {},
) {
    // A3 清空要二次确认，避免车机误触一把清空
    var confirmClear by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = glassContainerColor()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Icon(AppIcons.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "播放队列 · ${queue.size} 首",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            // A3/A4：清空 / 打乱顺序（改物理序，不是随机播放模式）+ 原有「存为歌单」
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            ) {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onShuffleOrder()
                    },
                    enabled = queue.size > 1,
                ) { Text("打乱顺序") }
                if (confirmClear) {
                    TextButton(onClick = { confirmClear = false }) { Text("取消") }
                    TextButton(
                        onClick = {
                            confirmClear = false
                            onClear()
                        },
                        enabled = queue.isNotEmpty(),
                    ) {
                        Text("确认清空", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = queue.isNotEmpty(),
                    ) { Text("清空队列") }
                }
                TextButton(
                    onClick = onSaveAsPlaylist,
                    enabled = queue.isNotEmpty(),
                ) { Text("存为歌单") }
            }
            Spacer(Modifier.height(8.dp))
'''

sp2 = sp[:m2.start()] + new_header + sp[m2.end():]
# TextButton Text with color= — Material3 TextButton content is a RowScope lambda; Text has color param - OK

# Reset confirmClear when queue becomes empty? optional via LaunchedEffect - skip for simplicity

write("app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt", sp2)

# ---------- MainScreen wiring ----------
ms = read("app/src/main/java/com/luckylearning/windtone/ui/MainScreen.kt")
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
    raise SystemExit("MainScreen QueueSheet block not found")
write("app/src/main/java/com/luckylearning/windtone/ui/MainScreen.kt", ms.replace(old, new))
print("ui done")
