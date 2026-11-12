package usb.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTraceLoggerTest {
    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("trace-test").toFile()

    @Test
    fun rotationKeepsTwoBoundedFilesAndNewestLines() {
        val root = tempDir()
        val logger = AppTraceLogger(root, maxBytes = 200L)
        repeat(30) { index -> logger.log("evt", "n" to index) }

        val dir = File(root, AppTraceLogger.DIR_NAME)
        val live = File(dir, AppTraceLogger.LIVE_NAME)
        val previous = File(dir, AppTraceLogger.PREVIOUS_NAME)
        assertTrue(previous.isFile)
        assertTrue(live.isFile)
        assertTrue("live file must stay bounded", live.length() <= 200L)
        assertTrue("newest event in live", live.readText().contains("n=29"))
        assertFalse("oldest event dropped", logger.snapshot().contains("n=0\n"))
        assertEquals(
            "no third trace file",
            setOf(AppTraceLogger.LIVE_NAME, AppTraceLogger.PREVIOUS_NAME),
            dir.list()!!.toSet(),
        )
    }

    @Test
    fun snapshotJoinsPreviousThenLiveAndRedacts() {
        val root = tempDir()
        val logger = AppTraceLogger(root, maxBytes = 200L)
        repeat(12) { index -> logger.log("evt", "n" to index, "password" to "hunter$index") }

        val snapshot = logger.snapshot()
        assertTrue(snapshot.contains("n=11"))
        assertFalse(snapshot.contains("hunter"))
        assertTrue(snapshot.contains("password=<redacted>"))
    }

    @Test
    fun redactsPasswordsTokensAndQueryValues() {
        assertEquals("password=<redacted>", redactTrace("password=hunter2"))
        assertTrue(redactTrace("adminPassword: s3cr3t").endsWith("<redacted>"))
        assertFalse(redactTrace("""{"password":"p@ss"}""").contains("p@ss"))
        assertTrue(redactTrace("token=abc123").contains("<redacted>"))
        assertFalse(redactTrace("Authorization: Bearer abc.def.ghi").contains("abc.def.ghi"))
        assertFalse(redactTrace("https://host/a/b?x=1&access_token=zzz").contains("zzz"))
        assertTrue(redactTrace("https://host/a/b?x=1").startsWith("https://host/a/b?"))
        assertFalse(redactTrace("content://com.provider/doc?name=secret").contains("secret"))
        assertTrue(redactTrace("on error=https://h/p?q=1#frag").contains("#frag"))
        assertTrue(
            redactTrace("<Password><Value>pw</Value></Password>").contains("<redacted>"),
        )
        assertFalse(redactTrace("<Password><Value>pw</Value></Password>").contains("pw"))
        assertFalse(
            redactTrace("<Password><PlainText>true</PlainText><Value>pw2</Value></Password>")
                .contains("pw2"),
        )
        assertFalse(
            redactTrace("""<AdminPassword Value="pw3"/>""").contains("pw3"),
        )
    }

    @Test
    fun concurrentWritesKeepWholeLines() {
        val root = tempDir()
        val logger = AppTraceLogger(root)
        val threads = (0 until 4).map { worker ->
            Thread {
                repeat(25) { index -> logger.log("op", "w" to worker, "n" to index) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val lines = logger.snapshot().lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(100, lines.size)
        assertTrue(lines.all { " event=op " in it })
    }
}
