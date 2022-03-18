package eu.kanade.tachiyomi.extension.en.manytoonme

import eu.kanade.tachiyomi.multisrc.madara.Madara

class ManyToonMe : Madara("ManyToon.me", "https://manytoon.me", "en") {

    override val useNewChapterEndpoint: Boolean = true

    // The website does not flag the content, so we just use the old selector.
    override fun popularMangaSelector() = "div.page-item-detail:not(:has(a[href*='bilibilicomics.com']))"
}
