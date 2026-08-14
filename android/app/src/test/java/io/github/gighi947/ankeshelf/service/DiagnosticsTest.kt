package io.github.gighi947.ankeshelf.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    @Test
    fun `report contains versions and file metadata`() {
        val text = Diagnostics.report(
            appVersion = "1.0.0",
            bridgeVersion = 1,
            system = mapOf("android" to "15 (API 35)", "webview" to "com.android.webview"),
            dataFiles = listOf(DataFileInfo("settings.json", true, 123, 3)),
            events = listOf("2026-08-14T00:00:00Z bridge ready version=1"),
            taskState = "stage=idle running=false detail= error=",
        )
        assertTrue(text.contains("app_version=1.0.0"))
        assertTrue(text.contains("bridge_version=1"))
        assertTrue(text.contains("settings.json exists=true size=123 version=3"))
        assertTrue(text.contains("bridge ready version=1"))
    }

    @Test
    fun `report never contains credentials or cookies`() {
        val text = Diagnostics.report(
            appVersion = "1.0.0",
            bridgeVersion = 1,
            system = mapOf("android" to "15"),
            dataFiles = emptyList(),
            events = emptyList(),
            taskState = "stage=idle",
        )
        assertFalse(text.contains("ngaPassport"))
        assertFalse(text.contains("nga_config"))
        assertFalse(text.contains("uid="))
        assertFalse(text.contains("cid="))
    }

    @Test
    fun `log events ring buffer caps and keeps latest`() {
        for (i in 0 until (LogEvents.MAX_EVENTS + 10)) {
            LogEvents.event("test", "tick", "i" to i)
        }
        val snapshot = LogEvents.snapshot()
        assertTrue(snapshot.size <= LogEvents.MAX_EVENTS)
        assertTrue(snapshot.last().contains("i=${LogEvents.MAX_EVENTS + 9}"))
        assertFalse(snapshot.last().contains("i=0 "))
    }

    @Test
    fun `book id hash is stable and short`() {
        assertEquals(LogEvents.bookIdHash("book-a"), LogEvents.bookIdHash("book-a"))
        assertTrue(LogEvents.bookIdHash("book-a").length == 12)
        assertFalse(LogEvents.bookIdHash("book-a") == LogEvents.bookIdHash("book-b"))
    }
}
