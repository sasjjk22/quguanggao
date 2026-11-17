import base64
import concurrent.futures
import json
import os
import re
import ssl
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

try:
    from curl_cffi import requests as cffi_requests
except ImportError:
    cffi_requests = None

OFFICIAL_SUB_URL = "https://raw.githubusercontent.com/Jason-wam/Cat-Maven-2023/main/const/index.main.json"
EXCLUDE_KEYWORDS = ["西瓜"]
AUTO_DROP_OBFUSCATED = True
AUTO_DROP_DEAD = True

SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE


def http_get(url: str, headers: dict = None, timeout: int = 10):
    parsed = urllib.parse.urlparse(url)
    origin = f"{parsed.scheme}://{parsed.netloc}/"
    req_headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
        "Accept": "application/json, text/xml, application/xml, text/plain, */*",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Referer": origin,
    }
    if headers:
        req_headers.update(headers)

    if cffi_requests is not None:
        resp = cffi_requests.get(
            url,
            headers=req_headers,
            timeout=timeout,
            impersonate="chrome",
            verify=False,
            allow_redirects=True,
        )
        if resp.status_code >= 400:
            raise RuntimeError(f"HTTP_{resp.status_code}")
        return resp.url, resp.text

    req = urllib.request.Request(url, headers=req_headers)
    with urllib.request.urlopen(req, timeout=timeout, context=SSL_CTX) as resp:
        return resp.geturl(), resp.read().decode("utf-8", errors="ignore")


def extract_m3u8_from_vod(raw_text: str) -> str:
    text = raw_text.lstrip("\ufeff \r\n\t")
    play_blobs = []
    if text.startswith("{"):
        data = json.loads(text)
        for v in data.get("list", []):
            play_blobs.extend(v.get("vod_play_url", "").split("$$$"))
    else:
        root = ET.fromstring(text)
        for dd in root.findall(".//dd"):
            if dd.text:
                play_blobs.append(dd.text)

    for blob in play_blobs:
        for ep in blob.split("#"):
            parts = ep.split("$")
            cand = parts[1].strip() if len(parts) >= 2 else parts[0].strip()
            if ".m3u8" in cand.lower():
                return cand
    return ""


def analyze_m3u8(m3u8_url: str, headers: dict):
    cur_url = m3u8_url
    content = ""
    for _ in range(3):
        cur_url, content = http_get(cur_url, headers=headers, timeout=10)
        if "#EXT-X-STREAM-INF" not in content:
            break
        lines = [ln.strip() for ln in content.splitlines() if ln.strip()]
        sub = next((ln for ln in lines if not ln.startswith("#")), None)
        if not sub:
            break
        cur_url = urllib.parse.urljoin(cur_url, sub)

    if "#EXTINF" not in content:
        return "⚠️ 非标准M3U8", cur_url, 0

    groups = []
    cur_dur = 0.0
    cur_count = 0
    for ln in content.splitlines():
        ln = ln.strip()
        if ln == "#EXT-X-DISCONTINUITY":
            if cur_count > 0:
                groups.append((cur_dur, cur_count))
                cur_dur, cur_count = 0.0, 0
        elif ln.startswith("#EXTINF:"):
            dur_str = ln.split(":", 1)[1].split(",", 1)[0]
            try:
                cur_dur += float(dur_str)
            except ValueError:
                pass
            cur_count += 1
    if cur_count > 0:
        groups.append((cur_dur, cur_count))

    g_num = len(groups)
    short_groups = sum(1 for d, _ in groups if d <= 45.0)
    host = urllib.parse.urlparse(cur_url).netloc

    if g_num >= 15:
        return f"🔴 高危混淆(分段{g_num}个)", host, g_num
    if 2 <= g_num <= 8 and short_groups > 0:
        return f"🟡 含可去插播(分段{g_num},短段{short_groups})", host, g_num
    if g_num == 1:
        return "🟢 结构极净(单段无插播)", host, g_num
    return f"🟢 常规多段(分段{g_num})", host, g_num


