package eu.kanade.tachiyomi.extension.zh.copymangas

import android.app.Application
import android.content.SharedPreferences
import com.luhuiguo.chinese.ChineseUtils
import eu.kanade.tachiyomi.network.GET
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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class CopyMangas : ConfigurableSource, HttpSource() {

    override val name = "拷贝漫画"
    override val lang = "zh"
    override val supportsLatest = true
    private val searchPageSize = 18 // default
    private val chapterPageSize = 100

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl = "https://${preferences.getString(API_URL_PREF, APIURLS[0])}"

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

    override val client: OkHttpClient = super.client.newBuilder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
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

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .set("User-Agent", "Dart/2.16(dart:io)")
        .set("source", "copyApp")
        .set("version", "1.3.7")
        .set("region", if (preferences.getBoolean(CHANGE_CDN_OVERSEAS, false)) "0" else "1")
        .set("webp", if (preferences.getBoolean(CHANGE_WEBP_OPTION, false)) "1" else "0")
        .set("authorization", "Token")
        .set("platform", "3")

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
                Pair("百合", "baihe"),
                Pair("東方", "dongfang"),
                Pair("奇幻", "qihuan"),
                Pair("校园", "xiaoyuan"),
                Pair("科幻", "kehuan"),
                Pair("生活", "shenghuo"),
                Pair("轻小说", "qingxiaoshuo"),
                Pair("格鬥", "gedou"),
                Pair("神鬼", "shengui"),
                Pair("悬疑", "xuanyi"),
                Pair("耽美", "danmei"),
                Pair("其他", "qita"),
                Pair("舰娘", "jianniang"),
                Pair("职场", "zhichang"),
                Pair("治愈", "zhiyu"),
                Pair("萌系", "mengxi"),
                Pair("四格", "sige"),
                Pair("伪娘", "weiniang"),
                Pair("竞技", "jingji"),
                Pair("搞笑", "gaoxiao"),
                Pair("長條", "changtiao"),
                Pair("性转换", "xingzhuanhuan"),
                Pair("侦探", "zhentan"),
                Pair("节操", "jiecao"),
                Pair("热血", "rexue"),
                Pair("美食", "meishi"),
                Pair("後宮", "hougong"),
                Pair("励志", "lizhi"),
                Pair("音乐舞蹈", "yinyuewudao"),
                Pair("彩色", "COLOR"),
                Pair("AA", "aa"),
                Pair("异世界", "yishijie"),
                Pair("历史", "lishi"),
                Pair("战争", "zhanzheng"),
                Pair("机战", "jizhan"),
                Pair("C97", "comiket97"),
                Pair("C96", "comiket96"),
                Pair("宅系", "zhaixi"),
                Pair("C98", "C98"),
                Pair("C95", "comiket95"),
                Pair("恐怖", "%E6%81%90%E6%80 %96"),
                Pair("FATE", "fate"),
                Pair("無修正", "Uncensored"),
                Pair("穿越", "chuanyue"),
                Pair("武侠", "wuxia"),
                Pair("生存", "shengcun"),
                Pair("惊悚", "jingsong"),
                Pair("都市", "dushi"),
                Pair("LoveLive", "loveLive"),
                Pair("转生", "zhuansheng"),
                Pair("重生", "chongsheng"),
                Pair("仙侠", "xianxia")
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

    // Change Title to Simplified Chinese For Library Gobal Search Optionally
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val zhPreference = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = SHOW_Simplified_Chinese_TITLE_PREF
            title = "将标题和简介转换为简体中文"
            summary = "需要重启软件以生效。已添加漫画需要迁移改变标题和简介。"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putBoolean(SHOW_Simplified_Chinese_TITLE_PREF, newValue as Boolean).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
        val cdnPreference = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = CHANGE_CDN_OVERSEAS
            title = "转换图片CDN为境外CDN"
            summary = "加载图片使用境外CDN，使用代理的情况下推荐打开此选项（境外CDN可能无法查看一些刚刚更新的漫画，需要等待资源更新到CDN）"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putBoolean(CHANGE_CDN_OVERSEAS, newValue as Boolean).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
        val webpPreference = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = CHANGE_WEBP_OPTION
            title = "加载webp格式的图片"
            summary = "加载webp格式的图片，推荐打开此选项，体积小加载更快（关闭时加载jpeg格式图片）"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putBoolean(CHANGE_WEBP_OPTION, newValue as Boolean).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
        val apiUrlPref = androidx.preference.ListPreference(screen.context).apply {
            key = API_URL_PREF
            title = "Api域名"
            entries = APIURLS
            entryValues = APIURLS
            summary = "选择所使用的api域名。重启软件生效。"

            setDefaultValue(APIURLS[0])
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(API_URL_PREF, newValue as String).commit()
            }
        }
        screen.addPreference(zhPreference)
        screen.addPreference(cdnPreference)
        screen.addPreference(webpPreference)
        screen.addPreference(apiUrlPref)
    }

    companion object {
        private const val SHOW_Simplified_Chinese_TITLE_PREF = "showSCTitle"
        private const val CHANGE_CDN_OVERSEAS = "changeCDN"
        private const val CHANGE_WEBP_OPTION = "changeWebp"
        private const val API_URL_PREF = "apiUrl"
        private val APIURLS = arrayOf("api.copymanga.org", "api.copymanga.com", "api.copymanga.net", "api.copymanga.info")
    }
}
