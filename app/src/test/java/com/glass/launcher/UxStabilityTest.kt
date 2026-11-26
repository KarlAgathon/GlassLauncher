package com.glass.launcher

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UxStabilityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aCancelledReplacementKeepsBothOriginals() {
        val source = folder.newFile("source").apply { writeText("new") }
        val destination = folder.newFile("destination").apply { writeText("old") }
        var progress = false
        assertFalse(FileOps.transfer(source, destination, move = true, replace = true,
            progress = { progress = true }, cancel = { progress }))
        assertEquals("new", source.readText())
        assertEquals("old", destination.readText())
        assertFalse(folder.root.listFiles().orEmpty().any { it.name.startsWith(".lead-transfer-") })
    }

    @Test fun failedCommitRollsBackReplacement() {
        val source = folder.newFile("source").apply { writeText("new") }
        val destination = folder.newFile("destination").apply { writeText("old") }
        assertFalse(FileOps.transfer(source, destination, move = true, replace = true,
            rename = { from, to -> from.name != "new" && from.renameTo(to) }))
        assertEquals("new", source.readText())
        assertEquals("old", destination.readText())
    }

    @Test fun verifiesFinalPathBeforeRemovingMoveSource() {
        val source = folder.newFile("source").apply { writeText("new") }
        val destination = folder.newFile("destination").apply { writeText("old") }
        assertTrue(FileOps.transfer(source, destination, move = true, replace = true))
        assertFalse(source.exists())
        assertEquals("new", destination.readText())
    }

    @Test fun missingDestinationAndDuplicateLeaveOriginals() {
        val source = folder.newFile("source").apply { writeText("new") }
        val destination = folder.newFile("name.txt").apply { writeText("old") }
        assertFalse(FileOps.transfer(source, File(folder.root, "missing/name.txt"), true, false))
        assertFalse(FileOps.transfer(source, destination, false, false))
        assertEquals("name (2).txt", FileOps.uniqueName(folder.root, destination.name))
        assertEquals("old", destination.readText())
        assertEquals("new", source.readText())
    }

    @Test fun homeLabelsAreIndependent() {
        for (focused in listOf(false, true)) {
            assertFalse(showHomeLabel(false, false, focused))
            assertTrue(showHomeLabel(true, false, focused))
            assertEquals(focused, showHomeLabel(false, true, focused))
            assertTrue(showHomeLabel(true, true, focused))
        }
    }

    @Test fun transparentAndOpaquePaddingUseTheSameVisibleBounds() {
        val background = 0xff151515.toInt()
        val foreground = 0xfffafafa.toInt()
        fun art(bg: Int) = IntArray(8 * 6) { index ->
            if (index % 8 in 3..6 && index / 8 in 2..3) foreground else bg
        }
        val bounds = intArrayOf(2, 1, 8, 5)
        assertArrayEquals(bounds, visibleLogoBounds(art(0), 8, 6))
        assertArrayEquals(bounds, visibleLogoBounds(art(background), 8, 6))
        assertArrayEquals(intArrayOf(0, 0, 8, 6), visibleLogoBounds(IntArray(48) { foreground }, 8, 6))
    }
    @Test fun phoneConnectRejectsInvalidDeniedExpiredAndOversizedRequests() {
        var now = 0L
        var allow = false
        val phone = PhoneConnect({ _, reply -> reply(allow) }, {}, java.net.InetAddress.getLoopbackAddress(),
            { folder.root }, { now })
        phone.start()
        try {
            val path = java.net.URI(phone.url()).path
            fun request(line: String, headers: String = "", payload: String = ""): String =
                java.net.Socket(java.net.InetAddress.getLoopbackAddress(), phone.port).use { socket ->
                    socket.soTimeout = 3_000
                    socket.getOutputStream().write(("$line HTTP/1.1\r\nHost: localhost\r\n$headers\r\n$payload").toByteArray())
                    socket.shutdownOutput()
                    socket.getInputStream().bufferedReader().readText()
                }
            assertTrue(request("GET /wrong").startsWith("HTTP/1.1 404"))
            assertTrue(request("PUT ${path}status").startsWith("HTTP/1.1 405"))
            assertTrue(request("GET ${path}status").contains("denied"))
            assertTrue(request("POST ${path}up?name=file", "Content-Length: 2\r\n", "ab").startsWith("HTTP/1.1 403"))
            now = 10 * 60_000L
            assertTrue(request("GET ${path}status").startsWith("HTTP/1.1 404"))
        } finally {
            phone.stop()
        }

        allow = true
        val next = PhoneConnect({ _, reply -> reply(allow) }, {}, java.net.InetAddress.getLoopbackAddress(),
            { folder.root }, { now })
        next.start()
        try {
            val path = java.net.URI(next.url()).path
            fun request(method: String, endpoint: String, size: Long? = null, data: String = "") =
                java.net.Socket(java.net.InetAddress.getLoopbackAddress(), next.port).use { socket ->
                    socket.soTimeout = 3_000
                    val headers = size?.let { "Content-Length: $it\r\n" } ?: ""
                    socket.getOutputStream().write(("$method $path$endpoint HTTP/1.1\r\nHost: localhost\r\n$headers\r\n$data").toByteArray())
                    socket.shutdownOutput()
                    socket.getInputStream().bufferedReader().readText()
                }
            assertTrue(request("GET", "status").contains("ok"))
            assertTrue(request("POST", "up?name=file", Long.MAX_VALUE).startsWith("HTTP/1.1 413"))
            assertTrue(request("POST", "up?name=file", 10, "a").startsWith("HTTP/1.1 400"))
            assertTrue(folder.root.listFiles().orEmpty().isEmpty())
            assertTrue(request("POST", "up?name=file", 2, "ab").startsWith("HTTP/1.1 200"))
            assertEquals("ab", File(folder.root, "file").readText())
        } finally {
            next.stop()
        }
    }
}
