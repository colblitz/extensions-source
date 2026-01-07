package eu.kanade.tachiyomi.extension.all.pixiv.api

import android.content.SharedPreferences
import eu.kanade.tachiyomi.extension.all.pixiv.PixivApiException
import eu.kanade.tachiyomi.extension.all.pixiv.PixivFilters
import eu.kanade.tachiyomi.extension.all.pixiv.PixivTarget
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import rx.Observable
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pixiv App API client implementation.
 * Uses Pixiv's official mobile app API endpoints (more stable, more features)
 * Requires refresh token for oauth
 */
internal class PixivAppApiClient(
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
    private val json: Json,
) : PixivApiClient {

    // oauth stuff

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var tokenExpiry: Long = 0

    private fun getAccessToken(): String {
        val token = cachedAccessToken
        if (token != null && System.currentTimeMillis() < tokenExpiry) {
            return token
        }

        val refreshToken = preferences.getString(PREF_REFRESH_TOKEN, "")!!
        if (refreshToken.isEmpty()) {
            throw PixivApiException(
                "App API requires a refresh token, see settings for more details.",
            )
        }

        return refreshAccessToken(refreshToken)
    }

    private fun refreshAccessToken(refreshToken: String): String {
        val timestamp = System.currentTimeMillis().toString()
        val hash = md5("$timestamp$HASH_SECRET")

        val response = client.newCall(
            POST(
                "$AUTH_URL/auth/token",
                // these are hardcoded to match pixivpy library
                headers = Headers.Builder()
                    .add("x-client-time", timestamp)
                    .add("x-client-hash", hash)
                    .add("app-os", APP_OS)
                    .add("app-os-version", APP_OS_VERSION)
                    .add("User-Agent", USER_AGENT)
                    .build(),
                body = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", CLIENT_ID)
                    .add("client_secret", CLIENT_SECRET)
                    .add("get_secure_url", "1")
                    .build(),
            ),
        ).execute()

        if (!response.isSuccessful) {
            throw PixivApiException(
                "Failed to refresh access token, your refresh token may be expired or invalid: ${response.code}",
            )
        }

        val auth = json.decodeFromString(PixivAuthResponse.serializer(), response.body.string())

        cachedAccessToken = auth.response.access_token
        tokenExpiry = System.currentTimeMillis() + (auth.response.expires_in * 1000L)

        // save new refresh token
        preferences.edit()
            .putString(PREF_REFRESH_TOKEN, auth.response.refresh_token)
            .apply()

        return auth.response.access_token
    }

    // interface methods

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        val offset = (page - 1) * 30
        val url = "$BASE_URL/v1/illust/ranking".toHttpUrl().newBuilder()
            .addQueryParameter("mode", "day_manga")
            .addQueryParameter("filter", "for_ios")
            .addQueryParameter("offset", offset.toString())
            .build()

        return apiGet(url).map(::parseIllustList)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        val offset = (page - 1) * 30
        val url = "$BASE_URL/v1/illust/new".toHttpUrl().newBuilder()
            .addQueryParameter("content_type", "manga")
            .addQueryParameter("filter", "for_ios")
            .addQueryParameter("offset", offset.toString())
            .build()

        return apiGet(url).map(::parseIllustList)
    }

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        val offset = (page - 1) * 30
        val pixivFilters = filters.list.filterIsInstance<PixivFilters>().firstOrNull()
            ?: PixivFilters()

        // deeplinks (direct URL or search prefixes - aid:123, user:456, sid:789)
        val target = PixivTarget.fromUri(query)
        if (target != null) {
            return fetchSearchMangaDeeplink(target)
        }

        // user filter
        if (pixivFilters.user.isNotBlank()) {
            return fetchSearchMangaByUser(pixivFilters.user.trim(), offset)
        }

        // keyword search
        if (query.isNotBlank()) {
            return fetchSearchMangaByKeyword(query, offset)
        }

        // default - no user, no keyword
        return fetchSearchMangaDefault(offset)
    }

    private fun fetchSearchMangaDeeplink(target: PixivTarget): Observable<MangasPage> {
        return when (target) {
            is PixivTarget.Illustration -> {
                apiGet(buildIllustDetailUrl("/artworks/${target.illustId}"))
                    .map(::parseSingleIllust)
            }
            is PixivTarget.User -> {
                // delegate to user search
                fetchSearchMangaByUser(target.userId, offset = 0)
            }
            is PixivTarget.Series -> {
                val url = "$BASE_URL/v1/illust/series".toHttpUrl().newBuilder()
                    .addQueryParameter("series_id", target.seriesId)
                    .addQueryParameter("filter", "for_ios")
                    .build()

                apiGet(url).map(::parseIllustList)
            }
        }
    }

    private fun fetchSearchMangaByUser(userInput: String, offset: Int): Observable<MangasPage> {
        val userId = userInput.toLongOrNull()

        // try detecting user id vs username - treat full numeric as userid, username otherwise
        return if (userId != null) {
            fetchSearchMangaByUserId(userId, offset)
        } else {
            fetchSearchMangaByUsername(userInput, offset)
        }
    }

    private fun fetchSearchMangaByUserId(userId: Long, offset: Int): Observable<MangasPage> {
        val url = "$BASE_URL/v1/user/illusts".toHttpUrl().newBuilder()
            .addQueryParameter("user_id", userId.toString())
            .addQueryParameter("type", "manga")
            .addQueryParameter("filter", "for_ios")
            .addQueryParameter("offset", offset.toString())
            .build()

        return apiGet(url).map(::parseIllustList)
    }

    private fun fetchSearchMangaByUsername(username: String, offset: Int): Observable<MangasPage> {
        val url = "$BASE_URL/v1/search/user".toHttpUrl().newBuilder()
            .addQueryParameter("word", username)
            .addQueryParameter("filter", "for_ios")
            .build()

        return apiGet(url).flatMap { response ->
            val result = json.decodeFromString(PixivUserSearchResponse.serializer(), response.body.string())
            if (result.user_previews.isEmpty()) {
                return@flatMap rx.Observable.just(MangasPage(emptyList(), false))
            }

            // we could probably do some fancy to display non-exact matches as "mangas", but just require exact match for now
            val exactMatch = result.user_previews.find { preview ->
                preview.user.name.equals(username, ignoreCase = true) ||
                    preview.user.account.equals(username, ignoreCase = true)
            }

            if (exactMatch != null) {
                fetchSearchMangaByUserId(exactMatch.user.id, offset)
            } else {
                rx.Observable.just(MangasPage(emptyList(), false))
            }
        }
    }

    private fun fetchSearchMangaByKeyword(keyword: String, offset: Int): Observable<MangasPage> {
        val url = "$BASE_URL/v1/search/illust".toHttpUrl().newBuilder()
            .addQueryParameter("word", keyword)
            .addQueryParameter("search_target", "partial_match_for_tags")
            .addQueryParameter("sort", "date_desc")
            .addQueryParameter("filter", "for_ios")
            .addQueryParameter("offset", offset.toString())
            .build()

        return apiGet(url).map(::parseIllustList)
    }

    private fun fetchSearchMangaDefault(offset: Int): Observable<MangasPage> {
        val url = "$BASE_URL/v1/search/illust".toHttpUrl().newBuilder()
            .addQueryParameter("word", "漫画")
            .addQueryParameter("search_target", "partial_match_for_tags")
            .addQueryParameter("sort", "date_desc")
            .addQueryParameter("filter", "for_ios")
            .addQueryParameter("offset", offset.toString())
            .build()

        return apiGet(url).map(::parseIllustList)
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return apiGet(buildIllustDetailUrl(manga.url))
            .map { response ->
                val result = json.decodeFromString(PixivIllustDetailResponse.serializer(), response.body.string())
                result.illust.toSManga()
            }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return apiGet(buildIllustDetailUrl(manga.url))
            .map { response ->
                val result = json.decodeFromString(PixivIllustDetailResponse.serializer(), response.body.string())
                val illust = result.illust

                // single illustration = single chapter
                listOf(
                    SChapter.create().apply {
                        url = "/artworks/${illust.id}"
                        name = if (illust.series != null) {
                            "${illust.series.title} - ${illust.title}"
                        } else {
                            illust.title
                        }
                        date_upload = parseDate(illust.create_date)
                        chapter_number = 1f
                    },
                )
            }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return apiGet(buildIllustDetailUrl(chapter.url))
            .map { response ->
                val result = json.decodeFromString(PixivIllustDetailResponse.serializer(), response.body.string())
                val illust = result.illust

                when {
                    // multi-page work
                    illust.meta_pages.isNotEmpty() -> {
                        illust.meta_pages.mapIndexed { index, _ ->
                            Page(index, imageUrl = getImageUrl(illust, index))
                        }
                    }
                    // single-page work
                    else -> {
                        listOf(Page(0, imageUrl = getImageUrl(illust, 0)))
                    }
                }
            }
    }

    // helper methods

    private fun apiHeaders(): Headers.Builder {
        return Headers.Builder()
            .add("Authorization", "Bearer ${getAccessToken()}")
            .add("User-Agent", USER_AGENT)
            .add("app-os", APP_OS)
            .add("app-os-version", APP_OS_VERSION)
            .add("Referer", "https://www.pixiv.net/")
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun getImageQuality(): String {
        return preferences.getString(PREF_IMAGE_QUALITY, "original") ?: "original"
    }

    private fun getImageUrl(illust: PixivAppIllust, pageIndex: Int = 0): String {
        val quality = getImageQuality()

        return when {
            // multi-page
            illust.meta_pages.isNotEmpty() -> {
                val page = illust.meta_pages.getOrNull(pageIndex)
                    ?: throw PixivApiException("Page $pageIndex not found in illust ${illust.id}")

                when (quality) {
                    "preview" -> page.image_urls.medium
                    "large" -> page.image_urls.large
                    else -> page.image_urls.original
                }
            }
            // single-page
            illust.meta_single_page.original_image_url != null -> {
                when (quality) {
                    "preview" -> illust.image_urls.medium
                    "large" -> illust.image_urls.large
                    else -> illust.meta_single_page.original_image_url
                }
            }
            // default
            else -> illust.image_urls.large
        }
    }

    private fun PixivAppIllust.toSManga(): SManga {
        return SManga.create().apply {
            url = "/artworks/$id"
            title = this@toSManga.title
            thumbnail_url = image_urls.large
            author = user.name
            artist = user.name
            description = buildString {
                append(caption.replace("<br />", "\n"))
                if (tags.isNotEmpty()) {
                    append("\n\nTags: ")
                    append(tags.joinToString(", ") { it.translated_name ?: it.name })
                }
                append("\n\nViews: $total_view")
                append("\nBookmarks: $total_bookmarks")
            }
            status = SManga.COMPLETED
        }
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            format.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // PixivAppApiResponse -> MangasPage
    private fun parseIllustList(response: okhttp3.Response): MangasPage {
        val result = json.decodeFromString(PixivAppApiResponse.serializer(), response.body.string())
        val mangas = result.illusts.map { it.toSManga() }
        val hasNextPage = result.next_url != null
        return MangasPage(mangas, hasNextPage)
    }

    // PixivIllustDetailResponse -> MangasPage
    private fun parseSingleIllust(response: okhttp3.Response): MangasPage {
        val result = json.decodeFromString(PixivIllustDetailResponse.serializer(), response.body.string())
        return MangasPage(listOf(result.illust.toSManga()), hasNextPage = false)
    }

    // url can be manga or chapter url
    private fun buildIllustDetailUrl(url: String): okhttp3.HttpUrl {
        val illustId = url.removePrefix("/artworks/")
        return "$BASE_URL/v1/illust/detail".toHttpUrl().newBuilder()
            .addQueryParameter("illust_id", illustId)
            .build()
    }

    private fun apiGet(url: okhttp3.HttpUrl): Observable<okhttp3.Response> {
        return client.newCall(GET(url, apiHeaders().build()))
            .asObservable()
    }

    companion object {
        private const val BASE_URL = "https://app-api.pixiv.net"
        private const val AUTH_URL = "https://oauth.secure.pixiv.net"

        // OAuth credentials and headers for Pixiv App API taken from pixivpy library
        // https://github.com/upbit/pixivpy/blob/4f2e9ea7fff6247d9f5bfe5a862e92c5dfe3b6dd/pixivpy3/api.py#L18
        private const val CLIENT_ID = "MOBrBDS8blbauoSck0ZfDbtuzpyT"
        private const val CLIENT_SECRET = "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj"
        private const val HASH_SECRET = "28c1fdd170a5204386cb1313c7077b34f83e4aaf4aa829ce78c231e05b0bae2c"

        // https://github.com/upbit/pixivpy/blob/4f2e9ea7fff6247d9f5bfe5a862e92c5dfe3b6dd/pixivpy3/api.py#L133
        private const val USER_AGENT = "PixivIOSApp/7.13.3 (iOS 14.6; iPhone13,2)"
        private const val APP_OS = "ios"
        private const val APP_OS_VERSION = "14.6"

        const val PREF_REFRESH_TOKEN = "pref_refresh_token"
        const val PREF_IMAGE_QUALITY = "pref_image_quality"
    }
}
