package eu.kanade.tachiyomi.extension.en.scribblehub

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar

class ScribbleHub : HttpSource(), NovelSource, ConfigurableSource {

    override val name = "Scribble Hub"
    override val baseUrl = "https://www.scribblehub.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular novels
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/series-finder/?sf=1&sort=ratings&order=desc&pg=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    // Latest updates
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest-series/?pg=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotEmpty()) {
            // Text search
            GET("$baseUrl/?s=${query.replace(" ", "+")}&post_type=fictionposts&page=$page", headers)
        } else {
            // Filter search
            val url = buildFilterUrl(page, filters)
            GET(url, headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    private fun parseNovelsFromSearch(doc: Document): MangasPage {
        val novels = doc.select("div.search_main_box").mapNotNull { element ->
            val titleElement = element.select(".search_title > a").first() ?: return@mapNotNull null
            val novelUrl = titleElement.attr("href")

            SManga.create().apply {
                title = titleElement.text()
                thumbnail_url = element.select(".search_img img").attr("src")
                url = novelUrl.removePrefix(baseUrl)
            }
        }

        // Check for next page
        val hasNextPage = doc.select(".pagination a:contains(Next)").isNotEmpty() ||
            doc.select("a.next").isNotEmpty()

        return MangasPage(novels, hasNextPage)
    }

    // Manga details
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title = doc.select(".fic_title").text().ifEmpty { "Untitled" }

            // Get high-res cover - try data-src first for lazy loading
            val coverElement = doc.select(".fic_image img").first()
            thumbnail_url = coverElement?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
            } ?: ""

            author = doc.select(".auth_name_fic").text().trim()
            genre = doc.select(".fic_genre").joinToString(", ") { it.text().trim() }

            // Extract status from stats
            val statsText = doc.select(".rnd_stats").text().lowercase()
            status = when {
                statsText.contains("ongoing") -> SManga.ONGOING
                statsText.contains("completed") -> SManga.COMPLETED
                statsText.contains("hiatus") -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            description = doc.select(".wi_fic_desc").text().trim()
        }
    }

    // Chapter list
    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())

        // Extract novel ID from the page - try multiple methods
        val novelId = extractNovelId(doc, response.request.url.encodedPath)
        if (novelId.isEmpty()) return emptyList()

        // Fetch full chapter list via AJAX (pagenum=-1 means all chapters)
        val formBody = FormBody.Builder()
            .add("action", "wi_getreleases_pagination")
            .add("pagenum", "-1")
            .add("mypostid", novelId)
            .build()

        val chaptersRequest = POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody)
        val chaptersResponse = client.newCall(chaptersRequest).execute()
        val chaptersHtml = chaptersResponse.body.string()

        val chaptersDoc = Jsoup.parse(chaptersHtml)

        return chaptersDoc.select(".toc_w, li.toc_w").mapNotNull { element ->
            val link = element.select("a").first() ?: return@mapNotNull null
            val chapterUrl = link.attr("href")
            val chapterName = element.select(".toc_a").first()?.text() ?: link.text()
            val dateText = element.select(".fic_date_pub").text().trim()

            SChapter.create().apply {
                name = chapterName.trim()
                url = chapterUrl.removePrefix(baseUrl)
                date_upload = parseRelativeDate(dateText)
            }
        }.reversed()
    }

    private fun extractNovelId(doc: Document, urlPath: String): String {
        // Method 1: Extract from URL path (e.g., /series/1135722/novel-name/ -> 1135722)
        val pathParts = urlPath.removePrefix("/").split("/")
        if (pathParts.size >= 2) {
            val idFromPath = pathParts[1]
            if (idFromPath.all { it.isDigit() }) {
                return idFromPath
            }
        }

        // Method 2: Try multiple HTML selectors
        return listOf(
            doc.select("input[name='mypostid']").attr("value"),
            doc.select("[data-nid]").attr("data-nid"),
            doc.select("#mypostid").attr("value"),
            doc.select("input#mypostid").attr("value"),
            // Try to find it in script tags
            doc.select("script").text().let { scripts ->
                Regex("""mypostid['":\s]+(\d+)""").find(scripts)?.groupValues?.get(1) ?: ""
            },
        ).firstOrNull { it.isNotEmpty() } ?: ""
    }

    private fun parseRelativeDate(dateText: String): Long {
        if (dateText.isEmpty()) return 0L

        return try {
            val calendar = Calendar.getInstance()
            val parts = dateText.split(" ")
            if (parts.size >= 2) {
                val amount = parts[0].toInt()
                val unit = parts[1].lowercase()

                when {
                    unit.contains("second") -> calendar.add(Calendar.SECOND, -amount)
                    unit.contains("minute") -> calendar.add(Calendar.MINUTE, -amount)
                    unit.contains("hour") -> calendar.add(Calendar.HOUR, -amount)
                    unit.contains("day") -> calendar.add(Calendar.DAY_OF_MONTH, -amount)
                    unit.contains("week") -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
                    unit.contains("month") -> calendar.add(Calendar.MONTH, -amount)
                    unit.contains("year") -> calendar.add(Calendar.YEAR, -amount)
                }
            }
            calendar.timeInMillis
        } catch (e: Exception) {
            0L
        }
    }

    // Page list
    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        // Return single page with chapter URL
        return listOf(Page(0, response.request.url.toString(), null))
    }

    // Novel source implementation
    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(page.url, headers)).execute()
        val body = response.body.string()
        val doc = Jsoup.parse(body, page.url)

        // Handle CAPTCHA cases
        val title = doc.select("title").text().trim().lowercase()
        val blockedTitles = listOf(
            "bot verification",
            "just a moment...",
            "redirecting...",
            "un instant...",
            "you are being redirected...",
        )
        if (blockedTitles.contains(title)) {
            throw Exception("Captcha detected, please open in webview.")
        }

        // Return chapter content
        return doc.select("div.chp_raw").html()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Add preferences if needed
    }

    override fun imageUrlParse(response: Response) = ""

    // Filters
    override fun getFilterList() = FilterList(
        Filter.Header("NOTE: Filters are ignored if using text search!"),
        Filter.Separator(),
        SortFilter(),
        OrderFilter(),
        StatusFilter(),
        GenreFilter(),
        ContentWarningFilter(),
        GenreOperatorFilter(),
        ContentWarningOperatorFilter(),
    )

    private fun buildFilterUrl(page: Int, filters: FilterList): String {
        val sortFilter = filters.findInstance<SortFilter>()!!
        val orderFilter = filters.findInstance<OrderFilter>()!!
        val statusFilter = filters.findInstance<StatusFilter>()!!
        val genreFilter = filters.findInstance<GenreFilter>()!!
        val contentWarningFilter = filters.findInstance<ContentWarningFilter>()!!
        val genreOperatorFilter = filters.findInstance<GenreOperatorFilter>()!!
        val contentWarningOperatorFilter = filters.findInstance<ContentWarningOperatorFilter>()!!

        return buildString {
            append("$baseUrl/series-finder/?sf=1")

            // Add genres
            val includedGenres = genreFilter.state.filter { it.isIncluded() }.map { it.id }
            val excludedGenres = genreFilter.state.filter { it.isExcluded() }.map { it.id }
            if (includedGenres.isNotEmpty()) {
                append("&gi=").append(includedGenres.joinToString(","))
                append("&mgi=").append(genreOperatorFilter.toUriPart())
            }
            if (excludedGenres.isNotEmpty()) {
                append("&ge=").append(excludedGenres.joinToString(","))
            }

            // Add content warnings
            val includedWarnings = contentWarningFilter.state.filter { it.isIncluded() }.map { it.id }
            val excludedWarnings = contentWarningFilter.state.filter { it.isExcluded() }.map { it.id }
            if (includedWarnings.isNotEmpty()) {
                append("&cti=").append(includedWarnings.joinToString(","))
                append("&mct=").append(contentWarningOperatorFilter.toUriPart())
            }
            if (excludedWarnings.isNotEmpty()) {
                append("&cte=").append(excludedWarnings.joinToString(","))
            }

            // Add status
            if (statusFilter.state != 0) {
                append("&cp=").append(statusFilter.toUriPart())
            }

            // Add sort and order
            append("&sort=").append(sortFilter.toUriPart())
            append("&order=").append(orderFilter.toUriPart())
            append("&pg=$page")
        }
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    // Filter classes
    private class SortFilter : Filter.Select<String>(
        "Sort Results By",
        arrayOf(
            "Ratings",
            "Chapters",
            "Chapters per Week",
            "Date Added",
            "Favorites",
            "Last Updated",
            "Number of Ratings",
            "Pages",
            "Pageviews",
            "Readers",
            "Reviews",
            "Total Words",
        ),
    ) {
        fun toUriPart() = when (state) {
            0 -> "ratings"
            1 -> "chapters"
            2 -> "frequency"
            3 -> "dateadded"
            4 -> "favorites"
            5 -> "lastchdate"
            6 -> "numofrate"
            7 -> "pages"
            8 -> "pageviews"
            9 -> "readers"
            10 -> "reviews"
            11 -> "totalwords"
            else -> "ratings"
        }
    }

    private class OrderFilter : Filter.Select<String>(
        "Order",
        arrayOf("Descending", "Ascending"),
    ) {
        fun toUriPart() = when (state) {
            0 -> "desc"
            1 -> "asc"
            else -> "desc"
        }
    }

    private class StatusFilter : Filter.Select<String>(
        "Story Status",
        arrayOf("All", "Ongoing", "Completed", "Hiatus"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "1" // Ongoing
            2 -> "2" // Completed
            3 -> "3" // Hiatus
            else -> "" // All
        }
    }

    private class Genre(name: String, val id: String) : Filter.TriState(name)
    private class GenreFilter : Filter.Group<Genre>(
        "Genres (0=ignore, 1=include, 2=exclude)",
        listOf(
            Genre("Action", "9"),
            Genre("Adult", "902"),
            Genre("Adventure", "8"),
            Genre("Boys Love", "891"),
            Genre("Comedy", "7"),
            Genre("Drama", "903"),
            Genre("Ecchi", "904"),
            Genre("Fanfiction", "38"),
            Genre("Fantasy", "19"),
            Genre("Gender Bender", "905"),
            Genre("Girls Love", "892"),
            Genre("Harem", "1015"),
            Genre("Historical", "21"),
            Genre("Horror", "22"),
            Genre("Isekai", "37"),
            Genre("Josei", "906"),
            Genre("LitRPG", "1180"),
            Genre("Martial Arts", "907"),
            Genre("Mature", "20"),
            Genre("Mecha", "908"),
            Genre("Mystery", "909"),
            Genre("Psychological", "910"),
            Genre("Romance", "6"),
            Genre("School Life", "911"),
            Genre("Sci-fi", "912"),
            Genre("Seinen", "913"),
            Genre("Slice of Life", "914"),
            Genre("Smut", "915"),
            Genre("Sports", "916"),
            Genre("Supernatural", "5"),
            Genre("Tragedy", "901"),
        ),
    )

    private class ContentWarning(name: String, val id: String) : Filter.TriState(name)
    private class ContentWarningFilter : Filter.Group<ContentWarning>(
        "Mature Content (0=ignore, 1=include, 2=exclude)",
        listOf(
            ContentWarning("Gore", "48"),
            ContentWarning("Sexual Content", "50"),
            ContentWarning("Strong Language", "49"),
        ),
    )

    private class GenreOperatorFilter : Filter.Select<String>(
        "Genres Operator",
        arrayOf("And", "Or"),
    ) {
        fun toUriPart() = when (state) {
            0 -> "and"
            1 -> "or"
            else -> "and"
        }
    }

    private class ContentWarningOperatorFilter : Filter.Select<String>(
        "Mature Content Operator",
        arrayOf("And", "Or"),
    ) {
        fun toUriPart() = when (state) {
            0 -> "and"
            1 -> "or"
            else -> "and"
        }
    }
}
