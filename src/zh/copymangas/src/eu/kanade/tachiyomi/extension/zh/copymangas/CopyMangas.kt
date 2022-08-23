package eu.kanade.tachiyomi.extension.zh.copymangas

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.luhuiguo.chinese.ChineseUtils
import eu.kanade.tachiyomi.extension.zh.copymangas.MangaDto.Companion.parseChapterGroups
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import java.util.concurrent.TimeUnit
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import rx.Observable
import rx.Single
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.concurrent.thread
import kotlin.random.Random

class CopyMangas : HttpSource(), ConfigurableSource {
    override val name = "拷贝漫画"
    override val lang = "zh"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences =
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private var convertToSc = preferences.getBoolean(SC_TITLE_PREF, false)
    private var domain = DOMAINS[preferences.getString(DOMAIN_PREF, "0")!!.toInt().coerceIn(0, DOMAINS.size - 1)]
    private var webDomain = WWW_PREFIX + DOMAINS[preferences.getString(DOMAIN_PREF, "0")!!.toInt().coerceIn(0, DOMAINS.size - 1)]
    override val baseUrl = webDomain
    private var apiUrl = API_PREFIX + domain // www. 也可以

    private val groupRatelimitRegex = Regex("""/group/.*/chapters""")
    private val chapterRatelimitRegex = Regex("""/chapter2/""")

