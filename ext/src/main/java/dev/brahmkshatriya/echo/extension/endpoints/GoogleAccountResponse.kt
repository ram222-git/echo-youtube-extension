package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.User
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class GoogleAccountResponse(
    val code: String? = null,
    val data: Data? = null,
    val actions: List<Action>? = null
) {

    private fun getAccountList(): List<Pair<String?, AccountItem>> {
        val actionList = actions ?: data?.actions ?: emptyList()
        val action = actionList.firstOrNull() ?: return emptyList()
        val menuRenderer = action.getMultiPageMenuAction?.menu?.multiPageMenuRenderer ?: return emptyList()
        val sections = menuRenderer.sections ?: emptyList()
        val section = sections.firstOrNull() ?: return emptyList()
        val accountSection = section.accountSectionListRenderer ?: return emptyList()
        val contents = accountSection.contents ?: emptyList()
        val headerEmail = accountSection.header?.googleAccountHeaderRenderer?.email?.text

        return contents.flatMap { sectionContent ->
            sectionContent.accountItemSectionRenderer?.contents?.mapNotNull { content ->
                content.accountItem
            } ?: emptyList()
        }.map {
            headerEmail to it
        }
    }

    fun getUsers(cookie: String, auth: String): List<User> {
        val parsedUsers = getAccountList().mapNotNull {
            val (email, item) = it
            if (item.isDisabled == true) return@mapNotNull null
            val avatarUrl = item.accountPhoto?.thumbnails?.lastOrNull()?.url
                ?: item.accountPhoto?.thumbnails?.firstOrNull()?.url
            val cover = avatarUrl?.toImageHolder()
            val supportedTokens = item.serviceEndpoint?.selectActiveIdentityEndpoint?.supportedTokens
            val signInUrl = supportedTokens
                ?.find { token -> token.accountSigninToken != null }
                ?.accountSigninToken?.signinUrl
            val channelId = supportedTokens
                ?.find { token -> token.offlineCacheKeyToken != null }
                ?.offlineCacheKeyToken?.clientCacheKey

            val userName = item.accountName?.text
                ?: item.channelHandle?.text
                ?: item.accountByline?.text

            val userEmail = email
                ?: item.accountByline?.text
                ?: item.channelHandle?.text

            val extrasMap = mutableMapOf(
                "auth" to auth,
                "cookie" to cookie,
                "isLoggedIn" to "true",
                "userService" to "youtube_music",
                "accountType" to "google"
            )
            if (!signInUrl.isNullOrEmpty()) {
                extrasMap["signInUrl"] = signInUrl
            }

            User(
                if (channelId != null) (if (channelId.startsWith("UC")) channelId else "UC$channelId") else "",
                userName ?: "YouTube Music User",
                cover,
                "YouTube Music",
                extrasMap
            )
        }

        return parsedUsers
    }

    companion object {
        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Comprehensive parser that tries typed deserialization first,
         * followed by dynamic recursive JsonElement extraction.
         */
        fun parseUsers(jsonString: String, cookie: String, auth: String): List<User> {
            val trimmed = if (jsonString.contains(")]}'")) {
                jsonString.substringAfter(")]}'").trim()
            } else {
                jsonString.trim()
            }

            if (trimmed.isEmpty() || trimmed.startsWith("<")) {
                return emptyList()
            }

            // 1. Try typed deserialization
            val typedResult = runCatching {
                val response = jsonParser.decodeFromString<GoogleAccountResponse>(trimmed)
                response.getUsers(cookie, auth)
            }.getOrNull()

            if (!typedResult.isNullOrEmpty() && typedResult.any { it.name != "YouTube Music User" }) {
                return typedResult
            }

            // 2. Try dynamic JsonElement tree walking
            val dynamicResult = runCatching {
                val element = jsonParser.parseToJsonElement(trimmed)
                extractUsersFromTree(element, cookie, auth)
            }.getOrNull()

            if (!dynamicResult.isNullOrEmpty() && dynamicResult.any { it.name != "YouTube Music User" }) {
                return dynamicResult
            }

            return typedResult ?: dynamicResult ?: emptyList()
        }

        private fun extractUsersFromTree(root: JsonElement, cookie: String, auth: String): List<User> {
            val results = mutableListOf<User>()

            fun searchKey(element: JsonElement, key: String): String? {
                if (element is JsonObject) {
                    element[key]?.jsonPrimitive?.contentOrNull?.let { return it }
                    for (v in element.values) {
                        val found = searchKey(v, key)
                        if (found != null) return found
                    }
                } else if (element is JsonArray) {
                    for (e in element) {
                        val found = searchKey(e, key)
                        if (found != null) return found
                    }
                }
                return null
            }

            fun walk(element: JsonElement) {
                if (element is JsonObject) {
                    val accountItem = element["accountItem"] as? JsonObject
                    val targetObj = accountItem ?: if (element.containsKey("accountName") && element.containsKey("accountPhoto")) element else null
                    if (targetObj != null) {
                        val name = targetObj["accountName"]?.jsonObject?.get("simpleText")?.jsonPrimitive?.contentOrNull
                            ?: targetObj["accountName"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                            ?: targetObj["title"]?.jsonObject?.get("simpleText")?.jsonPrimitive?.contentOrNull
                            ?: targetObj["title"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull

                        val email = targetObj["accountByline"]?.jsonObject?.get("simpleText")?.jsonPrimitive?.contentOrNull
                            ?: targetObj["channelHandle"]?.jsonObject?.get("simpleText")?.jsonPrimitive?.contentOrNull

                        val photoObj = targetObj["accountPhoto"] as? JsonObject
                        val thumbnails = photoObj?.get("thumbnails") as? JsonArray
                        val avatarUrl = thumbnails?.mapNotNull { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }?.lastOrNull()
                            ?: thumbnails?.mapNotNull { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }?.firstOrNull()

                        val channelId = searchKey(targetObj, "clientCacheKey") ?: searchKey(targetObj, "channelId")
                        val signInUrl = searchKey(targetObj, "signinUrl")

                        if (!name.isNullOrBlank() || avatarUrl != null) {
                            val extras = mutableMapOf(
                                "auth" to auth,
                                "cookie" to cookie,
                                "isLoggedIn" to "true",
                                "userService" to "youtube_music",
                                "accountType" to "google"
                            )
                            if (!signInUrl.isNullOrEmpty()) extras["signInUrl"] = signInUrl

                            results.add(
                                User(
                                    id = if (channelId != null) (if (channelId.startsWith("UC")) channelId else "UC$channelId") else "",
                                    name = name ?: "YouTube Music User",
                                    cover = avatarUrl?.toImageHolder(),
                                    subtitle = "YouTube Music",
                                    extras = extras
                                )
                            )
                        }
                    }
                    for (v in element.values) walk(v)
                } else if (element is JsonArray) {
                    for (e in element) walk(e)
                }
            }

            walk(root)
            return results
        }
    }

    @Serializable
    data class Data(
        val actions: List<Action>? = null
    )

    @Serializable
    data class Action(
        val getMultiPageMenuAction: GetMultiPageMenuAction? = null
    )

    @Serializable
    data class GetMultiPageMenuAction(
        val menu: Menu? = null
    )

    @Serializable
    data class Menu(
        val multiPageMenuRenderer: MultiPageMenuRenderer? = null
    )

    @Serializable
    data class MultiPageMenuRenderer(
        val header: MultiPageMenuRendererHeader? = null,
        val sections: List<Section>? = null,
        val footer: Footer? = null,
        val style: String? = null
    )

    @Serializable
    data class Footer(
        val multiPageMenuSectionRenderer: MultiPageMenuSectionRenderer? = null
    )

    @Serializable
    data class MultiPageMenuSectionRenderer(
        val items: List<Item>? = null
    )

    @Serializable
    data class Item(
        val compactLinkRenderer: ItemCompactLinkRenderer? = null
    )

    @Serializable
    data class ItemCompactLinkRenderer(
        val icon: Icon? = null,
        val title: Title? = null,
        val navigationEndpoint: PurpleNavigationEndpoint? = null,
        val style: String? = null
    )

    @Serializable
    data class Icon(
        val iconType: String? = null
    )

    @Serializable
    data class PurpleNavigationEndpoint(
        val commandMetadata: CommandMetadata? = null,
        val urlEndpoint: URLEndpoint? = null,
        val signOutEndpoint: SignOutEndpoint? = null
    )

    @Serializable
    data class CommandMetadata(
        val webCommandMetadata: WebCommandMetadata? = null
    )

    @Serializable
    data class WebCommandMetadata(
        val url: String? = null,
        val webPageType: String? = null,
        val rootVe: Long? = null
    )

    @Serializable
    data class SignOutEndpoint(
        val hack: Boolean? = null
    )

    @Serializable
    data class URLEndpoint(
        val url: String? = null
    )

    @Serializable
    data class Title(
        val simpleText: String? = null,
        val runs: List<Run>? = null
    ) {
        val text: String?
            get() = simpleText ?: runs?.joinToString("") { it.text }
    }

    @Serializable
    data class Run(
        val text: String
    )

    @Serializable
    data class MultiPageMenuRendererHeader(
        val simpleMenuHeaderRenderer: SimpleMenuHeaderRenderer? = null
    )

    @Serializable
    data class SimpleMenuHeaderRenderer(
        val backButton: BackButton? = null,
        val title: Title? = null
    )

    @Serializable
    data class BackButton(
        val buttonRenderer: ButtonRenderer? = null
    )

    @Serializable
    data class ButtonRenderer(
        val style: String? = null,
        val size: String? = null,
        val isDisabled: Boolean? = null,
        val icon: Icon? = null,
        val accessibility: Accessibility? = null,
        val accessibilityData: AccessibilityData? = null
    )

    @Serializable
    data class Accessibility(
        val label: String? = null
    )

    @Serializable
    data class AccessibilityData(
        val accessibilityData: Accessibility? = null
    )

    @Serializable
    data class Section(
        val accountSectionListRenderer: AccountSectionListRenderer? = null
    )

    @Serializable
    data class AccountSectionListRenderer(
        val contents: List<AccountSectionListRendererContent>? = null,
        val header: AccountSectionListRendererHeader? = null
    )

    @Serializable
    data class AccountSectionListRendererContent(
        val accountItemSectionRenderer: AccountItemSectionRenderer? = null
    )

    @Serializable
    data class AccountItemSectionRenderer(
        val contents: List<AccountItemSectionRendererContent>? = null
    )

    @Serializable
    data class AccountItemSectionRendererContent(
        val accountItem: AccountItem? = null,
        val compactLinkRenderer: ContentCompactLinkRenderer? = null
    )

    @Serializable
    data class AccountItem(
        val accountName: Title? = null,
        val accountPhoto: AccountPhoto? = null,
        val isSelected: Boolean? = null,
        val isDisabled: Boolean? = null,
        val hasChannel: Boolean? = null,
        val serviceEndpoint: ServiceEndpoint? = null,
        val accountByline: Title? = null,
        val unlimitedStatus: List<Title>? = null,
        val channelHandle: Title? = null
    )

    @Serializable
    data class AccountPhoto(
        val thumbnails: List<Thumbnail>? = null
    )

    @Serializable
    data class Thumbnail(
        val url: String? = null,
        val width: Long? = null,
        val height: Long? = null
    )

    @Serializable
    data class ServiceEndpoint(
        val selectActiveIdentityEndpoint: SelectActiveIdentityEndpoint? = null
    )

    @Serializable
    data class SelectActiveIdentityEndpoint(
        val supportedTokens: List<SupportedToken>? = null,
        val nextNavigationEndpoint: NextNavigationEndpoint? = null
    )

    @Serializable
    data class NextNavigationEndpoint(
        val commandMetadata: CommandMetadata? = null,
        val urlEndpoint: URLEndpoint? = null
    )

    @Serializable
    data class SupportedToken(
        val accountStateToken: AccountStateToken? = null,
        val offlineCacheKeyToken: OfflineCacheKeyToken? = null,
        val accountSigninToken: AccountSigninToken? = null,
        val datasyncIdToken: DatasyncIdToken? = null
    )

    @Serializable
    data class AccountSigninToken(
        val signinUrl: String? = null
    )

    @Serializable
    data class AccountStateToken(
        val hasChannel: Boolean? = null,
        val isMerged: Boolean? = null,
        val obfuscatedGaiaId: String? = null
    )

    @Serializable
    data class DatasyncIdToken(
        val datasyncIdToken: String? = null
    )

    @Serializable
    data class OfflineCacheKeyToken(
        val clientCacheKey: String? = null
    )

    @Serializable
    data class ContentCompactLinkRenderer(
        val title: Title? = null,
        val navigationEndpoint: FluffyNavigationEndpoint? = null
    )

    @Serializable
    data class FluffyNavigationEndpoint(
        val commandMetadata: CommandMetadata? = null,
        val signalNavigationEndpoint: SignalNavigationEndpoint? = null
    )

    @Serializable
    data class SignalNavigationEndpoint(
        val signal: String? = null
    )

    @Serializable
    data class AccountSectionListRendererHeader(
        val googleAccountHeaderRenderer: GoogleAccountHeaderRenderer? = null
    )

    @Serializable
    data class GoogleAccountHeaderRenderer(
        val name: Title? = null,
        val email: Title? = null
    )
}