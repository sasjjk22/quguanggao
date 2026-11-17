package com.myplugin

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * 去广告规则配置
 */
data class AdBlockRule(
    val enabled: Boolean = true,
    /** 黑名单正则：匹配到任意一条的切片 URL 会被剔除（如广告域名、路径关键词） */
    val urlBlacklistRegex: List<Regex> = listOf(
        Regex("""(?i)/(ad|ads|guanggao|promo|advert)/"""),
        Regex("""(?i)(ad|guanggao|zzad)\d*\.(ts|jpg|png)""")
    ),
    /** 是否启用 #EXT-X-DISCONTINUITY 分段智能去广告 */
    val enableDiscontinuityFilter: Boolean = true,
    /** 判定为插播/片头广告的最大分段时长（秒），超过此时长视为正常分段 */
    val maxAdGroupDurationSec: Double = 45.0,
    /**
     * 激进模式：
     * - false（推荐/零误杀）：仅当 Discontinuity 短分段满足「URL命中黑名单」或「域名/路径前缀与正片主体不一致」时剔除
     * - true：额外剔除所有处于正片中间、且总时长 <= maxAdGroupDurationSec 的短 Discontinuity 分段
     */
    val aggressiveDiscontinuityDrop: Boolean = false
)

object M3u8AdCleaner {

    private val keyUriRegex = Regex("""URI="([^"]+)"""")

    private data class Segment(
        val extInfLine: String,
        val durationSec: Double,
        val extraTags: List<String>,
        val url: String
    )

    private data class SegmentGroup(
        val segments: MutableList<Segment> = mutableListOf()
    ) {
        val totalDuration: Double get() = segments.sumOf { it.durationSec }
    }

    /**
     * 拉取并清洗 M3U8，失败时返回 null（外层自动回退到原始地址）
     */
    fun fetchAndClean(
        url: String,
        headers: Map<String, String> = emptyMap(),
        rule: AdBlockRule = AdBlockRule(),
        depth: Int = 0
    ): String? {
        if (!rule.enabled || depth > 3) return null

        val (finalUrl, rawContent) = httpGetText(url, headers) ?: return null
        if (!rawContent.contains("#EXTM3U")) return null

        // 1. 如果是 Master Playlist（包含多清晰度子列表），先选最高码率（或首个）递归追踪
        if (rawContent.contains("#EXT-X-STREAM-INF")) {
            val subPlaylistUrl = pickBestVariantUrl(finalUrl, rawContent) ?: return null
            return fetchAndClean(subPlaylistUrl, headers, rule, depth + 1)
        }

        // 2. 如果是具体 Media Playlist（包含 #EXTINF），执行清洗
        if (!rawContent.contains("#EXTINF")) return null
        return cleanMediaPlaylist(finalUrl, rawContent, rule)
    }

    /**
     * 清洗 Media Playlist 文本
     */
    fun cleanMediaPlaylist(baseUrl: String, content: String, rule: AdBlockRule): String {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val headerLines = mutableListOf<String>()
        val groups = mutableListOf<SegmentGroup>()
        var currentGroup = SegmentGroup()

        var inHeader = true
        var pendingTags = mutableListOf<String>()
        var pendingExtInf: String? = null
        var pendingDuration = 0.0
        var hasEndList = false

        for (line in lines) {
            when {
                line == "#EXTM3U" -> headerLines.add(line)
                line == "#EXT-X-ENDLIST" -> hasEndList = true
                line == "#EXT-X-DISCONTINUITY" -> {
                    inHeader = false
                    if (currentGroup.segments.isNotEmpty()) {
                        groups.add(currentGroup)
                        currentGroup = SegmentGroup()
                    }
                    // 分段标记由组间重建，不放入 pendingTags
                }
                line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP") -> {
                    val fixedLine = resolveTagUri(baseUrl, line)
                    if (inHeader) {
                        headerLines.add(fixedLine)
                    } else {
                        pendingTags.add(fixedLine)
                    }
                }
                line.startsWith("#EXTINF:") -> {
                    inHeader = false
                    pendingExtInf = line
                    pendingDuration = line.removePrefix("#EXTINF:")
                        .substringBefore(",")
                        .toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#") -> {
                    if (inHeader) {
                        headerLines.add(line)
                    } else {
                        pendingTags.add(line)
                    }
                }
                else -> {
                    // 切片 URL 行
                    inHeader = false
                    val absUrl = resolveUrl(baseUrl, line)
                    if (pendingExtInf != null) {
                        currentGroup.segments.add(
                            Segment(
                                extInfLine = pendingExtInf,
                                durationSec = pendingDuration,
                                extraTags = pendingTags.toList(),
                                url = absUrl
                            )
                        )
                        pendingExtInf = null
                        pendingDuration = 0.0
                        pendingTags = mutableListOf()
                    }
                }
            }
        }
        if (currentGroup.segments.isNotEmpty()) {
            groups.add(currentGroup)
        }

        if (groups.isEmpty()) return content

        // 统计全片占比最高的主体特征（Host + 前两级路径），用于识别异构 CDN 广告段
        val allSegments = groups.flatMap { it.segments }
        val dominantSignature = allSegments
            .groupingBy { urlSignature(it.url) }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        // 过滤分段与单个切片
        val keptGroups = mutableListOf<SegmentGroup>()
        for ((index, group) in groups.withIndex()) {
            // 先按 URL 黑名单正则过滤组内单个切片
            val filteredSegments = group.segments.filterNot { seg ->
                rule.urlBlacklistRegex.any { regex -> regex.containsMatchIn(seg.url) }
            }.toMutableList()

            if (filteredSegments.isEmpty()) continue

            // 再判断整个 Discontinuity 分段是否为广告
            val isAdGroup = if (rule.enableDiscontinuityFilter && groups.size > 1) {
                val isShortGroup = group.totalDuration <= rule.maxAdGroupDurationSec
                val groupSig = urlSignature(filteredSegments.first().url)
                val isDifferentSource = dominantSignature != null && groupSig != dominantSignature
                val isMiddleGroup = index in 1 until (groups.size - 1)

                isShortGroup && (
                    isDifferentSource ||
                        (rule.aggressiveDiscontinuityDrop && isMiddleGroup)
                    )
            } else {
                false
            }

            if (!isAdGroup) {
                keptGroups.add(SegmentGroup(filteredSegments))
            }
        }

        // 若规则过严导致全部被删，兜底返回未删减的版本，防止无法播放
        if (keptGroups.isEmpty()) return content

        // 重建清洗后的 M3U8 文本
        val sb = StringBuilder()
        headerLines.forEach { sb.appendLine(it) }

        // 追踪全局加密 Key，防止中间广告段带 METHOD=NONE 导致后续正片解密失败
        var lastHeaderKey: String? = headerLines.lastOrNull { it.startsWith("#EXT-X-KEY") }
        var currentActiveKey: String? = lastHeaderKey

        keptGroups.forEachIndexed { gIdx, group ->
            if (gIdx > 0) {
                sb.appendLine("#EXT-X-DISCONTINUITY")
            }
            group.segments.forEach { seg ->
                val segKey = seg.extraTags.lastOrNull { it.startsWith("#EXT-X-KEY") }
                if (segKey == null && currentActiveKey != lastHeaderKey && lastHeaderKey != null) {
                    sb.appendLine(lastHeaderKey)
                    currentActiveKey = lastHeaderKey
                }
                seg.extraTags.forEach { tag ->
                    sb.appendLine(tag)
                    if (tag.startsWith("#EXT-X-KEY")) {
                        currentActiveKey = tag
                        if (!tag.contains("METHOD=NONE", ignoreCase = true)) {
                            lastHeaderKey = tag
                        }
                    }
                }
                sb.appendLine(seg.extInfLine)
                sb.appendLine(seg.url)
            }
        }

        if (hasEndList) {
            sb.appendLine("#EXT-X-ENDLIST")
        }
        return sb.toString()
    }

