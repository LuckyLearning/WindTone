from pathlib import Path
p = Path("app/src/test/java/com/luckylearning/windtone/playback/QueueManagerTest.kt")
t = p.read_text(encoding="utf-8")
old = """        p.shuffleModeEnabled = false
        val beforeIds = p.items.map { it.mediaId }
        // nextBits=0 → Fisher–Yates 每次与 0 交换，结果确定且非原序
"""
new = """        p.shuffleModeEnabled = false
        p.events.clear() // 丢掉 setup 写入的 shuffle=false，只断言打乱动作本身
        val beforeIds = p.items.map { it.mediaId }
        // nextBits=0 → Fisher–Yates 每次与 0 交换，结果确定且非原序
"""
if old not in t:
    raise SystemExit("missing")
p.write_text(t.replace(old, new), encoding="utf-8", newline="\n")
print("ok")
