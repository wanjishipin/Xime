package com.kingzcheung.xime.plugin

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.verify

class PluginConfigStoreImplTest {

    /** 可逆 fake：`enc:` 前缀模拟加密值，无前缀视为历史明文。 */
    private class FakeCipher : ValueCipher {
        override fun encrypt(plain: String): String = "enc:" + plain
        override fun decrypt(stored: String): String? =
            if (stored.startsWith("enc:")) stored.removePrefix("enc:") else stored
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private val stored = HashMap<String, String>()
    private val cipher = FakeCipher()
    private lateinit var store: PluginConfigStore

    @Before
    fun setUp() {
        prefs = mock(SharedPreferences::class.java)
        editor = mock(SharedPreferences.Editor::class.java)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putString(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenAnswer {
            stored[it.getArgument(0)] = it.getArgument(1)
            editor
        }
        `when`(editor.remove(org.mockito.kotlin.any())).thenAnswer {
            stored.remove(it.getArgument(0))
            editor
        }
        `when`(editor.apply()).then {}
        `when`(prefs.getString(org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull())).thenAnswer {
            stored[it.getArgument(0)]
        }
        `when`(prefs.all).thenReturn(stored)
        val app = mock(Application::class.java)
        `when`(app.getSharedPreferences(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(prefs)
        store = PluginConfigStoreImpl(app, "com.test.plugin", cipher)
    }

    @Test
    fun `set stores encrypted value`() {
        store.set("apiKey", "secret-123")

        assertEquals("enc:secret-123", stored["apiKey"])
    }

    @Test
    fun `get decrypts encrypted value`() {
        stored["apiKey"] = "enc:secret-123"

        assertEquals("secret-123", store.get("apiKey"))
    }

    @Test
    fun `get returns legacy plaintext as-is`() {
        stored["apiKey"] = "secret-123"

        assertEquals("secret-123", store.get("apiKey"))
    }

    @Test
    fun `get returns null when key missing`() {
        assertNull(store.get("missing"))
    }

    @Test
    fun `remove deletes key`() {
        stored["apiKey"] = "enc:secret-123"

        store.remove("apiKey")

        verify(editor).remove("apiKey")
        assertNull(stored["apiKey"])
    }

    @Test
    fun `keys delegates to prefs`() {
        stored["a"] = "enc:1"
        stored["b"] = "enc:2"

        assertEquals(setOf("a", "b"), store.keys())
    }
}
