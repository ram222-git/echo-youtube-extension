package dev.brahmkshatriya.echo.extension.providers.playback

import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.auth.YouTubeAuthManager
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * Sends listening history and playback telemetry back to YouTube Music servers,
 * matching the protocol used by the official YouTube Music client and SimpMusic.
 */
class PlaybackTracker(
    override val api: YoutubeiApi,
    private val authManager: YouTubeAuthManager,
    private val json: Json
) : ApiEndpoint() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trackingJob: Job? = null
    private var currentSession: PlaybackSession? = null
    private var lastTrackedVideoId: String? = null
    private var lastTrackedTime: Long = 0

    data class PlaybackSession(
        val videoId: String,
        val cpn: String,
        val clientName: String,
        val playbackUrl: String?,
        val atrUrl: String?,
        val watchtimeUrl: String?,
        val playlistId: String?,
        val lengthSeconds: Float,
        var isMarkedWatched: Boolean = false
    )

    private fun generateCpn(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        return (1..16).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun webRemixContext(): JsonObject = buildJsonObject {
        put("context", buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20260304.03.00")
                put("hl", "en")
                put("gl", "US")
                put("userAgent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")
                api.visitor_id?.let { put("visitorData", it) }
            })
        })
    }

    private fun HttpRequestBuilder.attachAuthHeaders(eventTime: String) {
        headers {
            append("X-Goog-Event-Time", eventTime)
            append("X-Goog-Request-Time", eventTime)
            append("Origin", "https://music.youtube.com")
            append("Referer", "https://music.youtube.com/")
            append("X-YouTube-Client-Name", "67")
            append("X-YouTube-Client-Version", "1.20260304.03.00")
            append("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")
            api.user_auth_state?.headers?.forEach { key, values ->
                values.forEach { value -> append(key, value) }
            }
        }
    }

    fun startTracking(track: Track?, enabled: Boolean) {
        if (track == null) return
        startTracking(
            videoId = track.id,
            title = track.title,
            playlistId = track.extras["playlistId"] ?: track.extras["list"],
            durationMs = track.duration,
            enabled = enabled
        )
    }

    fun startTracking(
        videoId: String,
        title: String? = null,
        playlistId: String? = null,
        durationMs: Long? = null,
        enabled: Boolean
    ) {
        if (!enabled || videoId.isBlank()) return

        val now = System.currentTimeMillis()
        if (videoId == lastTrackedVideoId && (now - lastTrackedTime) < 10000L) {
            println("PlaybackTracker: Already tracking $videoId recently, skipping duplicate call")
            return
        }
        lastTrackedVideoId = videoId
        lastTrackedTime = now

        trackingJob?.cancel()
        trackingJob = scope.launch {
            try {
                authManager.ensureVisitorId()
                runCatching { authManager.requireAuth() }.getOrNull()

                val cpn = generateCpn()
                println("PlaybackTracker: Starting tracking for $videoId (${title ?: "Unknown"}), CPN: $cpn, loggedIn: ${api.user_auth_state != null}")

                val session = fetchPlaybackTracking(videoId, cpn, playlistId, durationMs)
                if (session == null) {
                    println("PlaybackTracker: fetchPlaybackTracking returned null for $videoId")
                    return@launch
                }

                currentSession = session
                runTelemetryLifecycle(session)
            } catch (e: Exception) {
                println("PlaybackTracker startTracking error: ${e.message}")
            }
        }
    }

    fun markWatched(videoId: String?, enabled: Boolean) {
        if (!enabled || videoId.isNullOrBlank()) return

        val session = currentSession?.takeIf { it.videoId == videoId } ?: return
        if (session.isMarkedWatched) {
            println("PlaybackTracker: $videoId already marked as watched, skipping duplicate call")
            return
        }
        session.isMarkedWatched = true

        scope.launch {
            try {
                send30sWatchtime(session)
            } catch (e: Exception) {
                println("PlaybackTracker markWatched error: ${e.message}")
            }
        }
    }

    fun markCompleted(videoId: String?, enabled: Boolean) {
        markWatched(videoId, enabled)
    }

    private suspend fun fetchPlaybackTracking(
        videoId: String,
        cpn: String,
        playlistId: String?,
        durationMs: Long?
    ): PlaybackSession? {
        val signatureTimestamp = (System.currentTimeMillis() / 86400000L).toInt()
        val eventTime = System.currentTimeMillis().toString()

        return try {
            val response: HttpResponse = api.client.request {
                endpointPath("player")
                attachAuthHeaders(eventTime)
                addApiHeadersWithAuthenticated()
                headers {
                    append("X-YouTube-Client-Name", "67")
                    append("X-YouTube-Client-Version", "1.20260304.03.00")
                }
                postWithBody(webRemixContext()) {
                    put("videoId", videoId)
                    put("cpn", cpn)
                    if (playlistId != null) {
                        put("playlistId", playlistId)
                    }
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                    put("playbackContext", buildJsonObject {
                        put("contentPlaybackContext", buildJsonObject {
                            put("html5Preference", "HTML5_PREF_WANTS")
                            put("signatureTimestamp", signatureTimestamp)
                        })
                    })
                }
            }

            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val playability = root["playabilityStatus"]?.jsonObject
            val status = playability?.get("status")?.jsonPrimitive?.contentOrNull

            println("PlaybackTracker: player response status = $status")

            val playbackTracking = root["playbackTracking"]?.jsonObject
            if (playbackTracking == null) {
                println("PlaybackTracker: No playbackTracking in response (status=$status, reason=${playability?.get("reason")})")
                return null
            }

            val fexp = root["streamingData"]?.jsonObject?.get("serverAbrStreamingUrl")
                ?.jsonPrimitive?.contentOrNull
                ?.let { Regex("fexp=([^&]+)").find(it)?.groupValues?.get(1) }

            fun cleanUrl(obj: JsonObject?): String? {
                val rawUrl = obj?.get("baseUrl")?.jsonPrimitive?.contentOrNull ?: return null
                val url = rawUrl.replace("https://s.youtube.com", "https://music.youtube.com")
                return if (fexp != null && !url.contains("fexp=")) {
                    if (url.contains("?")) "$url&fexp=$fexp" else "$url?fexp=$fexp"
                } else url
            }

            val playbackUrl = cleanUrl(playbackTracking["videostatsPlaybackUrl"]?.jsonObject)
            val atrUrl = cleanUrl(playbackTracking["atrUrl"]?.jsonObject)
            val watchtimeUrl = cleanUrl(playbackTracking["videostatsWatchtimeUrl"]?.jsonObject)

            val lengthSec = durationMs?.let { it / 1000f }
                ?: watchtimeUrl?.let { Regex("len=([^&]+)").find(it)?.groupValues?.get(1)?.toFloatOrNull() }
                ?: 180f

            println("PlaybackTracker: Found tracking URLs (playback=${playbackUrl != null}, atr=${atrUrl != null}, watchtime=${watchtimeUrl != null})")

            PlaybackSession(
                videoId = videoId,
                cpn = cpn,
                clientName = "WEB_REMIX",
                playbackUrl = playbackUrl,
                atrUrl = atrUrl,
                watchtimeUrl = watchtimeUrl,
                playlistId = playlistId,
                lengthSeconds = lengthSec
            )
        } catch (e: Exception) {
            println("PlaybackTracker fetchPlaybackTracking exception: ${e.message}")
            null
        }
    }

    private suspend fun runTelemetryLifecycle(session: PlaybackSession) {
        val playbackUrl = session.playbackUrl ?: return
        val cpn = session.cpn
        val eventTime = System.currentTimeMillis().toString()

        // 1. Initial playback ping (0s) - Registers CPN session on Google backend
        val pbResponse = runCatching {
            api.client.get(playbackUrl) {
                attachAuthHeaders(eventTime)
                url {
                    parameters.append("ver", "2")
                    parameters.append("c", session.clientName)
                    parameters.append("cpn", cpn)
                    if (session.playlistId != null) {
                        parameters.append("list", session.playlistId)
                        parameters.append("referrer", "https://music.youtube.com/playlist?list=${session.playlistId}")
                    }
                }
            }
        }.getOrNull()
        println("PlaybackTracker: 1. Playback ping status: ${pbResponse?.status?.value}")

        // 2. Audio tracking request (ATR) after 5 seconds - Confirms real audio streaming
        delay(5000L)
        session.atrUrl?.let { atrUrl ->
            val atrResponse = runCatching {
                api.client.post(atrUrl) {
                    attachAuthHeaders(System.currentTimeMillis().toString())
                    url {
                        parameters.append("cpn", cpn)
                        if (session.playlistId != null) {
                            parameters.append("list", session.playlistId)
                        }
                    }
                }
            }.getOrNull()
            println("PlaybackTracker: 2. ATR sent status: ${atrResponse?.status?.value}")
        }
    }

    private suspend fun send30sWatchtime(session: PlaybackSession) {
        val watchtimeUrl = session.watchtimeUrl ?: return
        val cpn = session.cpn
        val now = System.currentTimeMillis().toString()

        println("PlaybackTracker: 3. Song played for 30s in Echo! Sending watchtime qualification for ${session.videoId}")
        val response = runCatching {
            api.client.get(watchtimeUrl) {
                attachAuthHeaders(now)
                url {
                    parameters.append("ver", "2")
                    parameters.append("c", session.clientName)
                    parameters.append("cpn", cpn)
                    parameters.append("st", "0")
                    parameters.append("et", "30.0")
                    if (session.playlistId != null) {
                        parameters.append("list", session.playlistId)
                    }
                }
            }
        }.getOrNull()

        println("PlaybackTracker: 3. 30s watchtime qualification sent for ${session.videoId}, status: ${response?.status?.value}")
    }
}
