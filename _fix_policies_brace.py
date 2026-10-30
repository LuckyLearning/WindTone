from pathlib import Path
p = Path("app/src/test/java/com/luckylearning/windtone/playback/QueuePoliciesTest.kt")
t = p.read_text(encoding="utf-8")
# Remove the premature class-closing brace before shuffle tests
marker = "\n}\n\n    @Test\n    fun `shuffleOrderIndices 空表`"
if marker not in t:
    raise SystemExit("marker missing: " + repr(t[t.find("enqueueNewSongs"):t.find("enqueueNewSongs")+200]))
t2 = t.replace(marker, "\n\n    @Test\n    fun `shuffleOrderIndices 空表`", 1)
p.write_text(t2, encoding="utf-8", newline="\n")
print("braces", t2.count("{"), t2.count("}"))
