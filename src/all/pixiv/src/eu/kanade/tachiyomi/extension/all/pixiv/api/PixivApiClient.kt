package eu.kanade.tachiyomi.extension.all.pixiv.api

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import rx.Observable

/**
 * Interface for Pixiv API clients.
 * * Implementations:
 * - PixivWebApiClient: Uses Pixiv's web/touch API (current default)
 * - PixivAppApiClient: Uses Pixiv's mobile app API (experimental, opt-in)
 */
interface PixivApiClient {

    /**
     * Fetch popular manga (rankings).
     * @param page Page number (1-indexed)
     */
    fun fetchPopularManga(page: Int): Observable<MangasPage>

    /**
     * Fetch latest manga updates.
     * @param page Page number (1-indexed)
     */
    fun fetchLatestUpdates(page: Int): Observable<MangasPage>

    /**
     * Search for manga.
     * @param page Page number (1-indexed)
     * @param query Search query (can be empty if using filters)
     * @param filters Search filters (tags, users, rating, etc.)
     */
    fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage>

    /**
     * Fetch manga details.
     * @param manga Manga with URL set
     */
    fun fetchMangaDetails(manga: SManga): Observable<SManga>

    /**
     * Fetch chapter list for manga.
     * @param manga Manga with URL set
     */
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>>

    /**
     * Fetch page list for chapter.
     * @param chapter Chapter with URL set
     */
    fun fetchPageList(chapter: SChapter): Observable<List<Page>>
}
