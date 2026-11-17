import concurrent.futures
import json
import ssl
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

OFFICIAL_SUB_URL = "https://raw.githubusercontent.com/Jason-wam/Cat-Maven-2023/main/const/index.main.json"

# 已知不想保留的站点（名字或 id 关键词），生成 my_cat_sources.json 时会自动剔除
EXCLUDE_KEYWORDS = ["西瓜"]

# 设为 True 时，自动把测出“超过15段假标签混淆”的站点（类似西瓜）也剔除出纯净订阅
AUTO_DROP_OBFUSCATED = True

SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE


def http_get(url: str, headers: dict = None, timeout: int = 10):
    req_headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/136.0.0.0 Safari/537.36"
    }
    if headers:
        req_headers.update(headers)
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


def probe_one_site(cfg: dict):
    name = cfg.get("name", "")
    api = cfg.get("api", "")
    headers = cfg.get("headers") or {}
    sep = "&" if "?" in api else "?"

    t0 = time.time()
    try:
        # 优先拿最新一页列表测 M3U8（最快最稳）
        _, vod_raw = http_get(f"{api}{sep}ac=videolist&pg=1", headers=headers, timeout=10)
        latency_ms = int((time.time() - t0) * 1000)
        m3u8_url = extract_m3u8_from_vod(vod_raw)
        if not m3u8_url:
            return cfg, latency_ms, "⚪ 未抓到M3U8(可能是Sniff网页源)", "-"
        status, host, g_num = analyze_m3u8(m3u8_url, headers)
        return cfg, latency_ms, status, host
    except Exception as e:
        latency_ms = int((time.time() - t0) * 1000)
        return cfg, latency_ms, f"⚠️ 连接超时/受限({type(e).__name__})", "-"


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

    # 排序：🟢排最前，🟡其次，⚠️放后，🔴放最后
    def sort_key(row):
        st = row[2]
        if st.startswith("🟢"):
            return (0, row[1])
        if st.startswith("🟡"):
            return (1, row[1])
        if st.startswith("⚠️"):
            return (2, row[1])
        return (3, row[1])

    results.sort(key=sort_key)

    clean_sites = []
    report_lines = [
        "# 33个采集源自动筛查报告",
        "",
        "> 🟢 = M3U8结构干净（建议保留，但仍建议实测1部剧防硬编码）  ",
        "> 🟡 = 含可清除的短分段插播广告（插件可自动切除）  ",
        "> 🔴 = 全流多段假标签混淆（类似西瓜，无法靠M3U8规则清除）  ",
        "> ⚠️ = 海外GitHub服务器连不上该站（不代表国内手机不能用，已默认保留在订阅中）",
        "",
        "| 站点名称 | ID | 延迟 | M3U8体检结果 | 播放CDN域名 | 状态 |",
        "|---|---|---|---|---|---|",
    ]

    for cfg, ms, status, host in results:
        name = cfg.get("name", "")
        sid = cfg.get("id", "")
        in_blacklist = any(kw.lower() in name.lower() or kw.lower() == sid.lower() for kw in EXCLUDE_KEYWORDS)
        is_obfuscated = status.startswith("🔴") and AUTO_DROP_OBFUSCATED

        if in_blacklist or is_obfuscated:
            action = "❌ 已剔除"
        else:
            action = "✅ 已收入"
            # 精简保留 CatAdFreeBoxEngine 需要的核心字段，减小体积
            clean_sites.append({
                "id": sid,
                "name": name,
                "api": cfg.get("api", ""),
                "headers": cfg.get("headers", {}),
                "classEntities": cfg.get("classEntities", []),
                "dtNodesEntities": cfg.get("dtNodesEntities", []),
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

    print(f"完成！体检 {len(results)} 个源，收入 {len(clean_sites)} 个到 my_cat_sources.json")


if __name__ == "__main__":
    main()
