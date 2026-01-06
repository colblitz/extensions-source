package eu.kanade.tachiyomi.extension.all.pixiv.api

import eu.kanade.tachiyomi.extension.all.pixiv.PixivApiException
import eu.kanade.tachiyomi.extension.all.pixiv.PixivApiResponse
import eu.kanade.tachiyomi.extension.all.pixiv.PixivFilters
import eu.kanade.tachiyomi.extension.all.pixiv.PixivIllust
import eu.kanade.tachiyomi.extension.all.pixiv.PixivIllustDetails
import eu.kanade.tachiyomi.extension.all.pixiv.PixivIllustPage
import eu.kanade.tachiyomi.extension.all.pixiv.PixivIllustsDetails
import eu.kanade.tachiyomi.extension.all.pixiv.PixivRankings
import eu.kanade.tachiyomi.extension.all.pixiv.PixivResults
import eu.kanade.tachiyomi.extension.all.pixiv.PixivSearchResultSeries
import eu.kanade.tachiyomi.extension.all.pixiv.PixivSeries
import eu.kanade.tachiyomi.extension.all.pixiv.PixivSeriesContents
import eu.kanade.tachiyomi.extension.all.pixiv.PixivSeriesDetails
import eu.kanade.tachiyomi.extension.all.pixiv.PixivTarget
import eu.kanade.tachiyomi.extension.all.pixiv.countUp
import eu.kanade.tachiyomi.extension.all.pixiv.lruCached
import eu.kanade.tachiyomi.extension.all.pixiv.parseSMangaUrl
import eu.kanade.tachiyomi.extension.all.pixiv.truncateToList
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import rx.Observable

/**
 * Pixiv Web API client implementation.
 * Uses Pixiv's web/touch API endpoints.
 * This is the current/default implementation.
 */
