package eu.kanade.tachiyomi.extension.en.mtlreader

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
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
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MtlReader : HttpSource(), NovelSource {

    override val name = "MTL Reader"
    override val baseUrl = "https://mtlreader.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    // Note: isNovel=true is set in build.gradle

    // Cache token for search
    private var searchToken: String? = null

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

    // Search - MTLReader uses token-based search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Get token if not cached
        if (searchToken == null) {
            val tokenResponse = client.newCall(GET(baseUrl, headers)).execute()
            val tokenDoc = Jsoup.parse(tokenResponse.body.string())
            searchToken = tokenDoc.selectFirst("input[name=_token]")?.attr("value")
        }

        val url = if (searchToken.isNullOrEmpty()) {
            "$baseUrl/search?input=${java.net.URLEncoder.encode(query, "UTF-8")}"
        } else {
            "$baseUrl/search?_token=$searchToken&input=${java.net.URLEncoder.encode(query, "UTF-8")}"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    private fun parseNovelList(doc: Document): MangasPage {
        val novels = doc.select(".property_item").mapNotNull { element ->
            try {
                val h5 = element.selectFirst("h5") ?: return@mapNotNull null
                val link = h5.selectFirst("a") ?: return@mapNotNull null
                val url = link.attr("href").replace(baseUrl, "")
                val title = link.text().trim()
                    .ifEmpty { link.attr("title") }
                    .ifEmpty { h5.text().trim() }
                val img = element.selectFirst("img")
                val cover = img?.attr("src")

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
            // Get title from agent-title
            title = doc.selectFirst(".agent-title")?.text()?.trim() ?: ""

            // Get cover from agent-p-img
            thumbnail_url = doc.selectFirst(".agent-p-img > img")?.attr("src")
                ?: doc.selectFirst("img.thumbnail")?.attr("src")
                ?: doc.selectFirst(".property_img img")?.attr("src")

            // Get description
            description = doc.selectFirst("#editdescription")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst(".novel-description")?.text()
                ?: ""

            // Get author from fa-user icon
            author = doc.selectFirst("i.fa-user")?.parent()?.text()
                ?.replace("Author:", "")?.trim()
                ?.replace("Author：", "")?.trim()
                ?: doc.selectFirst(".novel-author a")?.text()
                ?: doc.selectFirst(".author")?.text()

            // Get alt titles/aliases
            val aliasesElement = doc.select(".agent-p-contact div.mb-2:contains(Aliases:)").firstOrNull()
            val aliasesText = aliasesElement?.text()
                ?.replace("Aliases:", "")?.trim()
                ?.replace("Aliases：", "")?.trim()

            if (!aliasesText.isNullOrEmpty()) {
                // Store in genre field if no other genre field exists
                genre = "Alt Title: $aliasesText"
            }

            // Get genres
            val genres = doc.select(".novel-genre a, .genre a").map { it.text() }
            if (genres.isNotEmpty()) {
                genre = if (genre.isNullOrEmpty()) {
                    genres.joinToString(", ")
                } else {
                    "$genre | ${genres.joinToString(", ")}"
                }
            }

            status = SManga.UNKNOWN
        }
    }

    // Chapter list
    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val allChapters = mutableListOf<SChapter>()
        var page = 1
        var hasNextPage = true
        val mangaUrl = response.request.url.encodedPath
        var currentDoc = Jsoup.parse(response.body.string())

        while (hasNextPage) {
            // Parse chapters from current page
            val chapters = parseChaptersFromPage(currentDoc)
            allChapters.addAll(chapters)

            // Check for next page
            val nextPageElement = currentDoc.selectFirst(
                """
                .page-item a[rel="next"], 
                .pagination-scrollbar a.page-link:contains(›)
                """.trimIndent(),
            )

            if (nextPageElement != null && nextPageElement.hasAttr("href")) {
                try {
                    val nextPageUrl = nextPageElement.attr("href")
                    val resp = client.newCall(GET(nextPageUrl, headers)).execute()
                    currentDoc = Jsoup.parse(resp.body.string())
                    page++

                    // Safety limit
                    if (page > 100) break
                } catch (e: Exception) {
                    break
                }
            } else {
                hasNextPage = false
            }
        }

        return allChapters
    }

    private fun parseChaptersFromPage(doc: Document): List<SChapter> {
        return doc.select("table.table-hover tbody tr, table.table tbody tr").mapNotNull { row ->
            try {
                val link = row.selectFirst("a[href*=/chapters/]") ?: return@mapNotNull null
                val chapterUrl = link.attr("href").replace(baseUrl, "")
                val chapterName = link.text().trim()
                val dateText = row.selectFirst("td:last-child")?.text()?.trim() ?: ""

                SChapter.create().apply {
                    url = chapterUrl
                    name = chapterName
                    date_upload = parseRelativeDate(dateText)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // Page list - returns single page with chapter URL for fetchPageText
    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    override fun imageUrlParse(response: Response): String = ""

    // Chapter content extraction
    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())

        // Remove unwanted elements
        doc.select("ins, script, .mtlreader, .fb-like, nav, header, footer").remove()

        // Strategy 1: Try to find div with style containing "font-family: Arial; font-size: 18px;"
        var contentDiv = doc.selectFirst("div[style*=\"font-family: Arial; font-size: 18px;\"]")

        // Strategy 2: Try to find div with font-size: 18px (any font family)
        if (contentDiv == null) {
            contentDiv = doc.selectFirst("div[style*=\"font-size: 18px\"]")
        }

        // Strategy 3: Look for the div after the container that has the chapter title
        if (contentDiv == null) {
            val titleContainer = doc.selectFirst("div.container:has(div[style*=\"font-size: 30px\"])")
            if (titleContainer != null) {
                val nextContainer = titleContainer.nextElementSibling()
                if (nextContainer != null && nextContainer.tagName() == "div" && nextContainer.hasClass("container")) {
                    val potentialDivs = nextContainer.select("div")
                    for (div in potentialDivs) {
                        val text = div.text().trim()
                        if (text.length > 100) { // Reasonable chapter length threshold
                            contentDiv = div
                            break
                        }
                    }
                }
            }
        }

        // Strategy 4: Last resort - find any div with substantial text
        if (contentDiv == null) {
            var maxTextLength = 0
            var bestDiv: Element? = null

            val allDivs = doc.select("div")
            for (div in allDivs) {
                val text = div.text().trim()
                if (text.length > maxTextLength && text.length > 100) {
                    maxTextLength = text.length
                    bestDiv = div
                }
            }

            contentDiv = bestDiv
        }

        return contentDiv?.html()?.trim() ?: ""
    }

    private fun parseRelativeDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L

        val calendar = Calendar.getInstance()
        val date = dateStr.lowercase(Locale.US)

        return try {
            when {
                date.contains("ago") -> {
                    val match = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""").find(date)
                    if (match != null) {
                        val amount = match.groupValues[1].toInt()
                        val unit = match.groupValues[2]

                        when (unit) {
                            "second" -> calendar.add(Calendar.SECOND, -amount)
                            "minute" -> calendar.add(Calendar.MINUTE, -amount)
                            "hour" -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
                            "day" -> calendar.add(Calendar.DAY_OF_MONTH, -amount)
                            "week" -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
                            "month" -> calendar.add(Calendar.MONTH, -amount)
                            "year" -> calendar.add(Calendar.YEAR, -amount)
                        }
                        calendar.timeInMillis
                    } else {
                        0L
                    }
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
