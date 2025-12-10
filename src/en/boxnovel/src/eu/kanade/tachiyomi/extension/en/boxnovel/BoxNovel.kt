package eu.kanade.tachiyomi.extension.en.boxnovel

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull

class BoxNovel : ReadNovelFull(
    name = "BoxNovel",
    baseUrl = "https://novlove.com",
    lang = "en",
) {
    override val latestPage = "sort/nov-love-daily-update"
}
