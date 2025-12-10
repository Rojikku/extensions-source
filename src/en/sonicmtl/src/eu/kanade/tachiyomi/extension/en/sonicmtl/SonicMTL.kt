package eu.kanade.tachiyomi.extension.en.sonicmtl

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class SonicMTL : MadaraNovel(
    baseUrl = "https://www.sonicmtl.com",
    name = "Sonic MTL",
    lang = "en",
) {
    override val useNewChapterEndpoint = true
}
