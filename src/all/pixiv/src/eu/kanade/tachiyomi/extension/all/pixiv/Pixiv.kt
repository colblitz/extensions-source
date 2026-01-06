package eu.kanade.tachiyomi.extension.all.pixiv

import eu.kanade.tachiyomi.extension.all.pixiv.api.PixivApiClient
import eu.kanade.tachiyomi.extension.all.pixiv.api.PixivWebApiClient
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class Pixiv(override val lang: String) : HttpSource() {
    override val name = "Pixiv"
    override val baseUrl = "https://www.pixiv.net"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder().add("Referer", "$baseUrl/")

    // Public wrappers for API clients to access HttpSource functionality
    fun getHeaders(): Headers.Builder = headersBuilder()
    fun getHttpClient() = client
    fun getBase() = baseUrl

    // API client - currently only Web API, App API will be added later
    private val apiClient: PixivApiClient by lazy {
        PixivWebApiClient(this, json, lang)
    }

    // Delegate all methods to the API client
    override fun fetchPopularManga(page: Int) = apiClient.fetchPopularManga(page)
    override fun fetchLatestUpdates(page: Int) = apiClient.fetchLatestUpdates(page)
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList) = apiClient.fetchSearchManga(page, query, filters)
    override fun fetchMangaDetails(manga: SManga) = apiClient.fetchMangaDetails(manga)
    override fun fetchChapterList(manga: SManga) = apiClient.fetchChapterList(manga)
    override fun fetchPageList(chapter: SChapter) = apiClient.fetchPageList(chapter)

    override fun getFilterList() = FilterList(PixivFilters())

    override fun chapterListParse(response: Response): List<SChapter> =
        throw UnsupportedOperationException()

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage =
        throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException()

    override fun mangaDetailsParse(response: Response): SManga =
        throw UnsupportedOperationException()

    override fun pageListParse(response: Response): List<Page> =
        throw UnsupportedOperationException()

    override fun popularMangaParse(response: Response): MangasPage =
        throw UnsupportedOperationException()

    override fun popularMangaRequest(page: Int): Request =
        throw UnsupportedOperationException()

    override fun searchMangaParse(response: Response): MangasPage =
        throw UnsupportedOperationException()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw UnsupportedOperationException()
}
