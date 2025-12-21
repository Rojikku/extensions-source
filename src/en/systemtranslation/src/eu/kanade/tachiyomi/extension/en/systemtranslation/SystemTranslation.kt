package eu.kanade.tachiyomi.extension.en.systemtranslation

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel

class SystemTranslation : LightNovelWPNovel(
    baseUrl = "https://systemtranslation.com",
    name = "System Translation",
    lang = "en",
) {
    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)
}
