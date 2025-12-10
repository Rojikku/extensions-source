package eu.kanade.tachiyomi.multisrc.madaranovel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.util.Calendar

/**
 * Base class for Madara Engine powered novel sites.
 * Handles common parsing and request logic.
 */
open class MadaraNovel(
    override val baseUrl: String,
    override val name: String,
    override val lang: String = "en",
) : HttpSource(), NovelSource {

    override val supportsLatest = true
    override val client = network.cloudflareClient

    protected val json: Json by injectLazy()

    protected open val useNewChapterEndpoint = false

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/page/$page/?s=&post_type=wp-manga"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = parseNovels(doc)
        val hasNextPage = doc.selectFirst(".pagination a:contains(next)") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/page/$page/?s=&post_type=wp-manga&m_orderby=latest"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$baseUrl/page/$page/?s=${query.replace(" ", "+")}&post_type=wp-manga"

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> {
                    if (filter.state != 0) {
                        url += "&m_orderby=${filter.toUriPart()}"
                    }
                }
                is SortFilter -> {
                    if (filter.state != 0) {
                        url += "&m_orderby=${filter.toUriPart()}"
                    }
                }
                else -> {}
            }
        }

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    protected fun parseNovels(doc: Document): List<SManga> {
        doc.select(".manga-title-badges").remove()

        return doc.select(".page-item-detail, .c-tabs-item__content").mapNotNull { element ->
            try {
                val title = element.selectFirst(".post-title")?.text()?.trim() ?: return@mapNotNull null
                val url = element.selectFirst(".post-title a")?.attr("href") ?: return@mapNotNull null
                val image = element.selectFirst("img")
                val cover = image?.attr("data-src")
                    ?: image?.attr("src")
                    ?: image?.attr("data-lazy-srcset")
                    ?: ""

                SManga.create().apply {
                    this.title = title
                    this.url = url.replace(baseUrl, "")
                    thumbnail_url = cover
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()

        doc.select(".manga-title-badges, #manga-title span").remove()

        return SManga.create().apply {
            description = doc.selectFirst("div.summary__content")?.text()?.trim()
                ?: doc.selectFirst("#tab-manga-about")?.text()?.trim()
                ?: doc.selectFirst(".manga-excerpt")?.text()?.trim()
                ?: ""
            author = doc.selectFirst(".manga-authors")?.text()?.trim()
                ?: doc.select(".post-content_item, .post-content")
                    .find { it.selectFirst("h5")?.text() == "Author" }
                    ?.selectFirst(".summary-content")?.text()?.trim()
                ?: ""
            genre = doc.select(".post-content_item, .post-content")
                .filter { element ->
                    val h5Text = element.selectFirst("h5")?.text()?.trim() ?: ""
                    h5Text.contains("Genre", ignoreCase = true) ||
                        h5Text.contains("Tags", ignoreCase = true)
                }
                .mapNotNull { it.selectFirst(".summary-content")?.select("a") }
                .flatten()
                .map { it.text().trim() }
                .joinToString(", ")
            status = if (doc.select(".post-content_item, .post-content")
                .find { it.selectFirst("h5")?.text() == "Status" }
                ?.selectFirst(".summary-content")?.text()?.contains("Ongoing", ignoreCase = true) == true
            ) {
                SManga.ONGOING
            } else {
                SManga.COMPLETED
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val mangaUrl = response.request.url.encodedPath

        val chapters = mutableListOf<SChapter>()
        var html = ""

        if (useNewChapterEndpoint) {
            val emptyBody = FormBody.Builder().build()
            val chapResponse = client.newCall(
                POST("$baseUrl${mangaUrl}ajax/chapters/", headers, emptyBody),
            ).execute()
            html = chapResponse.body.string()
        } else {
            val novelId = doc.selectFirst(".rating-post-id")?.attr("value")
                ?: doc.selectFirst("#manga-chapters-holder")?.attr("data-id")
                ?: ""

            val formBody = FormBody.Builder()
                .add("action", "manga_get_chapters")
                .add("manga", novelId)
                .build()

            val chapResponse = client.newCall(
                POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody),
            ).execute()
            html = chapResponse.body.string()
        }

        if (html != "0") {
            val chapDoc = Jsoup.parse(html)
            val totalChaps = chapDoc.select(".wp-manga-chapter").size

            chapDoc.select(".wp-manga-chapter").forEachIndexed { index, element ->
                try {
                    var chapterName = element.selectFirst("a")?.text()?.trim() ?: return@forEachIndexed
                    val isLocked = element.className().contains("premium-block")

                    if (isLocked) {
                        chapterName = "🔒 $chapterName"
                    }

                    val releaseDate = element.selectFirst(".chapter-release-date")?.text()?.trim() ?: ""
                    val chapterUrl = element.selectFirst("a")?.attr("href") ?: return@forEachIndexed

                    if (chapterUrl != "#") {
                        chapters.add(
                            SChapter.create().apply {
                                url = chapterUrl.replace(baseUrl, "")
                                name = chapterName
                                date_upload = parseDate(releaseDate)
                                chapter_number = (totalChaps - index).toFloat()
                            },
                        )
                    }
                } catch (e: Exception) {
                    // Skip problematic chapters
                }
            }
        }

        return chapters.reversed()
    }

    override fun pageListParse(response: Response): List<Page> = emptyList()

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = response.asJsoup()

        return doc.selectFirst(".text-left")?.html()
            ?: doc.selectFirst(".text-right")?.html()
            ?: doc.selectFirst(".entry-content")?.html()
            ?: doc.selectFirst(".c-blog-post > div > div:nth-child(2)")?.html()
            ?: ""
    }

    override fun getFilterList(): FilterList = FilterList(
        StatusFilter(),
        SortFilter(),
    )

    protected fun parseDate(dateStr: String): Long {
        return try {
            if (dateStr.isEmpty()) return 0L

            val number = Regex("\\d+").find(dateStr)?.value?.toIntOrNull() ?: return 0L
            val calendar = Calendar.getInstance()

            when {
                dateStr.contains("second", ignoreCase = true) -> calendar.add(Calendar.SECOND, -number)
                dateStr.contains("minute", ignoreCase = true) -> calendar.add(Calendar.MINUTE, -number)
                dateStr.contains("hour", ignoreCase = true) -> calendar.add(Calendar.HOUR_OF_DAY, -number)
                dateStr.contains("day", ignoreCase = true) -> calendar.add(Calendar.DAY_OF_MONTH, -number)
                dateStr.contains("week", ignoreCase = true) -> calendar.add(Calendar.WEEK_OF_YEAR, -number)
                dateStr.contains("month", ignoreCase = true) -> calendar.add(Calendar.MONTH, -number)
                dateStr.contains("year", ignoreCase = true) -> calendar.add(Calendar.YEAR, -number)
            }

            calendar.timeInMillis
        } catch (e: Exception) {
            0L
        }
    }

    protected fun Response.asJsoup(): Document = Jsoup.parse(body.string())

    private class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("All", "Ongoing", "Completed"),
    ) {
        fun toUriPart() = when (state) {
            0 -> ""
            1 -> "latest"
            2 -> "completed"
            else -> ""
        }
    }

    private class SortFilter : Filter.Select<String>(
        "Sort",
        arrayOf("Latest", "Trending", "Rating", "Review"),
    ) {
        fun toUriPart() = when (state) {
            0 -> "latest"
            1 -> "trending"
            2 -> "rating"
            3 -> "review"
            else -> "latest"
        }
    }
}
