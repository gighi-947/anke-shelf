package io.github.gighi947.ankeshelf.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolTest {

    @Test
    fun `parses compatible ready payload`() {
        val ready = BridgeProtocol.parseReady(
            """{"bridgeVersion":1,"capabilities":["paged","scroll","scrollRatio"]}""",
        )
        assertEquals(1, ready?.version ?: -1)
        assertEquals(setOf("paged", "scroll", "scrollRatio"), ready?.capabilities)
        assertTrue(BridgeProtocol.isCompatible(ready))
    }

    @Test
    fun `rejects malformed payload`() {
        assertNull(BridgeProtocol.parseReady(null))
        assertNull(BridgeProtocol.parseReady(""))
        assertNull(BridgeProtocol.parseReady("not-json"))
        assertNull(BridgeProtocol.parseReady("""{"bridgeVersion":1}"""))
        assertFalse(BridgeProtocol.isCompatible(null))
    }

    @Test
    fun `detects incompatible version`() {
        val ready = BridgeProtocol.parseReady("""{"bridgeVersion":2,"capabilities":["paged"]}""")
        assertEquals(2, ready?.version ?: -1)
        assertFalse(BridgeProtocol.isCompatible(ready))
    }
}
