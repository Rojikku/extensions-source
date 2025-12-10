package eu.kanade.tachiyomi.extension.en.readfromnet

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

class ReadFromNet : ParsedHttpSource(), NovelSource {

    override val name = "ReadFromNet"

    override val baseUrl = "https://readfrom.net"

    override val lang = "en"

    override val supportsLatest = true

    // Popular Manga (Novels)

    override fun popularMangaRequest(page: Int): Request {
        // Placeholder: Assuming a list page exists
        return GET("$baseUrl/list/$page", headers)
    }

    override fun popularMangaSelector() = ".book-list .book-item" // Placeholder

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.select("h3").text()
            setUrlWithoutDomain(element.select("a").attr("href"))
            thumbnail_url = element.select("img").attr("src")
        }
    }

    override fun popularMangaNextPageSelector() = ".pagination .next" // Placeholder

    // Latest Updates

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/search?q=$query", headers) // Placeholder
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Manga Details

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.select("h1").text()
            description = document.select(".description").text()
            // Add more details
        }
    }

    // Chapters

    override fun chapterListSelector() = "div.pages > a"

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        // First chapter is the page itself
        chapters.add(
            SChapter.create().apply {
                name = "Chapter 1"
                url = response.request.url.toString().replace(baseUrl, "")
                chapter_number = 1f
            },
        )

        // Other chapters from pagination
        document.select(chapterListSelector()).forEach { element ->
            chapters.add(chapterFromElement(element))
        }

        return chapters
    }

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            name = element.text()
            url = element.attr("href")
            // Try to parse chapter number
            val num = element.text().toFloatOrNull()
            if (num != null) {
                chapter_number = num
            }
        }
    }

    // Page List (Novel Content)

    override fun pageListParse(document: Document): List<Page> {
        return listOf(Page(0, document.location(), ""))
    }

    override fun imageUrlParse(document: Document) = ""

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(page.url, headers)).execute()
        val document = response.asJsoup()

        val contentElement = document.selectFirst("#textToRead") ?: return ""

        // Cleanup
        contentElement.select("span:empty, center").remove()

        val sb = StringBuilder()

        contentElement.childNodes().forEach { node ->
            if (node is TextNode) {
                val text = node.text().trim()
                if (text.isNotEmpty()) {
                    sb.append("<p>").append(text).append("</p>")
                }
            } else if (node is Element && node.tagName() != "br") {
                sb.append(node.outerHtml())
            }
        }

        return sb.toString()
    }

    private fun Response.asJsoup(): Document = org.jsoup.Jsoup.parse(body?.string() ?: "")
}
