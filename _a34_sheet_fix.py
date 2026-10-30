# -*- coding: utf-8 -*-
from pathlib import Path
import re, subprocess
ROOT = Path(r"E:\workspace\Android\WindTone")

sp_path = ROOT / "app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt"
# Start from committed G5 version to be safe
subprocess.check_call(["git", "checkout", "HEAD", "--", "app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt"], cwd=ROOT)
# Actually HEAD doesn't have SheetsPlayback change - checkout HEAD is current G5 from parent... 
# Working tree SheetsPlayback may differ from HEAD. Reset to HEAD first.
subprocess.check_call(["git", "checkout", "96fe17e", "--", "app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt"], cwd=ROOT)
# Wait - we want G5 content as base. HEAD's parent for this file is same as 96fe17e since A3 commit didn't touch it.
# Use HEAD version:
subprocess.check_call(["git", "checkout", "HEAD", "--", "app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt"], cwd=ROOT)

sp = sp_path.read_text(encoding="utf-8")
print("base has 自定义", "自定义" in sp, "打乱", "打乱顺序" in sp)

NEW_QS = '''@Composable
fun QueueSheet(
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

m = re.search(
    r"@Composable\r?\nfun QueueSheet\(.*?Spacer\(Modifier\.height\(8\.dp\)\)\r?\n",
    sp,
    re.S,
)
if not m:
    raise SystemExit("header not found")
print("matched len", len(m.group(0)))
print("matched has 存为歌单", "存为歌单" in m.group(0))
sp2 = sp[:m.start()] + NEW_QS + sp[m.end():]
if "打乱顺序" not in sp2 or "自定义" not in sp2:
    raise SystemExit(f"merge bad 打乱={'打乱顺序' in sp2} 自定义={'自定义' in sp2}")
sp_path.write_text(sp2, encoding="utf-8", newline="\n")
print("SheetsPlayback written", sp_path.stat().st_size)

# 功能说明
feat_path = ROOT / "docs/功能说明.md"
feat = feat_path.read_text(encoding="utf-8")
m2 = re.search(r"- \*\*播放队列抽屉\*\*：[^\n]+", feat)
if m2 and "打乱顺序" not in m2.group(0):
    feat = feat[:m2.start()] + "- **播放队列抽屉**：查看队列、点击跳转、上移 / 下移排序、移除单曲、**打乱顺序**（改物理序，非随机模式）、**清空队列**（二次确认）、存为歌单" + feat[m2.end():]
    feat_path.write_text(feat, encoding="utf-8", newline="\n")
    print("功能说明 updated")

# verify MainScreen still wired
ms = (ROOT / "app/src/main/java/com/luckylearning/windtone/ui/MainScreen.kt").read_text(encoding="utf-8")
if "playerVm.clearQueue()" not in ms:
    raise SystemExit("MainScreen lost wiring")

subprocess.check_call(["git", "add", "--",
    "app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt",
    "docs/功能说明.md",
], cwd=ROOT)
msg = ROOT / "_a34_commit_msg2.txt"
msg.write_text(
    "feat(queue): QueueSheet 补齐清空/打乱按钮 UI（接上 A3/A4）\n\n"
    "前一提交已含 QueueManager/Policies/接线；本提交补抽屉操作行与功能说明。\n",
    encoding="utf-8",
)
subprocess.check_call(["git", "commit", "-F", str(msg)], cwd=ROOT)
# verify
sp3 = sp_path.read_text(encoding="utf-8")
assert "打乱顺序" in sp3 and "onClear" in sp3 and "自定义" in sp3
print("DONE", subprocess.check_output(["git","log","-1","--oneline"], cwd=ROOT).decode("utf-8", errors="replace"))
