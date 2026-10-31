package com.luckylearning.windtone.ui.library

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class M3uLocalImportTest {

    private fun song(
        id: Long,
        title: String,
        path: String? = "/sdcard/Music/$title.mp3",
        uriStr: String = "content://media/external/audio/media/$id",
    ) = Song(
        id = id,
        uri = Uri.parse(uriStr),
        title = title,
        artist = "a",
        album = "b",
        durationMs = 1000L,
        path = path,
        dbId = id,
    )

    @Test
    fun match_byExactPathAndFileName() {
        val lib = listOf(
            song(1, "One", path = "/music/One.flac"),
            song(2, "Two", path = "/other/Two.mp3"),
        )
        val text = """
            #EXTM3U
            /music/One.flac
            file:///other/Two.mp3
        """.trimIndent()
        val out = M3uLocalImport.match(text, lib)
        assertEquals(listOf(1L, 2L), out.matched.map { it.id })
        assertFalse(out.truncated)
    }

    @Test
    fun match_byExtinfTitleFallback() {
        val lib = listOf(song(9, "Hello World", path = null))
        val text = """
            #EXTM3U
            #EXTINF:12,Someone - Hello World
            http://example.invalid/missing.mp3
        """.trimIndent()
        val out = M3uLocalImport.match(text, lib)
        assertEquals(listOf(9L), out.matched.map { it.id })
    }

    @Test
    fun match_dedupesAndCapsMatched() {
        val lib = (1..5).map { song(it.toLong(), "T$it", path = "/p/T$it.mp3") }
        val lines = (1..5).flatMap { listOf("/p/T$it.mp3", "/p/T$it.mp3") }
        val text = (listOf("#EXTM3U") + lines).joinToString("\n")
        val out = M3uLocalImport.match(text, lib)
        assertEquals(5, out.matched.size)
        assertFalse(out.truncated)
    }

    @Test
    fun match_truncatesAtMaxMatched() {
        val n = M3uLocalImport.MAX_MATCHED + 3
        val lib = (1..n).map { song(it.toLong(), "S$it", path = "/x/S$it.mp3") }
        val text = (listOf("#EXTM3U") + lib.map { it.path!! }).joinToString("\n")
        val out = M3uLocalImport.match(text, lib)
        assertEquals(M3uLocalImport.MAX_MATCHED, out.matched.size)
        assertTrue(out.truncated)
    }

    @Test
    fun readCapped_flagsOversize() {
        val big = ByteArray(M3uLocalImport.MAX_BYTES + 8) { 'x'.code.toByte() }
        val (bytes, over) = M3uLocalImport.readCapped(ByteArrayInputStream(big))
        assertTrue(over)
        assertEquals(M3uLocalImport.MAX_BYTES, bytes.size)
        assertEquals(null, M3uLocalImport.decodeUtf8Capped(big))
        assertEquals("ok", M3uLocalImport.decodeUtf8Capped("ok".toByteArray()))
    }

    @Test
    fun fileNameOf_handlesSlashAndBackslash() {
        assertEquals("a.mp3", M3uLocalImport.fileNameOf("/foo/bar/a.mp3"))
        assertEquals("b.flac", M3uLocalImport.fileNameOf("C:\\Music\\b.flac"))
    }
}
