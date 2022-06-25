package eu.kanade.tachiyomi.extension.zh.copymangas

import android.app.Application
import android.content.SharedPreferences
import com.luhuiguo.chinese.ChineseUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class CopyMangas : ConfigurableSource, HttpSource() {

    override val name = "拷贝漫画"
    override val lang = "zh"
    override val supportsLatest = true
    private val searchPageSize = 18 // default
    private val chapterPageSize = 100

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl = "https://${preferences.getString(API_URL_PREF, APIURLS[2])}"

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(preferences.getString(API_RATELIMIT_PREF, "3")!!.toInt(), 1) // N requests per second
        .build()

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/api/v3/comics?ordering=-popular&offset=${(page - 1) * searchPageSize}&limit=$searchPageSize&platform=3&free_type=1", headers)
    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/api/v3/comics?ordering=-datetime_updated&offset=${(page - 1) * searchPageSize}&limit=$searchPageSize&platform=3&free_type=1", headers)
    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val apiUrlString = "$baseUrl/api/v3/search/comic?limit=$searchPageSize&offset=${(page - 1) * searchPageSize}&platform=3&q=$query&q_type="
        val themeUrlString = "$baseUrl/api/v3/comics?offset=${(page - 1) * searchPageSize}&limit=$searchPageSize"
        val requestUrlString: String

        val params = filters.map {
            if (it is MangaFilter) {
                it.toUriPart()
            } else ""
        }.filter { it != "" }.joinToString("&")
        // perform theme search only when do have filter and not search anything
        if (params != "" && query == "") {
            requestUrlString = "$themeUrlString&$params"
        } else {
            requestUrlString = apiUrlString
        }
        val url = requestUrlString.toHttpUrlOrNull()?.newBuilder()
        return GET(url.toString(), headers)
    }
    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body!!.string()
        // results > list []
        val res = JSONObject(body)
        val comicArray = res.optJSONObject("results")?.optJSONArray("list")
        if (comicArray == null) {
            return MangasPage(listOf(), false)
        }

        val ret = ArrayList<SManga>(comicArray.length())
        for (i in 0 until comicArray.length()) {
            val obj = comicArray.getJSONObject(i)
            val authorArray = obj.getJSONArray("author")
            var _title: String = obj.getString("name")
            if (preferences.getBoolean(SHOW_Simplified_Chinese_TITLE_PREF, false)) {
                _title = ChineseUtils.toSimplified(_title)
            }
            ret.add(
                SManga.create().apply {
                    title = _title
                    thumbnail_url = obj.getString("cover")
                    author = Array<String?>(authorArray.length()) { i -> authorArray.getJSONObject(i).getString("name") }.joinToString(", ")
                    status = SManga.UNKNOWN
                    // url = "/api/v3/comic2/${obj.getString("path_word")}?platform=3"
                    url = "/comic/${obj.getString("path_word")}"
                }
            )
        }

        val hasNextPage = comicArray.length() == searchPageSize
        return MangasPage(ret, hasNextPage)
    }

    // Compatible with old url
    // old url:"/comic/${obj.getString("path_word")}"
    // new url:"/api/v3/comic2/${obj.getString("path_word")}?platform=3"
    override fun mangaDetailsRequest(manga: SManga) = GET(baseUrl + manga.url.replace("/comic/", "/api/v3/comic2/") + "?platform=3", headers)
    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body!!.string()
        // results > comic
        val res = JSONObject(body)
        val obj = res.getJSONObject("results").getJSONObject("comic")

        val manga = SManga.create().apply {
            var _title: String = obj.getString("name")
            if (preferences.getBoolean(SHOW_Simplified_Chinese_TITLE_PREF, false)) {
                _title = ChineseUtils.toSimplified(_title)
            }
            title = _title
            thumbnail_url = obj.getString("cover")
            var _description: String = obj.getString("brief")
            if (preferences.getBoolean(SHOW_Simplified_Chinese_TITLE_PREF, false)) {
                _description = ChineseUtils.toSimplified(_description)
            }
            description = _description
            val authorArray = obj.getJSONArray("author")
            author = Array<String?>(authorArray.length()) { i -> authorArray.getJSONObject(i).getString("name") }.joinToString(", ")
            status = when (obj.getJSONObject("status").getString("display")) {
                "已完結" -> SManga.COMPLETED
                "連載中" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
            val genreArray = obj.getJSONArray("theme")
            genre = Array<String?>(genreArray.length()) { i -> genreArray.getJSONObject(i).getString("name") }.joinToString(", ")
        }
        return manga
    }

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)
    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body!!.string()
        // Get chapter groups
        // results > comic
        val res = JSONObject(body)
        val comicPathWord = res.getJSONObject("results").getJSONObject("comic").getString("path_word")
        val chapterGroups = res.getJSONObject("results").optJSONObject("groups")
        if (chapterGroups == null) {
            return listOf()
        }

        val retChapter = ArrayList<SChapter>()
        // Get chaptersList according to groupName
        val keys = chapterGroups.keys().asSequence().toList()
        keys.filter { it -> it == "default" }.forEach { groupName ->
            run {
                val chapterGroup = chapterGroups.getJSONObject(groupName)
                fillChapters(chapterGroup, retChapter, comicPathWord)
            }
        }
        val otherChapters = ArrayList<SChapter>()
        keys.filter { it -> it != "default" }.forEach { groupName ->
            run {
                val chapterGroup = chapterGroups.getJSONObject(groupName)
                fillChapters(chapterGroup, otherChapters, comicPathWord)
            }
        }
        retChapter.addAll(0, otherChapters)
        // place others to top, as other group updates not so often
        return retChapter.asReversed()
    }

    private fun fillChapters(chapterGroup: JSONObject, retChapter: ArrayList<SChapter>, comicPathWord: String?) {
        val groupName = chapterGroup.getString("path_word")
        val total = chapterGroup.getInt("count")
        val pages = total / chapterPageSize + 1
        // Get all chapter pages
        for (page in 1..pages) {
            val chapterUrlString = "$baseUrl/api/v3/comic/$comicPathWord/group/$groupName/chapters?limit=$chapterPageSize&offset=${(page - 1) * chapterPageSize}&platform=3"
            val response: Response = client.newCall(GET(chapterUrlString, headers)).execute()
            // results > list
            val chapterArray = JSONObject(response.body!!.string()).optJSONObject("results").optJSONArray("list")
            if (chapterArray != null) {
                for (i in 0 until chapterArray.length()) {
                    val chapter = chapterArray.getJSONObject(i)
                    retChapter.add(
                        SChapter.create().apply {
                            name = chapter.getString("name")
                            date_upload = stringToUnixTimestamp(chapter.getString("datetime_created"))
                            // url = "/api/v3/comic/$comicPathWord/chapter2/${chapter.getString("uuid")}"
                            url = "/comic/$comicPathWord/chapter/${chapter.getString("uuid")}"
                        }
                    )
                }
            }
        }
    }

    // Compatible with old url
    // old url:"/comic/$comicPathWord/chapter/${chapter.getString("uuid")}"
    // new url:"/api/v3/comic/$comicPathWord/chapter2/${chapter.getString("uuid")}"
    override fun pageListRequest(chapter: SChapter) = GET(baseUrl + chapter.url.replace("/comic/", "/api/v3/comic/").replace("/chapter/", "/chapter2/"), headers)
    override fun pageListParse(response: Response): List<Page> {
        val body = response.body!!.string()
        // results > chapter > contents[]
        val res = JSONObject(body)
        val chapter = res.getJSONObject("results").getJSONObject("chapter")
        val wordsArray = chapter.getJSONArray("words")
        val pageArray = chapter.getJSONArray("contents")

        val ret = ArrayList<Page>(pageArray.length())
        for (i in 0 until pageArray.length()) {
            val order = wordsArray.getInt(i)
            val page = pageArray.getJSONObject(i).getString("url")
            ret.add(Page(order, "", page))
        }
        ret.sortBy { it.index }
        return ret
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        set("User-Agent", "Dart/2.16(dart:io)")
        set("source", "copyApp")
        set("version", "1.3.8")
        set("region", if (preferences.getBoolean(CHANGE_CDN_OVERSEAS_PREF, false)) "0" else "1")
        set("webp", if (preferences.getBoolean(CHANGE_WEBP_PREF, false)) "1" else "0")
        set("authorization", "Token")
        set("platform", "3")
    }

    // Unused, we can get image urls directly from the chapter page
    override fun imageUrlParse(response: Response) =
        throw UnsupportedOperationException("This method should not be called!")

    // Copymanga has different logic in polular and search page, mix two logic in search progress for now
    override fun getFilterList() = FilterList(
        MangaFilter(
            "题材",
            "theme",
            arrayOf(
                Pair("全部", ""),
                Pair("愛情", "aiqing"),
                Pair("歡樂向", "huanlexiang"),
                Pair("冒险", "maoxian"),
                Pair("奇幻", "qihuan"),
                Pair("百合", "baihe"),
                Pair("校园", "xiaoyuan"),
                Pair("科幻", "kehuan"),
                Pair("東方", "dongfang"),
                Pair("生活", "shenghuo"),
                Pair("轻小说", "qingxiaoshuo"),
                Pair("格鬥", "gedou"),
                Pair("耽美", "danmei"),
                Pair("悬疑", "xuanyi"),
                Pair("神鬼", "shengui"),
                Pair("其他", "qita"),
                Pair("职场", "zhichang"),
                Pair("萌系", "mengxi"),
                Pair("治愈", "zhiyu"),
                Pair("長條", "changtiao"),
                Pair("四格", "sige"),
                Pair("舰娘", "jianniang"),
                Pair("节操", "jiecao"),
                Pair("TL", "teenslove"),
                Pair("竞技", "jingji"),
                Pair("搞笑", "gaoxiao"),
                Pair("伪娘", "weiniang"),
                Pair("热血", "rexue"),
                Pair("後宮", "hougong"),
                Pair("美食", "meishi"),
                Pair("性转换", "xingzhuanhuan"),
                Pair("侦探", "zhentan"),
                Pair("励志", "lizhi"),
                Pair("AA", "aa"),
                Pair("彩色", "COLOR"),
                Pair("音乐舞蹈", "yinyuewudao"),
                Pair("异世界", "yishijie"),
                Pair("战争", "zhanzheng"),
                Pair("历史", "lishi"),
                Pair("机战", "jizhan"),
                Pair("惊悚", "jingsong"),
                Pair("恐怖", "%E6%81%90%E6%80 %96"),
                Pair("都市", "dushi"),
                Pair("穿越", "chuanyue"),
                Pair("重生", "chongsheng"),
                Pair("魔幻", "mohuan"),
                Pair("宅系", "zhaixi"),
                Pair("武侠", "wuxia"),
                Pair("生存", "shengcun"),
                Pair("FATE", "fate"),
                Pair("無修正", "Uncensored"),
                Pair("转生", "zhuansheng"),
                Pair("LoveLive", "loveLive"),
                Pair("男同", "nantong"),
                Pair("仙侠", "xianxia"),
                Pair("C99", "comiket99"),
                Pair("C98", "C98"),
                Pair("C97", "comiket97"),
                Pair("C96", "comiket96"),
                Pair("C95", "comiket95")
            )
        ),
        MangaFilter(
            "排序",
            "ordering",
            arrayOf(
                Pair("最热门", "-popular"),
                Pair("最冷门", "popular"),
                Pair("最新", "-datetime_updated"),
                Pair("最早", "datetime_updated"),
            )
        ),
        MangaFilter(
            "类别",
            "top",
            arrayOf(
                Pair("全部", ""),
                Pair("日漫", "japan"),
                Pair("韩漫", "korea"),
                Pair("美漫", "west"),
                Pair("已完结", "finish"),
            )
        ),
    )

    private class MangaFilter(
        displayName: String,
        searchName: String,
        val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0
    ) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue) {
        val searchName = searchName
        fun toUriPart(): String {
            val selectVal = vals[state].second
            return if (selectVal != "") "$searchName=$selectVal" else ""
        }
    }

    private fun stringToUnixTimestamp(string: String?, pattern: String = "yyyy-MM-dd", locale: Locale = Locale.CHINA): Long {
        if (string == null) System.currentTimeMillis()

        return try {
            val time = SimpleDateFormat(pattern, locale).parse(string)?.time
            if (time != null) time else System.currentTimeMillis()
        } catch (ex: Exception) {
            // Set the time to current in order to display the updated manga in the "Recent updates" section
            System.currentTimeMillis()
        }
    }

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val zhPreference = androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_Simplified_Chinese_TITLE_PREF
            title = "将标题和简介转换为简体中文"
            summary = "需要重启软件以生效。已添加漫画需要迁移改变标题和简介。"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(SHOW_Simplified_Chinese_TITLE_PREF, newValue as Boolean).commit()
            }
        }
        val cdnPreference = androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = CHANGE_CDN_OVERSEAS_PREF
            title = "转换图片CDN为境外CDN"
            summary = "加载图片使用境外CDN，使用代理的情况下推荐打开此选项（境外CDN可能无法查看一些刚刚更新的漫画，需要等待资源更新到CDN）"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(CHANGE_CDN_OVERSEAS_PREF, newValue as Boolean).commit()
            }
        }
        val webpPreference = androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = CHANGE_WEBP_PREF
            title = "加载webp格式的图片"
            summary = "加载webp格式的图片，推荐打开此选项，体积小加载更快（关闭时加载jpeg格式图片）"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(CHANGE_WEBP_PREF, newValue as Boolean).commit()
            }
        }
        val apiUrlPreference = androidx.preference.ListPreference(screen.context).apply {
            key = API_URL_PREF
            title = "Api域名"
            summary = "选择所使用的api域名。重启软件生效。\n当前值：%s"            
            entries = APIURLS
            entryValues = APIURLS

            setDefaultValue(APIURLS[2])
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(API_URL_PREF, newValue as String).commit()
            }
        }
        val apiRateLimitPreference = androidx.preference.ListPreference(screen.context).apply {
            key = API_RATELIMIT_PREF
            title = "网站每秒连接数限制"
            summary = "此值影响向网站发起连接请求的数量。调低此值可能减少发生网络错误的几率，但加载速度也会变慢。需要重启软件以生效。\n当前值：%s"
            entries = ENTRIES_ARRAY
            entryValues = ENTRIES_ARRAY

            setDefaultValue("3")
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(API_RATELIMIT_PREF, newValue as String).commit()
            }
        }
        screen.addPreference(zhPreference)
        screen.addPreference(cdnPreference)
        screen.addPreference(webpPreference)
        screen.addPreference(apiUrlPreference)
        screen.addPreference(apiRateLimitPreference)
    }

    companion object {
        private const val SHOW_Simplified_Chinese_TITLE_PREF = "showSCTitle"
        
        private const val CHANGE_CDN_OVERSEAS_PREF = "changeCDN"
        
        private const val CHANGE_WEBP_PREF = "changeWebp"
        
        private const val API_URL_PREF = "apiUrl"
        private val APIURLS = arrayOf("api.copymanga.org", "api.copymanga.com", "api.copymanga.net", "api.copymanga.info")
        
        private const val API_RATELIMIT_PREF = "apiRatelimit"
        private val ENTRIES_ARRAY = (1..10).map { i -> i.toString() }.toTypedArray()
    }
}
