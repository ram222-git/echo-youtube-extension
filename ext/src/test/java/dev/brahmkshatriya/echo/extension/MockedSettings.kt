package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.settings.Settings

class MockedSettings : Settings {
    private val map = mutableMapOf<String, Any?>()
    override fun getBoolean(key: String): Boolean? = map[key] as? Boolean
    override fun getInt(key: String): Int? = map[key] as? Int
    override fun getString(key: String): String? = map[key] as? String
    override fun getStringSet(key: String): Set<String>? = @Suppress("UNCHECKED_CAST") (map[key] as? Set<String>)
    override fun putBoolean(key: String, value: Boolean?) { map[key] = value }
    override fun putInt(key: String, value: Int?) { map[key] = value }
    override fun putString(key: String, value: String?) { map[key] = value }
    override fun putStringSet(key: String, value: Set<String>?) { map[key] = value }
}