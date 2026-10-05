package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.extension.RealNewPipeVideoFormatsEndpoint
import dev.brahmkshatriya.echo.extension.utils.CookieParser
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class EchoVideoEndpoint(override val api: YoutubeiApi) : ApiEndpoint() {

    private suspend fun request(
        context: JsonObject,
        id: String,
        playlist: String? = null
    ): HttpResponse {
        return api.client.request {
            endpointPath("player")
            if (api.user_auth_state != null) {
                addApiHeadersWithAuthenticated()
            } else {
                addApiHeadersWithoutAuthentication()
            }
            postWithBody(context) {
                put("videoId", id)
                put("playlistId", playlist)
            }
        }
    }

    suspend fun getVideo(resolve: Boolean, id: String, playlist: String? = null) = coroutineScope {
        val web = async {
            if (resolve) request(webRemix, id, playlist)
                .body<YoutubeFormatResponse>().videoDetails?.musicVideoType
            else null
        }
        val ios = request(iosContext(), id, playlist).body<YoutubeFormatResponse>()
        ios to web.await()
    }

    private fun iosContext() = buildJsonObject {
        put("context", buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", "IOS")
                put("clientVersion", "19.34.2")
                put("visitorData", api.visitor_id)
            })
        })
    }

    private val webRemix = buildJsonObject {
        put("context", buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20220606.03.00")
            })
        })
    }

    private val okHttpClient by lazy { OkHttpClient() }

    suspend fun getRestrictedVideo(id: String, cookie: String? = null): YoutubeFormatResponse? {
        val userCookie = cookie ?: RealNewPipeVideoFormatsEndpoint.globalCookie ?: api.user_auth_state?.headers?.get("cookie")
        println("EchoVideoEndpoint: getRestrictedVideo for $id. Cookie present: ${!userCookie.isNullOrBlank()}, length: ${userCookie?.length ?: 0}")
        if (!userCookie.isNullOrBlank()) {
            val cookieMap = CookieParser.parse(userCookie)
            println("EchoVideoEndpoint: Cookie keys (${cookieMap.size}): ${cookieMap.keys}")
        } else {
            println("EchoVideoEndpoint: WARNING - No cookie found in globalCookie or user_auth_state! Age-restricted videos require login cookies.")
        }
        val remixResp = tryWebRemix(id, userCookie)
        if (remixResp != null) return remixResp

        println("EchoVideoEndpoint: tryWebRemix failed to return formats, falling back to tryWebCreator...")
        val creatorResp = tryWebCreator(id, userCookie)
        if (creatorResp != null) return creatorResp

        println("EchoVideoEndpoint: tryWebCreator failed, falling back to tryTvClient...")
        return tryTvClient(id, userCookie)
    }

    private suspend fun tryWebRemix(id: String, cookie: String?): YoutubeFormatResponse? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject {
                put("videoId", id)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("playbackContext", buildJsonObject {
                    put("contentPlaybackContext", buildJsonObject {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        put("signatureTimestamp", 20723)
                    })
                })
                put("context", buildJsonObject {
                    put("client", buildJsonObject {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20260928.13.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }

            val cookieMap = if (!cookie.isNullOrBlank()) CookieParser.parse(cookie) else emptyMap()
            val authHeader = if (cookieMap.isNotEmpty()) {
                CookieParser.generateDynamicAuthHeader(cookieMap, "https://music.youtube.com")
            } else ""

            println("tryWebRemix: Sending request for $id. Cookie length: ${cookie?.length ?: 0}, AuthHeader present: ${authHeader.isNotBlank()}")

            val requestBuilder = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/player")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36")
                .header("Content-Type", "application/json")
                .header("Origin", "https://music.youtube.com")
                .header("X-Origin", "https://music.youtube.com")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", "1.20260928.13.00")
                .header("X-Goog-AuthUser", "0")
                .header("X-Youtube-Bootstrap-Logged-In", "true")

            if (!cookie.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookie)
            }
            if (authHeader.isNotEmpty()) {
                requestBuilder.header("Authorization", authHeader)
            }

            val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
            requestBuilder.post(requestBody)

            val (bodyString, setCookies) = okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                response.body?.string().orEmpty() to response.headers("Set-Cookie")
            }

            if (setCookies.isNotEmpty()) {
                RealNewPipeVideoFormatsEndpoint.cookieUpdateListener?.invoke(setCookies)
            }

            val resp = api.json.decodeFromString<YoutubeFormatResponse>(bodyString)
            println("tryWebRemix resp: status=${resp.playabilityStatus?.status}, reason=${resp.playabilityStatus?.reason}, formats=${resp.streamingData?.formats?.size ?: 0}, adaptive=${resp.streamingData?.adaptiveFormats?.size ?: 0}")
            val formats = resp.streamingData?.formats.orEmpty() + resp.streamingData?.adaptiveFormats.orEmpty()
            if (formats.isNotEmpty()) resp else null
        }.onFailure {
            println("tryWebRemix failed: ${it.javaClass.simpleName} - ${it.message}")
            it.printStackTrace()
        }.getOrNull()
    }

    private suspend fun tryWebCreator(id: String, cookie: String?): YoutubeFormatResponse? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject {
                put("videoId", id)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("playbackContext", buildJsonObject {
                    put("contentPlaybackContext", buildJsonObject {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        put("signatureTimestamp", 20723)
                    })
                })
                put("context", buildJsonObject {
                    put("client", buildJsonObject {
                        put("clientName", "WEB_CREATOR")
                        put("clientVersion", "1.20261001.00.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }

            val cookieMap = if (!cookie.isNullOrBlank()) CookieParser.parse(cookie) else emptyMap()
            val authHeader = if (cookieMap.isNotEmpty()) {
                CookieParser.generateDynamicAuthHeader(cookieMap, "https://www.youtube.com")
            } else ""

            val requestBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36")
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.youtube.com")
                .header("X-Origin", "https://www.youtube.com")
                .header("X-YouTube-Client-Name", "62")
                .header("X-YouTube-Client-Version", "1.20261001.00.00")
                .header("X-Goog-AuthUser", "0")
                .header("X-Youtube-Bootstrap-Logged-In", "true")

            if (!cookie.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookie)
            }
            if (authHeader.isNotEmpty()) {
                requestBuilder.header("Authorization", authHeader)
            }

            val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
            requestBuilder.post(requestBody)

            val (bodyString, setCookies) = okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                response.body?.string().orEmpty() to response.headers("Set-Cookie")
            }

            if (setCookies.isNotEmpty()) {
                RealNewPipeVideoFormatsEndpoint.cookieUpdateListener?.invoke(setCookies)
            }

            val resp = api.json.decodeFromString<YoutubeFormatResponse>(bodyString)
            println("tryWebCreator resp: status=${resp.playabilityStatus?.status}, reason=${resp.playabilityStatus?.reason}, formats=${resp.streamingData?.formats?.size}, adaptive=${resp.streamingData?.adaptiveFormats?.size}")
            val formats = resp.streamingData?.formats.orEmpty() + resp.streamingData?.adaptiveFormats.orEmpty()
            if (formats.isNotEmpty()) resp else null
        }.onFailure {
            println("tryWebCreator failed: ${it.javaClass.simpleName} - ${it.message}")
            it.printStackTrace()
        }.getOrNull()
    }

    private suspend fun tryTvClient(id: String, cookie: String?): YoutubeFormatResponse? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject {
                put("videoId", id)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("context", buildJsonObject {
                    put("client", buildJsonObject {
                        put("clientName", "TVHTML5")
                        put("clientVersion", "7.20230405.08.01")
                        put("platform", "TV")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }

            val requestBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
                .header("Content-Type", "application/json")
                .header("X-YouTube-Client-Name", "85")
                .header("X-YouTube-Client-Version", "7.20230405.08.01")
                .header("Origin", "https://www.youtube.com")
                .header("X-Origin", "https://www.youtube.com")

            if (!cookie.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookie)
            }

            val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
            requestBuilder.post(requestBody)

            val bodyString = okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                response.body?.string().orEmpty()
            }
            val resp = api.json.decodeFromString<YoutubeFormatResponse>(bodyString)
            println("tryTvClient resp: status=${resp.playabilityStatus?.status}, reason=${resp.playabilityStatus?.reason}, formats=${resp.streamingData?.formats?.size}, adaptive=${resp.streamingData?.adaptiveFormats?.size}")
            val formats = resp.streamingData?.formats.orEmpty() + resp.streamingData?.adaptiveFormats.orEmpty()
            if (formats.isNotEmpty()) resp else null
        }.getOrNull()
    }
}

@Serializable
data class YoutubeFormatResponse(
    val playabilityStatus: PlayabilityStatus? = null,
    val streamingData: StreamingData? = null,
    val videoDetails: VideoDetails? = null
)

@Serializable
data class PlayabilityStatus(
    val status: String,
    val reason: String? = null,
    val playableInEmbed: Boolean? = null
)

@Serializable
data class StreamingData(
    val expiresInSeconds: String? = null,
    val hlsManifestUrl: String? = null,
    val dashManifestUrl: String? = null,
    val formats: List<AdaptiveFormat> = emptyList(),
    val adaptiveFormats: List<AdaptiveFormat> = emptyList()
)

@Serializable
data class AdaptiveFormat(
    val itag: Long? = null,
    val url: String? = null,
    val signatureCipher: String? = null,
    val cipher: String? = null,
    val mimeType: String,
    val bitrate: Int,
    val width: Long? = null,
    val height: Long? = null,
    val initRange: Range? = null,
    val indexRange: Range? = null,
    val lastModified: String? = null,
    val contentLength: String? = null,
    val quality: String? = null,
    val fps: Long? = null,
    val qualityLabel: String? = null,
    val projectionType: String? = null,
    val averageBitrate: Long? = null,
    val approxDurationMs: String? = null,
    val colorInfo: ColorInfo? = null,
    val highReplication: Boolean? = null,
    val audioQuality: String? = null,
    val audioSampleRate: String? = null,
    val audioChannels: Long? = null,
    val loudnessDb: Double? = null
)

@Serializable
data class ColorInfo(
    val primaries: String? = null,
    val transferCharacteristics: String? = null,
    val matrixCoefficients: String? = null
)

@Serializable
data class Range(
    val start: String? = null,
    val end: String? = null
)

@Serializable
data class VideoDetails(
    val videoId: String, 
    val title: String? = null,
    val lengthSeconds: String? = null,
    val channelId: String? = null,
    val isOwnerViewing: Boolean? = null,
    val isCrawlable: Boolean? = null,
    val allowRatings: Boolean? = null,
    val viewCount: String? = null,
    val author: String? = null,
    val isPrivate: Boolean? = null,
    val isUnpluggedCorpus: Boolean? = null,
    val musicVideoType: String? = null,
    val isLiveContent: Boolean? = null,
    val shortDescription: String? = null,
)