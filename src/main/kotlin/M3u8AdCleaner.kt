import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class AdBlockRule(
    val enabled: Boolean = true,
    val urlBlacklistRegex: List<Regex> = listOf(
        Regex("""(?i)/(ad|ads|guanggao|promo|advert)/"""),
        Regex("""(?i)(^|/)(ad|ads|guanggao|zzad)[_-]?\d*\.(ts|jpg|png)""")
    ),
    val enableDiscontinuityFilter: Boolean = true,
    val maxAdGroupDurationSec: Double = 45.0,
    val aggressiveDiscontinuityDrop: Boolean = false
)

data class CleanReport(
    val cleanedContent: String,
    val rawSegCount: Int,
    val keptSegCount: Int,
    val groupCount: Int,
    val sampleAround600s: String,
    val finalUrl: String
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

    fun fetchAndClean(
        url: String,
        headers: Map<String, String> = emptyMap(),
        rule: AdBlockRule = AdBlockRule(),
        depth: Int = 0
    ): String? {
        return fetchAndCleanWithReport(url, headers, rule, depth).first?.cleanedContent
    }

    fun fetchAndCleanWithReport(
        url: String,
        headers: Map<String, String> = emptyMap(),
        rule: AdBlockRule = AdBlockRule(),
        depth: Int = 0
    ): Pair<CleanReport?, String> {
        if (!rule.enabled) return null to "模式为关闭(0)"
        if (depth > 3) return null to "嵌套层数过深"

        val (finalUrl, rawContent, errMsg) = httpGetTextWithRedirects(url, headers)
        if (finalUrl == null || rawContent == null) return null to "拉取失败: $errMsg"
        if (!rawContent.contains("#EXTM3U")) return null to "非M3U8内容: ${rawContent.take(80)}"

        if (rawContent.contains("#EXT-X-STREAM-INF")) {
            val subPlaylistUrl = pickBestVariantUrl(finalUrl, rawContent)
                ?: return null to "主列表未找到子播放列表"
            return fetchAndCleanWithReport(subPlaylistUrl, headers, rule, depth + 1)
        }

        if (!rawContent.contains("#EXTINF")) return null to "未找到#EXTINF切片"
        val report = cleanMediaPlaylistWithReport(finalUrl, rawContent, rule)
        return report to "清洗成功: ${report.rawSegCount}片->${report.keptSegCount}片(分段:${report.groupCount})"
    }

    fun cleanMediaPlaylist(baseUrl: String, content: String, rule: AdBlockRule): String {
        return cleanMediaPlaylistWithReport(baseUrl, content, rule).cleanedContent
    }

    fun cleanMediaPlaylistWithReport(
        baseUrl: String,
        content: String,
        rule: AdBlockRule
    ): CleanReport {
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
                }
                line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP") -> {
                    val fixedLine = resolveTagUri(baseUrl, line)
                    if (inHeader) headerLines.add(fixedLine) else pendingTags.add(fixedLine)
                }
                line.startsWith("#EXTINF:") -> {
                    inHeader = false
                    pendingExtInf = line
                    pendingDuration = line.removePrefix("#EXTINF:")
                        .substringBefore(",")
                        .toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#") -> {
                    if (inHeader) headerLines.add(line) else pendingTags.add(line)
                }
                else -> {
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

        val allSegments = groups.flatMap { it.segments }
        if (allSegments.isEmpty()) {
            return CleanReport(content, 0, 0, 0, "无切片", baseUrl)
        }

        val dominantSignature = allSegments
            .groupingBy { urlSignature(it.url) }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        val suspectUrls = mutableSetOf<String>()
        val dominantShape = allSegments
            .groupingBy { nameShape(it.url) }
            .eachCount()
            .maxByOrNull { it.value }
        if (dominantShape != null && allSegments.size >= 10 && dominantShape.value >= allSegments.size * 0.8) {
            var run = mutableListOf<Segment>()
            for (seg in allSegments) {
                if (nameShape(seg.url) != dominantShape.key) {
                    run.add(seg)
                } else {
                    if (run.isNotEmpty() && run.sumOf { it.durationSec } <= rule.maxAdGroupDurationSec) {
                        run.forEach { suspectUrls.add(it.url) }
                    }
                    run = mutableListOf()
                }
            }
            if (run.isNotEmpty() && run.sumOf { it.durationSec } <= rule.maxAdGroupDurationSec) {
                run.forEach { suspectUrls.add(it.url) }
            }
        }

        val diagBuilder = StringBuilder()
        diagBuilder.appendLine("--- 分段概览(共${groups.size}段) ---")

        val keptGroups = mutableListOf<SegmentGroup>()
        var accSec = 0.0
        for ((index, group) in groups.withIndex()) {
            val groupStartSec = accSec.toInt()
            val groupDurSec = group.totalDuration.toInt()
            accSec += group.totalDuration

            var regexHitCount = 0
            var shapeHitCount = 0
            val filteredSegments = group.segments.filterNot { seg ->
                val hitShape = seg.url in suspectUrls
                val hitRegex = rule.urlBlacklistRegex.any { it.containsMatchIn(seg.url) }
                if (hitShape) shapeHitCount++
                if (hitRegex) regexHitCount++
                hitShape || hitRegex
            }.toMutableList()

            val isAdGroup = if (filteredSegments.isNotEmpty() && rule.enableDiscontinuityFilter && groups.size > 1) {
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

            if (index < 15) {
                val firstFile = group.segments.first().url.substringAfterLast("/").take(28)
                val status = when {
                    filteredSegments.isEmpty() -> "全删(正则$regexHitCount/突变$shapeHitCount)"
                    isAdGroup -> "删短段"
                    else -> "保留(正则删$regexHitCount/突变删$shapeHitCount)"
                }
                diagBuilder.appendLine("G$index[@${groupStartSec}s,${groupDurSec}s,${group.segments.size}片] $status | $firstFile")
            }

            if (filteredSegments.isNotEmpty() && !isAdGroup) {
                keptGroups.add(SegmentGroup(filteredSegments))
            }
        }

        diagBuilder.appendLine("--- 10分钟附近切片 ---")
        var tSec = 0.0
        groups.forEachIndexed { gIdx, g ->
            g.segments.forEach { s ->
                val startSec = tSec
                tSec += s.durationSec
                if (tSec >= 570.0 && startSec <= 645.0) {
                    diagBuilder.appendLine(
                        "G$gIdx(${startSec.toInt()}s,+${s.durationSec}s) ${s.url.substringAfterLast("/")}"
                    )
                }
            }
        }

        if (keptGroups.isEmpty()) {
            return CleanReport(content, allSegments.size, allSegments.size, groups.size, diagBuilder.toString(), baseUrl)
        }

        val sb = StringBuilder()
        headerLines.forEach { sb.appendLine(it) }

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
        val keptCount = keptGroups.sumOf { it.segments.size }
        return CleanReport(sb.toString(), allSegments.size, keptCount, groups.size, diagBuilder.toString(), baseUrl)
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

    private fun nameShape(urlStr: String): String {
        val fileName = urlStr.substringBefore("?").substringAfterLast("/").substringBeforeLast(".")
        val isAllDigits = fileName.isNotEmpty() && fileName.all { it.isDigit() }
        val lenBucket = fileName.length / 4
        return "$isAllDigits-$lenBucket"
    }

    private fun httpGetTextWithRedirects(
        urlStr: String,
        headers: Map<String, String>
    ): Triple<String?, String?, String> {
        var currentUrl = urlStr
        for (hop in 0..5) {
            var conn: HttpURLConnection? = null
            try {
                val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty(
                        "User-Agent",
                        headers["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"
                    )
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                conn = connection
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val loc = connection.getHeaderField("Location")
                        ?: return Triple(null, null, "重定向缺少Location")
                    currentUrl = resolveUrl(currentUrl, loc)
                    continue
                }
                if (code !in 200..299) {
                    return Triple(null, null, "HTTP $code")
                }
                val text = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                    it.readText()
                }
                return Triple(currentUrl, text, "OK")
            } catch (e: Exception) {
                return Triple(null, null, "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
        return Triple(null, null, "重定向超过5次")
    }
}
