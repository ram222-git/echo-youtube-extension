package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.extension.streaming.SpoofClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoofClientTest {

    @Test
    fun testAllSpoofClientsHaveValidConfig() {
        val clients = SpoofClient.entries
        assertTrue(clients.isNotEmpty())

        for (client in clients) {
            assertTrue(client.id.isNotBlank())
            assertTrue(client.displayName.isNotBlank())
            assertTrue(client.clientName.isNotBlank())
            assertTrue(client.clientVersion.isNotBlank())
            assertTrue(client.userAgent.isNotBlank())
            assertTrue(client.origin.startsWith("https://"))
            assertTrue(client.clientNumber.isNotBlank())

            val context = client.buildClientContext("test_visitor_id")
            val clientObj = context["client"]?.jsonObject
            assertNotNull(clientObj)
            assertEquals(client.clientName, clientObj!!["clientName"]?.jsonPrimitive?.content)
            assertEquals(client.clientVersion, clientObj["clientVersion"]?.jsonPrimitive?.content)
            assertEquals("test_visitor_id", clientObj["visitorData"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun testSpoofClientLookupById() {
        assertEquals(SpoofClient.ANDROID_VR, SpoofClient.fromId("android_vr"))
        assertEquals(SpoofClient.ANDROID_VR, SpoofClient.fromId("ANDROID_VR"))
        assertEquals(SpoofClient.IOS, SpoofClient.fromId("ios"))
        assertEquals(SpoofClient.TV, SpoofClient.fromId("tv"))
        assertEquals(SpoofClient.MWEB, SpoofClient.fromId("mweb"))
        assertEquals(SpoofClient.WEB_REMIX, SpoofClient.fromId("web_remix"))
        assertEquals(null, SpoofClient.fromId("auto"))
        assertEquals(null, SpoofClient.fromId("unknown_client"))
    }

    @Test
    fun testExtensionExposesSpoofClientSetting() = runTest {
        val extension = YoutubeExtension()
        val settings = extension.getSettingItems()

        val spoofSetting = settings.filterIsInstance<SettingList>().firstOrNull { it.key == "spoof_stream_client" }
        assertNotNull("spoof_stream_client SettingList should be registered", spoofSetting)
        assertEquals("Spoof Video Streams Client", spoofSetting!!.title)
        assertEquals(0, spoofSetting.defaultEntryIndex)
        assertEquals("auto", spoofSetting.entryValues[0])
        assertTrue(spoofSetting.entryValues.contains("android_vr"))
        assertTrue(spoofSetting.entryValues.contains("ios"))
        assertTrue(spoofSetting.entryValues.contains("tv"))
        assertTrue(spoofSetting.entryValues.contains("mweb"))
        assertTrue(spoofSetting.entryValues.contains("web_remix"))
    }

    @Test
    fun testSettingsChangeUpdatesSpoofClient() = runTest {
        val extension = YoutubeExtension()
        val mockSettings = MockedSettings()

        mockSettings.putString("spoof_stream_client", "android_vr")
        extension.setSettings(mockSettings)

        assertEquals("android_vr", mockSettings.getString("spoof_stream_client"))
        assertEquals(SpoofClient.ANDROID_VR, SpoofClient.fromId(mockSettings.getString("spoof_stream_client")))

        mockSettings.putString("spoof_stream_client", "tv")
        extension.onSettingsChanged(mockSettings, "spoof_stream_client")

        assertEquals("tv", mockSettings.getString("spoof_stream_client"))
        assertEquals(SpoofClient.TV, SpoofClient.fromId(mockSettings.getString("spoof_stream_client")))
    }

    @Test
    fun testAndroidVrResolutionDoesNotFail() = runTest {
        val extension = YoutubeExtension()
        val mockSettings = MockedSettings()
        mockSettings.putString("spoof_stream_client", "android_vr")
        mockSettings.putBoolean("enable_video", true)
        extension.setSettings(mockSettings)

        val track = extension.loadTrack(dev.brahmkshatriya.echo.common.models.Track("qeFt3fdsydA", ""), false)
        val audioStreamable = track.streamables.firstOrNull { it.extras["type"] == "audio" }
            ?: track.streamables.first()
        val audioMedia = extension.loadStreamableMedia(audioStreamable, false)
        assertTrue(audioMedia is dev.brahmkshatriya.echo.common.models.Streamable.Media.Server)
        val audioServer = audioMedia as dev.brahmkshatriya.echo.common.models.Streamable.Media.Server
        assertTrue(audioServer.sources.isNotEmpty())

        val videoStreamable = track.streamables.firstOrNull { it.extras["type"] == "video" }
        assertNotNull(videoStreamable)
        val videoMedia = extension.loadStreamableMedia(videoStreamable!!, false)
        assertTrue(videoMedia is dev.brahmkshatriya.echo.common.models.Streamable.Media.Server)
        val videoServer = videoMedia as dev.brahmkshatriya.echo.common.models.Streamable.Media.Server
        assertTrue(videoServer.sources.isNotEmpty())
    }
}
