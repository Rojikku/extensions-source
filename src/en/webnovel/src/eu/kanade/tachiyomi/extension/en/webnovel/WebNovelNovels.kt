package eu.kanade.tachiyomi.extension.en.webnovel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class WebNovelNovels : HttpSource(), NovelSource {

    override val name = "Webnovel Novels"

    override val baseUrl = "https://www.webnovel.com"

    override val lang = "en"

    override val supportsLatest = true

    override val isNovelSource = true

    override val client = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/stories/novel?orderBy=1&pageIndex=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        val mangas = document.select(".j_category_wrapper li").map { element ->
            SManga.create().apply {
                val thumb = element.selectFirst(".g_thumb")!!
                title = thumb.attr("title")
                setUrlWithoutDomain(thumb.attr("href"))
                thumbnail_url = "https:" + element.selectFirst(".g_thumb > img")?.attr("data-original")
            }
        }
        // Check if there are more pages - webnovel uses infinite scroll with pageIndex
        val hasNextPage = mangas.isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/stories/novel?orderBy=5&pageIndex=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/search?keywords=$query&pageIndex=$page", headers)
        }

        // Filters
        var gender = "1" // Male default
        var genre = ""
        var status = "0"
        var sort = "1"
        var type = "0"

        filters.forEach { filter ->
            when (filter) {
                is GenderFilter -> gender = filter.toUriPart()
                is SortFilter -> sort = filter.toUriPart()
                is StatusFilter -> status = filter.toUriPart()
                is TypeFilter -> type = filter.toUriPart()
                is MaleGenreFilter -> {
                    if (gender == "1" && filter.state != 0) {
                        genre = filter.toUriPart()
                    }
                }
                is FemaleGenreFilter -> {
                    if (gender == "2" && filter.state != 0) {
                        genre = filter.toUriPart()
                    }
                }
                else -> {}
            }
        }

        val builder = "$baseUrl/stories".toHttpUrl().newBuilder()

        if (genre.isNotEmpty()) {
            return GET("$baseUrl/stories/$genre?bookStatus=$status&orderBy=$sort&pageIndex=$page", headers)
        } else {
            builder.addPathSegment("novel")
            builder.addQueryParameter("gender", gender)
        }

        if (type != "3") {
            if (type != "0") builder.addQueryParameter("sourceType", type)
        } else {
            builder.addQueryParameter("translateMode", "3")
            builder.addQueryParameter("sourceType", "1")
        }

        builder.addQueryParameter("bookStatus", status)
        builder.addQueryParameter("orderBy", sort)
        builder.addQueryParameter("pageIndex", page.toString())

        return GET(builder.build().toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        val mangas = document.select(".j_list_container li, .j_category_wrapper li").map { element ->
            SManga.create().apply {
                val thumb = element.selectFirst(".g_thumb")!!
                title = thumb.attr("title")
                setUrlWithoutDomain(thumb.attr("href"))
                thumbnail_url = "https:" + (
                    element.selectFirst(".g_thumb > img")?.attr("src")
                        ?: element.selectFirst(".g_thumb > img")?.attr("data-original")
                    )
            }
        }
        val hasNextPage = mangas.isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    // Details
    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())
        return SManga.create().apply {
            title = document.selectFirst(".g_thumb > img")?.attr("alt") ?: "No Title"
            thumbnail_url = "https:" + document.selectFirst(".g_thumb > img")?.attr("src")
            description = document.select(".j_synopsis > p").joinToString("\n") { it.text() }
            author = document.select(".det-info .c_s").firstOrNull { it.text().contains("Author") }?.nextElementSibling()?.text()
            genre = document.select(".det-hd-detail > .det-hd-tag").attr("title")
            status = when (document.select(".det-hd-detail svg").firstOrNull { it.attr("title") == "Status" }?.nextElementSibling()?.text()?.trim()) {
                "Completed" -> SManga.COMPLETED
                "Ongoing" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters
    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url + "/catalog", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())
        val chapters = mutableListOf<SChapter>()
        document.select(".volume-item").forEach volumeLoop@{ volumeItem ->
            val volumeName = volumeItem.ownText().trim().let { text ->
                val match = Regex("Volume\\s(\\d+)").find(text)
                if (match != null) "Volume ${match.groupValues[1]}" else "Unknown Volume"
            }

            volumeItem.select("li").forEach chapterLoop@{ li ->
                val a = li.selectFirst("a") ?: return@chapterLoop
                val chapter = SChapter.create().apply {
                    val rawName = a.attr("title").trim()
                    name = "$volumeName: $rawName"
                    setUrlWithoutDomain(a.attr("href"))
                    // Locked check
                    if (li.select("svg").isNotEmpty()) {
                        name += " \uD83D\uDD12" // Lock emoji
                    }
                }
                chapters.add(chapter)
            }
        }
        return chapters.reversed()
    }

    // Pages - novel content - return single page with chapter URL for text fetching
    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    override fun imageUrlParse(response: Response): String = ""

    // Novel content
    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val document = Jsoup.parse(response.body.string())

        // Remove bloat elements (same as TS plugin)
        document.select(".para-comment").remove()

        // TS plugin: .cha-tit + .cha-words
        val title = document.selectFirst(".cha-tit")?.html() ?: ""
        val content = document.selectFirst(".cha-words")?.html() ?: ""

        return if (title.isNotEmpty() || content.isNotEmpty()) {
            "$title$content"
        } else {
            // Fallback
            document.selectFirst(".cha-content")?.html() ?: ""
        }
    }

    // Filters
    override fun getFilterList() = FilterList(
        GenderFilter(),
        MaleGenreFilter(),
        FemaleGenreFilter(),
        StatusFilter(),
        SortFilter(),
        TypeFilter(),
    )

    private class GenderFilter : Filter.Select<String>("Gender", arrayOf("Male", "Female"), 0) {
        fun toUriPart() = if (state == 0) "1" else "2"
    }

    private class MaleGenreFilter : Filter.Select<String>(
        "Male Genres",
        arrayOf("All", "Action", "ACG", "Eastern", "Fantasy", "Games", "History", "Horror", "Realistic", "Sci-fi", "Sports", "Urban", "War"),
        0,
    ) {
        private val vals = arrayOf(
            "1", "novel-action-male", "novel-acg-male", "novel-eastern-male", "novel-fantasy-male",
            "novel-games-male", "novel-history-male", "novel-horror-male", "novel-realistic-male",
            "novel-scifi-male", "novel-sports-male", "novel-urban-male", "novel-war-male",
        )
        fun toUriPart() = vals[state]
    }

    private class FemaleGenreFilter : Filter.Select<String>(
        "Female Genres",
        arrayOf("All", "Fantasy", "General", "History", "LGBT+", "Sci-fi", "Teen", "Urban"),
        0,
    ) {
        private val vals = arrayOf(
            "2",
            "novel-fantasy-female",
            "novel-general-female",
            "novel-history-female",
            "novel-lgbt-female",
            "novel-scifi-female",
            "novel-teen-female",
            "novel-urban-female",
        )
        fun toUriPart() = vals[state]
    }

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("All", "Ongoing", "Completed"), 0) {
        fun toUriPart() = when (state) {
            1 -> "1"
            2 -> "2"
            else -> "0"
        }
    }

    private class SortFilter : Filter.Select<String>(
        "Sort By",
        arrayOf("Popular", "Recommended", "Most Collections", "Rating", "Time Updated"),
        0,
    ) {
        fun toUriPart() = (state + 1).toString()
    }

    private class TypeFilter : Filter.Select<String>("Type", arrayOf("All", "Translate", "Original", "MTL"), 0) {
        fun toUriPart() = state.toString()
    }
}
