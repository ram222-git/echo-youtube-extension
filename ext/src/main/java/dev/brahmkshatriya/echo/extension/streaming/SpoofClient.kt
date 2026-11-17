package dev.brahmkshatriya.echo.extension.streaming

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Spoof client definitions matching Morphe / ReVanced "Spoof video streams" patch.
 * Spoofs client parameters sent in YouTube Innertube player requests to bypass
 * playback restrictions, bot detection, 403 Forbidden, and 1-minute playback stops.
 */
enum class SpoofClient(
    val id: String,
    val displayName: String,
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val platform: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val clientFormFactor: String? = null,
    val origin: String = "https://www.youtube.com",
    val clientNumber: String = "1",
    val isWebClient: Boolean = false
) {
    ANDROID_VR(
        id = "android_vr",
        displayName = "Android VR (Quest 3)",
        clientName = "ANDROID_VR",
        clientVersion = "1.65.10",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        platform = "MOBILE",
        deviceMake = "Oculus",
        deviceModel = "Quest 3",
        osName = "Android",
        osVersion = "12L",
        origin = "https://www.youtube.com",
        clientNumber = "28",
        isWebClient = false
    ),
    IOS(
        id = "ios",
        displayName = "iOS",
        clientName = "IOS",
        clientVersion = "19.34.2",
        userAgent = "com.google.ios.youtube/19.34.2 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)",
        platform = "MOBILE",
        deviceMake = "Apple",
        deviceModel = "iPhone16,2",
        osName = "iOS",
        osVersion = "17.5.1.21F90",
        origin = "https://www.youtube.com",
        clientNumber = "5",
        isWebClient = false
    ),
    TV(
        id = "tv",
        displayName = "Android TV / Smart TV",
        clientName = "TVHTML5",
        clientVersion = "7.20230405.08.01",
        userAgent = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 5.0) AppleWebKit/537.36 (KHTML, like Gecko) Version/5.0 TV Safari/537.36",
        platform = "TV",
        origin = "https://www.youtube.com",
        clientNumber = "85",
        isWebClient = true
    ),
    MWEB(
        id = "mweb",
        displayName = "Mobile Web (MWEB)",
        clientName = "MWEB",
        clientVersion = "2.20240901.00.00",
        userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
        platform = "MOBILE",
        origin = "https://m.youtube.com",
        clientNumber = "2",
        isWebClient = true
    ),
    WEB_REMIX(
        id = "web_remix",
        displayName = "YouTube Music Web",
        clientName = "WEB_REMIX",
        clientVersion = "1.20260928.13.00",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
        platform = "DESKTOP",
        origin = "https://music.youtube.com",
        clientNumber = "67",
        isWebClient = true
    );

    fun buildClientContext(visitorId: String? = null): JsonObject {
        return buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", clientName)
                put("clientVersion", clientVersion)
                platform?.let { put("platform", it) }
                deviceMake?.let { put("deviceMake", it) }
                deviceModel?.let { put("deviceModel", it) }
                osName?.let { put("osName", it) }
                osVersion?.let { put("osVersion", it) }
                clientFormFactor?.let { put("clientFormFactor", it) }
                put("hl", "en")
                put("gl", "US")
                if (!visitorId.isNullOrBlank()) {
                    put("visitorData", visitorId)
                }
            })
        }
    }

    companion object {
        fun fromId(id: String?): SpoofClient? {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
        }
    }
}
