package eu.kanade.tachiyomi.extension.en.wuxiaworldsite

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class WuxiaWorldSite : MadaraNovel(
    baseUrl = "https://wuxiaworld.site",
    name = "WuxiaWorld.Site",
    lang = "en",
) {
    override val useNewChapterEndpointDefault = true
}
