package eu.kanade.tachiyomi.extension.en.srankmanga

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class SrankManga : MadaraNovel(
    baseUrl = "https://srankmanga.com",
    name = "Srank Manga",
    lang = "en",
) {
    override val useNewChapterEndpointDefault = true

    override fun getFilterList() = FilterList(
        GenreFilter(),
        StatusFilter(),
        SortFilter(),
    )

    private class GenreFilter : Filter.Group<Genre>(
        "Genre",
        listOf(
            Genre("Action", "action"),
            Genre("Adventure", "adventure"),
            Genre("Comedy", "comedy"),
            Genre("Fantasy", "fantasy"),
            Genre("Harem", "harem"),
            Genre("Isekai", "isekai"),
            Genre("Martial arts", "martial-arts"),
            Genre("Mature", "mature"),
            Genre("Mystery", "mystery"),
            Genre("Psychological", "psychological"),
            Genre("Romance", "romance"),
            Genre("Sci-fi", "sci-fi"),
            Genre("Shounen", "shounen"),
            Genre("Supernatural", "supernatural"),
            Genre("Tragedy", "tragedy"),
            Genre("xianxia", "xianxia"),
            Genre("Xuanhuan", "xuanhuan"),
        ),
    )

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)

    private class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("All", "OnGoing", "Completed", "Canceled", "On Hold", "Upcoming"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "on-going"
            2 -> "end"
            3 -> "canceled"
            4 -> "on-hold"
            5 -> "upcoming"
            else -> ""
        }
    }

    private class SortFilter : Filter.Select<String>(
        "Order by",
        arrayOf("Relevance", "Latest", "A-Z", "Rating", "Trending", "Most Views", "New"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "latest"
            2 -> "alphabet"
            3 -> "rating"
            4 -> "trending"
            5 -> "views"
            6 -> "new-manga"
            else -> ""
        }
    }
}
