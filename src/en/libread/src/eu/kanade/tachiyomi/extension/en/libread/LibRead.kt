package eu.kanade.tachiyomi.extension.en.libread

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class LibRead : ReadNovelFull(
    name = "LibRead",
    baseUrl = "https://libread.com",
    lang = "en",
) {
    override val latestPage = "sort/latest-release"

    // LibRead uses /libread/ prefix for novels
    override fun popularMangaRequest(page: Int): Request {
        return okhttp3.Request.Builder()
            .url("$baseUrl/sort/most-popular?page=$page")
            .headers(headers)
            .build()
    }

    override fun popularMangaSelector() = "div.ul-list1 div.li, ul.ul-list2 li"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("h3.tit a, a.tit, a.con")
        if (link != null) {
            title = link.attr("title").ifEmpty { link.text().trim() }
            setUrlWithoutDomain(link.attr("abs:href"))
        }
        thumbnail_url = element.selectFirst("img")?.let { img ->
            val src = img.attr("data-src").ifEmpty { img.attr("src") }
            if (src.startsWith("/")) "$baseUrl$src" else src
        }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return okhttp3.Request.Builder()
            .url("$baseUrl/$latestPage?page=$page")
            .headers(headers)
            .build()
    }

    override fun latestUpdatesSelector() = "div.ul-list1 div.li, ul.ul-list2 li"

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    // Novel detail page parsing
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        document.selectFirst("div.m-imgtxt, div.m-book1")?.let { info ->
            thumbnail_url = info.selectFirst("img")?.let { img ->
                val src = img.attr("data-src").ifEmpty { img.attr("src") }
                if (src.startsWith("/")) "$baseUrl$src" else src
            }
            title = info.selectFirst("h1.tit")?.text()?.trim() ?: ""
        }

        // Parse info
        document.select("div.m-imgtxt div.item, ul.info-meta li").forEach { element ->
            val text = element.text()
            when {
                text.contains("Author", ignoreCase = true) -> {
                    author = element.select("a").joinToString { it.text().trim() }
                        .ifEmpty { text.substringAfter(":").trim() }
                }
                text.contains("Genre", ignoreCase = true) -> {
                    genre = element.select("a").joinToString { it.text().trim() }
                }
                text.contains("Status", ignoreCase = true) -> {
                    status = when {
                        text.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                        text.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                }
            }
        }

        description = document.selectFirst("div.m-desc div.txt div.inner, div.desc-text")?.text()?.trim()
    }

    // Chapter list parsing - LibRead uses select with options
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        // Try to get chapters from select options
        val chapters = document.select("select#idData option, ul#idData li a").mapIndexedNotNull { index, element ->
            val chapterUrl = if (element.tagName() == "option") {
                val value = element.attr("value")
                if (value.isNotBlank() && value != "0") {
                    if (value.startsWith("/")) value else "/$value"
                } else {
                    null
                }
            } else {
                element.attr("href")
            }

            if (chapterUrl.isNullOrBlank()) return@mapIndexedNotNull null

            SChapter.create().apply {
                setUrlWithoutDomain(chapterUrl)
                name = element.text().trim().ifEmpty { "Chapter ${index + 1}" }
                chapter_number = (index + 1).toFloat()
            }
        }

        return chapters
    }

    // Content parsing
    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(okhttp3.Request.Builder().url(page.url).headers(headers).build()).execute()
        val document = response.asJsoup()

        val content = document.selectFirst("div.txt div#article, div#chapter-content, div.chapter-content, div#chr-content")
        if (content != null) {
            // Remove ads and unwanted elements
            content.select("div.ads, script, ins, .adsbygoogle, .chapter-ad").remove()
            return content.html()
        }

        return ""
    }
}
