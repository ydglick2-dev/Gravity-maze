package il.kolan

import il.kolan.call.SignalingClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalingProtocolTest {

    @Test
    fun `room codes are always six digits`() {
        repeat(500) {
            val code = SignalingClient.generateRoomCode()
            assertEquals(6, code.length)
            assertTrue(code.all { it.isDigit() })
            assertTrue(SignalingClient.isValidRoomCode(code))
        }
    }

    @Test
    fun `room code validation rejects anything that is not six digits`() {
        assertTrue(SignalingClient.isValidRoomCode("123456"))
        assertFalse(SignalingClient.isValidRoomCode("12345"))
        assertFalse(SignalingClient.isValidRoomCode("1234567"))
        assertFalse(SignalingClient.isValidRoomCode("12345a"))
        assertFalse(SignalingClient.isValidRoomCode(""))
        assertFalse(SignalingClient.isValidRoomCode("      "))
    }

    @Test
    fun `only websocket urls are accepted`() {
        assertTrue(SignalingClient.isValidUrl("wss://signal.example.com"))
        assertTrue(SignalingClient.isValidUrl("ws://10.0.2.2:8080"))
        // An https URL is the most likely thing a user will paste, and silently accepting it
        // would fail later with an opaque connection error instead of an explanation.
        assertFalse(SignalingClient.isValidUrl("https://signal.example.com"))
        assertFalse(SignalingClient.isValidUrl("signal.example.com"))
        assertFalse(SignalingClient.isValidUrl(""))
    }
}
