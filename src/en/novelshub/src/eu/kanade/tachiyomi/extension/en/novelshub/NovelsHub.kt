package eu.kanade.tachiyomi.extension.en.novelshub

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

/**
 * NovelsHub.org - Novel reading extension
 * Uses RSC (React Server Components) for data fetching
 * @see instructions.html for RSC parsing details
 */
class NovelsHub : HttpSource(), NovelSource {

    override val name = "NovelsHub"
    override val baseUrl = "https://novelshub.org"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient
    private val json: Json by injectLazy()

    // RSC headers - required for x-component response
    private fun rscHeaders(): Headers = headers.newBuilder()
        .add("rsc", "1")
        .add("Accept", "*/*")
        .build()

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/?page=$page", rscHeaders())
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        val novels = mutableListOf<SManga>()
        val doc = Jsoup.parse(body)

        // Per instructions.html: Popular novels from wrapper elements with cover image
        // Selector: div.wrapper img.cover-image + h1 for title
        doc.select("div.wrapper").forEach { wrapper ->
            try {
                val img = wrapper.selectFirst("img.cover-image, img[alt*=Cover]")
                val cover = img?.attr("src")?.ifEmpty { null } ?: img?.attr("data-nimg")
                val titleElement = wrapper.selectFirst("h1")
                val title = titleElement?.text()?.trim() ?: return@forEach

                // Find the parent link to get URL
                val link = wrapper.parent()?.closest("a[href*=/series/]")
                    ?: wrapper.selectFirst("a[href*=/series/]")
                val url = link?.attr("href")?.replace(baseUrl, "") ?: return@forEach

                novels.add(
                    SManga.create().apply {
                        this.title = title
                        this.url = url
                        thumbnail_url = cover
                    },
                )
            } catch (e: Exception) {
                // Skip
            }
        }

        // Per instructions.html: Latest novels from figure elements
        // figure.relative > a[href*=/series/] with img and title link
        doc.select("figure.relative, figure").forEach { figure ->
            try {
                val link = figure.selectFirst("a[href*=/series/]") ?: return@forEach
                val url = link.attr("href").replace(baseUrl, "")
                if (url.isEmpty() || novels.any { it.url == url }) return@forEach

                val img = figure.selectFirst("img")
                val cover = img?.attr("src")?.ifEmpty { null } ?: img?.attr("data-nimg")

                val titleLink = figure.selectFirst("a.text-sm, a.font-bold, a[title]")
                    ?: figure.select("a[href*=/series/]").lastOrNull()
                val title = titleLink?.attr("title")?.ifEmpty { null }
                    ?: titleLink?.text()?.trim()
                    ?: link.attr("title")
                    ?: return@forEach

                novels.add(
                    SManga.create().apply {
                        this.title = title
                        this.url = url
                        thumbnail_url = cover
                    },
                )
            } catch (e: Exception) {
                // Skip
            }
        }

        // Additional: Try card-group elements
        doc.select(".card-group, div[class*=card]").forEach { card ->
            try {
                val link = card.selectFirst("a[href*=/series/]") ?: return@forEach
                val url = link.attr("href").replace(baseUrl, "")
                if (url.isEmpty() || novels.any { it.url == url }) return@forEach

                val img = card.selectFirst("img")
                val cover = img?.attr("src")?.ifEmpty { null }

                val title = link.attr("title")?.ifEmpty { null }
                    ?: card.selectFirst("a.font-bold, .line-clamp-2")?.text()?.trim()
                    ?: return@forEach

                novels.add(
                    SManga.create().apply {
                        this.title = title
                        this.url = url
                        thumbnail_url = cover
                    },
                )
            } catch (e: Exception) {
                // Skip
            }
        }

