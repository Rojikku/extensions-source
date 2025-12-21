package eu.kanade.tachiyomi.extension.en.storyseedling

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy

/**
 * StorySeedling novel source - ported from LN Reader plugin
 * @see https://github.com/LNReader/lnreader-plugins StorySeedling.ts
 * Uses AJAX API with FormData for chapter list (series_toc action)
 */
class StorySeedling : HttpSource(), NovelSource {

    override val name = "StorySeedling"
    override val baseUrl = "https://storyseedling.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient
    private val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", "$baseUrl/")

    // ======================== Turnstile Detection ========================

    /**
     * LN Reader: Detect Cloudflare Turnstile captcha
     * Throws exception if detected to prompt webview open
     */
    private fun checkTurnstile(doc: Document) {
        // LN Reader checks for turnstile in page title or content
        val hasTurnstile = doc.selectFirst("script[src*='challenges.cloudflare.com/turnstile']") != null ||
            doc.title().contains("Turnstile", ignoreCase = true) ||
            doc.selectFirst("[cf-turnstile-response]") != null
        if (hasTurnstile) {
            throw Exception("Cloudflare Turnstile detected, please open in WebView")
        }
    }

    // ======================== Post Value Cache ========================

    /**
     * LN Reader: The browse page has a dynamic post value that must be extracted
     * Format: browse('xxxxx') in div[ax-load][x-data] attribute
     */
    @Volatile
    private var cachedPostValue: String? = null

    private fun getPostValue(): String {
        cachedPostValue?.let { return it }

        // Fetch browse page to extract dynamic post value
        val browseResponse = client.newCall(GET("$baseUrl/browse", headers)).execute()
        val doc = Jsoup.parse(browseResponse.body.string())

        // LN Reader: Extract from div[ax-load][x-data] with format browse('xxxxx')
        val xData = doc.selectFirst("div[ax-load][x-data*=browse]")?.attr("x-data") ?: ""
        val postValue = Regex("""browse\s*\(\s*['"]([^'"]+)['"]\s*\)""").find(xData)?.groupValues?.get(1) ?: "browse"

        cachedPostValue = postValue
        return postValue
    }

    // ======================== Popular/Browse ========================

