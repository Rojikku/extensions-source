package eu.kanade.tachiyomi.extension.en.fictionzone

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedNovelSource
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FictionZone : ParsedNovelSource() {

    override val name = "Fiction Zone"

    override val baseUrl = "https://fictionzone.net"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    // Popular Novels
    override fun popularMangaRequest(page: Int) = GET("$baseUrl/library?page=$page", headers)

    override fun popularMangaSelector() = "div.novel-card"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        title = element.select("div.title > h1").text()
        thumbnail_url = element.select("img").attr("src")
        setUrlWithoutDomain(element.select("a").attr("href"))
    }

    override fun popularMangaNextPageSelector() = null

    // Latest Updates
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/library?page=$page&sort=created_at", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        GET("$baseUrl/library?query=$query&page=$page", headers)

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Novel Details
    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.select("div.novel-title > h1").text()
        author = document.select("div.novel-author > content").text()
        description = document.select("#synopsis > div.content").text()
        genre = document.select("div.genres > .items > span, div.tags > .items > a").joinToString { it.text() }
        status = when (document.select("div.novel-status > div.content").text().trim()) {
            "Ongoing" -> SManga.ONGOING
            "Completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        thumbnail_url = document.select("div.novel-img > img").attr("src")
    }

    // Chapters
    override fun chapterListSelector() = "div.chapters > div.list-wrapper > div.items > a.chapter"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        name = element.select("span.chapter-title").text()
        setUrlWithoutDomain(element.attr("href"))
        date_upload = 0L
    }

    // Content
    override fun novelContentSelector() = "div.chapter-content"
}
