package eu.kanade.tachiyomi.extension.en.translatino

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class TranslatinOtaku : MadaraNovel(
    baseUrl = "https://translatinotaku.net",
    name = "Translatin Otaku",
    lang = "en",
) {
    override val useNewChapterEndpointDefault = true
}
