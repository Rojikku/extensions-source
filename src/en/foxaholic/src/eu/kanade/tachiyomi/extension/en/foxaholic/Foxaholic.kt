package eu.kanade.tachiyomi.extension.en.foxaholic

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class Foxaholic : MadaraNovel(
    baseUrl = "https://www.foxaholic.com",
    name = "Foxaholic",
    lang = "en",
) {
    // Uses new chapter endpoint (/ajax/chapters/) which returns clean chapter HTML
    // The old admin-ajax.php endpoint returns the full page instead of chapter list
    override val useNewChapterEndpointDefault = true
}
