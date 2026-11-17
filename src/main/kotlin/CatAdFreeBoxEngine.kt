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
import org.w3c.dom.Element
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class CatAdFreeBoxEngine(context: JarSourceContext) : BaseJarSource(context) {

    companion object {
        // 可把此处换成你自己的单文件订阅:
        // "https://fastly.jsdelivr.net/gh/你的GitHub用户名/quguanggao@main/my_cat_sources.json"
        private const val DEFAULT_SUB_URL =
            "https://fastly.jsdelivr.net/gh/Jason-wam/Cat-Maven-2023@main/const/index.main.json"
        private const val PREF_SUB_URL_KEY = "sub_url_v3"
        private const val PREF_CACHE_SITES_KEY = "cached_sites_json_v4"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
    }

    private data class MacClassItem(val title: String, val url: String, val weight: Int)
    private data class MacNodeRule(val id: String, val name: String, val videoType: String, val weight: Int)
    private data class MacSiteConfig(
        val id: String,
        val name: String,
        val api: String,
        val headers: Map<String, String>,
        val classes: List<MacClassItem>,
        val nodes: List<MacNodeRule>
    )

    private data class VodItem(
        val id: String,
        val name: String,
        val pic: String,
        val note: String,
        val typeName: String,
        val year: String,
        val area: String,
        val lang: String,
        val actor: String,
        val des: String,
        val playLines: List<Pair<String, String>>
    )

    private data class VodPageResult(
        val hasMore: Boolean,
        val items: List<VodItem>
    )

    private val ioExecutor = Executors.newCachedThreadPool()
    private val siteFetchExecutor = Executors.newFixedThreadPool(6)

    private val mainHandlerObj: Any? by lazy {
        runCatching {
            val looperCls = Class.forName("android.os.Looper")
            val mainLooper = looperCls.getMethod("getMainLooper").invoke(null)
            val handlerCls = Class.forName("android.os.Handler")
            handlerCls.getConstructor(looperCls).newInstance(mainLooper)
        }.getOrNull()
    }

    private fun runOnMainThread(action: () -> Unit) {
        val handler = mainHandlerObj
        if (handler != null) {
            val ok = runCatching {
                handler.javaClass.getMethod("post", Runnable::class.java)
                    .invoke(handler, Runnable { action() })
                true
            }.getOrDefault(false)
            if (ok) return
        }
        action()
    }

    @Volatile
    private var cachedSites: List<MacSiteConfig>? = null

    private val subUrlPref = object : TextEditPreference(
        title = "订阅地址（支持 my_cat_sources.json 单文件）",
        summary = "填入你的单文件订阅链接，自动支持 7 条国内加速线路切换",
        hint = DEFAULT_SUB_URL
    ) {
        override fun getText(): String = context.getString(PREF_SUB_URL_KEY, DEFAULT_SUB_URL)

        override fun onTextChanged(text: String) {
            context.putString(PREF_SUB_URL_KEY, text.trim().ifEmpty { DEFAULT_SUB_URL })
            cachedSites = null
            context.remove(PREF_CACHE_SITES_KEY)
            context.showToast("订阅已更新，下次搜索将重新加载站点")
            refreshUserViews()
        }
    }

    private val blockedSitesPref = object : TextEditPreference(
        title = "屏蔽站点关键词",
        summary = "不想看到的站点名用逗号隔开（如：西瓜,淘片），保存后自动过滤",
        hint = "例如: 西瓜"
    ) {
        override fun getText(): String = context.getString("blocked_sites", "")

        override fun onTextChanged(text: String) {
            context.putString("blocked_sites", text.trim())
            context.showToast("屏蔽列表已保存")
            refreshUserViews()
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
            context.showToast("广告过滤规则已保存")
        }
    }

    private val adModePref = object : TextEditPreference(
        title = "去广告模式（1=开启 / 0=关闭）",
        summary = "1：智能过滤异常域及2~8段短插播；0：关闭去广告",
        hint = "1"
    ) {
        override fun getText(): String = context.getString("ad_mode", "1")

        override fun onTextChanged(text: String) {
            val mode = if (text.trim() == "0") "0" else "1"
            context.putString("ad_mode", mode)
            context.showToast(if (mode == "1") "已开启去广告" else "已关闭去广告")
        }
    }

    private val debugLogPref = object : TextEditPreference(
        title = "最近播放诊断日志（可复制）",
        summary = "查看已加载站点数量、去广告状态及 10:00 附近切片结构",
        hint = "暂无播放记录"
    ) {
        override fun getText(): String = context.getString("last_debug_log", "暂无播放记录")
        override fun onTextChanged(text: String) {}
    }

    init {
        id = "cat_adfree_box"
        name = "去广告聚合盒子"
        versionCode = 4
        versionName = "1.0.3"
        description = "支持纯净单文件订阅 my_cat_sources.json 与官方多线拉取，自动过滤普通插播切片广告并支持站点屏蔽。"
    }

    override fun getFaviconUrl(): String = "https://caiji.xgzyapi.com/favicon.ico"

    override fun isSupportClass(): Boolean = true

    override fun isSupportSearch(): Boolean = true

    override fun isSupportHomeRecommends(): Boolean = false

    override fun getPreferences(): List<BasePreference> {
        return listOf(subUrlPref, blockedSitesPref, customRegexPref, adModePref, debugLogPref)
    }

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
        ioExecutor.execute {
            runCatching {
                val sites = loadMacSites()
                val entities = mutableListOf<VideoClassEntity>()
                var globalWeight = 0
                for (site in sites) {
                    val shortName = site.name.removeSuffix("资源网")
                    for (cls in site.classes.sortedBy { it.weight }) {
                        entities.add(VideoClassEntity().apply {
                            title = "[$shortName] ${cls.title}"
                            url = "${site.id}|${cls.url}"
                            weight = globalWeight++
                        })
                    }
                }
                runOnMainThread { onSuccess(entities) }
            }.onFailure { err ->
                runOnMainThread { onError(err) }
            }
        }
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
                val parts = clazz.url.split("|", limit = 2)
                val siteId = parts.getOrNull(0) ?: ""
                val typeId = parts.getOrNull(1) ?: ""
                val site = loadMacSites().firstOrNull { it.id == siteId }
                    ?: throw IllegalStateException("未找到站点: $siteId")

                val pg = page + 1
                val sep = if (site.api.contains("?")) "&" else "?"
                val apiUrl = "${site.api}${sep}ac=videolist&t=$typeId&pg=$pg"
                val raw = httpGet(apiUrl, site.headers)
                    ?: throw IllegalStateException("请求站点失败: ${site.name}")

                val parsed = parseVodResponse(raw)
                val results = parsed.items.map { item ->
                    VideoClassResultEntity().apply {
                        title = item.name
                        subtitle = listOf(item.note, item.year, item.area).filter { it.isNotBlank() }.joinToString(" · ")
                        cover = item.pic
                        duration = item.note
                        detailPageUrl = "catbox://${site.id}|${item.id}"
                        sourceId = this@CatAdFreeBoxEngine.id
                        sourceName = this@CatAdFreeBoxEngine.name
                    }
                }
                val res = BaseClassResultEntity(hasMore = parsed.hasMore, results)
                runOnMainThread { onSuccess(res) }
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
                val sites = loadMacSites()
                val encodedKw = URLEncoder.encode(keywords, "UTF-8")
                val latch = CountDownLatch(sites.size)
                val merged = java.util.Collections.synchronizedList(mutableListOf<VideoSearchResultEntity>())
                var anyHasMore = false

                for (site in sites) {
                    ioExecutor.execute {
                        try {
                            val sep = if (site.api.contains("?")) "&" else "?"
                            val apiUrl = "${site.api}${sep}ac=videolist&wd=$encodedKw&pg=$page"
                            val raw = httpGet(apiUrl, site.headers, timeoutMs = 8000)
                            if (!raw.isNullOrBlank()) {
                                val parsed = parseVodResponse(raw)
                                if (parsed.hasMore) anyHasMore = true
                                parsed.items.forEach { item ->
                                    merged.add(VideoSearchResultEntity().apply {
                                        cover = item.pic
                                        title = "【${site.name}】${item.name}"
                                        duration = item.note
                                        subtitle = listOf(site.name, item.typeName, item.year, item.note)
                                            .filter { it.isNotBlank() }
                                            .joinToString(" / ")
                                        detailPageUrl = "catbox://${site.id}|${item.id}"
                                        sourceId = this@CatAdFreeBoxEngine.id
                                        sourceName = this@CatAdFreeBoxEngine.name
                                    })
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                latch.await(10, TimeUnit.SECONDS)
                val res = BaseSearchResultEntity(hasMore = anyHasMore, value = merged.toList())
                runOnMainThread { onSuccess(res) }
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
                val payload = detailPageUrl.removePrefix("catbox://")
                val parts = payload.split("|", limit = 2)
                val siteId = parts.getOrNull(0) ?: ""
                val vodId = parts.getOrNull(1) ?: ""
                val site = loadMacSites().firstOrNull { it.id == siteId }
                    ?: throw IllegalStateException("未找到站点: $siteId")

                val sep = if (site.api.contains("?")) "&" else "?"
                val apiUrl = "${site.api}${sep}ac=videolist&ids=$vodId"
                val raw = httpGet(apiUrl, site.headers)
                    ?: throw IllegalStateException("获取详情失败")
                val item = parseVodResponse(raw).items.firstOrNull()
                    ?: throw IllegalStateException("未解析到视频详情")

                val detail = VideoDetailEntity().apply {
                    id = "${site.id}_${item.id}"
                    title = item.name
                    subtitle = listOf(site.name, item.lang, item.typeName, item.year, item.note)
                        .filter { it.isNotBlank() }
                        .joinToString(" / ")
                    cover = item.pic
                    description = buildString {
                        if (item.actor.isNotBlank()) append("主演：${item.actor}\n")
                        append(
                            item.des
                                .replace("&nbsp;", " ")
                                .replace(Regex("<[^>]+>"), "")
                                .trim()
                        )
                    }
                    browserUrl = apiUrl
                    url = apiUrl
                    sourceId = this@CatAdFreeBoxEngine.id
                    sourceName = this@CatAdFreeBoxEngine.name

                    item.playLines.forEachIndexed { lineIdx, (flag, rawList) ->
                        val nodeCfg = site.nodes.firstOrNull { it.id.equals(flag, true) }
                        val channelTitle = nodeCfg?.name ?: flag.ifBlank { "线路${lineIdx + 1}" }
                        val isSniffConfig = nodeCfg?.videoType.equals("Sniff", ignoreCase = true)

                        val episodes = rawList.split("#").map { it.trim() }.filter { it.isNotEmpty() }
                        if (episodes.isNotEmpty()) {
                            channels.add(VideoDetailChannelEntity().apply {
                                id = "${site.id}_${item.id}_$flag".toMd5String()
                                title = channelTitle
                                weight = nodeCfg?.weight ?: lineIdx
                                videoDataList.addAll(episodes.mapIndexed { epIdx, epStr ->
                                    val epParts = epStr.split("$")
                                    val epName = if (epParts.size >= 2) epParts[0].trim() else "第${epIdx + 1}集"
                                    val epUrl = if (epParts.size >= 2) epParts[1].trim() else epParts[0].trim()
                                    val isDirectMedia = epUrl.contains(".m3u8", true) || epUrl.contains(".mp4", true)

                                    VideoDetailDataEntity().apply {
                                        id = "${site.id}_${item.id}_${flag}_$epIdx".toMd5String()
                                        title = epName
                                        if (!isSniffConfig || isDirectMedia) {
                                            videoUrl = "catplay://${site.id}|$epUrl"
                                            videoType = VideoType.Decode
                                            canDownload = true
                                        } else {
                                            videoUrl = epUrl
                                            videoType = runCatching { VideoType.valueOf("Sniff") }
                                                .getOrDefault(VideoType.Decode)
                                            canDownload = false
                                        }
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
            val rawPayload = dataItem.videoUrl
            val (siteId, actualUrl) = if (rawPayload.startsWith("catplay://")) {
                val body = rawPayload.removePrefix("catplay://")
                val idx = body.indexOf('|')
                if (idx > 0) body.substring(0, idx) to body.substring(idx + 1)
                else "" to body
            } else {
                "" to rawPayload
            }

            val sites = loadMacSites()
            val siteHeaders = sites.firstOrNull { it.id == siteId }?.headers ?: emptyMap()

            val playHeaders = hashMapOf<String, String>()
            playHeaders["User-Agent"] = siteHeaders["User-Agent"] ?: DEFAULT_UA
            siteHeaders.forEach { (k, v) -> playHeaders[k] = v }

            val rule = buildCurrentAdRule()
            val shouldClean = rule.enabled && actualUrl.contains(".m3u8", ignoreCase = true)

            val loadedSummary = "已加载站点(${sites.size}个): " + sites.joinToString(",") { it.name.removeSuffix("资源网") }

            val finalPlayUrl = if (shouldClean) {
                val (report, status) = M3u8AdCleaner.fetchAndCleanWithReport(actualUrl, playHeaders, rule)
                val logText = buildString {
                    appendLine(loadedSummary)
                    appendLine("订阅来源: ${context.getString("last_sub_source", "缓存/默认")}")
                    appendLine("原始地址: $actualUrl")
                    appendLine("状态: $status")
                    if (report != null) {
                        appendLine("最终M3U8: ${report.finalUrl}")
                        appendLine(report.sampleAround600s.ifBlank { "(无)" })
                    }
                }
                context.putString("last_debug_log", logText)
                runOnMainThread { context.showToast("去广告: $status") }

                if (report != null) {
                    LocalM3u8Proxy.register(report.cleanedContent)
                } else {
                    actualUrl
                }
            } else {
                context.putString(
                    "last_debug_log",
                    "$loadedSummary\n未清洗(mode=${adModePref.getText()}, url=$actualUrl)"
                )
                actualUrl
            }

            val entity = PlayUrlEntity(url = finalPlayUrl, headers = playHeaders)
            runOnMainThread { onSuccess(entity) }
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
            .mapNotNull { pattern -> runCatching { Regex(pattern) }.getOrNull() }

        return AdBlockRule(
            enabled = enabled,
            urlBlacklistRegex = defaultRegex + extraRegex,
            enableDiscontinuityFilter = true,
            maxAdGroupDurationSec = 45.0,
            aggressiveDiscontinuityDrop = false
        )
    }

    private fun buildMirrorCandidates(subUrl: String): List<String> {
        val rawMatch = Regex("""^https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/(?:refs/heads/)?([^/]+)/(.+)$""")
            .find(subUrl)
        val jsdMatch = Regex("""^https?://[^/]*jsdelivr\.net/gh/([^/]+)/([^/@]+)@([^/]+)/(.+)$""")
            .find(subUrl)

        val match = rawMatch ?: jsdMatch ?: return listOf(subUrl)
        val (owner, repo, branch, path) = match.destructured

        return listOf(
            subUrl,
            "https://fastly.jsdelivr.net/gh/$owner/$repo@$branch/$path",
            "https://gcore.jsdelivr.net/gh/$owner/$repo@$branch/$path",
            "https://testingcf.jsdelivr.net/gh/$owner/$repo@$branch/$path",
            "https://raw.kkgithub.com/$owner/$repo/$branch/$path",
            "https://ghproxy.net/https://raw.githubusercontent.com/$owner/$repo/$branch/$path",
            "https://hk.gh-proxy.org/https://raw.githubusercontent.com/$owner/$repo/$branch/$path",
            "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        ).distinct()
    }

    private fun applyBlockedFilter(list: List<MacSiteConfig>): List<MacSiteConfig> {
        val blockedKeywords = blockedSitesPref.getText()
            .split(",", "，", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (blockedKeywords.isEmpty()) return list
        return list.filterNot { site ->
            blockedKeywords.any { kw ->
                site.name.contains(kw, ignoreCase = true) || site.id.equals(kw, true)
            }
        }
    }

    @Synchronized
    private fun loadMacSites(): List<MacSiteConfig> {
        cachedSites?.let { if (it.isNotEmpty()) return applyBlockedFilter(it) }

        val subUrl = subUrlPref.getText()
        val fetched = runCatching { fetchSitesFromSubscription(subUrl) }.getOrNull()
        val baseList = if (!fetched.isNullOrEmpty()) {
            fetched.toMutableList()
        } else {
            val diskCached = context.getString(PREF_CACHE_SITES_KEY, "")
            val restored = if (diskCached.isNotBlank()) {
                runCatching { deserializeSites(diskCached) }.getOrNull()
            } else null
            restored?.toMutableList() ?: mutableListOf()
        }

        if (baseList.isEmpty()) {
            baseList.add(defaultXgzySite())
        }

        if (!fetched.isNullOrEmpty()) {
            runCatching { context.putString(PREF_CACHE_SITES_KEY, serializeSites(baseList)) }
        }
        cachedSites = baseList
        return applyBlockedFilter(baseList)
    }

    private fun fetchSitesFromSubscription(subUrl: String): List<MacSiteConfig> {
        val candidates = buildMirrorCandidates(subUrl)
        var workingSubUrl: String? = null
        var rootObj: JSONObject? = null

        for (candidate in candidates) {
            val subText = httpGet(candidate, mapOf("User-Agent" to DEFAULT_UA), timeoutMs = 6000)
            if (!subText.isNullOrBlank()) {
                val parsed = runCatching { JSONObject(subText) }.getOrNull()
                val hasSites = (parsed?.optJSONArray("sites")?.length() ?: 0) > 0
                val hasList = (parsed?.optJSONArray("list")?.length() ?: 0) > 0
                if (hasSites || hasList) {
                    workingSubUrl = candidate
                    rootObj = parsed
                    break
                }
            }
        }

        if (workingSubUrl == null || rootObj == null) return emptyList()
        context.putString("last_sub_source", workingSubUrl)

        val inlineSites = rootObj.optJSONArray("sites")
        if (inlineSites != null && inlineSites.length() > 0) {
            return (0 until inlineSites.length()).mapNotNull { i ->
                inlineSites.optJSONObject(i)?.let { parseMacSiteJson(it) }
            }
        }

        val list = rootObj.optJSONArray("list") ?: return emptyList()
        val baseDir = workingSubUrl.substringBeforeLast("/")
        val macUrls = mutableListOf<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optString("engineType").equals("Mac", ignoreCase = true)) {
                val dl = item.optString("downloadUrl").trim()
                if (dl.isNotEmpty()) {
                    val fullUrl = if (dl.startsWith("http://", true) || dl.startsWith("https://", true)) {
                        dl
                    } else {
                        "$baseDir/${dl.trimStart('/')}"
                    }
                    macUrls.add(fullUrl)
                }
            }
        }

        val result = java.util.Collections.synchronizedList(mutableListOf<MacSiteConfig>())
        val latch = CountDownLatch(macUrls.size)
        for (cfgUrl in macUrls) {
            siteFetchExecutor.execute {
                try {
                    val cfgText = httpGet(cfgUrl, mapOf("User-Agent" to DEFAULT_UA), timeoutMs = 8000)
                    if (!cfgText.isNullOrBlank()) {
                        parseMacSiteJson(JSONObject(cfgText))?.let { result.add(it) }
                    }
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(20, TimeUnit.SECONDS)
        return result.toList()
    }

    private fun parseMacSiteJson(obj: JSONObject): MacSiteConfig? {
        val id = obj.optString("id").trim()
        val api = obj.optString("api").trim()
        val name = obj.optString("name").trim()
        if (id.isEmpty() || api.isEmpty()) return null

        val headers = mutableMapOf<String, String>()
        obj.optJSONObject("headers")?.let { hObj ->
            val keys = hObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                headers[k] = hObj.optString(k)
            }
        }

        val classes = mutableListOf<MacClassItem>()
        obj.optJSONArray("classEntities")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                classes.add(
                    MacClassItem(
                        title = c.optString("title"),
                        url = c.optString("url"),
                        weight = c.optInt("weight", i)
                    )
                )
            }
        }

        val nodes = mutableListOf<MacNodeRule>()
        obj.optJSONArray("dtNodesEntities")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i) ?: continue
                nodes.add(
                    MacNodeRule(
                        id = n.optString("id"),
                        name = n.optString("name"),
                        videoType = n.optString("videoType", "Direct"),
                        weight = n.optInt("weight", i)
                    )
                )
            }
        }

        return MacSiteConfig(id, name, api, headers, classes, nodes)
    }

    private fun serializeSites(sites: List<MacSiteConfig>): String {
        val arr = JSONArray()
        sites.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("api", s.api)
                put("headers", JSONObject(s.headers))
                put("classEntities", JSONArray().apply {
                    s.classes.forEach { c ->
                        put(JSONObject().apply {
                            put("title", c.title)
                            put("url", c.url)
                            put("weight", c.weight)
                        })
                    }
                })
                put("dtNodesEntities", JSONArray().apply {
                    s.nodes.forEach { n ->
                        put(JSONObject().apply {
                            put("id", n.id)
                            put("name", n.name)
                            put("videoType", n.videoType)
                            put("weight", n.weight)
                        })
                    }
                })
            })
        }
        return arr.toString()
    }

    private fun deserializeSites(jsonStr: String): List<MacSiteConfig> {
        val arr = JSONArray(jsonStr)
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parseMacSiteJson(it) }
        }
    }

    private fun defaultXgzySite(): MacSiteConfig {
        return MacSiteConfig(
            id = "xgzyapi",
            name = "西瓜资源网",
            api = "https://caiji.xgzyapi.com/api.php/provide/vod/at/xml/",
            headers = mapOf("User-Agent" to DEFAULT_UA),
            classes = listOf(
                MacClassItem("短剧", "36", 1),
                MacClassItem("动作片", "6", 2),
                MacClassItem("喜剧片", "7", 3),
                MacClassItem("国产剧", "13", 4),
                MacClassItem("韩国剧", "15", 5),
                MacClassItem("欧美剧", "16", 6),
                MacClassItem("国产动漫", "29", 7),
                MacClassItem("日韩动漫", "30", 8)
            ),
            nodes = listOf(
                MacNodeRule("xiguam3u8", "在线播放", "Direct", 0),
                MacNodeRule("xigua", "云播放", "Sniff", 1)
            )
        )
    }

    private fun parseVodResponse(rawText: String): VodPageResult {
        val trimmed = rawText.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        return if (trimmed.startsWith("{")) {
            parseVodJson(trimmed)
        } else {
            parseVodXml(trimmed)
        }
    }

    private fun parseVodJson(jsonText: String): VodPageResult {
        val root = JSONObject(jsonText)
        val page = root.optInt("page", 1)
        val pageCount = root.optInt("pagecount", 1)
        val list = root.optJSONArray("list") ?: JSONArray()
        val items = mutableListOf<VodItem>()

        for (i in 0 until list.length()) {
            val v = list.optJSONObject(i) ?: continue
            val playFroms = v.optString("vod_play_from").split("$$$")
            val playUrls = v.optString("vod_play_url").split("$$$")
            val lines = playUrls.mapIndexed { idx, urls ->
                val flag = playFroms.getOrNull(idx)?.trim() ?: "line$idx"
                flag to urls.trim()
            }.filter { it.second.isNotEmpty() }

            items.add(
                VodItem(
                    id = v.optString("vod_id"),
                    name = v.optString("vod_name"),
                    pic = v.optString("vod_pic"),
                    note = v.optString("vod_remarks"),
                    typeName = v.optString("type_name"),
                    year = v.optString("vod_year"),
                    area = v.optString("vod_area"),
                    lang = v.optString("vod_lang"),
                    actor = v.optString("vod_actor"),
                    des = v.optString("vod_content"),
                    playLines = lines
                )
            )
        }
        return VodPageResult(hasMore = page < pageCount, items = items)
    }

    private fun parseVodXml(xmlText: String): VodPageResult {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(xmlText.toByteArray(Charsets.UTF_8)))
        val listEl = doc.getElementsByTagName("list").item(0) as? Element
        val page = listEl?.getAttribute("page")?.toIntOrNull() ?: 1
        val pageCount = listEl?.getAttribute("pagecount")?.toIntOrNull() ?: 1

        val videoNodes = doc.getElementsByTagName("video")
        val items = mutableListOf<VodItem>()
        for (i in 0 until videoNodes.length) {
            val el = videoNodes.item(i) as? Element ?: continue
            val ddNodes = el.getElementsByTagName("dd")
            val lines = mutableListOf<Pair<String, String>>()
            for (j in 0 until ddNodes.length) {
                val dd = ddNodes.item(j) as? Element ?: continue
                val flag = dd.getAttribute("flag").ifBlank { "line$j" }
                val rawPlay = dd.textContent?.trim() ?: ""
                if (rawPlay.isNotEmpty()) {
                    lines.add(flag to rawPlay)
                }
            }

            items.add(
                VodItem(
                    id = el.tagText("id"),
                    name = el.tagText("name"),
                    pic = el.tagText("pic"),
                    note = el.tagText("note"),
                    typeName = el.tagText("type"),
                    year = el.tagText("year"),
                    area = el.tagText("area"),
                    lang = el.tagText("lang"),
                    actor = el.tagText("actor"),
                    des = el.tagText("des"),
                    playLines = lines
                )
            )
        }
        return VodPageResult(hasMore = page < pageCount, items = items)
    }

    private fun Element.tagText(tagName: String): String {
        val node = getElementsByTagName(tagName).item(0) ?: return ""
        return node.textContent?.trim() ?: ""
    }

    private fun httpGet(urlStr: String, headers: Map<String, String>, timeoutMs: Int = 8000): String? {
        var conn: HttpURLConnection? = null
        return runCatching {
            val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", headers["User-Agent"] ?: DEFAULT_UA)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn = connection
            if (connection.responseCode !in 200..299) return null
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
        }.also {
            conn?.disconnect()
        }.getOrNull()
    }
}