        // Fallback: Parse RSC response for JSON objects with novel data
        // The RSC response contains embedded JSON with "slug", "postTitle", "featuredImage" fields
        if (novels.isEmpty()) {
            // Pattern to extract novel data: "slug":"xxx","postTitle":"xxx"
            val slugTitlePattern = Regex(""""slug"\s*:\s*"([^"]+)"\s*,\s*"postTitle"\s*:\s*"([^"]+)"""")
            val imagePattern = Regex(""""featuredImage"\s*:\s*"([^"]+)"""")

            // Find all slug/title pairs
            slugTitlePattern.findAll(body).forEach { match ->
                try {
                    val slug = match.groupValues[1]
                    val title = match.groupValues[2]

                    // Skip chapter slugs (like "chapter-1")
                    if (slug.startsWith("chapter-")) return@forEach

                    // Try to find the corresponding image (search nearby in the text)
                    val startIdx = maxOf(0, match.range.first - 200)
                    val endIdx = minOf(body.length, match.range.last + 500)
                    val nearbyText = body.substring(startIdx, endIdx)
                    val cover = imagePattern.find(nearbyText)?.groupValues?.get(1)

                    novels.add(
                        SManga.create().apply {
                            this.title = title
                            url = "/series/$slug"
                            thumbnail_url = cover
                        },
                    )
                } catch (e: Exception) {
                    // Skip malformed entries
                }
            }
        }

        return MangasPage(novels.distinctBy { it.url }, novels.size >= 10)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search?q=$encodedQuery&page=$page", rscHeaders())
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, rscHeaders())
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()

        return SManga.create().apply {
            // Extract postTitle
            title = Regex(""""postTitle"\s*:\s*"([^"]+)"""").find(body)
                ?.groupValues?.get(1) ?: ""

            // Extract postContent (description) - HTML content
            val postContent = Regex(""""postContent"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)
                ?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\n", "\n")
                ?.replace("\\/", "/")
            description = postContent?.let { Jsoup.parse(it).text() }

            // Extract featuredImage for cover
            thumbnail_url = Regex(""""featuredImage"\s*:\s*"([^"]+)"""").find(body)
                ?.groupValues?.get(1)
                ?: Regex(""""ImageObject"[^}]*"url"\s*:\s*"([^"]+)"""").find(body)
                    ?.groupValues?.get(1)

            // Extract author
            author = Regex(""""author"\s*:\s*"([^"]+)"""").find(body)
                ?.groupValues?.get(1)

            // Extract genres array
            val genresMatch = Regex(""""genres"\s*:\s*\[(.*?)\]""").find(body)
            genre = genresMatch?.groupValues?.get(1)?.let { genresStr ->
                Regex(""""name"\s*:\s*"([^"]+)"""").findAll(genresStr)
                    .map { it.groupValues[1].trim() }
                    .joinToString(", ")
            }

            // Extract status
            val statusStr = Regex(""""seriesStatus"\s*:\s*"([^"]+)"""").find(body)
                ?.groupValues?.get(1)
            status = when (statusStr?.uppercase()) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, rscHeaders())
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val novelPath = response.request.url.encodedPath

        val chapters = mutableListOf<SChapter>()

        // Per instructions.html: Look for "_count":{"chapters":N} for total chapter count
        val totalChapters = Regex(""""_count"\s*:\s*\{[^}]*"chapters"\s*:\s*(\d+)""").find(body)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""Chapters\s*\(\s*(\d+)\s*\)""").find(body)
                ?.groupValues?.get(1)?.toIntOrNull()
            ?: 0

        // Extract novel slug for URL construction
        val novelSlug = Regex(""""slug"\s*:\s*"([^"]+)"""").find(body)
            ?.groupValues?.get(1)
            ?: novelPath.split("/").lastOrNull { it.isNotEmpty() }
            ?: return emptyList()

        // First, try to extract chapters directly from the RSC response
        // RSC format has: {"id":152012,"slug":"chapter-166","number":166,"title":"",..."mangaPost":{...},...}
        // Use simpler regex that matches "slug":"chapter-X" and "number":X pairs directly
        // Find all occurrences of "id":...,slug":"chapter-X","number":X pattern (before mangaPost)
        Regex(""""id":\d+,"slug":"(chapter-\d+)","number":(\d+)""")
            .findAll(body)
            .forEach { match ->
                val slug = match.groupValues[1]
                val number = match.groupValues[2].toIntOrNull() ?: return@forEach
                chapters.add(
                    SChapter.create().apply {
                        url = "/series/$novelSlug/$slug"
                        name = "Chapter $number"
                        chapter_number = number.toFloat()
                    },
                )
            }

        // Fallback: generate chapters from _count if parsing failed
        if (chapters.isEmpty() && totalChapters > 0) {
            for (chapterNum in 1..totalChapters) {
                chapters.add(
                    SChapter.create().apply {
                        url = "/series/$novelSlug/chapter-$chapterNum"
                        name = "Chapter $chapterNum"
                        chapter_number = chapterNum.toFloat()
                    },
                )
            }
        }

        return chapters.reversed()
    }

    // ======================== Pages ========================

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    override fun imageUrlParse(response: Response): String = ""

    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        // Per instructions.html: Use RSC request with rsc:1 header
        val rscRequest = GET(baseUrl + page.url, rscHeaders())
        val response = client.newCall(rscRequest).execute()
        val body = response.body.string()

        // RSC T-tag format: NUMBER:THEX,<p>content</p>
        // Example: 21:T4844,<p>-----------------------------------------------------------------</p><p>Translator...
        // The pattern is: digits:T followed by hex digits, then comma, then HTML content
        val tTagPattern = Regex("""\d+:T[0-9a-f]+,(<p>.*)""", RegexOption.DOT_MATCHES_ALL)
        val tTagMatch = tTagPattern.find(body)

        if (tTagMatch != null) {
            var content = tTagMatch.groupValues[1]

            // The content usually ends before the next JSON block (e.g., 4:["$","$Lc",...)
            // We look for the last closing paragraph tag
            val lastP = content.lastIndexOf("</p>")
            if (lastP != -1) {
                content = content.substring(0, lastP + 4)
            }

            // Clean up the content - remove separator lines and metadata
            content = content.replace(Regex("""<p>-+</p>"""), "")
            content = content.replace(Regex("""<p>Translator:.*?</p>""", RegexOption.IGNORE_CASE), "")
            content = content.replace(Regex("""<p>Chapter:.*?</p>""", RegexOption.IGNORE_CASE), "")
            content = content.replace(Regex("""<p>Chapter Title:.*?</p>""", RegexOption.IGNORE_CASE), "")

            // Remove any trailing JSON artifacts if they slipped through
            if (content.contains(":[") || content.contains("\":")) {
                val jsonStart = content.indexOf("\":[")
                if (jsonStart != -1) {
                    content = content.substring(0, jsonStart)
                    val lastValidP = content.lastIndexOf("</p>")
                    if (lastValidP != -1) {
                        content = content.substring(0, lastValidP + 4)
                    }
                }
            }

            return content.trim()
        }

        // Alternative pattern: look for "content" field reference
        val contentPattern = Regex(""""content"\s*:\s*"\$(\d+)"""")
        val contentMatch = contentPattern.find(body)
        if (contentMatch != null) {
            val contentRef = contentMatch.groupValues[1]
            // Find the referenced content block
            val refPattern = Regex("""$contentRef:T[0-9a-f]+,(.+?)(?=\d+:\[|\d+:|$)""", RegexOption.DOT_MATCHES_ALL)
            val refMatch = refPattern.find(body)
            if (refMatch != null) {
                return refMatch.groupValues[1].trim()
            }
        }

        // Fallback: Extract all <p> tags that look like content (not metadata)
        val paragraphs = Regex("""<p>([^<]*(?:(?!</p>)<[^<]*)*)</p>""").findAll(body)
            .map { it.value }
            .filter { p ->
                !p.contains("---") &&
                    !p.contains("Translator:") &&
                    !p.contains("Chapter Title:") &&
                    !p.startsWith("<p>Chapter:") &&
                    p.length > 20
            }
            .toList()

        if (paragraphs.isNotEmpty()) {
            return paragraphs.joinToString("\n")
        }

        // Last resort: Try HTML parsing
        val doc = Jsoup.parse(body)
        return doc.selectFirst("div.prose, article, .chapter-content")?.html() ?: ""
    }

    override fun getFilterList(): FilterList = FilterList()
}

private val kotlinx.serialization.json.JsonElement.jsonObject: kotlinx.serialization.json.JsonObject
    get() = this as kotlinx.serialization.json.JsonObject

private val kotlinx.serialization.json.JsonElement.jsonPrimitive: kotlinx.serialization.json.JsonPrimitive
    get() = this as kotlinx.serialization.json.JsonPrimitive

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else null
