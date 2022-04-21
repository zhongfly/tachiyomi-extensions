package eu.kanade.tachiyomi.extension.en.firstkissmanga

import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.multisrc.madara.Madara
import okhttp3.Headers
import java.util.concurrent.TimeUnit

class FirstKissManga : Madara(
    "1st Kiss",
    "https://1stkissmanga.io",
    "en"
) {
    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", baseUrl)

    private val rateLimitInterceptor = RateLimitInterceptor(1, 2, TimeUnit.SECONDS)

    override val client = network.cloudflareClient.newBuilder()
        .addNetworkInterceptor(rateLimitInterceptor)
        .build()
}
