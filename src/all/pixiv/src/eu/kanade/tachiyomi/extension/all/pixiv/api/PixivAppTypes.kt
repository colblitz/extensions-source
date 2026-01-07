package eu.kanade.tachiyomi.extension.all.pixiv.api

import kotlinx.serialization.Serializable

@Serializable
internal data class PixivAuthResponse(
    val response: AuthResponseData,
)

@Serializable
internal data class AuthResponseData(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Int,
)

@Serializable
internal data class PixivAppApiResponse(
    val illusts: List<PixivAppIllust>,
    val next_url: String? = null,
)

@Serializable
internal data class PixivIllustDetailResponse(
    val illust: PixivAppIllust,
)

@Serializable
internal data class PixivAppIllust(
    val id: Long,
    val title: String,
    val type: String, // "illust", "manga", "ugoira"
    val image_urls: ImageUrls,
    val caption: String,
    val restrict: Int,
    val user: PixivUser,
    val tags: List<PixivTag>,
    val tools: List<String>,
    val create_date: String,
    val page_count: Int,
    val width: Int,
    val height: Int,
    val sanity_level: Int,
    val x_restrict: Int,
    val series: PixivSeriesInfo? = null,
    val meta_single_page: MetaSinglePage,
    val meta_pages: List<MetaPage>,
    val total_view: Int,
    val total_bookmarks: Int,
    val is_bookmarked: Boolean,
    val visible: Boolean,
    val is_muted: Boolean,
    val total_comments: Int? = null,
    val illust_ai_type: Int? = null,
    val illust_book_style: Int? = null,
)

@Serializable
internal data class ImageUrls(
    val square_medium: String,
    val medium: String,
    val large: String,
)

@Serializable
internal data class MetaSinglePage(
    val original_image_url: String? = null,
)

@Serializable
internal data class MetaPage(
    val image_urls: PageImageUrls,
)

@Serializable
internal data class PageImageUrls(
    val square_medium: String,
    val medium: String,
    val large: String,
    val original: String,
)

@Serializable
internal data class PixivUser(
    val id: Long,
    val name: String,
    val account: String,
    val is_followed: Boolean,
)

@Serializable
internal data class PixivTag(
    val name: String,
    val translated_name: String? = null,
)

@Serializable
internal data class PixivSeriesInfo(
    val id: Long,
    val title: String,
)

@Serializable
internal data class PixivUserSearchResponse(
    val user_previews: List<PixivUserPreview>,
    val next_url: String? = null,
)

@Serializable
internal data class PixivUserPreview(
    val user: PixivUser,
    val illusts: List<PixivAppIllust>,
    val is_muted: Boolean,
)
