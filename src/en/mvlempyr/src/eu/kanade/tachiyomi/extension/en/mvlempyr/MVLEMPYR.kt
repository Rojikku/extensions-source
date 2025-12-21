package eu.kanade.tachiyomi.extension.en.mvlempyr

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Locale

class MVLEMPYR : HttpSource(), NovelSource {

    override val name = "MVLEMPYR"
    override val baseUrl = "https://www.mvlempyr.io"
    override val lang = "en"
    override val supportsLatest = true

    override val isNovelSource = true

    override val client = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Referer", chapSite)
        .add("Origin", chapSite)

    private val json: Json = Json { ignoreUnknownKeys = true }
    private val chapSite = "https://chap.heliosarchive.online"
    private val assetsSite = "https://assets.mvlempyr.app/images/600"

    // WordPress API Response structure
    @Serializable
    private data class WpNovel(
        val id: Int = 0,
        val date: String? = null,
        val slug: String = "",
        val title: WpRendered = WpRendered(),
        val content: WpRendered = WpRendered(),
        val excerpt: WpRendered = WpRendered(),
        @SerialName("featured_media") val featuredMedia: Int = 0,
        val genres: List<Int> = emptyList(),
        val tags: List<Long> = emptyList(),
        @SerialName("author-name") val authorName: String? = null,
        val bookid: String? = null,
        @SerialName("novel-code") val novelCode: Long? = null,
    )

    @Serializable
    private data class WpRendered(
        val rendered: String = "",
    )

    @Serializable
    private data class ChapterPost(
        val id: Int = 0,
        val date: String? = null,
        val link: String? = null,
        val title: WpRendered = WpRendered(),
        val acf: ChapterAcf? = null,
    )

    @Serializable
    private data class ChapterAcf(
        @SerialName("ch_name") val chName: String? = null,
        @SerialName("novel_code") val novelCode: kotlinx.serialization.json.JsonElement? = null,
        @SerialName("chapter_number") val chapterNumber: kotlinx.serialization.json.JsonElement? = null,
    )

    override fun popularMangaRequest(page: Int): Request {
        // Order by comment_count for popularity (or id desc as fallback)
        return GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=20&page=$page&orderby=id&order=desc", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return parseNovelsResponse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=20&page=$page&orderby=date&order=desc", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return parseNovelsResponse(response)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=20&page=$page&search=$encodedQuery", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return parseNovelsResponse(response)
    }

    private fun parseNovelsResponse(response: Response): MangasPage {
        val responseBody = response.body.string()

        // Check pagination headers
        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = currentPage < totalPages

        return try {
            // Parse as JSON array manually to handle the WordPress format
            val jsonArray = json.parseToJsonElement(responseBody).jsonArray

            val novels = jsonArray.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    createSMangaFromJson(obj)
                } catch (e: Exception) {
                    null
                }
            }

