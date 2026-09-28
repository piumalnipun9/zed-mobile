package dev.zed.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSettingsTest {
    @Test
    fun `stay connected defaults to enabled until the user makes a choice`() {
        assertTrue(stayConnectedOrDefault(null))
    }

    @Test
    fun `stay connected preserves an explicit user choice`() {
        assertTrue(stayConnectedOrDefault(true))
        assertFalse(stayConnectedOrDefault(false))
    }
}
