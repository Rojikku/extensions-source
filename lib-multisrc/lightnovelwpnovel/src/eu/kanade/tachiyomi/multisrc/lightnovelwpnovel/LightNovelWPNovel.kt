package eu.kanade.tachiyomi.multisrc.lightnovelwpnovel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Base class for LightNovelWP Engine powered novel sites.
 * Handles common parsing and request logic.
 */
open class LightNovelWPNovel(
    override val baseUrl: String,
    override val name: String,
    override val lang: String = "en",
) : HttpSource(), NovelSource {

    override val supportsLatest = true
    override val client = network.cloudflareClient

    protected open val seriesPath = "series"

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/$seriesPath?page=$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = parseNovels(doc)
        val hasNextPage = doc.selectFirst(".pagination .next, .pagination a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/$seriesPath?page=$page&order=latest"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$baseUrl/$seriesPath?page=$page"

        if (query.isNotBlank()) {
            url += "&s=${query.replace(" ", "+")}"
        }

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> {
                    if (filter.state != 0) {
                        url += "&status=${filter.toUriPart()}"
                    }
                }
                is SortFilter -> {
                    if (filter.state != 0) {
                        url += "&order=${filter.toUriPart()}"
                    }
                }
                else -> {}
            }
        }

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    protected fun parseNovels(doc: Document): List<SManga> {
        return doc.select("article").mapNotNull { element ->
            try {
                val titleElement = element.selectFirst("a[title]") ?: return@mapNotNull null
                val title = titleElement.attr("title")
                val url = titleElement.attr("href")
                val image = element.selectFirst("img")
                val cover = image?.attr("data-src") ?: image?.attr("src") ?: ""

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
        return SManga.create().apply {
            description = doc.selectFirst(".entry-content, [itemprop=description]")?.text()?.trim() ?: ""

            val authorElement = doc.select(".spe span:contains(Author), .serl:contains(Author)").first()
            author = authorElement?.nextElementSibling()?.text()?.trim()
                ?: authorElement?.parent()?.text()?.substringAfter("Author")?.replace(":", "")?.trim()
                ?: ""

            val artistElement = doc.select(".spe span:contains(Artist), .serl:contains(Artist)").first()
            artist = artistElement?.nextElementSibling()?.text()?.trim()
                ?: artistElement?.parent()?.text()?.substringAfter("Artist")?.replace(":", "")?.trim()
                ?: ""

            genre = doc.select(".genxed a, .sertogenre a").joinToString(", ") { it.text() }

            status = when {
                doc.select(".sertostat, .spe, .serl").text().contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                doc.select(".sertostat, .spe, .serl").text().contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                doc.select(".sertostat, .spe, .serl").text().contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        doc.select(".eplister li").forEach { element ->
            try {
                val linkElement = element.selectFirst("a") ?: return@forEach
                val url = linkElement.attr("href")
                val chapterNum = element.selectFirst(".epl-num")?.text() ?: ""
                val chapterTitle = element.selectFirst(".epl-title")?.text() ?: ""
                val dateStr = element.selectFirst(".epl-date")?.text() ?: ""

                // Check for locked status
                val isLocked = element.select(".epl-price").text().let {
                    !it.contains("Free", ignoreCase = true) && it.isNotEmpty()
                } || chapterNum.contains("🔒")

                var name = if (chapterTitle.isNotEmpty()) chapterTitle else "Chapter $chapterNum"
                if (isLocked) {
                    name = "🔒 $name"
                }

                chapters.add(
                    SChapter.create().apply {
                        this.url = url.replace(baseUrl, "")
                        this.name = name
                        date_upload = parseDate(dateStr)
                    },
                )
            } catch (e: Exception) {
                // Skip problematic chapters
            }
        }

        return chapters
    }

    override fun pageListParse(response: Response): List<Page> = emptyList()

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = response.asJsoup()

        // Remove unwanted elements
        doc.select(".unlock-buttons, .ads, script, style").remove()

        return doc.selectFirst(".epcontent, .entry-content, #chapter-content")?.html() ?: ""
    }

    override fun getFilterList(): FilterList = FilterList(
        StatusFilter(),
        SortFilter(),
    )

    protected fun parseDate(dateStr: String): Long {
        return try {
            // Basic parsing, can be improved
            0L
        } catch (e: Exception) {
            0L
        }
    }

    protected fun Response.asJsoup(): Document = Jsoup.parse(body.string())

    private class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("All", "Ongoing", "Completed", "Hiatus"),
    ) {
        fun toUriPart() = when (state) {
            0 -> ""
            1 -> "ongoing"
            2 -> "completed"
            3 -> "hiatus"
            else -> ""
        }
    }

    private class SortFilter : Filter.Select<String>(
        "Sort",
        arrayOf("Default", "A-Z", "Z-A", "Latest Update", "Latest Added", "Popular", "Rating"),
    ) {
        fun toUriPart() = when (state) {
            0 -> ""
            1 -> "title"
            2 -> "titlereverse"
            3 -> "update"
            4 -> "latest"
            5 -> "popular"
            6 -> "rating"
            else -> ""
        }
    }
}
