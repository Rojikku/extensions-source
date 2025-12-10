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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Locale

class MVLEMPYR : HttpSource(), NovelSource {

    override val name = "MVLEMPYR"
    override val baseUrl = "https://www.mvlempyr.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()
    private val chapSite = "https://chap.heliosarchive.online"
    private val assetsSite = "https://assets.mvlempyr.app/images/600"

    // Cache for all novels
    private var cachedNovels: List<NovelApiData>? = null

    @Serializable
    private data class NovelApiData(
        @SerialName("author-name") val authorName: String? = null,
        val name: String? = null,
        val slug: String = "",
        @SerialName("associated-names") val associatedNames: String? = null,
        val genre: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        @SerialName("synopsis-text") val synopsisText: String? = null,
        @SerialName("novel-code") val novelCode: Int = 0,
        @SerialName("total-chapters") val totalChapters: Int = 0,
        @SerialName("average-review") val averageReview: Double = 0.0,
        @SerialName("read-link") val readLink: String? = null,
        val status: String? = null,
        val createdOn: String? = null,
        val language: String? = null,
        val synopsis: String? = null,
        @SerialName("total-reviews") val totalReviews: Int = 0,
        val rank: Int = 0,
        @SerialName("monthly-rank") val monthlyRank: Int = 0,
        @SerialName("weekly-rank") val weeklyRank: Int = 0,
    )

    @Serializable
    private data class ChapterPost(
        val id: Int = 0,
        val date: String? = null,
        val acf: ChapterAcf? = null,
    )

    @Serializable
    private data class ChapterAcf(
        @SerialName("ch_name") val chName: String? = null,
        @SerialName("novel_code") val novelCode: String? = null,
        @SerialName("chapter_number") val chapterNumber: String? = null,
    )

    // Fetch all novels from API
    private fun getAllNovels(): List<NovelApiData> {
        cachedNovels?.let { return it }

        val response = client.newCall(
            GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=10000&page=1", headers),
        ).execute()

        val novels = json.decodeFromString<List<NovelApiData>>(response.body.string())
        cachedNovels = novels
        return novels
    }

    override fun popularMangaRequest(page: Int): Request {
        return GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=10000&page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val novels = getAllNovels()
        val sorted = novels.sortedByDescending { it.totalReviews }

        val url = response.request.url.toString()
        val pageMatch = Regex("page=(\\d+)").find(url)
        val currentPage = pageMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val paginatedNovels = sorted.drop((currentPage - 1) * 20).take(20)
            .map { createSManga(it) }

        return MangasPage(paginatedNovels, sorted.size > currentPage * 20)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=10000&page=$page&_sort=created", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val novels = getAllNovels()
        val sorted = novels.sortedByDescending { parseCreatedDate(it.createdOn) }

        val url = response.request.url.toString()
        val pageMatch = Regex("page=(\\d+)").find(url)
        val currentPage = pageMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val paginatedNovels = sorted.drop((currentPage - 1) * 20).take(20)
            .map { createSManga(it) }

        return MangasPage(paginatedNovels, sorted.size > currentPage * 20)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=10000&page=$page&search=$query", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val allNovels = getAllNovels()

        val url = response.request.url.toString()
        val searchMatch = Regex("search=([^&]+)").find(url)
        val query = searchMatch?.groupValues?.get(1)?.lowercase() ?: ""
        val pageMatch = Regex("page=(\\d+)").find(url)
        val currentPage = pageMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val filtered = if (query.isNotEmpty()) {
            allNovels.filter { novel ->
                novel.name?.lowercase()?.contains(query) == true ||
                    novel.associatedNames?.lowercase()?.contains(query) == true
            }
        } else {
            allNovels
        }

        val paginatedNovels = filtered.drop((currentPage - 1) * 20).take(20)
            .map { createSManga(it) }

        return MangasPage(paginatedNovels, filtered.size > currentPage * 20)
    }

    private fun createSManga(novel: NovelApiData): SManga = SManga.create().apply {
        url = "/novel/${novel.slug}"
        title = novel.name ?: "Untitled"
        thumbnail_url = "$assetsSite/${novel.novelCode}.webp"
        author = novel.authorName
        genre = novel.genre.joinToString(", ")
        description = novel.synopsisText ?: cleanHtml(novel.synopsis ?: "")
        status = when (novel.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title = doc.selectFirst("h1.novel-title")?.text() ?: "Untitled"
            description = doc.selectFirst("div.synopsis.w-richtext")?.text()?.trim() ?: ""
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

            val chapData = json.decodeFromString<List<ChapterPost>>(chaptersJson)

            if (chapData.isEmpty()) {
                hasMore = false
                continue
            }

            chapData.forEach { chap ->
                val acf = chap.acf ?: return@forEach
                val chapterName = acf.chName ?: "Chapter"
                val chapterNumber = acf.chapterNumber ?: ""
                val novelCodeStr = acf.novelCode ?: ""

                chapters.add(
                    SChapter.create().apply {
                        url = "/chapter/$novelCodeStr-$chapterNumber"
                        name = chapterName
                        date_upload = parseDate(chap.date)
                        chapter_number = chapterNumber.toFloatOrNull() ?: 0f
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
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())
        return doc.selectFirst("#chapter")?.html() ?: ""
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