    override fun popularMangaRequest(page: Int): Request {
        // LN Reader: Uses browse() post value from page, with fetch_browse action
        val postValue = getPostValue()
        return POST(
            "$baseUrl/ajax",
            headers,
            FormBody.Builder()
                .add("search", "")
                .add("orderBy", "recent")
                .add("curpage", page.toString())
                .add("post", postValue)
                .add("action", "fetch_browse")
                .build(),
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val responseBody = response.body.string()
        if (responseBody.isBlank()) return MangasPage(emptyList(), false)

        return try {
            val jsonData = json.parseToJsonElement(responseBody).jsonObject
            val posts = jsonData["data"]?.jsonObject?.get("posts")?.jsonArray ?: return MangasPage(emptyList(), false)

            val mangas = posts.mapNotNull { post ->
                try {
                    val postObj = post.jsonObject
                    val title = postObj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val cover = postObj["thumbnail"]?.jsonPrimitive?.content ?: ""
                    val permalink = postObj["permalink"]?.jsonPrimitive?.content ?: return@mapNotNull null

                    SManga.create().apply {
                        this.title = title
                        thumbnail_url = cover
                        url = permalink.replace(baseUrl, "")
                    }
                } catch (e: Exception) {
                    null
                }
            }

            MangasPage(mangas, mangas.size == 10)
        } catch (e: Exception) {
            MangasPage(emptyList(), false)
        }
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var orderBy = "recent"
        filters.forEach { filter ->
            if (filter is SortFilter) {
                orderBy = filter.toUriPart()
            }
        }

        val postValue = getPostValue()
        return POST(
            "$baseUrl/ajax",
            headers,
            FormBody.Builder()
                .add("search", query)
                .add("orderBy", orderBy)
                .add("curpage", page.toString())
                .add("post", postValue)
                .add("action", "fetch_browse")
                .build(),
        )
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ======================== Details ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        // Check for Turnstile
        checkTurnstile(doc)

        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim() ?: ""

            // LN Reader: img[x-ref="art"].w-full.rounded.shadow-md
            val coverUrl = doc.selectFirst("img[x-ref=\"art\"].w-full.rounded.shadow-md")?.attr("src")
            if (coverUrl != null) {
                thumbnail_url = if (coverUrl.startsWith("http")) coverUrl else "$baseUrl$coverUrl"
            }

            // LN Reader: genres from specific section
            val genres = doc.select(
                "section[x-data=\"{ tab: location.hash.substr(1) || 'chapters' }\"].relative > div > div > div.flex.flex-wrap > a",
            ).map { it.text().trim() }
            genre = genres.joinToString(", ")

            // LN Reader: summary from p tags
            description = doc.select("div.mb-4.text-base p, div.synopsis p")
                .joinToString("\n\n") { it.text().trim() }
                .ifEmpty { doc.selectFirst(".prose, .description")?.text()?.trim() }

            status = when {
                doc.text().contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                doc.text().contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    // ======================== Chapter List ========================

    /**
     * LN Reader: Extracts toc data from x-data attribute
     * Format: toc('dataNovelId', 'dataNovelN') - e.g., toc('000000', 'xxxxxxxxxx')
     */
    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())

        // Check for Turnstile
        checkTurnstile(doc)

        // LN Reader: Extract toc data from x-data attribute - div[ax-load][x-data*=toc]
        // Format: toc('dataNovelId', 'dataNovelN')
        val xData = doc.selectFirst("div[ax-load][x-data*=toc]")?.attr("x-data")
            ?: doc.selectFirst(".bg-accent div[ax-load][x-data]")?.attr("x-data")
            ?: doc.selectFirst("[x-data*=toc]")?.attr("x-data")
            ?: ""

        // Parse toc('dataNovelId', 'dataNovelN') format
        val tocMatch = Regex("""toc\s*\(\s*['"]([^'"]+)['"],\s*['"]([^'"]+)['"]\)""").find(xData)
        val dataNovelId = tocMatch?.groupValues?.get(1)
        val dataNovelN = tocMatch?.groupValues?.get(2)

        if (dataNovelId != null && dataNovelN != null) {
            try {
                // LN Reader: Fetch chapters via AJAX with series_toc action
                // FormData: post=dataNovelN, id=dataNovelId, action=series_toc
                val ajaxResponse = client.newCall(
                    POST(
                        "$baseUrl/ajax",
                        headers,
                        FormBody.Builder()
                            .add("post", dataNovelN)
                            .add("id", dataNovelId)
                            .add("action", "series_toc")
                            .build(),
                    ),
                ).execute()

                val responseBody = ajaxResponse.body.string()
                if (responseBody.isNotBlank()) {
                    val jsonData = json.parseToJsonElement(responseBody).jsonObject
                    val chaptersData = jsonData["data"]

                    // LN Reader: Handle JSON array format [{title, url, date, slug, is_locked}, ...]
                    when {
                        chaptersData?.let { it is kotlinx.serialization.json.JsonArray } == true -> {
                            val chapters = chaptersData.jsonArray.mapNotNull { chapterJson ->
                                try {
                                    val chapterObj = chapterJson.jsonObject
                                    val url = chapterObj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                    val title = chapterObj["title"]?.jsonPrimitive?.content ?: ""
                                    val slug = chapterObj["slug"]?.jsonPrimitive?.content ?: ""
                                    val isLocked = chapterObj["is_locked"]?.jsonPrimitive?.content == "true"

                                    SChapter.create().apply {
                                        this.url = url.replace(baseUrl, "")
                                        this.name = if (isLocked) "🔒 $title" else title
                                        date_upload = 0L
                                        chapter_number = slug.toFloatOrNull() ?: 0f
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            if (chapters.isNotEmpty()) return chapters
                        }
                        // HTML string format (fallback)
                        chaptersData?.jsonPrimitive?.isString == true -> {
                            val chaptersHtml = chaptersData.jsonPrimitive.content
                            if (chaptersHtml.isNotBlank()) {
                                val chaptersDoc = Jsoup.parse(chaptersHtml)

                                val chapters = chaptersDoc.select("a[href*='/chapter/']").mapNotNull { element ->
                                    try {
                                        val url = element.attr("href").replace(baseUrl, "")
                                        val name = element.text().trim()

                                        SChapter.create().apply {
                                            this.url = url
                                            this.name = name
                                            date_upload = 0L
                                        }
                                    } catch (e: Exception) {
                                        null
                                    }
                                }.reversed()

                                if (chapters.isNotEmpty()) return chapters
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Fall through to HTML parsing fallback
            }
        }

        // Fallback to HTML parsing if AJAX fails
        return doc.select("div[x-show=\"tab === 'chapters'\"] a[href*='/chapter/'], a[href*='/chapter/']").mapNotNull { element ->
            try {
                val url = element.attr("href").replace(baseUrl, "")
                val name = element.text().trim()
                if (name.isBlank()) return@mapNotNull null

                SChapter.create().apply {
                    this.url = url
                    this.name = name
                    date_upload = 0L
                }
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.url }.reversed()
    }

    // ======================== Pages ========================

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    // ======================== Novel Content ========================

    /**
     * LN Reader: Chapter content is in div.justify-center > div.mb-4
     * Note: StorySeedling uses Turnstile protection on chapter pages
     * Content is loaded dynamically via loadChapter() JavaScript function
     */
    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())

        // Check for Turnstile - StorySeedling uses loadChapter() with Turnstile
        // The pattern is: x-data="loadChapter('sitekey', 'chapterId')"
        val hasLoadChapter = doc.selectFirst("div[x-data*=loadChapter]") != null
        if (hasLoadChapter) {
            // Check for Turnstile first
            checkTurnstile(doc)
            // If no Turnstile detected but loadChapter exists, content requires JavaScript
            throw Exception("Chapter content requires WebView (Turnstile protection). Please read in WebView.")
        }

        // Check for standard Turnstile
        checkTurnstile(doc)

        // LN Reader: div.justify-center > div.mb-4
        // Try multiple approaches to find content
        val content = doc.selectFirst("div.justify-center > div.mb-4")?.html()
            ?: doc.select("div.justify-center").firstOrNull()
                ?.select("> div.mb-4")?.firstOrNull()?.html()
            // Fallback: find div.mb-4 that's inside justify-center
            ?: doc.select("div.mb-4").firstOrNull { element ->
                element.parent()?.hasClass("justify-center") == true
            }?.html()
            // Last fallback: any content div
            ?: doc.selectFirst(".prose")?.html()
            ?: ""

        // Filter out HC content like TypeScript plugin does
        // Remove any span containing "storyseedling" or "story seedling" (case insensitive)
        val cleanedDoc = Jsoup.parse(content)
        cleanedDoc.select("span").forEach { span ->
            val text = span.text().lowercase()
            if (text.contains("storyseedling") || text.contains("story seedling")) {
                span.text("")
            }
        }

        return cleanedDoc.html()
    }

    // Image URL - not used for novels
    override fun imageUrlParse(response: Response): String = ""

    override fun getFilterList() = FilterList(
        SortFilter(),
    )

    private class SortFilter : Filter.Select<String>(
        "Order By",
        arrayOf("Recent", "Popular", "Alphabetical", "Rating"),
    ) {
        fun toUriPart() = when (state) {
            0 -> "recent"
            1 -> "views"
            2 -> "title"
            3 -> "rating"
            else -> "recent"
        }
    }
}
