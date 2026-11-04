package com.braveplayer.music.jam

import android.content.Context
import com.braveplayer.music.jam.security.JamKeyStoreManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class JamKeyStoreManagerTest {

    private lateinit var context: Context
    private lateinit var keyStoreManager: JamKeyStoreManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        keyStoreManager = JamKeyStoreManager(context)
        keyStoreManager.clearSession()
    }

    @Test
    fun testInstallationIdIsDeterministicAndPersistent() {
        val id1 = keyStoreManager.getOrCreateInstallationId()
        assertNotNull(id1)
        assertTrue(id1.startsWith("inst_"))

        val id2 = keyStoreManager.getOrCreateInstallationId()
        assertEquals(id1, id2)
    }

    @Test
    fun testDisplayNameDefaultAndCustomization() {
        val defaultName = keyStoreManager.getDisplayName()
        assertTrue(defaultName.startsWith("Melody User"))

        keyStoreManager.setDisplayName("Rohit Sharma")
        assertEquals("Rohit Sharma", keyStoreManager.getDisplayName())
    }

    @Test
    fun testSaveAndRetrieveGuestSession() {
        assertNull(keyStoreManager.getGuestToken())
        assertNull(keyStoreManager.getGuestId())

        val futureExpiry = System.currentTimeMillis() + 100000L
        keyStoreManager.saveSession("gst_test_99", "secret_token_abc_123", futureExpiry)

        assertEquals("gst_test_99", keyStoreManager.getGuestId())
        assertEquals("secret_token_abc_123", keyStoreManager.getGuestToken())
    }

    @Test
    fun testExpiredTokenReturnsNull() {
        val pastExpiry = System.currentTimeMillis() - 1000L
        keyStoreManager.saveSession("gst_expired_01", "old_token", pastExpiry)

        assertNull("Expired token must return null", keyStoreManager.getGuestToken())
    }

    @Test
    fun testClearSessionRemovesCredentials() {
        val futureExpiry = System.currentTimeMillis() + 100000L
        keyStoreManager.saveSession("gst_to_clear", "token_to_clear", futureExpiry)
        assertNotNull(keyStoreManager.getGuestToken())

        keyStoreManager.clearSession()
        assertNull(keyStoreManager.getGuestToken())
        assertNull(keyStoreManager.getGuestId())
    }
}
