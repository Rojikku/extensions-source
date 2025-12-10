package eu.kanade.tachiyomi.extension.en.storyseedling

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.NovelSource
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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy

class StorySeedling : HttpSource(), NovelSource {

    override val name = "StorySeedling"
    override val baseUrl = "https://storyseedling.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient
    private val json: Json by injectLazy()

    override fun popularMangaRequest(page: Int): Request {
        return POST(
            "$baseUrl/ajax",
            headers,
            FormBody.Builder()
                .add("search", "")
                .add("orderBy", "recent")
                .add("curpage", page.toString())
                .add("post", "browse")
                .add("action", "fetch_browse")
                .build(),
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonData = json.parseToJsonElement(response.body.string()).jsonObject
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

        return MangasPage(mangas, mangas.size == 10)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return POST(
            "$baseUrl/ajax",
            headers,
            FormBody.Builder()
                .add("search", query)
                .add("orderBy", "recent")
                .add("curpage", page.toString())
                .add("post", "browse")
                .add("action", "fetch_browse")
                .build(),
        )
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // Manga details
    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim() ?: ""
            val coverUrl = doc.selectFirst("img[x-ref=\"art\"].w-full.rounded.shadow-md")?.attr("src")
            if (coverUrl != null) {
                thumbnail_url = if (coverUrl.startsWith("http")) coverUrl else "$baseUrl$coverUrl"
            }

            val genres = doc.select(
                "section[x-data=\"{ tab: location.hash.substr(1) || 'chapters' }\"].relative > div > div > div.flex.flex-wrap > a",
            ).map { it.text().trim() }
            genre = genres.joinToString(", ")

            status = when {
                doc.text().contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                doc.text().contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapter list - needs to extract post ID and use AJAX
    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())
        val requestUrl = response.request.url.toString()

        // Extract post ID from x-data attribute
        val xData = doc.selectFirst("[x-data*=post]")?.attr("x-data") ?: ""
        val postIdMatch = Regex("post:\\s*(\\d+)").find(xData)
        val postId = postIdMatch?.groupValues?.get(1)

        if (postId != null) {
            // Fetch chapters via AJAX
            val ajaxResponse = client.newCall(
                POST(
                    "$baseUrl/ajax",
                    headers,
                    FormBody.Builder()
                        .add("post", postId)
                        .add("action", "series_toc")
                        .build(),
                ),
            ).execute()

            val jsonData = json.parseToJsonElement(ajaxResponse.body.string()).jsonObject
            val chaptersHtml = jsonData["data"]?.jsonPrimitive?.content ?: ""
            val chaptersDoc = Jsoup.parse(chaptersHtml)

            return chaptersDoc.select("a[href*='/chapter/']").mapNotNull { element ->
                try {
                    val url = element.attr("href")?.replace(baseUrl, "") ?: return@mapNotNull null
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
        }

        // Fallback to HTML parsing if AJAX fails
        return doc.select("div[x-show=\"tab === 'chapters'\"] a[href*='/chapter/']").mapNotNull { element ->
            try {
                val url = element.attr("href")?.replace(baseUrl, "") ?: return@mapNotNull null
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
    }

    // Page list
    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    // Novel text fetching
    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())
        // The chapter content is in div.justify-center > div.mb-4
        return doc.selectFirst("div.justify-center > div.mb-4")?.html() ?: ""
    }

    // Image URL - not used for novels
    override fun imageUrlParse(response: Response): String = ""

    override fun getFilterList(): FilterList = FilterList()
}