class PixivWebApiClient(
    private val source: eu.kanade.tachiyomi.extension.all.pixiv.Pixiv,
    private val json: Json,
    private val lang: String,
) : PixivApiClient {

    private val client get() = source.getHttpClient()
    private val baseUrl get() = source.getBase()

    private fun headersBuilder(): Headers.Builder = source.getHeaders()

    // Extension functions to match HttpSource behavior
    // Original implementation: https://github.com/mihonapp/mihon/blob/master/source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/online/HttpSource.kt
    // Simplified here since we only pass relative URLs (no scheme/domain stripping needed)
    private fun SManga.setUrlWithoutDomain(url: String) {
        this.url = url
    }

    private fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = url
    }

    private open inner class HttpCall(href: String?) {
        val url: HttpUrl.Builder = baseUrl.toHttpUrl()
            .run { href?.let { newBuilder(it)!! } ?: newBuilder() }

        val request: Request.Builder = Request.Builder()
            .headers(headersBuilder().build())

        fun execute(): Response =
            client.newCall(request.url(url.build()).build()).execute()
    }

    private inner class ApiCall(href: String?) : HttpCall(href) {
        init {
            url.addEncodedQueryParameter("lang", lang)
            request.addHeader("Accept", "application/json")
        }

        inline fun <reified T> executeApi(): Result<T> {
            val resp = json.decodeFromString<PixivApiResponse>(execute().body.string())
            if (resp.error) {
                return Result.failure(PixivApiException(resp.message))
            }
            return Result.success(json.decodeFromJsonElement<T>(resp.body!!))
        }
    }

    // Popular Manga
    private var popularMangaNextPage = 1
    private lateinit var popularMangaIterator: Iterator<SManga>

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        if (page == 1) {
            popularMangaIterator = sequence {
                val call = ApiCall("/touch/ajax/ranking/illust?mode=daily&type=manga")

                for (p in countUp(start = 1)) {
                    call.url.setEncodedQueryParameter("page", p.toString())

                    val entries = call.executeApi<PixivRankings>().getOrThrow().ranking!!
                    if (entries.isEmpty()) break

                    val detailsCall = ApiCall("/touch/ajax/illust/details/many")
                    entries.forEach { detailsCall.url.addEncodedQueryParameter("illust_ids[]", it.illustId!!) }

                    detailsCall.executeApi<PixivIllustsDetails>().getOrThrow().illust_details!!.forEach { yield(it) }
                }
            }
                .toSManga()
                .iterator()

            popularMangaNextPage = 2
        } else {
            require(page == popularMangaNextPage++)
        }

        val mangas = popularMangaIterator.truncateToList(50)
        return Observable.just(MangasPage(mangas, hasNextPage = mangas.isNotEmpty()))
    }

    // Latest Updates
    private var latestMangaNextPage = 1
    private lateinit var latestMangaIterator: Iterator<SManga>

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        if (page == 1) {
            latestMangaIterator = sequence {
                val call = ApiCall("/touch/ajax/latest?type=manga")

                for (p in countUp(start = 1)) {
                    call.url.setEncodedQueryParameter("p", p.toString())

                    val illusts = call.executeApi<PixivResults>().getOrThrow().illusts!!
                    if (illusts.isEmpty()) break

                    for (illust in illusts) {
                        if (illust.is_ad_container == 1) continue
                        yield(illust)
                    }
                }
            }
                .toSManga()
                .iterator()

            latestMangaNextPage = 2
        } else {
            require(page == latestMangaNextPage++)
        }

        val mangas = latestMangaIterator.truncateToList(50).toList()
        return Observable.just(MangasPage(mangas, hasNextPage = mangas.isNotEmpty()))
    }

    // Search
    private var searchNextPage = 1
    private var searchHash: Int? = null
    private lateinit var searchIterator: Iterator<SManga>

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        val target = PixivTarget.fromUri(query) /*?: PixivTarget.fromSearchQuery(query)*/

        val singleResult = { manga: SManga? ->
            Observable.just(
                MangasPage(
                    if (manga != null) {
                        listOf(manga)
                    } else {
                        emptyList()
                    },
                    hasNextPage = false,
                ),
            )
        }

        // Deeplink selection of specific IDs: simply fetch the single object and return
        when (target) {
            is PixivTarget.Illustration ->
                singleResult(getIllustCached(target.illustId)?.toSManga())
            is PixivTarget.Series -> {
                val series = ApiCall("/touch/ajax/illust/series/${target.seriesId}")
                    .executeApi<PixivSeriesDetails>().getOrNull()?.series
                singleResult(series?.toSManga())
            }
            else -> null
        }?.let { return it }

        val pixivFilters = filters.list as PixivFilters
        val hash = Pair(query, pixivFilters.toList()).hashCode()

        if (hash != searchHash || page == 1) {
            searchHash = hash

            lateinit var searchSequence: Sequence<PixivIllust>
            lateinit var predicates: List<(PixivIllust) -> Boolean>

            if (target is PixivTarget.User) {
                searchSequence = makeUserIdIllustSearchSequence(
                    id = target.userId,
                    type = pixivFilters.type,
                )

                predicates = buildList {
                    pixivFilters.makeTagsPredicate()?.let(::add)
                    pixivFilters.makeRatingPredicate()?.let(::add)
                }
            } else if (query.isNotBlank()) {
                searchSequence = makeIllustSearchSequence(
                    word = query,
                    order = pixivFilters.order,
                    mode = pixivFilters.rating,
                    sMode = "s_tc",
                    type = pixivFilters.type,
                    dateBefore = pixivFilters.dateBefore.ifBlank { null },
                    dateAfter = pixivFilters.dateAfter.ifBlank { null },
                )

                predicates = buildList {
                    pixivFilters.makeTagsPredicate()?.let(::add)
                    pixivFilters.makeUsersPredicate()?.let(::add)
                }
            } else if (pixivFilters.users.isNotBlank()) {
                searchSequence = makeUserIllustSearchSequence(
                    nick = pixivFilters.users,
                    type = pixivFilters.type,
                )

                predicates = buildList {
                    pixivFilters.makeTagsPredicate()?.let(::add)
                    pixivFilters.makeRatingPredicate()?.let(::add)
                }
            } else {
                searchSequence = makeIllustSearchSequence(
                    word = pixivFilters.tags.ifBlank { "漫画" },
                    order = pixivFilters.order,
                    mode = pixivFilters.rating,
                    sMode = pixivFilters.searchMode,
                    type = pixivFilters.type,
                    dateBefore = pixivFilters.dateBefore.ifBlank { null },
                    dateAfter = pixivFilters.dateAfter.ifBlank { null },
                )

                predicates = emptyList()
            }

            if (predicates.isNotEmpty()) {
                searchSequence = searchSequence.filter { predicates.all { p -> p(it) } }
            }

            searchIterator = searchSequence.toSManga().iterator()
            searchNextPage = 2
        } else {
            require(page == searchNextPage++)
        }

        val mangas = searchIterator.truncateToList(50).toList()
        return Observable.just(MangasPage(mangas, hasNextPage = mangas.isNotEmpty()))
    }

    private fun makeIllustSearchSequence(
        word: String,
        sMode: String,
        order: String?,
        mode: String?,
        type: String?,
        dateBefore: String?,
        dateAfter: String?,
    ) = sequence<PixivIllust> {
        val call = ApiCall("/touch/ajax/search/illusts")

        call.url.addQueryParameter("word", word)
        call.url.addEncodedQueryParameter("s_mode", sMode)
        type?.let { call.url.addEncodedQueryParameter("type", it) }
        order?.let { call.url.addEncodedQueryParameter("order", it) }
        mode?.let { call.url.addEncodedQueryParameter("mode", it) }
        dateBefore?.let { call.url.addEncodedQueryParameter("ecd", it) }
        dateAfter?.let { call.url.addEncodedQueryParameter("scd", it) }

        for (p in countUp(start = 1)) {
            call.url.setEncodedQueryParameter("p", p.toString())

            val illusts = call.executeApi<PixivResults>().getOrThrow().illusts!!
            if (illusts.isEmpty()) break

            for (illust in illusts) {
                if (illust.is_ad_container == 1) continue
                if (illust.type == "2") continue

                yield(illust)
            }
        }
    }

    private fun makeUserIllustSearchSequence(nick: String, type: String?) = sequence<PixivIllust> {
        val searchUsers = HttpCall("/search_user.php?s_mode=s_usr")
            .apply { url.addQueryParameter("nick", nick) }

        for (p in countUp(start = 1)) {
            searchUsers.url.setEncodedQueryParameter("p", p.toString())

            val userIds = searchUsers.execute().asJsoup()
                .select(".user-recommendation-item > a").eachAttr("href")
                .map { it.substringAfterLast('/') }

            if (userIds.isEmpty()) break

            for (userId in userIds) {
                yieldAll(makeUserIdIllustSearchSequence(userId, type))
            }
        }
    }

    private fun makeUserIdIllustSearchSequence(id: String, type: String?) = sequence<PixivIllust> {
        val fetchUserIllusts = ApiCall("/touch/ajax/user/illusts")
            .apply {
                type?.let { url.setEncodedQueryParameter("type", it) }
                url.setEncodedQueryParameter("id", id)
            }

        for (p in countUp(start = 1)) {
            fetchUserIllusts.url.setEncodedQueryParameter("p", p.toString())

            val illusts = fetchUserIllusts.executeApi<PixivResults>().getOrThrow().illusts!!
            if (illusts.isEmpty()) break

            yieldAll(illusts)
        }
    }

    // Manga Details
    private val getIllustCached by lazy {
        lruCached<String, PixivIllust?>(25) { illustId ->
            val call = ApiCall("/touch/ajax/illust/details?illust_id=$illustId")
            return@lruCached call.executeApi<PixivIllustDetails>().getOrNull()?.illust_details
        }
    }

    private val getSeriesIllustsCached by lazy {
        lruCached<String, List<PixivIllust>?>(25) { seriesId ->
            val call = ApiCall("/touch/ajax/illust/series_content/$seriesId")
            var lastOrder = 0

            return@lruCached buildList {
                while (true) {
                    call.url.setEncodedQueryParameter("last_order", lastOrder.toString())

                    val illusts = call.executeApi<PixivSeriesContents>()
                        .getOrElse { return@lruCached null }.series_contents!!
                    if (illusts.isEmpty()) break

                    addAll(illusts)
                    lastOrder += illusts.size
                }
            }
        }
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        val (id, isSeries) = parseSMangaUrl(manga.url)

        if (isSeries) {
            val series = ApiCall("/touch/ajax/illust/series/$id")
                .executeApi<PixivSeriesDetails>().getOrThrow().series!!

            val illusts = getSeriesIllustsCached(id)!!

            if (series.id != null && series.userId != null) {
                manga.setUrlWithoutDomain("/user/${series.userId}/series/${series.id}")
            }

            series.title?.let { manga.title = it }
            series.caption?.let { manga.description = it }

            illusts.firstOrNull()?.author_details?.user_name?.let {
                manga.artist = it
                manga.author = it
            }

            val tags = illusts.flatMap { it.tags ?: emptyList() }.toSet()
            if (tags.isNotEmpty()) manga.genre = tags.joinToString()

            val coverImage = series.coverImage?.let { if (it.isString) it.content else null }
            (coverImage ?: illusts.firstOrNull()?.url)?.let { manga.thumbnail_url = it }
        } else {
            val illust = getIllustCached(id)!!

            illust.id?.let { manga.setUrlWithoutDomain("/artworks/$it") }
            illust.title?.let { manga.title = it }

            illust.author_details?.user_name?.let {
                manga.artist = it
                manga.author = it
            }

            illust.comment?.let { manga.description = it }
            illust.tags?.let { manga.genre = it.joinToString() }
            illust.url?.let { manga.thumbnail_url = it }
        }

        return Observable.just(manga)
    }

    // Chapter List
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val (id, isSeries) = parseSMangaUrl(manga.url)

        val illusts = when (isSeries) {
            true -> getSeriesIllustsCached(id)!!
            false -> listOf(getIllustCached(id)!!)
        }

        val chapters = illusts.mapIndexed { i, illust ->
            SChapter.create().apply {
                setUrlWithoutDomain("/artworks/${illust.id!!}")
                name = illust.title ?: "(null)"
                date_upload = (illust.upload_timestamp ?: 0) * 1000
                chapter_number = (illusts.size - i).toFloat()
            }
        }

        return Observable.just(chapters)
    }

    // Page List
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val illustId = chapter.url.substringAfterLast('/')

        val pages = ApiCall("/ajax/illust/$illustId/pages")
            .executeApi<List<PixivIllustPage>>().getOrThrow()
            .mapIndexed { i, it -> Page(i, chapter.url, it.urls!!.original!!) }

        return Observable.just(pages)
    }

    // Helper methods
    private fun List<PixivIllust>.toSManga() = asSequence().toSManga().toList()

    private fun Sequence<PixivIllust>.toSManga() = sequence {
        val seriesIdsSeen = mutableSetOf<String>()

        forEach { illust ->
            val manga = illust.toSManga()
            if (seriesIdsSeen.add(manga.url)) {
                yield(manga)
            }
        }
    }

    private fun PixivSeries.toSearchResult() = PixivSearchResultSeries(
        id = id,
        title = title,
        userId = userId,
        coverImage = coverImage?.let { if (it.isString) it.content else null },
    )

    private fun PixivIllust.toSManga(): SManga {
        if (series == null) {
            val manga = SManga.create()
            manga.setUrlWithoutDomain("/artworks/${id!!}")
            manga.title = title ?: "(null)"
            manga.thumbnail_url = url
            return manga
        } else {
            val series = series.copy(userId = series.userId ?: author_details?.user_id)
            val manga = series.toSManga().apply {
                thumbnail_url = thumbnail_url ?: this@toSManga.url
            }
            return manga
        }
    }

    private fun PixivSeries.toSManga() = toSearchResult().toSManga()

    private fun PixivSearchResultSeries.toSManga(): SManga {
        val manga = SManga.create()
        manga.setUrlWithoutDomain("/user/${userId!!}/series/$id")
        manga.title = title ?: "(null)"
        manga.thumbnail_url = coverImage
        return manga
    }
}