    private val trustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return emptyArray()
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        }
    }
    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }
    private val groupInterceptor = RateLimitInterceptor(preferences.getString(GROUP_API_RATE_PREF, "30")!!.toInt(), 1, TimeUnit.MINUTES)
    private val chapterInterceptor = RateLimitInterceptor(preferences.getString(CHAPTER_API_RATE_PREF, "20")!!.toInt(), 1, TimeUnit.MINUTES)
    
    override val client: OkHttpClient = network.client.newBuilder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .addNetworkInterceptor { chain ->
          chain.proceed(
              chain.request()
                  .newBuilder()
                  .removeHeader("cache-control")
                  .removeHeader("if-modified-since")
                  .build()
          )
        }
        .addNetworkInterceptor{ chain ->
            val url = chain.request().url.toString()
            when {
                url.contains(groupRatelimitRegex) -> groupInterceptor.intercept(chain)
                url.contains(chapterRatelimitRegex) -> chapterInterceptor.intercept(chain)
                else -> chain.proceed(chain.request())
            }
        }
        .build()

    private fun Headers.Builder.setUserAgent(userAgent: String) = set("User-Agent", userAgent)
    private fun Headers.Builder.setWebp(useWebp: Boolean) = set("webp", if (useWebp) "1" else "0")
    private fun Headers.Builder.setRegion(useOverseasCdn: Boolean) = set("region", if (useOverseasCdn) "0" else "1")
    private fun Headers.Builder.setReferer(referer: String) = set("Referer", referer)
    private fun Headers.Builder.setVersion(version: String) = set("version", version)
    private fun Headers.Builder.setToken(token: String) = set("authorization", if (!token.isNullOrBlank()) "Token "+token else "Token")

    private var apiHeaders = Headers.Builder()
        .removeAll("if-modified-since")
        .removeAll("cookie")
        .setUserAgent(preferences.getString(USER_AGENT_PREF, DEFAULT_USER_AGENT)!!)
        .add("source","copyApp")
        .setWebp(preferences.getBoolean(WEBP_PREF, true))
        .setVersion(preferences.getString(VERSION_PREF, DEFAULT_VERSION)!!)
        .setRegion(preferences.getBoolean(OVERSEAS_CDN_PREF, false))
        .setToken(preferences.getString(TOKEN_PREF, "")!!)
        .add("platform", "3")
        .build()
    
    override fun headersBuilder() = Headers.Builder()
        .setUserAgent(preferences.getString(BROWSER_USER_AGENT_PREF, DEFAULT_BROWSER_USER_AGENT)!!)
        .setReferer(webDomain)

    init {
        MangaDto.convertToSc = preferences.getBoolean(SC_TITLE_PREF, false)
    }

    override fun popularMangaRequest(page: Int): Request {
        val offset = PAGE_SIZE * (page - 1)
        return GET("$apiUrl/api/v3/comics?limit=$PAGE_SIZE&offset=$offset&free_type=1&ordering=-popular&theme=&top=&platform=3", apiHeaders)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val page: ListDto<MangaDto> = response.parseAs()
        val hasNextPage = page.offset + page.limit < page.total
        return MangasPage(page.list.map { it.toSManga() }, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val offset = PAGE_SIZE * (page - 1)
        return GET("$apiUrl/api/v3/comics?limit=$PAGE_SIZE&offset=$offset&free_type=1&ordering=-datetime_updated&theme=&top=&platform=3", apiHeaders)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val offset = PAGE_SIZE * (page - 1)
        val builder = apiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("limit", "$PAGE_SIZE")
            .addQueryParameter("offset", "$offset")
        if (query.isNotBlank()) {
            builder.addPathSegments("api/v3/search/comic")
                .addQueryParameter("q", query)
            filters.filterIsInstance<SearchFilter>().firstOrNull()?.addQuery(builder)
            builder.addQueryParameter("q_type","").addQueryParameter("platform","3")
        } else {
            builder.addPathSegments("api/v3/comics")
            filters.filterIsInstance<CopyMangaFilter>().forEach {
                if (it !is SearchFilter) it.addQuery(builder)
            }
        }
        return Request.Builder().url(builder.build()).headers(apiHeaders).build()
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val page: ListDto<MangaDto> = response.parseAs()
        val hasNextPage = page.offset + page.limit < page.total
        return MangasPage(page.list.map { it.toSManga() }, hasNextPage)
    }

    // 让 WebView 打开网页而不是 API
    override fun mangaDetailsRequest(manga: SManga): Request = GET(webDomain + manga.url, headers)

    private fun realMangaDetailsRequest(manga: SManga) =
        GET("$apiUrl/api/v3/comic2/${manga.url.removePrefix(MangaDto.URL_PREFIX)}?platform=3", apiHeaders)

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        client.newCall(realMangaDetailsRequest(manga)).asObservableSuccess().map { mangaDetailsParse(it) }

    override fun mangaDetailsParse(response: Response): SManga =
        response.parseAs<MangaWrapperDto>().toSMangaDetails()

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Single.create<List<SChapter>> {
        val result = ArrayList<SChapter>()
        val response = client.newCall(realMangaDetailsRequest(manga)).execute()
        val groups = response.parseAs<MangaWrapperDto>().groups!!.values
        val mangaSlug = manga.url.removePrefix(MangaDto.URL_PREFIX)
        for (group in groups) {
            result.fetchChapterGroup(mangaSlug, group.path_word, group.name)
        }
        it.onSuccess(result)
    }.toObservable()

    private fun ArrayList<SChapter>.fetchChapterGroup(manga: String, key: String, name: String) {
        val result = ArrayList<SChapter>(0)
        var offset = 0
        var hasNextPage = true
        val groupName = when {
            key.equals("default") -> ""
            convertToSc -> ChineseUtils.toSimplified(name)
            else -> name
        }
        while (hasNextPage) {
            val response = client.newCall(GET("$apiUrl/api/v3/comic/$manga/group/$key/chapters?limit=$CHAPTER_PAGE_SIZE&offset=$offset&platform=3", apiHeaders)).execute()
            val chapters: ListDto<ChapterDto> = response.parseAs()
            result.ensureCapacity(chapters.total)
            chapters.list.mapTo(result) { it.toSChapter(groupName) }
            offset += CHAPTER_PAGE_SIZE
            hasNextPage = offset < chapters.total
        }
        addAll(result.asReversed())
    }

    override fun chapterListRequest(manga: SManga) = throw UnsupportedOperationException("Not used.")
    override fun chapterListParse(response: Response) = throw UnsupportedOperationException("Not used.")

    // 新版 API 中间是 /chapter2/ 并且返回值需要排序
    override fun pageListRequest(chapter: SChapter) = GET("$apiUrl/api/v3${chapter.url}?platform=3 ", apiHeaders)

    override fun pageListParse(response: Response): List<Page> {
        val result: ChapterPageListWrapperDto = response.parseAs()
        val slug = response.request.url.pathSegments[3]
        if (result.show_app) {
            throw Exception("访问受限，请尝试在插件设置中修改 User Agent")
        }
        val orders = result.chapter.words
        val pageList = result.chapter.contents.filter{ it.url.contains("/${slug}/") }.withIndex().sortedBy{ orders[it.index] }.map { it.value }
        return pageList.mapIndexed { i, it ->
            Page(i, imageUrl = it.url)
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("Not used.")

    private var imageQuality = preferences.getString(QUALITY_PREF, QUALITY[0])
    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl!!
        val headers = Headers.Builder().setUserAgent(preferences.getString(USER_AGENT_PREF, DEFAULT_USER_AGENT)!!).build()

        return GET(imageUrl.replace("c800x.","c${imageQuality}x."),headers)
    }

    private inline fun <reified T> Response.parseAs(): T = use {
        if (header("Content-Type") != "application/json") {
            throw Exception("返回数据错误，不是json")
        } else if (code != 200) {
            throw Exception(json.decodeFromStream<ResultMessageDto>(body!!.byteStream()).message)
        }
        json.decodeFromStream<ResultDto<T>>(body!!.byteStream()).results
    }

    private var genres: Array<Param> = emptyArray()
    private var isFetchingGenres = false

    override fun getFilterList(): FilterList {
        val genreFilter = if (genres.isEmpty()) {
            fetchGenres()
            Filter.Header("点击“重置”尝试刷新题材分类")
        } else {
            GenreFilter(genres)
        }
        return FilterList(
            SearchFilter(),
            Filter.Separator(),
            Filter.Header("分类（搜索文本时无效）"),
            genreFilter,
            RegionFilter(),
            StatusFilter(),
            SortFilter(),
        )
    }

    private fun fetchGenres() {
        if (genres.isNotEmpty() || isFetchingGenres) return
        isFetchingGenres = true
        thread {
            try {
                val response = client.newCall(GET("$apiUrl/api/v3/theme/comic/count?limit=500&offset=0&free_type=1&platform=3", apiHeaders)).execute()
                val list = response.parseAs<ListDto<KeywordDto>>().list
                val result = ArrayList<Param>(list.size + 1).apply { add(Param("全部", "")) }
                genres = list.mapTo(result) { it.toParam() }.toTypedArray()
            } catch (e: Exception) {
                Log.e("CopyManga", "failed to fetch genres", e)
            } finally {
                isFetchingGenres = false
            }
        }
    }

    var fetchVersionState = 0 // 0 = not yet or failed, 1 = fetching, 2 = fetched

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = DOMAIN_PREF
            title = "API域名"
            summary = "连接不稳定时可以尝试切换\n当前值：%s"
            entries = DOMAINS
            entryValues = DOMAIN_INDICES
            setDefaultValue(DOMAIN_INDICES[0])
            setOnPreferenceChangeListener { _, newValue ->
                val index = newValue as String
                preferences.edit().putString(DOMAIN_PREF, index).apply()
                domain = DOMAINS[index.toInt()]
                apiUrl = API_PREFIX + domain
                apiHeaders = apiHeaders.newBuilder().setReferer(apiUrl).build()
                true
            }
        }.let { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = WEB_DOMAIN_PREF
            title = "网页版域名"
            summary = "webview中使用的域名\n当前值：%s"
            entries = DOMAINS
            entryValues = DOMAIN_INDICES
            setDefaultValue(DOMAIN_INDICES[0])
            setOnPreferenceChangeListener { _, newValue ->
                val index = newValue as String
                preferences.edit().putString(WEB_DOMAIN_PREF, index).apply()
                webDomain = WWW_PREFIX + DOMAINS[index.toInt()]
                true
            }
        }.let { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = OVERSEAS_CDN_PREF
            title = "使用“港台及海外线路”"
            summary = "连接不稳定时可以尝试切换，关闭时使用“大陆用户线路”，已阅读章节需要清空缓存才能生效"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                val useOverseasCdn = newValue as Boolean
                preferences.edit().putBoolean(OVERSEAS_CDN_PREF, useOverseasCdn).apply()
                apiHeaders = apiHeaders.newBuilder().setRegion(useOverseasCdn).build()
                true
            }
        }.let { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = QUALITY_PREF
            title = "图片分辨率（像素）"
            summary = "阅读过的部分需要清空缓存才能生效\n当前值：%s"
            entries = QUALITY
            entryValues = QUALITY
            setDefaultValue(QUALITY[0])
            setOnPreferenceChangeListener { _, newValue ->
                imageQuality = newValue as String
                preferences.edit().putString(QUALITY_PREF, imageQuality).apply()
                true
            }
        }.let { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = WEBP_PREF
            title = "使用 WebP 图片格式"
            summary = "默认开启，可以节省网站流量"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                val useWebp = newValue as Boolean
                preferences.edit().putBoolean(WEBP_PREF, useWebp).apply()
                apiHeaders = apiHeaders.newBuilder().setWebp(useWebp).build()
                true
            }
        }.let { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = GROUP_API_RATE_PREF
            title = "章节目录请求频率限制"
            summary = "此值影响向章节目录api时发起连接请求的数量。需要重启软件以生效。\n当前值：每分钟 %s 个请求"
            entries = RATE_ARRAY
            entryValues = RATE_ARRAY
            setDefaultValue(RATE_ARRAY.last())
            setOnPreferenceChangeListener { _, newValue ->
                val rateLimit = newValue as String
                preferences.edit().putString(GROUP_API_RATE_PREF, rateLimit).apply()
                true
            }
        }.let { screen.addPreference(it) }
        
        ListPreference(screen.context).apply {
            key = CHAPTER_API_RATE_PREF
            title = "章节图片列表请求频率限制"
            summary = "此值影响向章节图片列表api时发起连接请求的数量。需要重启软件以生效。\n当前值：每分钟 %s 个请求"
            entries = RATE_ARRAY
            entryValues = RATE_ARRAY
            setDefaultValue(RATE_ARRAY.last())
            setOnPreferenceChangeListener { _, newValue ->
                val rateLimit = newValue as String
                preferences.edit().putString(CHAPTER_API_RATE_PREF, rateLimit).apply()
                true
            }
        }.let { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = SC_TITLE_PREF
            title = "将作品标题及简介转换为简体中文"
            summary = "修改后，已添加漫画需要迁移才能更新信息"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                convertToSc = newValue as Boolean
                preferences.edit().putBoolean(SC_TITLE_PREF, convertToSc).apply()
                MangaDto.convertToSc = convertToSc
                true
            }
        }.let { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = USERNAME_PREF
            title = "用户名"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(USERNAME_PREF, newValue as String).commit()
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "密码"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PASSWORD_PREF, newValue as String).commit()
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = TOKEN_PREF
            title = "用户登录Token"
            summary = "输入登录Token即可以搜索阅读仅登录用户可见的漫画；可点击下方的“更新Token”来自动获取/更新"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val token = newValue as String
                preferences.edit().putString(TOKEN_PREF, token).apply()
                apiHeaders = apiHeaders.newBuilder().setToken(token).build()
                true
            }
        }.let { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            title = "更新Token"
            summary = "填写用户名及密码设置后，点击此选项尝试登录以更新Token"
            setOnPreferenceChangeListener { _, _ ->
                if (fetchVersionState == 1) {
                    Toast.makeText(screen.context, "正在尝试登录，请勿反复点击", Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceChangeListener false
                } else if (fetchVersionState == 2) {
                    Toast.makeText(screen.context, "Token已经成功更新，返回重进刷新", Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceChangeListener false
                }
                Toast.makeText(screen.context, "开始尝试登录以更新Token", Toast.LENGTH_SHORT).show()
                fetchVersionState = 1
                thread {
                    try {
                        val username = preferences.getString(USERNAME_PREF, "")!!
                        var password = preferences.getString(PASSWORD_PREF, "")!!
                        if (username.isEmpty() || password.isEmpty()) {
                            Toast.makeText(screen.context, "请在扩展设置界面输入用户名和密码", Toast.LENGTH_SHORT).show()
                            throw Exception("请在扩展设置界面输入用户名和密码")
                        }
                        val salt = (1000..9999).random()
                        password = Base64.encodeToString("$password-$salt".toByteArray(), Base64.DEFAULT).trim()
                        val formBody: RequestBody = FormBody.Builder()
                            .addEncoded("username", username)
                            .addEncoded("password", password)
                            .addEncoded("salt",salt)
                            .addEncoded("platform","3")
                            .addEncoded("authorization","Token+")
                            .addEncoded("version",preferences.getString(VERSION_PREF, DEFAULT_VERSION)!!)
                            .addEncoded("source","copyApp")
                            .build()
                        val headers = apiHeaders.newBuilder()
                            .setToken("")
                            // .add("Content-Length", formBody.contentLength().toString())
                            // .add("Content-Type", formBody.contentType().toString())
                            .build()                        
                        val response = client.newCall(POST("$apiUrl/api/v3/login?platform=3", headers,formBody)).execute()
                        val token = response.parseAs<TokenDto>().token
                        preferences.edit().putString(TOKEN_PREF, token).apply()
                        apiHeaders = apiHeaders.newBuilder().setToken(token).build()
                        fetchVersionState = 2
                    } catch (e: Throwable) {
                        fetchVersionState = 0
                        Log.e("CopyMangas", "failed to fetch token", e)
                    }
                }
                false
            }
        }.let { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = USER_AGENT_PREF
            title = "User Agent"
            summary = "高级设置，不建议修改"
            setDefaultValue(DEFAULT_USER_AGENT)
            setOnPreferenceChangeListener { _, newValue ->
                val userAgent = newValue as String
                preferences.edit().putString(USER_AGENT_PREF, userAgent).apply()
                apiHeaders = apiHeaders.newBuilder().setUserAgent(userAgent).build()
                true
            }
        }.let { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = VERSION_PREF
            title = "官方应用版本号"
            summary = "高级设置，不建议修改"
            setDefaultValue(DEFAULT_VERSION)
            setOnPreferenceChangeListener { _, newValue ->
                val version = newValue as String
                preferences.edit().putString(VERSION_PREF, version).apply()
                apiHeaders = apiHeaders.newBuilder().setVersion(version).build()
                true
            }
        }.let { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = BROWSER_USER_AGENT_PREF
            title = "浏览器User Agent"
            summary = "高级设置，不建议修改\n重启生效"
            setDefaultValue(DEFAULT_BROWSER_USER_AGENT)
            setOnPreferenceChangeListener { _, newValue ->
                val userAgent = newValue as String
                preferences.edit().putString(BROWSER_USER_AGENT_PREF, userAgent).apply()
                true
            }
        }.let { screen.addPreference(it) }
    }

    companion object {
        private const val DOMAIN_PREF = "domainZ"
        private const val WEB_DOMAIN_PREF = "webDomainZ"
        private const val OVERSEAS_CDN_PREF = "changeCDNZ"
        private const val QUALITY_PREF = "imageQualityZ"
        private const val SC_TITLE_PREF = "showSCTitleZ"
        private const val WEBP_PREF = "useWebpZ"
        private const val GROUP_API_RATE_PREF = "groupApiRateZ"
        private const val CHAPTER_API_RATE_PREF = "chapterApiRateZ"
        private const val USERNAME_PREF = "usernameZ"
        private const val PASSWORD_PREF = "passwordZ"
        private const val TOKEN_PREF = "tokenZ"
        private const val USER_AGENT_PREF = "userAgentZ"
        private const val VERSION_PREF = "versionZ"
        private const val BROWSER_USER_AGENT_PREF = "browserUserAgent"        

        private const val WWW_PREFIX = "https://www."
        private const val API_PREFIX = "https://api."
        private val DOMAINS = arrayOf("copymanga.net", "copymanga.info", "copymanga.org", "copymanga.site")
        private val DOMAIN_INDICES = arrayOf("0", "1", "2", "3")
        private val QUALITY = arrayOf("800", "1200", "1500")
        private val RATE_ARRAY = (5..120 step 5).map { i -> i.toString() }.toTypedArray()
        private const val DEFAULT_USER_AGENT = "Dart/2.16(dart:io)"
        private const val DEFAULT_VERSION = "1.4.1"
        private const val DEFAULT_BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; ) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/103.0.5060.53 Mobile Safari/537.36"

        private const val PAGE_SIZE = 20
        private const val CHAPTER_PAGE_SIZE = 400
    }
}