def detect_better_json_api(api: str, headers: dict) -> tuple:
    """尝试把 /at/xml/ 转成 TVBox 兼容性更好的 type=1 JSON 接口"""
    api_lower = api.lower()
    if "/at/xml" not in api_lower and not api_lower.endswith("xml") and not api_lower.endswith("xml/"):
        return api, 1

    candidates = [
        re.sub(r"/at/xml/?$", "/", api, flags=re.I),
        re.sub(r"/at/xml/?$", "/at/json/", api, flags=re.I),
    ]
    for cand in candidates:
        if cand == api:
            continue
        sep = "&" if "?" in cand else "?"
        try:
            _, txt = http_get(f"{cand}{sep}ac=videolist&pg=1", headers=headers, timeout=6)
            stripped = txt.lstrip("\ufeff \r\n\t")
            if stripped.startswith("{") and '"list"' in stripped:
                return cand, 1
        except Exception:
            pass
    return api, 0


def probe_one_site(cfg: dict):
    api = cfg.get("api", "")
    headers = cfg.get("headers") or {}
    sep = "&" if "?" in api else "?"

    t0 = time.time()
    try:
        _, vod_raw = http_get(f"{api}{sep}ac=videolist&pg=1", headers=headers, timeout=10)
        latency_ms = int((time.time() - t0) * 1000)

        tv_api, tv_type = detect_better_json_api(api, headers)
        cfg["_tvbox_api"] = tv_api
        cfg["_tvbox_type"] = tv_type

        m3u8_url = extract_m3u8_from_vod(vod_raw)
        if not m3u8_url:
            return cfg, latency_ms, "⚪ 未抓到M3U8(可能是Sniff网页源)", "-"
        status, host, _ = analyze_m3u8(m3u8_url, headers)
        return cfg, latency_ms, status, host
    except Exception as e:
        latency_ms = int((time.time() - t0) * 1000)
        cfg["_tvbox_api"] = api
        api_lower = api.lower()
        cfg["_tvbox_type"] = 0 if ("/at/xml" in api_lower or api_lower.endswith("xml") or api_lower.endswith("xml/")) else 1
        msg = str(e)
        if "HTTP_" in msg:
            code = msg.split("HTTP_", 1)[1].split()[0]
            return cfg, latency_ms, f"⚠️ 被拦截/报错(HTTP {code})", "-"
        if "timed out" in msg.lower() or "timeout" in msg.lower():
            return cfg, latency_ms, "⚠️ 连接超时", "-"
        if (
            "could not resolve" in msg.lower()
            or "name or service not known" in msg.lower()
            or "nodename nor servname" in msg.lower()
        ):
            return cfg, latency_ms, "💀 域名已失效(DNS解析失败)", "-"
        return cfg, latency_ms, f"⚠️ 连接失败({type(e).__name__})", "-"


