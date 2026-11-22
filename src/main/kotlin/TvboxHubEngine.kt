import com.jason.network.request.UrlRequest
import com.jason.searcher.base.BaseJarSource
import com.jason.searcher.entities.*
import com.jason.searcher.entities.home.HomeRecommendsEntity
import com.jason.searcher.preference.BasePreference
import com.jason.searcher.preference.TextEditPreference
import com.jason.searcher.utils.JarSourceContext
import com.jason.searcher.utils.toMd5String
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class TvboxHubEngine(context: JarSourceContext) : BaseJarSource(context) {

    companion object {
        private const val DEFAULT_DOMAIN = "https://tv.metvb.de5.net"
        private const val DEFAULT_TOKEN = "mytvbox2026"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
    }

    private data class CatDef(val title: String, val query: String)

    private val ioExecutor = Executors.newCachedThreadPool()

    private val mainHandlerObj: Any? by lazy {
        runCatching {
            val looperCls = Class.forName("android.os.Looper")
            val mainLooper = looperCls.getMethod("getMainLooper").invoke(null)
            Class.forName("android.os.Handler").getConstructor(looperCls).newInstance(mainLooper)
        }.getOrNull()
    }

    private fun runOnMainThread(action: () -> Unit) {
        val handler = mainHandlerObj
        if (handler != null) {
            val ok = runCatching {
                handler.javaClass.getMethod("post", Runnable::class.java).invoke(handler, Runnable { action() })
                true
            }.getOrDefault(false)
            if (ok) return
        }
        action()
    }

    private val domainPref = object : TextEditPreference(
        title = "聚合服务器域名",
        summary = "填写你绑定的 Cloudflare Worker 域名（末尾不要带 /）",
        hint = DEFAULT_DOMAIN
    ) {
        override fun getText(): String = context.getString("hub_domain", DEFAULT_DOMAIN)
        override fun onTextChanged(text: String) {
            context.putString("hub_domain", text.trim().trimEnd('/'))
            context.showToast("域名已保存")
        }
    }

    private val tokenPref = object : TextEditPreference(
        title = "访问密钥（ACCESS_TOKEN）",
        summary = "对应 Cloudflare 后台设置的防白嫖访问密码",
        hint = DEFAULT_TOKEN
    ) {
        override fun getText(): String = context.getString("hub_token", DEFAULT_TOKEN)
        override fun onTextChanged(text: String) {
            context.putString("hub_token", text.trim())
            context.showToast("密钥已保存")
        }
    }

    private val adModePref = object : TextEditPreference(
        title = "手机本地 M3U8 去广告（1=开启 / 0=关闭）",
        summary = "1：直连高速CDN并在手机本地洗掉插播广告；0：原地址直出",
        hint = "1"
    ) {
        override fun getText(): String = context.getString("ad_mode", "1")
        override fun onTextChanged(text: String) {
            val mode = if (text.trim() == "0") "0" else "1"
            context.putString("ad_mode", mode)
            context.showToast(if (mode == "1") "已开启本地去广告" else "已关闭去广告")
        }
    }

    private val customRegexPref = object : TextEditPreference(
        title = "自定义广告黑名单正则",
        summary = "多条正则用英文逗号或换行分隔，匹配切片 URL 时自动剔除",
        hint = "例如: ad-cdn\\.example\\.com, /promo/"
    ) {
        override fun getText(): String = context.getString("ad_custom_regex", "")
        override fun onTextChanged(text: String) {
            context.putString("ad_custom_regex", text.trim())
            context.showToast("规则已保存")
        }
    }

    private val debugLogPref = object : TextEditPreference(
        title = "最近播放诊断日志",
        summary = "查看最近一次 M3U8 去广告状态",
        hint = "暂无播放记录"
    ) {
        override fun getText(): String = context.getString("last_debug_log", "暂无播放记录")
        override fun onTextChanged(text: String) {}
    }

    init {
        id = "tvbox_hub_cat"
        name = "全网聚合(8.6万片库)"
        versionCode = 1
        versionName = "1.0.0"
        description = "直连自建8.6万去重片库，支持六大类+豆瓣TOP250+多线路画质标识+手机本地M3U8去广告。"
    }

    private fun apiBase(): String {
        val domain = domainPref.getText().ifBlank { DEFAULT_DOMAIN }.trimEnd('/')
        val token = tokenPref.getText().ifBlank { DEFAULT_TOKEN }
        val prefix = if (token.isNotBlank()) "/t/${URLEncoder.encode(token, "UTF-8")}" else ""
        return "$domain$prefix/api/t4"
    }

    override fun getFaviconUrl(): String = "https://hd.kuktxu.com/favicon.ico"
    override fun isSupportClass(): Boolean = true
    override fun isSupportSearch(): Boolean = true
    override fun isSupportHomeRecommends(): Boolean = false

    override fun getPreferences(): List<BasePreference> =
        listOf(domainPref, tokenPref, adModePref, customRegexPref, debugLogPref)

    override fun getHomeRecommends(
        requestConfigs: UrlRequest<*>.() -> Unit,
        onError: (Throwable) -> Unit,
        onSuccess: (HomeRecommendsEntity) -> Unit
    ) {
        onError(Exception("Not support"))
    }

    override fun getClassEntities(
        requestConfigs: UrlRequest<*>.() -> Unit,
        onError: (Throwable) -> Unit,
        onSuccess: (List<VideoClassEntity>) -> Unit
    ) {
        val cats = listOf(
            CatDef("豆瓣TOP250", "t=电影&by=douban250"),
            CatDef("电影·最新", "t=电影"),
            CatDef("电影·高分", "t=电影&by=score"),
            CatDef("电视剧·最新", "t=电视剧"),
            CatDef("电视剧·高分", "t=电视剧&by=score"),
            CatDef("短剧", "t=短剧"),
            CatDef("动漫", "t=动漫"),
            CatDef("综艺", "t=综艺"),
            CatDef("纪录片", "t=纪录片"),
            CatDef("国产电影", "t=电影&sub=国产电影"),
            CatDef("欧美电影", "t=电影&sub=欧美电影"),
            CatDef("日韩电影", "t=电影&sub=日韩电影"),
            CatDef("港台电影", "t=电影&sub=港台电影"),
            CatDef("伦理电影", "t=电影&genre=伦理"),
            CatDef("国产剧", "t=电视剧&sub=国产剧"),
            CatDef("美剧", "t=电视剧&sub=美剧"),
            CatDef("韩剧", "t=电视剧&sub=韩剧"),
            CatDef("日剧", "t=电视剧&sub=日剧"),
            CatDef("港剧", "t=电视剧&sub=港剧"),
            CatDef("日漫", "t=动漫&sub=日漫"),
            CatDef("国产动漫", "t=动漫&sub=国产动漫"),
            CatDef("漫剧/动态漫", "t=短剧&sub=漫剧/动态漫")
        )
        val list = cats.mapIndexed { idx, c ->
            VideoClassEntity().apply {
                title = c.title
                url = c.query
                weight = idx
            }
        }
        runOnMainThread { onSuccess(list) }
    }

    override fun getVideoClassList(
        clazz: VideoClassEntity,
        page: Int,
        requestConfigs: UrlRequest<*>.() -> Unit,
        onError: (Throwable) -> Unit,
        onSuccess: (BaseClassResultEntity) -> Unit
    ) {
        ioExecutor.execute {
            runCatching {
                val pg = page + 1
                val apiUrl = "${apiBase()}?${encodeQueryParams(clazz.url)}&pg=$pg"
                val raw = httpGet(apiUrl) ?: throw IllegalStateException("请求聚合接口失败")
                val root = JSONObject(raw)
                val arr = root.optJSONArray("list") ?: JSONArray()
                val pageCount = root.optInt("pagecount", pg)
                val results = (0 until arr.length()).mapNotNull { i ->
                    val v = arr.optJSONObject(i) ?: return@mapNotNull null
                    VideoClassResultEntity().apply {
                        title = v.optString("vod_name")
                        subtitle = listOf(v.optString("vod_remarks"), v.optString("vod_year"), v.optString("vod_area"))
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        cover = v.optString("vod_pic")
                        duration = v.optString("vod_remarks")
                        detailPageUrl = "hub://${v.optString("vod_id")}"
                        sourceId = this@TvboxHubEngine.id
                        sourceName = this@TvboxHubEngine.name
                    }
                }
                val hasMore = pg < pageCount && results.isNotEmpty()
                runOnMainThread { onSuccess(BaseClassResultEntity(hasMore = hasMore, results)) }
            }.onFailure { err ->
                runOnMainThread { onError(err) }
            }
        }
    }

    override fun search(
        keywords: String,
        page: Int,
        requestConfigs: UrlRequest<*>.() -> Unit,
        onError: (Throwable) -> Unit,
        onSuccess: (BaseSearchResultEntity) -> Unit
    ) {
        ioExecutor.execute {
            runCatching {
                val pg = if (page <= 0) 1 else page
                val encodedKw = URLEncoder.encode(keywords, "UTF-8")
                val apiUrl = "${apiBase()}?wd=$encodedKw&pg=$pg"
                val raw = httpGet(apiUrl) ?: throw IllegalStateException("搜索请求失败")
                val root = JSONObject(raw)
                val arr = root.optJSONArray("list") ?: JSONArray()
                val pageCount = root.optInt("pagecount", pg)
                val results = (0 until arr.length()).mapNotNull { i ->
                    val v = arr.optJSONObject(i) ?: return@mapNotNull null
                    VideoSearchResultEntity().apply {
                        cover = v.optString("vod_pic")
                        title = v.optString("vod_name")
                        duration = v.optString("vod_remarks")
                        subtitle = listOf(v.optString("type_name"), v.optString("vod_year"), v.optString("vod_remarks"))
                            .filter { it.isNotBlank() }.joinToString(" / ")
                        detailPageUrl = "hub://${v.optString("vod_id")}"
                        sourceId = this@TvboxHubEngine.id
                        sourceName = this@TvboxHubEngine.name
                    }
                }
                val hasMore = pg < pageCount && results.isNotEmpty()
                runOnMainThread { onSuccess(BaseSearchResultEntity(hasMore = hasMore, value = results)) }
            }.onFailure { err ->
                runOnMainThread { onError(err) }
            }
        }
    }

    override fun getDetail(
        result: VideoSearchResultEntity,
        requestConfigs: UrlRequest<*>.() -> Unit,
        onError: (Throwable) -> Unit,
        onSuccess: (VideoDetailEntity) -> Unit
    ) {
        doGetDetail(result.detailPageUrl, onError, onSuccess)
    }

    override fun getDetail(
        detailPageUrl: String,
        requestConfigs: UrlRequest<*>.() -> Unit,
        onError: (Throwable) -> Unit,
        onSuccess: (VideoDetailEntity) -> Unit
    ) {
        doGetDetail(detailPageUrl, onError, onSuccess)
    }

    private fun doGetDetail(
        detailPageUrl: String,
        onError: (Throwable) -> Unit,
        onSuccess: (VideoDetailEntity) -> Unit
    ) {
        ioExecutor.execute {
            runCatching {
                val vodId = detailPageUrl.removePrefix("hub://").trim()
                val apiUrl = "${apiBase()}?ids=${URLEncoder.encode(vodId, "UTF-8")}"
                val raw = httpGet(apiUrl) ?: throw IllegalStateException("获取详情失败")
                val item = JSONObject(raw).optJSONArray("list")?.optJSONObject(0)
                    ?: throw IllegalStateException("未解析到详情")

                val froms = item.optString("vod_play_from").split("$$$")
                val urls = item.optString("vod_play_url").split("$$$")

                val detail = VideoDetailEntity().apply {
                    id = vodId
                    title = item.optString("vod_name")
                    subtitle = listOf(item.optString("type_name"), item.optString("vod_year"), item.optString("vod_remarks"))
                        .filter { it.isNotBlank() }.joinToString(" / ")
                    cover = item.optString("vod_pic")
                    description = buildString {
                        val score = item.optString("vod_score")
                        if (score.isNotBlank()) append("评分：★$score\n")
                        val actor = item.optString("vod_actor")
                        if (actor.isNotBlank()) append("主演：$actor\n")
                        append(item.optString("vod_content").replace("&nbsp;", " ").replace(Regex("<[^>]+>"), "").trim())
                    }
                    browserUrl = apiUrl
                    url = apiUrl
                    sourceId = this@TvboxHubEngine.id
                    sourceName = this@TvboxHubEngine.name

                    urls.forEachIndexed { lineIdx, rawList ->
                        val chName = froms.getOrNull(lineIdx)?.trim()?.ifBlank { "线路${lineIdx + 1}" } ?: "线路${lineIdx + 1}"
                        val episodes = rawList.split("#").map { it.trim() }.filter { it.isNotEmpty() }
                        if (episodes.isNotEmpty()) {
                            channels.add(VideoDetailChannelEntity().apply {
                                id = "${vodId}_${lineIdx}".toMd5String()
                                title = chName
                                weight = lineIdx
                                videoDataList.addAll(episodes.mapIndexed { epIdx, epStr ->
                                    val epParts = epStr.split("$", limit = 2)
                                    val epName = if (epParts.size >= 2) epParts[0].trim() else "第${epIdx + 1}集"
                                    val epUrl = if (epParts.size >= 2) epParts[1].trim() else epParts[0].trim()
                                    VideoDetailDataEntity().apply {
                                        id = "${vodId}_${lineIdx}_$epIdx".toMd5String()
                                        title = epName
                                        videoUrl = "catplay://$epUrl"
                                        videoType = VideoType.Decode
                                        canDownload = true
                                    }
                                })
                            })
                        }
                    }
                }
                runOnMainThread { onSuccess(detail) }
            }.onFailure { err ->
                runOnMainThread { onError(err) }
            }
        }
    }

    override fun getPlayUrl(
        dataItem: VideoDetailDataEntity,
        requestConfigs: UrlRequest<*>.() -> Unit,
        onError: (Throwable) -> Unit,
        onSuccess: (PlayUrlEntity) -> Unit
    ) {
        ioExecutor.execute {
            val actualUrl = dataItem.videoUrl.removePrefix("catplay://").trim()
            val playHeaders = hashMapOf("User-Agent" to DEFAULT_UA)
            val rule = buildCurrentAdRule()
            val shouldClean = rule.enabled && actualUrl.contains(".m3u8", ignoreCase = true)

            val finalPlayUrl = if (shouldClean) {
                val (report, status) = M3u8AdCleaner.fetchAndCleanWithReport(actualUrl, playHeaders, rule)
                context.putString("last_debug_log", "原始: $actualUrl\n状态: $status")
                runOnMainThread { context.showToast("去广告: $status") }
                if (report != null) LocalM3u8Proxy.register(report.cleanedContent) else actualUrl
            } else {
                actualUrl
            }
            runOnMainThread { onSuccess(PlayUrlEntity(url = finalPlayUrl, headers = playHeaders)) }
        }
    }

    override fun getDownloadUrl(
        dataItem: VideoDetailDataEntity,
        requestConfigs: UrlRequest<*>.() -> Unit,
        onError: (Throwable) -> Unit,
        onSuccess: (DownloadUrlEntity) -> Unit
    ) {
        getPlayUrl(dataItem, requestConfigs, onError, onSuccess = {
            onSuccess(DownloadUrlEntity(url = it.url))
        })
    }

    private fun buildCurrentAdRule(): AdBlockRule {
        val enabled = adModePref.getText() != "0"
        val defaultRegex = listOf(
            Regex("""(?i)/(ad|ads|guanggao|promo|advert)/"""),
            Regex("""(?i)(^|/)(ad|ads|guanggao|zzad)[_-]?\d*\.(ts|jpg|png)""")
        )
        val extraRegex = customRegexPref.getText()
            .split(",", "\n", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { runCatching { Regex(it) }.getOrNull() }
        return AdBlockRule(
            enabled = enabled,
            urlBlacklistRegex = defaultRegex + extraRegex,
            enableDiscontinuityFilter = true,
            maxAdGroupDurationSec = 45.0,
            aggressiveDiscontinuityDrop = false
        )
    }

    private fun encodeQueryParams(rawQuery: String): String {
        return rawQuery.split("&").mapNotNull { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv[0].isBlank()) return@mapNotNull null
            val k = URLEncoder.encode(kv[0], "UTF-8")
            val v = if (kv.size > 1) URLEncoder.encode(kv[1], "UTF-8") else ""
            "$k=$v"
        }.joinToString("&")
    }

    private fun httpGet(urlStr: String, timeoutMs: Int = 8000): String? {
        var currentUrl = urlStr
        for (hop in 0..3) {
            var conn: HttpURLConnection? = null
            try {
                val parsed = URL(currentUrl)
                val connection = (parsed.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", DEFAULT_UA)
                    setRequestProperty("Accept", "application/json, */*")
                }
                conn = connection
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val loc = connection.getHeaderField("Location") ?: return null
                    currentUrl = URI(currentUrl).resolve(loc).toString()
                    continue
                }
                if (code !in 200..299) return null
                return BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            } catch (_: Exception) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }
}
