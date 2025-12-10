package eu.kanade.tachiyomi.extension.en.mtlreader

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MtlReader : HttpSource(), NovelSource {

    override val name = "MTL Reader"
    override val baseUrl = "https://mtlreader.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    // Popular novels
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/novels?sort=popular&page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelList(doc)
    }

    // Latest updates
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/novels?sort=latest&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/novels".toHttpUrl().newBuilder().apply {
            addQueryParameter("search", query)
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    private fun parseNovelList(doc: Document): MangasPage {
        val novels = doc.select("div.image.text-center.position-relative").mapNotNull { element ->
            try {
                val link = element.selectFirst("a[href*=/novels/]") ?: return@mapNotNull null
                val img = element.selectFirst("img")
                val url = link.attr("href").replace(baseUrl, "")
                // Get title from image alt attribute
                val title = img?.attr("alt") ?: return@mapNotNull null
                val cover = img.attr("src")

                SManga.create().apply {
                    this.url = url
                    this.title = title
                    thumbnail_url = cover
                }
            } catch (e: Exception) {
                null
            }
        }

        val hasNextPage = doc.selectFirst("li.page-item a[rel=next]") != null
        return MangasPage(novels, hasNextPage)
    }

    // Manga details
    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            // Get description from meta or content
            description = doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst(".novel-description")?.text()
                ?: ""

            // Try to get author and genres if available
            author = doc.selectFirst(".novel-author a")?.text()
                ?: doc.selectFirst(".author")?.text()

            genre = doc.select(".novel-genre a, .genre a").joinToString(", ") { it.text() }

            status = SManga.UNKNOWN
        }
    }

    // Chapter list with pagination
    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val allChapters = mutableListOf<SChapter>()
        var page = 1
        var hasNextPage = true
        val mangaUrl = response.request.url.encodedPath

        while (hasNextPage) {
            val doc = if (page == 1) {
                Jsoup.parse(response.body.string())
            } else {
                val url = "$baseUrl$mangaUrl?page=$page"
                val resp = client.newCall(GET(url, headers)).execute()
                Jsoup.parse(resp.body.string())
            }

            val chapters = doc.select("table.table tbody tr").mapNotNull { row ->
                try {
                    val link = row.selectFirst("a.chapter-link") ?: return@mapNotNull null
                    val chapterUrl = link.attr("href").replace(baseUrl, "")
                    val chapterName = link.text().trim()
                    val dateText = row.selectFirst("td.updated-col")?.text()?.trim() ?: ""

                    SChapter.create().apply {
                        url = chapterUrl
                        name = chapterName
                        date_upload = parseRelativeDate(dateText)
                    }
                } catch (e: Exception) {
                    null
                }
            }

            allChapters.addAll(chapters)

            // Check for next page
            hasNextPage = doc.selectFirst("li.page-item a[rel=next]") != null
            page++

            // Safety limit to prevent infinite loops
            if (page > 100) break
        }

        return allChapters
    }

    // Page list (not used for novels)
    override fun pageListParse(response: Response): List<Page> = emptyList()

    override fun imageUrlParse(response: Response): String = ""

    // Novel content
    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())

        // Try multiple selectors for chapter content
        return doc.selectFirst("div.ea57f85d74")?.html()
            ?: doc.selectFirst("div.chapter-content")?.html()
            ?: doc.selectFirst("div.container .content")?.html()
            ?: doc.selectFirst("article .content")?.html()
            ?: ""
    }

    private fun parseRelativeDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L

        val calendar = Calendar.getInstance()

        return try {
            when {
                dateStr.contains("ago", ignoreCase = true) -> {
                    val parts = dateStr.lowercase().split(" ")
                    val amount = parts[0].toIntOrNull() ?: return 0L

                    when {
                        dateStr.contains("minute", ignoreCase = true) -> {
                            calendar.add(Calendar.MINUTE, -amount)
                        }
                        dateStr.contains("hour", ignoreCase = true) -> {
                            calendar.add(Calendar.HOUR_OF_DAY, -amount)
                        }
                        dateStr.contains("day", ignoreCase = true) -> {
                            calendar.add(Calendar.DAY_OF_MONTH, -amount)
                        }
                        dateStr.contains("week", ignoreCase = true) -> {
                            calendar.add(Calendar.WEEK_OF_YEAR, -amount)
                        }
                        dateStr.contains("month", ignoreCase = true) -> {
                            calendar.add(Calendar.MONTH, -amount)
                        }
                        dateStr.contains("year", ignoreCase = true) -> {
                            calendar.add(Calendar.YEAR, -amount)
                        }
                    }
                    calendar.timeInMillis
                }
                else -> {
                    // Try to parse as date format
                    try {
                        SimpleDateFormat("MMM d, yyyy", Locale.US).parse(dateStr)?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                }
            }
        } catch (e: Exception) {
            0L
        }
    }

    override fun getFilterList(): FilterList = FilterList()
}
