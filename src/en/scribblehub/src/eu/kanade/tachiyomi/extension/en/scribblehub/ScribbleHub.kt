package eu.kanade.tachiyomi.extension.en.scribblehub

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
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.Calendar

class ScribbleHub : HttpSource(), NovelSource {

    override val name = "Scribble Hub"
    override val baseUrl = "https://www.scribblehub.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    // Popular novels
    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/series-finder/?sf=1&sort=ratings&order=desc"
        } else {
            "$baseUrl/series-finder/?sf=1&sort=ratings&order=desc&pg=$page"
        }
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        val mangas = parseNovels(doc)
        val hasNextPage = doc.selectFirst(".pagination .next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // Latest updates
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/latest-series"
        } else {
            "$baseUrl/latest-series/?pg=$page"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (filters.isEmpty()) {
            GET("$baseUrl?s=${query.replace(" ", "+")}&post_type=fictionposts", headers)
        } else {
            val url = "$baseUrl/series-finder/?sf=1".toHttpUrl().newBuilder()

            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        filter.included.filter { it.state }.forEach { genre ->
                            url.addQueryParameter("gi", genre.value)
                        }
                        filter.excluded.filter { it.state }.forEach { genre ->
                            url.addQueryParameter("ge", genre.value)
                        }
                    }
                    is ContentWarningFilter -> {
                        filter.included.filter { it.state }.forEach { warning ->
                            url.addQueryParameter("cti", warning.value)
                        }
                        filter.excluded.filter { it.state }.forEach { warning ->
                            url.addQueryParameter("cte", warning.value)
                        }
                    }
                    is StatusFilter -> url.addQueryParameter("cp", filter.toUriPart())
                    is SortFilter -> {
                        url.addQueryParameter("sort", filter.toUriPart())
                        url.addQueryParameter("order", if (filter.state!!.ascending) "asc" else "desc")
                    }
                    else -> {}
                }
            }

            url.addQueryParameter("pg", page.toString())
            GET(url.build(), headers)
        }
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // Manga details
    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            description = doc.selectFirst(".wi_fic_desc")?.text() ?: ""
            author = doc.selectFirst(".auth_name_fic")?.text() ?: ""
            genre = doc.select(".fic_genre").map { it.text() }.joinToString(", ")
            status = if (doc.selectFirst(".rnd_stats")?.nextElementSibling()?.text()?.contains("Ongoing", ignoreCase = true) == true) {
                SManga.ONGOING
            } else {
                SManga.COMPLETED
            }
        }
    }

    // Chapter list - parse response and make additional AJAX call for full chapter list
    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())

        // Extract novel ID from response URL
        val url = response.request.url.toString()
        val novelId = url.removePrefix(baseUrl).trim('/').split("/").firstOrNull() ?: return emptyList()

        val formBody = FormBody.Builder()
            .add("action", "wi_getreleases_pagination")
            .add("pagenum", "-1")
            .add("mypostid", novelId)
            .build()

        val chapResponse = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody)).execute()
        val chapDoc = Jsoup.parse(chapResponse.body.string())

        return chapDoc.select(".toc_w").mapNotNull { element ->
            try {
                val name = element.selectFirst(".toc_a")?.text() ?: return@mapNotNull null
                val chapterUrl = element.selectFirst("a")?.attr("href")?.replace(baseUrl, "") ?: return@mapNotNull null
                val dateStr = element.selectFirst(".fic_date_pub")?.text() ?: ""

                SChapter.create().apply {
                    this.url = chapterUrl
                    this.name = name
                    date_upload = parseDate(dateStr)
                }
            } catch (e: Exception) {
                null
            }
        }.reversed()
    }

    // Page list - return single page with chapter URL for text fetching
    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    // Novel source text fetching
    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())
        return doc.selectFirst("div.chp_raw")?.html() ?: ""
    }

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(),
        ContentWarningFilter(),
        StatusFilter(),
        SortFilter(),
    )

    // Image URL - not used for novels
    override fun imageUrlParse(response: Response): String = ""

    // Helper functions
    private fun parseNovels(doc: org.jsoup.nodes.Document): List<SManga> {
        return doc.select(".search_main_box").mapNotNull { element ->
            try {
                val name = element.selectFirst(".search_title > a")?.text() ?: return@mapNotNull null
                val url = element.selectFirst(".search_title > a")?.attr("href")?.replace(baseUrl, "") ?: return@mapNotNull null
                val cover = element.selectFirst(".search_img > img")?.attr("src") ?: ""

                SManga.create().apply {
                    title = name
                    this.url = url
                    thumbnail_url = cover
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            if (dateStr.contains("ago", ignoreCase = true)) {
                val parts = dateStr.split(" ")
                val amount = parts[0].toIntOrNull() ?: return 0L
                val calendar = Calendar.getInstance()

                when {
                    dateStr.contains("hours", ignoreCase = true) -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
                    dateStr.contains("days", ignoreCase = true) -> calendar.add(Calendar.DAY_OF_MONTH, -amount)
                    dateStr.contains("months", ignoreCase = true) -> calendar.add(Calendar.MONTH, -amount)
                }

                calendar.timeInMillis
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    // Filters
    private class Genre(name: String, val value: String) : Filter.CheckBox(name)

    private class GenreFilter : Filter.Group<Genre>(
        "Genres",
        listOf(
            Genre("Action", "9"),
            Genre("Adult", "902"),
            Genre("Adventure", "8"),
            Genre("Boys Love", "891"),
            Genre("Comedy", "7"),
            Genre("Drama", "903"),
            Genre("Ecchi", "904"),
            Genre("Fanfiction", "35"),
            Genre("Fantasy", "19"),
            Genre("Gender Bender", "168"),
            Genre("Girls Love", "549"),
            Genre("Harem", "3"),
            Genre("Historical", "21"),
            Genre("Horror", "22"),
            Genre("Isekai", "37"),
            Genre("Josei", "13"),
            Genre("LitRPG", "218"),
            Genre("Martial Arts", "4"),
            Genre("Mature", "5"),
            Genre("Mecha", "23"),
            Genre("Mystery", "245"),
            Genre("Psychological", "17"),
            Genre("Romance", "15"),
            Genre("School Life", "6"),
            Genre("Sci-Fi", "24"),
            Genre("Seinen", "18"),
            Genre("Slice of Life", "10"),
            Genre("Smut", "905"),
            Genre("Sports", "25"),
            Genre("Supernatural", "906"),
            Genre("Tragedy", "907"),
            Genre("Xianxia", "908"),
        ),
    ) {
        val included: List<Genre> get() = state.filter { it.state }
        val excluded: List<Genre> get() = state.filter { !it.state }
    }

    private class Warning(name: String, val value: String) : Filter.CheckBox(name)

    private class ContentWarningFilter : Filter.Group<Warning>(
        "Content Warnings",
        listOf(
            Warning("Gore", "48"),
            Warning("Traumatising Content", "324"),
            Warning("Sexual Content", "50"),
        ),
    ) {
        val included: List<Warning> get() = state.filter { it.state }
        val excluded: List<Warning> get() = state.filter { !it.state }
    }

    private class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("All", "Ongoing", "Completed"),
    ) {
        fun toUriPart() = when (state) {
            0 -> ""
            1 -> "1"
            2 -> "2"
            else -> ""
        }
    }

    private class SortFilter : Filter.Sort(
        "Sort",
        arrayOf("Ratings", "Chapters", "Frequency", "Date Added", "Last Updated"),
        Filter.Sort.Selection(0, true),
    ) {
        fun toUriPart() = when (state?.index) {
            0 -> "ratings"
            1 -> "chapters"
            2 -> "frequency"
            3 -> "dateadded"
            4 -> "lastchdate"
            else -> "ratings"
        }
    }
}
