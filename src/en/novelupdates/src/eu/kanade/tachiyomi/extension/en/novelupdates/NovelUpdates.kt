package eu.kanade.tachiyomi.extension.en.novelupdates

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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class NovelUpdates : HttpSource(), NovelSource {

    override val name = "Novel Updates"
    override val baseUrl = "https://www.novelupdates.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    // Novel source implementation
    override suspend fun fetchPageText(page: Page): String {
        // NovelUpdates is an aggregator - chapters link to external sites
        // We need to fetch the external chapter and parse its content
        val chapterUrl = page.url

        val response = client.newCall(GET(chapterUrl, headers)).execute()
        val body = response.body.string()
        val url = response.request.url.toString()
        val domainParts = url.lowercase().split("/")[2].split(".")

        val doc = Jsoup.parse(body, url)

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

        // Try to extract chapter content based on the domain
        return getChapterBody(doc, domainParts, url)
    }

    private fun getChapterBody(doc: Document, domain: List<String>, url: String): String {
        val unwanted = listOf("app", "blogspot", "casper", "wordpress", "www")
        val targetDomain = domain.find { !unwanted.contains(it) }

        var chapterTitle = ""
        var chapterContent = ""

        when (targetDomain) {
            "scribblehub" -> {
                doc.select(".wi_authornotes").remove()
                chapterTitle = doc.select(".chapter-title").first()?.text() ?: ""
                chapterContent = doc.select(".chp_raw").html()
            }
            "webnovel" -> {
                chapterTitle = doc.select(".cha-tit .pr .dib").first()?.text() ?: ""
                chapterContent = doc.select(".cha-words").html().ifEmpty {
                    doc.select("._content").html()
                }
            }
            "wuxiaworld" -> {
                doc.select(".MuiLink-root").remove()
                chapterTitle = doc.select("h4 span").first()?.text() ?: ""
                chapterContent = doc.select(".chapter-content").html()
            }
            "hostednovel" -> {
                chapterTitle = doc.select("#chapter-title").first()?.text() ?: ""
                chapterContent = doc.select("#chapter-content").html()
            }
            "royalroad" -> {
                chapterTitle = doc.select("h1").first()?.text() ?: ""
                chapterContent = doc.select(".chapter-content").html()
            }
            else -> {
                // Generic fallback - try common selectors
                val contentSelectors = listOf(
                    ".chapter-content",
                    ".entry-content",
                    ".post-content",
                    ".content",
                    "#content",
                    ".chapter__content",
                    ".text_story",
                    "article",
                )

                for (selector in contentSelectors) {
                    val content = doc.select(selector).html()
                    if (content.isNotEmpty() && content.length > 100) {
                        chapterContent = content
                        break
                    }
                }

                // Try to find title
                val titleSelectors = listOf(
                    ".chapter-title",
                    ".entry-title",
                    "h1",
                    "h2",
                    ".title",
                )
                for (selector in titleSelectors) {
                    val title = doc.select(selector).first()?.text()
                    if (!title.isNullOrEmpty()) {
                        chapterTitle = title
                        break
                    }
                }
            }
        }

        // Fallback to body content
        if (chapterContent.isEmpty()) {
            doc.select("nav, header, footer, .hidden, script, style").remove()
            chapterContent = doc.select("body").html()
        }

        return if (chapterTitle.isNotEmpty()) {
            "<h2>$chapterTitle</h2><hr><br>$chapterContent"
        } else {
            chapterContent
        }
    }

    // Popular novels
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/series-ranking/?rank=popmonth&pg=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    // Latest updates
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/series-finder/?sf=1&sort=sdate&order=desc&pg=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotEmpty()) {
            val searchTerm = query.replace(Regex("[''']"), "'").replace(Regex("\\s+"), "+")
            "$baseUrl/series-finder/?sf=1&sh=$searchTerm&sort=srank&order=asc&pg=$page"
        } else {
            buildFilterUrl(page, filters)
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    private fun parseNovelsFromSearch(doc: Document): MangasPage {
        val novels = doc.select("div.search_main_box_nu").mapNotNull { element ->
            val titleElement = element.select(".search_title > a").first() ?: return@mapNotNull null
            val novelUrl = titleElement.attr("href")

            SManga.create().apply {
                title = titleElement.text()
                thumbnail_url = element.select("img").attr("src")
                url = novelUrl.removePrefix(baseUrl)
            }
        }

        // Check if there's a next page
        val hasNextPage = doc.select(".digg_pagination a.next_page").isNotEmpty() ||
            doc.select(".pagination a:contains(Next)").isNotEmpty()

        return MangasPage(novels, hasNextPage)
    }

    // Manga details
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title = doc.select(".seriestitlenu").text().ifEmpty { "Untitled" }
            thumbnail_url = doc.select(".wpb_wrapper img").attr("src")

            author = doc.select("#authtag").joinToString(", ") { it.text().trim() }

            genre = doc.select("#seriesgenre a").joinToString(", ") { it.text() }

            status = when {
                doc.select("#editstatus").text().contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                doc.select("#editstatus").text().contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            val type = doc.select("#showtype").text().trim()
            val summary = doc.select("#editdescription").text().trim()
            description = if (type.isNotEmpty()) {
                "$summary\n\nType: $type"
            } else {
                summary
            }
        }
    }

    // Chapter list
    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())

        // Get the novel ID for fetching chapters
        val novelId = doc.select("input#mypostid").attr("value")
        if (novelId.isEmpty()) return emptyList()

        // Fetch chapters via AJAX
        val formBody = FormBody.Builder()
            .add("action", "nd_getchapters")
            .add("mygrr", "0")
            .add("mypostid", novelId)
            .build()

        val chaptersRequest = POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody)
        val chaptersResponse = client.newCall(chaptersRequest).execute()
        val chaptersHtml = chaptersResponse.body.string()

        val chaptersDoc = Jsoup.parse(chaptersHtml)

        return chaptersDoc.select("li.sp_li_chp").mapNotNull { element ->
            val chapterName = element.text()
                .replace("v", "volume ")
                .replace("c", " chapter ")
                .replace("part", "part ")
                .replace("ss", "SS")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                .trim()

            val chapterLink = element.select("a").first()?.nextElementSibling()?.attr("href")
                ?: return@mapNotNull null

            val fullUrl = if (chapterLink.startsWith("//")) {
                "https:$chapterLink"
            } else {
                chapterLink
            }

            SChapter.create().apply {
                name = chapterName
                url = fullUrl // Store the full external URL
                date_upload = 0L
            }
        }.reversed()
    }

    // Page list - return single page with the chapter URL
    override fun pageListRequest(chapter: SChapter): Request {
        // chapter.url contains the external chapter URL
        return GET(chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        // Return single page with the chapter URL for fetchPageText
        return listOf(Page(0, response.request.url.toString(), null))
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
        LanguageFilter(),
        NovelTypeFilter(),
    )

    private fun buildFilterUrl(page: Int, filters: FilterList): String {
        val sortFilter = filters.findInstance<SortFilter>()!!
        val orderFilter = filters.findInstance<OrderFilter>()!!
        val statusFilter = filters.findInstance<StatusFilter>()!!
        val genreFilter = filters.findInstance<GenreFilter>()!!
        val languageFilter = filters.findInstance<LanguageFilter>()!!
        val novelTypeFilter = filters.findInstance<NovelTypeFilter>()!!

        val sortValue = sortFilter.toUriPart()
        val orderValue = orderFilter.toUriPart()

        return when {
            sortValue == "popmonth" || sortValue == "popular" -> {
                "$baseUrl/series-ranking/?rank=$sortValue"
            }
            sortValue == "latest" -> {
                buildString {
                    append("$baseUrl/latest-series/?st=1")

                    val selectedGenres = genreFilter.state.filter { it.state }.map { it.id }
                    if (selectedGenres.isNotEmpty()) {
                        append("&gi=").append(selectedGenres.joinToString(","))
                        append("&mgi=and")
                    }

                    val selectedLanguages = languageFilter.state.filter { it.state }.map { it.id }
                    if (selectedLanguages.isNotEmpty()) {
                        append("&org=").append(selectedLanguages.joinToString(","))
                    }

                    append("&pg=$page")
                }
            }
            else -> {
                buildString {
                    append("$baseUrl/series-finder/?sf=1")

                    val selectedGenres = genreFilter.state.filter { it.state }.map { it.id }
                    if (selectedGenres.isNotEmpty()) {
                        append("&gi=").append(selectedGenres.joinToString(","))
                        append("&mgi=and")
                    }

                    val selectedLanguages = languageFilter.state.filter { it.state }.map { it.id }
                    if (selectedLanguages.isNotEmpty()) {
                        append("&org=").append(selectedLanguages.joinToString(","))
                    }

                    val selectedNovelTypes = novelTypeFilter.state.filter { it.state }.map { it.id }
                    if (selectedNovelTypes.isNotEmpty()) {
                        append("&nt=").append(selectedNovelTypes.joinToString(","))
                    }

                    if (statusFilter.state != 0) {
                        append("&ss=").append(statusFilter.toUriPart())
                    }

                    append("&sort=$sortValue")
                    append("&order=$orderValue")
                    append("&pg=$page")
                }
            }
        }
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    private class SortFilter : Filter.Select<String>(
        "Sort Results By",
        arrayOf(
            "Popular (Month)",
            "Popular (All)",
            "Latest Added",
            "Last Updated",
            "Rating",
            "Rank",
            "Reviews",
            "Chapters",
            "Title",
            "Readers",
            "Frequency",
        ),
    ) {
        fun toUriPart() = when (state) {
            0 -> "popmonth"
            1 -> "popular"
            2 -> "latest"
            3 -> "sdate"
            4 -> "srate"
            5 -> "srank"
            6 -> "sreview"
            7 -> "srel"
            8 -> "abc"
            9 -> "sread"
            10 -> "sfrel"
            else -> "popmonth"
        }
    }

    private class OrderFilter : Filter.Select<String>(
        "Order (Not for Popular)",
        arrayOf("Descending", "Ascending"),
    ) {
        fun toUriPart() = when (state) {
            0 -> "desc"
            1 -> "asc"
            else -> "desc"
        }
    }

    private class StatusFilter : Filter.Select<String>(
        "Story Status (Translation)",
        arrayOf("All", "Completed", "Ongoing", "Hiatus"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "2"
            2 -> "3"
            3 -> "4"
            else -> ""
        }
    }

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)

    private class GenreFilter : Filter.Group<Genre>(
        "Genres",
        listOf(
            Genre("Action", "8"),
            Genre("Adult", "280"),
            Genre("Adventure", "13"),
            Genre("Comedy", "17"),
            Genre("Drama", "9"),
            Genre("Ecchi", "292"),
            Genre("Fantasy", "5"),
            Genre("Gender Bender", "168"),
            Genre("Harem", "3"),
            Genre("Historical", "330"),
            Genre("Horror", "343"),
            Genre("Josei", "324"),
            Genre("Martial Arts", "14"),
            Genre("Mature", "4"),
            Genre("Mecha", "10"),
            Genre("Mystery", "245"),
            Genre("Psychological", "486"),
            Genre("Romance", "15"),
            Genre("School Life", "6"),
            Genre("Sci-fi", "11"),
            Genre("Seinen", "18"),
            Genre("Shoujo", "157"),
            Genre("Shoujo Ai", "851"),
            Genre("Shounen", "12"),
            Genre("Shounen Ai", "1692"),
            Genre("Slice of Life", "7"),
            Genre("Smut", "281"),
            Genre("Sports", "1357"),
            Genre("Supernatural", "16"),
            Genre("Tragedy", "132"),
            Genre("Wuxia", "479"),
            Genre("Xianxia", "480"),
            Genre("Xuanhuan", "3954"),
            Genre("Yaoi", "560"),
            Genre("Yuri", "922"),
        ),
    )

    private class Language(name: String, val id: String) : Filter.CheckBox(name)

    private class LanguageFilter : Filter.Group<Language>(
        "Language",
        listOf(
            Language("Chinese", "495"),
            Language("Filipino", "9181"),
            Language("Indonesian", "9179"),
            Language("Japanese", "496"),
            Language("Khmer", "18657"),
            Language("Korean", "497"),
            Language("Malaysian", "9183"),
            Language("Thai", "9954"),
            Language("Vietnamese", "9177"),
        ),
    )

    private class NovelType(name: String, val id: String) : Filter.CheckBox(name)

    private class NovelTypeFilter : Filter.Group<NovelType>(
        "Novel Type (Not for Popular)",
        listOf(
            NovelType("Light Novel", "2443"),
            NovelType("Published Novel", "26874"),
            NovelType("Web Novel", "2444"),
        ),
    )
}
