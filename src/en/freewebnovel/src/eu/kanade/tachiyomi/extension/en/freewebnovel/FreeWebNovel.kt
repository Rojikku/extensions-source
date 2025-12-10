package eu.kanade.tachiyomi.extension.en.freewebnovel

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull

class FreeWebNovel : ReadNovelFull(
    name = "FreeWebNovel",
    baseUrl = "https://freewebnovel.com",
    lang = "en",
) {
    override val noAjax = true
    override val postSearch = true
    override val searchKey = "searchkey"
    override val pageAsPath = true
    override val latestPage = "sort/latest-novels"
}