            MangasPage(novels, hasNextPage)
        } catch (e: Exception) {
            MangasPage(emptyList(), false)
        }
    }

    private fun createSMangaFromJson(obj: JsonObject): SManga = SManga.create().apply {
        val slug = obj["slug"]?.jsonPrimitive?.content ?: ""

        // Try multiple title fields: "name" (API), "title.rendered" (WP), "title" (fallback)
        val titleRendered = obj["name"]?.jsonPrimitive?.content
            ?: obj["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content
            ?: obj["title"]?.jsonPrimitive?.contentOrNull
            ?: "Untitled"
        val contentRendered = obj["content"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: ""
        val excerptRendered = obj["excerpt"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: ""
        val synopsisText = obj["synopsis-text"]?.jsonPrimitive?.content
            ?: obj["synopsis"]?.jsonPrimitive?.contentOrNull
        val bookId = obj["bookid"]?.jsonPrimitive?.content
        val novelCode = obj["novel-code"]?.jsonPrimitive?.longOrNull
        val authorNameValue = obj["author-name"]?.jsonPrimitive?.content

        url = "/novel/$slug"
        title = cleanHtml(titleRendered)
        author = authorNameValue

        // Use novelCode for thumbnail if available, otherwise bookid
        thumbnail_url = if (novelCode != null) {
            "$assetsSite/$novelCode.webp"
        } else if (!bookId.isNullOrBlank()) {
            "$assetsSite/$bookId.webp"
        } else {
            null
        }

        // Use synopsis-text, synopsis, excerpt or content for description
        description = synopsisText?.let { cleanHtml(it) }
            ?: cleanHtml(excerptRendered.ifBlank { contentRendered })
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title = doc.selectFirst("h1.novel-title")?.text() ?: "Untitled"

            // Parse associated names/alternative titles and include in description
            val associatedNamesText = doc.select("div.additionalinfo.tm10 > div.textwrapper")
                .find { it.selectFirst("span")?.text()?.contains("Associated Names", ignoreCase = true) == true }
                ?.selectFirst("span:last-child, a")?.text()?.trim()

            var desc = doc.selectFirst("div.synopsis.w-richtext")?.text()?.trim() ?: ""
            if (!associatedNamesText.isNullOrBlank()) {
                // Split by common delimiters and clean
                val altTitles = associatedNamesText.split(",", ";", "/", "|")
                    .mapNotNull { it.trim().takeIf { s -> s.isNotBlank() && s != title } }
                    .distinct()
                if (altTitles.isNotEmpty()) {
                    desc = "Alternative Titles: ${altTitles.joinToString(", ")}\n\n$desc"
                }
            }

            description = desc
            author = doc.select("div.additionalinfo.tm10 > div.textwrapper")
                .find { it.selectFirst("span")?.text()?.contains("Author") == true }
                ?.selectFirst("a, span:last-child")?.text() ?: ""
            genre = doc.select(".genre-tags").map { it.text() }.joinToString(", ")
            status = when {
                doc.selectFirst(".novelstatustextlarge")?.text()?.contains("Ongoing", ignoreCase = true) == true -> SManga.ONGOING
                doc.selectFirst(".novelstatustextlarge")?.text()?.contains("Completed", ignoreCase = true) == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = doc.selectFirst("img.novel-image")?.attr("src")
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())

        val novelCode = doc.selectFirst("#novel-code")?.text()?.toLongOrNull() ?: return emptyList()
        val convertedId = convertNovelId(BigInteger.valueOf(novelCode))

        val chapters = mutableListOf<SChapter>()
        var page = 1
        var hasMore = true

        while (hasMore) {
            val chapResponse = client.newCall(
                GET("$chapSite/wp-json/wp/v2/posts?tags=$convertedId&per_page=500&page=$page", headers),
            ).execute()

            val chaptersJson = chapResponse.body.string()
            if (chaptersJson.isBlank() || chaptersJson == "[]") {
                hasMore = false
                continue
            }

            val chapData: List<ChapterPost> = json.decodeFromString(chaptersJson)

            if (chapData.isEmpty()) {
                hasMore = false
                continue
            }

            chapData.forEach { chap ->
                val acf = chap.acf ?: return@forEach
                val chapterName = acf.chName ?: "Chapter"
                val chapterNumberStr = acf.chapterNumber?.jsonPrimitive?.contentOrNull
                    ?: acf.chapterNumber?.jsonPrimitive?.intOrNull?.toString()
                    ?: ""
                val novelCodeStr = acf.novelCode?.jsonPrimitive?.content ?: ""

                chapters.add(
                    SChapter.create().apply {
                        url = "/chapter/$novelCodeStr-$chapterNumberStr"
                        name = chapterName
                        date_upload = parseDate(chap.date)
                        chapter_number = chapterNumberStr.toFloatOrNull() ?: 0f
                    },
                )
            }

            val totalPages = chapResponse.headers["X-Wp-Totalpages"]?.toIntOrNull() ?: 1
            hasMore = page < totalPages
            page++
        }

        return chapters.reversed()
    }

    override fun pageListParse(response: Response): List<Page> {
        // The chapter URL format is /chapter/{novelCode}-{chapterNumber}
        val chapterUrl = response.request.url.toString()
        return listOf(Page(0, chapterUrl))
    }

    override suspend fun fetchPageText(page: Page): String {
        // Chapter content is on chap.heliosarchive.online
        val url = if (page.url.startsWith("http")) {
            page.url
        } else {
            // page.url is like /chapter/{novelCode}-{chapterNumber}
            "$chapSite${page.url}"
        }
        val response = client.newCall(GET(url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())
        // Content is in #chapter-content #chapter based on API docs
        return doc.selectFirst("#chapter-content #chapter")?.html()
            ?: doc.selectFirst("#chapter")?.html()
            ?: doc.selectFirst(".ChapterContent")?.html()
            ?: ""
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        GenreFilter(),
        TagFilter(),
    )

    private fun convertNovelId(code: BigInteger): BigInteger {
        val t = BigInteger("1999999997")
        var u = BigInteger.ONE
        var c = BigInteger("7").mod(t)
        var d = code

        while (d > BigInteger.ZERO) {
            if (d and BigInteger.ONE == BigInteger.ONE) {
                u = u.multiply(c).mod(t)
            }
            c = c.multiply(c).mod(t)
            d = d.shiftRight(1)
        }

        return u
    }

    private fun parseDate(dateString: String?): Long {
        if (dateString == null) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            format.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseCreatedDate(dateString: String?): Long {
        if (dateString == null) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            format.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun cleanHtml(html: String): String {
        return Jsoup.parse(html).text()
    }

    private class SortFilter : Filter.Select<String>(
        "Sort by",
        arrayOf("Most Reviewed", "Best Rated", "Chapter Count", "Latest Added"),
    )

    private class GenreFilter : Filter.Group<Genre>(
        "Genres",
        listOf(
            Genre("Action"), Genre("Adult"), Genre("Adventure"), Genre("Comedy"),
            Genre("Drama"), Genre("Ecchi"), Genre("Fan-Fiction"), Genre("Fantasy"),
            Genre("Gender Bender"), Genre("Harem"), Genre("Historical"), Genre("Horror"),
            Genre("Josei"), Genre("Martial Arts"), Genre("Mature"), Genre("Mecha"),
            Genre("Mystery"), Genre("Psychological"), Genre("Romance"), Genre("School Life"),
            Genre("Sci-fi"), Genre("Seinen"), Genre("Shoujo"), Genre("Shoujo Ai"),
            Genre("Shounen"), Genre("Shounen Ai"), Genre("Slice of Life"), Genre("Smut"),
            Genre("Sports"), Genre("Supernatural"), Genre("Tragedy"), Genre("Wuxia"),
            Genre("Xianxia"), Genre("Xuanhuan"), Genre("Yaoi"), Genre("Yuri"),
        ),
    )

    private class Genre(name: String) : Filter.CheckBox(name)

    private class TagFilter : Filter.Group<TagItem>(
        "Tags",
        listOf(
            TagItem("Academy"), TagItem("Antihero Protagonist"), TagItem("Beast Companions"),
            TagItem("Calm Protagonist"), TagItem("Cheats"), TagItem("Clever Protagonist"),
            TagItem("Cold Protagonist"), TagItem("Cultivation"), TagItem("Cunning Protagonist"),
            TagItem("Dark"), TagItem("Demons"), TagItem("Dragons"), TagItem("Dungeons"),
            TagItem("Fantasy World"), TagItem("Female Protagonist"), TagItem("Game Elements"),
            TagItem("Gods"), TagItem("Hidden Abilities"), TagItem("Level System"),
            TagItem("Magic"), TagItem("Male Protagonist"), TagItem("Monsters"),
            TagItem("Nobles"), TagItem("Overpowered Protagonist"), TagItem("Reincarnation"),
            TagItem("Revenge"), TagItem("Royalty"), TagItem("Second Chance"),
            TagItem("System"), TagItem("Transmigration"), TagItem("Weak to Strong"),
        ),
    )

    private class TagItem(name: String) : Filter.CheckBox(name)
}
