package dev.brahmkshatriya.echo.extension.utils

import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * Defensive settings reads that heal a corrupted stored type.
 *
 * SharedPreferences is strictly typed per key: an older experimental build of
 * this extension defined `potoken_mode` as a `SettingMultipleChoice`, which the
 * host app stores as a `Set<String>`. Later builds (and the host app's own
 * settings screen, a ListPreference) read the same key with `getString`, and
 * `SharedPreferencesImpl.getString` throws `ClassCastException:
 * java.util.HashSet cannot be cast to java.lang.String` — crashing playback for
 * every track and preventing the settings screen from opening.
 *
 * [stringSetting] falls back to the set value, prefers one of [valid], and
 * **rewrites the key as a String** so the host app's UI recovers without the
 * user having to clear app data.
 */
fun Settings.stringSetting(key: String, valid: Set<String>? = null): String? {
    val direct = runCatching { getString(key) }.getOrNull()
    if (direct != null) return direct

    // `getString` returned null: either the key is absent (getStringSet is then
    // null too) or a non-string value is stored (recoverable below).
    val storedSet = runCatching { getStringSet(key) }.getOrNull() ?: return null
    val chosen = storedSet.firstOrNull { valid == null || it in valid }
        ?: storedSet.firstOrNull()
    if (chosen != null) {
        // Heal: replace the Set<String> entry with a String so ListPreference
        // and every later read succeed.
        runCatching { putString(key, chosen) }
    }
    return chosen
}
