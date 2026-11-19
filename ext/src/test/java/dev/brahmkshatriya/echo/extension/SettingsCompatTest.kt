package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.streaming.SettingsPoTokenProvider
import dev.brahmkshatriya.echo.extension.utils.stringSetting
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emulates android.app.SharedPreferencesImpl's strict per-key typing: reading a
 * key with the wrong accessor throws ClassCastException, exactly like the device
 * crash caused by the old experimental build storing `potoken_mode` as a
 * SettingMultipleChoice (Set<String>).
 */
private class StrictSettings : Settings {
    private val map = HashMap<String, Any>()

    private fun wrongCast(value: Any, expected: String): ClassCastException =
        ClassCastException("${value.javaClass.name} cannot be cast to $expected")

    override fun getBoolean(key: String): Boolean? {
        val value = map[key] ?: return null
        if (value !is Boolean) throw wrongCast(value, "java.lang.Boolean")
        return value
    }

    override fun getInt(key: String): Int? {
        val value = map[key] ?: return null
        if (value !is Int) throw wrongCast(value, "java.lang.Integer")
        return value
    }

    override fun getString(key: String): String? {
        val value = map[key] ?: return null
        if (value !is String) throw wrongCast(value, "java.lang.String")
        return value
    }

    override fun getStringSet(key: String): Set<String>? {
        val value = map[key] ?: return null
        if (value !is Set<*>) throw wrongCast(value, "java.util.Set")
        @Suppress("UNCHECKED_CAST")
        return value as Set<String>
    }

    override fun putBoolean(key: String, value: Boolean?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun putInt(key: String, value: Int?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun putStringSet(key: String, value: Set<String>?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

class SettingsCompatTest {

    @Test
    fun `heals a set-stored value into a string`() {
        val settings = StrictSettings().apply { putStringSet("k", setOf("external")) }

        assertEquals("external", settings.stringSetting("k"))
        // The stored type is healed: plain reads (and the host app's UI) work again.
        assertEquals("external", settings.getString("k"))
    }

    @Test
    fun `prefers a valid value when healing`() {
        val settings = StrictSettings().apply { putStringSet("k", setOf("bogus", "android")) }

        assertEquals(
            "android",
            settings.stringSetting("k", valid = setOf("off", "android", "external"))
        )
        assertEquals("android", settings.getString("k"))
    }

    @Test
    fun `absent key returns null without writing`() {
        val settings = StrictSettings()

        assertNull(settings.stringSetting("k"))
        assertNull(settings.getString("k"))
    }

    @Test
    fun `plain string values pass through untouched`() {
        val settings = StrictSettings().apply { putString("k", "value") }

        assertEquals("value", settings.stringSetting("k"))
        assertEquals("value", settings.getString("k"))
    }

    @Test
    fun `provider reads a set-stored mode and heals it`() = runTest {
        val settings = StrictSettings().apply { putStringSet("potoken_mode", setOf("external")) }
        val chain = SettingsPoTokenProvider(settings)

        // External mode without a configured URL degrades to off (existing contract).
        assertEquals("off", chain.kind)
        assertNull(chain.token("v"))
        // The stored type is healed either way: plain reads (and the host app's UI) work again.
        assertEquals("external", settings.getString("potoken_mode"))
    }

    @Test
    fun `provider reads a set-stored android mode`() {
        val settings = StrictSettings().apply { putStringSet("potoken_mode", setOf("android")) }
        val chain = SettingsPoTokenProvider(settings)

        assertEquals("android", chain.kind)
    }

    @Test
    fun `provider with absent mode stays off`() = runTest {
        val settings = StrictSettings()
        val chain = SettingsPoTokenProvider(settings)

        assertEquals("off", chain.kind)
        assertNull(chain.token("v"))
    }

    @Test
    fun `describeState exposes mode and availability without secrets`() {
        val settings = StrictSettings().apply { putString("potoken_mode", "android") }
        val chain = SettingsPoTokenProvider(settings)

        val state = chain.describeState()
        assertTrue(state, state.contains("mode=android"))
        assertTrue(state, state.contains("kind=android"))
        assertTrue(state, state.contains("urlConfigured=false"))
        assertTrue(state, state.contains("onDeviceBroken=false"))
    }
}