    private fun pickBestVariantUrl(baseUrl: String, masterContent: String): String? {
        val lines = masterContent.lines().map { it.trim() }
        var bestBandwidth = -1L
        var bestUrl: String? = null
        var firstUrl: String? = null

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) {
                    j++
                }
                if (j < lines.size) {
                    val abs = resolveUrl(baseUrl, lines[j])
                    if (firstUrl == null) firstUrl = abs
                    if (bw > bestBandwidth) {
                        bestBandwidth = bw
                        bestUrl = abs
                    }
                }
                i = j
            } else {
                i++
            }
        }
        return bestUrl ?: firstUrl
    }

    private fun resolveTagUri(baseUrl: String, tagLine: String): String {
        return keyUriRegex.replace(tagLine) { match ->
            val rawUri = match.groupValues[1]
            val absUri = resolveUrl(baseUrl, rawUri)
            """URI="$absUri""""
        }
    }

    private fun resolveUrl(baseUrl: String, target: String): String {
        if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
            return target
        }
        return runCatching { URI(baseUrl).resolve(target).toString() }.getOrDefault(target)
    }

    private fun urlSignature(urlStr: String): String {
        return runCatching {
            val uri = URI(urlStr)
            val pathParts = uri.path.split("/").filter { it.isNotEmpty() }.take(2)
            "${uri.host}/${pathParts.joinToString("/")}"
        }.getOrDefault(urlStr)
    }

    private fun httpGetText(urlStr: String, headers: Map<String, String>): Pair<String, String>? {
        var conn: HttpURLConnection? = null
        return runCatching {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    headers["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"
                )
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn!!.responseCode
            if (code !in 200..299) return null
            val finalUrl = conn!!.url.toString()
            val text = BufferedReader(InputStreamReader(conn!!.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
            finalUrl to text
        }.also {
            conn?.disconnect()
        }.getOrNull()
    }
}