def write_builtin_kotlin(sub_obj: dict):
    os.makedirs("src/main/kotlin", exist_ok=True)
    raw_bytes = json.dumps(sub_obj, ensure_ascii=False).encode("utf-8")
    b64_str = base64.b64encode(raw_bytes).decode("ascii")
    chunks = [b64_str[i:i + 8000] for i in range(0, len(b64_str), 8000)]

    lines = [
        "import java.util.Base64",
        "",
        "object BuiltinSources {",
        "    private val chunks = listOf(",
    ]
    for idx, ch in enumerate(chunks):
        comma = "," if idx < len(chunks) - 1 else ""
        lines.append(f'        "{ch}"{comma}')
    lines.extend([
        "    )",
        "",
        "    fun loadJson(): String {",
        '        val b64 = chunks.joinToString("")',
        "        return String(Base64.getDecoder().decode(b64), Charsets.UTF_8)",
        "    }",
        "}",
        "",
    ])
    with open("src/main/kotlin/BuiltinSources.kt", "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def sort_tvbox_categories(class_entities: list) -> list:
    """提取站点真实子分类，过滤无效项并把剧集/动漫/短剧排前面"""
    titles = [c.get("title", "").strip() for c in sorted(class_entities, key=lambda x: x.get("weight", 0))]
    titles = [t for t in titles if t and t not in ("伦理片", "福利", "里番", "电影", "连续剧", "电视剧")]

    def cat_rank(t: str):
        if any(k in t for k in ("国产剧", "内地剧", "大陆剧")):
            return 0
        if "短剧" in t:
            return 1
        if "剧" in t:
            return 2
        if "动漫" in t or "动画" in t:
            return 3
        if "综艺" in t:
            return 4
        if "片" in t:
            return 5
        return 6

    return sorted(titles, key=cat_rank)


def build_tvbox_json(clean_sites: list) -> dict:
    tvbox_sites = []
    for s in clean_sites:
        tv_api = s.get("_tvbox_api") or s.get("api", "")
        tv_type = s.get("_tvbox_type")
        if tv_type is None:
            api_lower = tv_api.lower()
            tv_type = 0 if ("/at/xml" in api_lower or api_lower.endswith("xml") or api_lower.endswith("xml/")) else 1

        site_entry = {
            "key": s["id"],
            "name": s["name"],
            "type": tv_type,
            "api": tv_api,
            "searchable": 1,
            "quickSearch": 1,
            "filterable": 1,
        }
        cats = sort_tvbox_categories(s.get("classEntities", []))
        if cats:
            site_entry["categories"] = cats
        tvbox_sites.append(site_entry)

    return {
        "spider": "",
        "wallpaper": "",
        "lives": [],
        "sites": tvbox_sites,
        "parses": [],
        "flags": [
            "youku", "qq", "iqiyi", "qiyi", "letv", "sohu",
            "tudou", "pptv", "mgtv", "imgo", "bilibili", "m1905"
        ],
        "rules": [
            {
                "name": "暴风广告",
                "hosts": ["bfzy", "bfbfvip", "bfengbf", "fengbao"],
                "regex": [
                    "#EXT-X-DISCONTINUITY\\r*\\n*#EXTINF:3,[\\s\\S]*?#EXT-X-DISCONTINUITY",
                    "#EXTINF.*?\\s+.*?adjump.*?\\.ts",
                    "#EXTINF.*?\\s+.*?1o.*?\\.ts\\s+"
                ]
            },
            {
                "name": "樱花广告",
                "hosts": ["wgslsw.com", "apiyhzy"],
                "regex": [
                    "#EXT-X-DISCONTINUITY\\r*\\n*#EXTINF:.*?\\r*\\n*.*?\\r*\\n*#EXT-X-DISCONTINUITY"
                ]
            },
            {
                "name": "量子广告",
                "hosts": ["vip.lz", "hd.lz", ".cdnlz", "v.cdnlz"],
                "regex": [
                    "#EXT-X-DISCONTINUITY\\r*\\n*#EXTINF:6\\.433333,[\\s\\S]*?#EXT-X-DISCONTINUITY",
                    "#EXTINF.*?\\s+.*?1o.*?\\.ts\\s+",
                    "18.5333",
                    "19.52"
                ]
            },
            {
                "name": "非凡广告",
                "hosts": ["vip.ffzy", "hd.ffzy", "ffzy"],
                "regex": [
                    "#EXT-X-DISCONTINUITY\\r*\\n*#EXTINF:6\\.666667,[\\s\\S]*?#EXT-X-DISCONTINUITY",
                    "#EXT-X-DISCONTINUITY\\r*\\n*#EXTINF:6\\.600000,[\\s\\S]*?#EXT-X-DISCONTINUITY",
                    "#EXTINF.*?\\s+.*?1o.*?\\.ts\\s+",
                    "25.0666",
                    "25.08",
                    "25.1"
                ]
            },
            {
                "name": "索尼广告",
                "hosts": ["suonizy", "qrssv.com"],
                "regex": [
                    "#EXT-X-DISCONTINUITY\\r*\\n*#EXTINF:7\\.680000,[\\s\\S]*?#EXT-X-DISCONTINUITY",
                    "15.1666",
                    "15.2666"
                ]
            },
            {
                "name": "优质广告",
                "hosts": ["yzzy", "playback"],
                "regex": ["16.63", "18.66", "17.66", "19.13"]
            },
            {
                "name": "快看广告",
                "hosts": ["kuaikan", "vip.kuaikan"],
                "regex": ["20.123", "20.167"]
            }
        ]
    }


def main():
    _, index_raw = http_get(OFFICIAL_SUB_URL, timeout=15)
    index_data = json.loads(index_raw)
    base_dir = OFFICIAL_SUB_URL.rsplit("/", 1)[0]

    mac_urls = []
    for item in index_data.get("list", []):
        if item.get("engineType", "").lower() == "mac":
            dl = item.get("downloadUrl", "").strip()
            if dl:
                mac_urls.append(dl if dl.startswith("http") else f"{base_dir}/{dl.lstrip('/')}")

    configs = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        futs = [pool.submit(http_get, u, None, 10) for u in mac_urls]
        for f in concurrent.futures.as_completed(futs):
            try:
                _, txt = f.result()
                cfg = json.loads(txt)
                if cfg.get("id") and cfg.get("api"):
                    configs.append(cfg)
            except Exception:
                pass

    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for res in pool.map(probe_one_site, configs):
            results.append(res)

    def sort_key(row):
        st = row[2]
        if st.startswith("🟢"):
            return (0, row[1])
        if st.startswith("🟡"):
            return (1, row[1])
        if st.startswith("⚪"):
            return (2, row[1])
        if st.startswith("⚠️"):
            return (3, row[1])
        if st.startswith("💀"):
            return (4, row[1])
        return (5, row[1])

    results.sort(key=sort_key)

    clean_sites = []
    tvbox_raw_sites = []
    report_lines = [
        "# 采集源自动筛查报告（Chrome 指纹增强版）",
        "",
        "> 🟢 = M3U8结构干净（建议保留）  ",
        "> 🟡 = 含可清除的短分段插播广告（插件可自动切除）  ",
        "> 🔴 = 全流多段假标签混淆（已自动剔除）  ",
        "> ⚠️ = 被防火墙拦截(如403)或海外连接超时（国内手机仍可能正常）  ",
        "> 💀 = 域名解析失败/已失效（已自动剔除）",
        "",
        "| 站点名称 | ID | 延迟 | M3U8体检结果 | 播放CDN域名 | 状态 |",
        "|---|---|---|---|---|---|",
    ]

    for cfg, ms, status, host in results:
        name = cfg.get("name", "")
        sid = cfg.get("id", "")
        in_blacklist = any(kw.lower() in name.lower() or kw.lower() == sid.lower() for kw in EXCLUDE_KEYWORDS)
        is_obfuscated = status.startswith("🔴") and AUTO_DROP_OBFUSCATED
        is_dead = status.startswith("💀") and AUTO_DROP_DEAD

        if in_blacklist or is_obfuscated or is_dead:
            action = "❌ 已剔除"
        else:
            action = "✅ 已收入"
            clean_entry = {
                "id": sid,
                "name": name,
                "api": cfg.get("api", ""),
                "headers": cfg.get("headers", {}),
                "classEntities": cfg.get("classEntities", []),
                "dtNodesEntities": cfg.get("dtNodesEntities", []),
            }
            clean_sites.append(clean_entry)
            tvbox_raw_sites.append({
                **clean_entry,
                "_tvbox_api": cfg.get("_tvbox_api"),
                "_tvbox_type": cfg.get("_tvbox_type"),
            })

        report_lines.append(f"| {name} | `{sid}` | {ms}ms | {status} | `{host}` | {action} |")

    with open("sources_report.md", "w", encoding="utf-8") as f:
        f.write("\n".join(report_lines) + "\n")

    sub_obj = {
        "name": "我的纯净去广告单文件订阅",
        "sites": clean_sites,
    }
    with open("my_cat_sources.json", "w", encoding="utf-8") as f:
        json.dump(sub_obj, f, ensure_ascii=False, indent=2)

    with open("my_sources.json", "w", encoding="utf-8") as f:
        json.dump(build_tvbox_json(tvbox_raw_sites), f, ensure_ascii=False, indent=2)

    write_builtin_kotlin(sub_obj)
    print(f"完成！体检 {len(results)} 个源，收入 {len(clean_sites)} 个到 my_cat_sources.json 与 my_sources.json")


if __name__ == "__main__":
    main()
