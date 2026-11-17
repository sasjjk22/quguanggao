# 33个采集源自动筛查报告

> 🟢 = M3U8结构干净（建议保留，但仍建议实测1部剧防硬编码）  
> 🟡 = 含可清除的短分段插播广告（插件可自动切除）  
> 🔴 = 全流多段假标签混淆（类似西瓜，无法靠M3U8规则清除）  
> ⚠️ = 海外GitHub服务器连不上该站（不代表国内手机不能用，已默认保留在订阅中）

| 站点名称 | ID | 延迟 | M3U8体检结果 | 播放CDN域名 | 状态 |
|---|---|---|---|---|---|
| 光速资源 | `guangsuapi` | 152ms | 🟢 结构极净(单段无插播) | `v.gsuus.com` | ✅ 已收入 |
| 虎牙资源网 | `huyaapi` | 163ms | 🟢 结构极净(单段无插播) | `1080p.huyall.com` | ✅ 已收入 |
| 极速资源网 | `jszyapi` | 179ms | 🟢 结构极净(单段无插播) | `vv.jisuzyv.com` | ✅ 已收入 |
| 金鹰资源网 | `jyzyapi` | 259ms | 🟢 结构极净(单段无插播) | `hd.kuktxu.com` | ✅ 已收入 |
| 爱坤资源 | `ikunzyapi` | 268ms | 🟢 常规多段(分段10) | `bfikuncdn.com` | ✅ 已收入 |
| 豪华资源网 | `hhzyapi` | 321ms | 🟢 结构极净(单段无插播) | `play.hhuus.com` | ✅ 已收入 |
| 红牛资源网 | `hongniuzy2` | 478ms | 🟢 结构极净(单段无插播) | `hn.bfvvs.com` | ✅ 已收入 |
| 新浪资源 | `xinlangapi` | 587ms | 🟢 结构极净(单段无插播) | `play.xluuss.com` | ✅ 已收入 |
| 360资源 | `360zy` | 2095ms | 🟢 常规多段(分段10) | `vod1.maowushi.com` | ✅ 已收入 |
| 樱花资源 | `apiyhzy` | 350ms | 🟡 含可去插播(分段2,短段1) | `vod12.wgslsw.com` | ✅ 已收入 |
| 暴风资源 | `bfzyapi` | 514ms | 🟡 含可去插播(分段5,短段2) | `v.fengbao14.com` | ✅ 已收入 |
| 卧龙资源 | `wolongzy` | 37ms | ⚠️ 连接超时/受限(URLError) | `-` | ✅ 已收入 |
| 果脯资源网 | `gfzywapi` | 41ms | ⚠️ 连接超时/受限(URLError) | `-` | ✅ 已收入 |
| 豆瓣资源 | `dbzy5` | 165ms | ⚠️ 连接超时/受限(URLError) | `-` | ✅ 已收入 |
| 天涯资源网 | `tyyszyapi` | 467ms | ⚠️ 连接超时/受限(RemoteDisconnected) | `-` | ✅ 已收入 |
| 无尽资源网 | `wujinapi` | 680ms | ⚠️ 连接超时/受限(HTTPError) | `-` | ✅ 已收入 |
| 快车资源 | `kuaichezy` | 750ms | ⚠️ 连接超时/受限(HTTPError) | `-` | ✅ 已收入 |
| OK资源 | `okzyw` | 783ms | ⚠️ 连接超时/受限(HTTPError) | `-` | ✅ 已收入 |
| 最大资源网 | `zuidapi` | 806ms | ⚠️ 连接超时/受限(HTTPError) | `-` | ✅ 已收入 |
| 索尼资源 | `suoniapi` | 836ms | ⚠️ 连接超时/受限(HTTPError) | `-` | ✅ 已收入 |
| 鸭鸭资源网 | `yayazyapi` | 925ms | ⚠️ 连接超时/受限(HTTPError) | `-` | ✅ 已收入 |
| 百度资源 | `apibdzy` | 1587ms | ⚠️ 连接超时/受限(HTTPError) | `-` | ✅ 已收入 |
| U酷资源 | `ukuapi88` | 1765ms | ⚠️ 连接超时/受限(HTTPError) | `-` | ✅ 已收入 |
| 茅台资源网 | `maotaizy` | 2107ms | ⚠️ 连接超时/受限(URLError) | `-` | ✅ 已收入 |
| 天堂资源网 | `dyttzyapi` | 2876ms | ⚠️ 连接超时/受限(HTTPError) | `-` | ✅ 已收入 |
| 淘片资源 | `taopianzy` | 2885ms | ⚠️ 连接超时/受限(HTTPError) | `-` | ✅ 已收入 |
| 优质资源网 | `yzzy-api` | 3157ms | ⚠️ 连接超时/受限(HTTPError) | `-` | ✅ 已收入 |
| 非凡资源 | `ffzyapi` | 3984ms | ⚠️ 连接超时/受限(HTTPError) | `-` | ✅ 已收入 |
| 如意资源 | `rycjapi` | 4380ms | ⚠️ 连接超时/受限(HTTPError) | `-` | ✅ 已收入 |
| 量子资源网 | `lziapi` | 4741ms | ⚠️ 连接超时/受限(HTTPError) | `-` | ✅ 已收入 |
| 猫眼资源网 | `maoyanapi` | 12188ms | ⚠️ 连接超时/受限(TimeoutError) | `-` | ✅ 已收入 |
| 王者资源 | `wsyzyapi` | 421ms | 🔴 高危混淆(分段152个) | `v5.wsyzym3u8.com` | ❌ 已剔除 |
| 西瓜资源网 | `xgzyapi` | 694ms | 🔴 高危混淆(分段108个) | `vip17.jimxtc.com` | ❌ 已剔除 |
