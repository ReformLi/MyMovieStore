# 项目结构说明

本文档描述 `MyMovieStore` 当前代码结构、分层职责和主要数据流。项目是单模块 Android 应用，主包名为 `com.hpu.mymoviestore`。

## 架构概览

项目采用 MVVM + Repository + Data Source 的轻量分层结构。当前实现把数据能力拆成内容发现、内容播放和下载管理三条链路。

```text
Presentation
Activity / Fragment / Adapter
        ↓
ViewModel
        ↓
Repository
        ↓
Data Source / Room DAO / DownloadEngine
        ↓
DoubanDiscoverySource / CrawlerVideoSource / VideoSourceManager / DownloadEngine / SQLite
```

内容发现层由 `DoubanDiscoverySource` 负责，主要服务首页。内容播放层由 `VideoSource` 接口及其实现类负责，主要服务搜索、详情和播放。下载管理层由 `DownloadEngine` + `DownloadRepository` 负责，提供 M3U8 分片下载、弹幕下载和离线播放。每个播放源持有独立的 `RequestRateLimiter` 限流器。`VideoSourceManager` 读取本地 `assets/sample_video_source.json`，作为首页兜底挡板和本地数据补充。

## 顶层目录

```text
MyMovieStore/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── AGENTS.md
├── README.md
├── UI 视觉统一规范文档.md
└── project_structure.md
```

| 文件或目录 | 说明 |
|------------|------|
| `app/` | Android 应用模块 |
| `gradle/libs.versions.toml` | 统一管理依赖和插件版本 |
| `AGENTS.md` | 面向协作方的工程约定（构建命令、工具链、架构要点、领域约定） |
| `README.md` | 项目功能、构建和使用说明（含 Android TV 适配章节） |
| `project_structure.md` | 当前架构与文件职责说明（本文档） |
| `UI 视觉统一规范文档.md` | 颜色/字体/间距/组件与 TV 焦点视觉规范 |

## 应用模块结构

```text
app/src/main/
├── AndroidManifest.xml
├── assets/
│   └── sample_video_source.json
├── java/com/hpu/mymoviestore/
│   ├── MovieApplication.kt
│   ├── data/
│   └── presentation/
└── res/
    ├── color/
    ├── drawable/
    ├── layout/            ← 手机竖屏
    ├── layout-land/       ← 横屏（电视 + 手机横屏共用，**非 TV 专属**）
    ├── menu/
    ├── mipmap-*/
    ├── values/
    ├── values-night/
    └── xml/
```

## 应用入口

### `MovieApplication.kt`

`MovieApplication` 是应用级初始化入口，`onCreate` 中按以下顺序完成：

1. 记录静态单例 `instance`，随后 `ThemeManager.applySaved(this)` 应用持久化的浅色/深色模式（**必须在任何 Activity 创建前调用**）。
2. `CloudflareBypassManager.init(this)`：读取设备 WebView 真实 UA，供过盾与爬虫请求统一使用。
3. 初始化 Room 数据库 `MovieDatabase`。
4. 初始化 `PlayHistoryRepository`、`SearchHistoryRepository`、`ApiCacheRepository`、`DownloadRepository`、`PermissionConfigRepository`。
5. 创建本地挡板源 `VideoSourceManager`（`assets/sample_video_source.json` + `api_cache` TTL 缓存）与首页发现源 `DoubanDiscoverySource`。
6. 创建 `VideoRepository`（初始源列表为空，后续由配置管理器注入）。
7. `VideoSourceConfigManager.initConfig()`：爬虫源**由远程 JSON 动态构建**（非硬编码），有缓存则同步加载（毫秒级）并异步做每日更新，无缓存则重试最多 5 次；`state` 被观察，失败时打日志。
8. 启动即执行、此后每 6 小时一轮的过期 `api_cache` 清理循环。
9. `downloadRepository.pauseAll()`：应用重启后把数据库里「下载中/等待中」的任务重置为**暂停**（`DownloadEngine` 是内存态，重启后任务已丢失）。
10. 后台静默触发一次权限配置检查（`fetchPermissionAsync()`）。

另提供：

- 全局 Coil `ImageLoader`（`newImageLoader()`）：为豆瓣图片自动补充 `Referer`、`Origin` 与浏览器 `User-Agent` 处理防盗链；磁盘缓存**收敛到 128MB**（默认 250MB 偏大）。
- 全局 `allVideoSources` 访问器 + `updateVideoSources()`，供「我的」页面管理播放源并同步注入 `VideoRepository`。
- `applicationScope`（`SupervisorJob + Dispatchers.IO`），供跨 Activity 生存周期的后台任务使用。

当前全局依赖通过 `MovieApplication.get()` 获取。

## Data 层

```text
data/
├── cache/
│   └── DanmakuCache.kt
├── CloudflareBypassManager.kt
├── HttpClientProvider.kt
├── WebViewHtmlFetcher.kt
├── dao/
│   ├── ApiCacheDao.kt
│   ├── DownloadTaskDao.kt
│   ├── DownloadedVideoIndexDao.kt
│   ├── PlayHistoryDao.kt
│   └── SearchHistoryDao.kt
├── database/
│   └── MovieDatabase.kt
├── download/
│   ├── DanmakuDownloadManager.kt
│   ├── DownloadEngine.kt
│   ├── DownloadNotificationManager.kt
│   ├── DownloadService.kt
│   └── M3u8Parser.kt
├── entity/
│   ├── ApiCacheEntity.kt
│   ├── DownloadTaskEntity.kt
│   ├── DownloadedVideoIndexEntity.kt
│   ├── PlayHistoryEntity.kt
│   └── SearchHistoryEntity.kt
├── model/
│   ├── CrawlError.kt
│   ├── CrawlerVideoDetail.kt
│   ├── DoubanMoviePageResult.kt
│   ├── PlayEpisode.kt
│   ├── PlayLine.kt
│   ├── SearchPageResult.kt
│   ├── VideoItem.kt
│   ├── danmaku/
│   │   ├── DanmakuBangumiResponse.kt
│   │   ├── DanmakuCommentResponse.kt
│   │   └── DanmakuSearchResponse.kt
│   └── remote/
│       ├── RemoteCategory.kt
│       ├── RemoteVideo.kt
│       ├── RemoteVideoMapper.kt
│       └── RemoteVideoResponse.kt
├── repository/
│   ├── ApiCacheRepository.kt
│   ├── DanmakuRepository.kt
│   ├── DownloadRepository.kt
│   ├── PlayHistoryRepository.kt
│   ├── SearchHistoryRepository.kt
│   └── VideoRepository.kt
└── source/
    ├── CrawlerVideoSource.kt
    ├── DanmakuApi.kt
    ├── DoubanDiscoverySource.kt
    ├── RequestRateLimiter.kt
    ├── VideoSource.kt
    ├── VideoSourceConfigManager.kt
    ├── VideoSourceManager.kt
    └── impl/                      ← 19 个爬虫源子类
        ├── A38TvVideoSource.kt        （crawler_a38tv）
        ├── BaJieVideoSource.kt        （crawler_bajie）
        ├── CechiVideoSource.kt        （crawler_cechi）
        ├── ChongchongVideoSource.kt   （crawler_chongchong）
        ├── DaMaoVideoSource.kt        （crawler_damao）
        ├── DadatuVideoSource.kt       （crawler_dadatu）
        ├── DoujiaoVideoSource.kt      （crawler_dj）
        ├── HanSenVideoSource.kt       （crawler_hansen）
        ├── HantvVideoSource.kt        （crawler_hantv）
        ├── JujiwuVideoSource.kt       （crawler_jju）
        ├── KaCheVideoSource.kt        （crawler_kache）
        ├── NiuerVideoSource.kt        （crawler_niuer）
        ├── NongminTvVideoSource.kt    （crawler_nongmin_tv）
        ├── NongmingVideoSource.kt     （crawler_nm）
        ├── ShenMaVideoSource.kt       （crawler_shenma）
        ├── TiantangVideoSource.kt     （crawler_tiantang）
        ├── XingChenVideoSource.kt     （crawler_xingchen）
        ├── YinghuaVideoSource.kt      （crawler_yinghua）
        └── ZaiXianVideoSource.kt      （crawler_zaixian）
```

### 反爬应对组件（data/ 根目录）

爬虫源在请求目标站时可能遇到两类拦截，分别由两组组件应对：

**① Cloudflare 人机验证自动绕过（`CloudflareBypassManager.kt`）**

后台无界面 WebView 过盾 + Cookie 管理 + 引擎兼容性判定：

| 能力 | 说明 |
|------|------|
| `init(context)` | Application.onCreate 调用；读取设备 WebView 真实 UA（CF 会按 UA 下发匹配引擎版本的挑战脚本，伪装高版本 UA 反而导致老引擎解析崩溃）并打印内核包版本日志 |
| `userAgent()` | 统一 UA：过盾 WebView、OkHttp 爬虫请求、人工验证 WebView 三处共用（`cf_clearance` 与 UA 强绑定）；读取失败回退 Chrome/110 移动版兜底 UA |
| `ensureBypassed(url)` | 过盾主入口：Cookie 缓存命中（30 分钟）直接返回 → 后台 WebView 加载目标页自动执行挑战 → JS 桥（loadUrl 前注入，当前页即生效）+ 轮询脚本检测 `#challenge-form` 自动点击 → 通过后从 CookieManager 取 `cf_clearance`；同域名并发去重 |
| 交互挑战 | 每 3 秒定位 Turnstile 挑战框位置，用 `dispatchTouchEvent` 派发真实 MotionEvent（`isTrusted=true`，JS 点击会被 CF 拒绝）模拟触屏点击 |
| 人工兜底 | 自动过盾失败弹 `CloudflareChallengeActivity`（不透明暗色窗口 + 卡片内嵌 WebView），用户点一下勾选框，CookieManager 轮询到 `cf_clearance` 自动关闭；结果经 `completeInteractive()` 回传挂起协程 |
| 引擎不兼容快速跳过 | WebChromeClient 捕获 challenge-platform 脚本 SyntaxError（设备 WebView 内核 < Chrome 80 无法解析可选链等新语法）→ 置全局 `engineIncompatible` 标记 → 后续过盾入口快速失败、不再弹验证窗；`CrawlerVideoSource` 据此对 CAPTCHA 错误写 1 小时负缓存，搜索静默跳过该源；升级系统 WebView + 重启 App 自愈 |
| `isCloudflareChallenge(body)` | 挑战页检测：中文标记（正在进行安全验证/请稍候）+ 脚本特征（challenge-platform/_cf_chl），与页面语言无关 |
| `invalidate(url)` | 过盾后仍被拦截时清除该域名的失效 Cookie 缓存 |

**② TLS 指纹拦截降级抓取（`WebViewHtmlFetcher.kt`）**

部分站点 WAF 按 TLS 握手指纹（JA3）识别并拒绝 OkHttp 等非浏览器客户端（`SSLHandshakeException: Connection reset by peer`，浏览器可正常打开）。WebView 使用浏览器同款 TLS 栈（BoringSSL），指纹与真实浏览器一致，可以绕过：

| 能力 | 说明 |
|------|------|
| `fetchHtml(url)` | 后台 WebView 加载页面（复用过盾基建：虚拟视口、统一 UA），加载完成后留 500ms 给 JS 渲染再提取 `outerHTML`，25 秒超时；不依赖 CF 挑战脚本，老引擎设备可用 |
| 降级触发 | `CrawlerVideoSource.requestDocument` 首次请求捕获 `SSLException` 时自动降级到 WebView 抓取，HTML 交给 Jsoup 解析，后续流程不变；降级也失败才报 NETWORK_ERROR（写 1 小时负缓存） |

**③ 统一 HttpClient（`HttpClientProvider.kt`）**

| 客户端 | 说明 |
|--------|------|
| `crawlerClient` | 爬虫专用 OkHttpClient，拦截器统一注入 `CloudflareBypassManager.userAgent()`（动态读取，与过盾 WebView 一致）及浏览器化请求头 |

**`HttpClientProvider.kt`** 整合全部 OkHttpClient 实例（标准/弹幕/下载/爬虫四类），超时分别为 **标准 15s / 弹幕 20s / 下载 30s**（爬虫沿用标准客户端），消除重复配置。

### 数据库

`MovieDatabase.kt` 是 Room 数据库入口：

| 配置 | 当前值 |
|------|--------|
| 数据库名 | `movie_database` |
| 当前版本 | `1` |
| 表 | `play_history`、`search_history`、`api_cache`、`download_task`、`downloaded_video_index` |
| 迁移策略 | 无迁移，应用重装即重建 |
| Schema 导出 | `exportSchema = false` |

### Entity

| Entity | 表名 | 主要用途 |
|--------|------|----------|
| `PlayHistoryEntity` | `play_history` | 保存播放历史、播放地址、播放进度、总时长、最后播放时间和**播放源名称** |
| `SearchHistoryEntity` | `search_history` | 保存搜索关键词、搜索次数和最后搜索时间 |
| `ApiCacheEntity` | `api_cache` | 保存 JSON 响应缓存，包含 TTL、创建时间和过期时间 |
| `DownloadTaskEntity` | `download_task` | 下载任务状态、进度（分片/文件大小）、本地文件路径、弹幕下载状态、**离线播放进度**（百分比/位置/时长） |
| `DownloadedVideoIndexEntity` | `downloaded_video_index` | 已下载视频索引，用于快速查找（预留） |

`DownloadTaskEntity` 关键字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `taskId` | String | 主键，格式 `{videoId}_{episodeIndex}` |
| `status` | Int | 0=等待中, 1=下载中, 2=已暂停, 3=已完成, 4=失败, 5=已取消, 6=合并中 |
| `downloadedSegments` / `totalSegments` | Int | 分片下载进度 |
| `fileSize` | Long | 文件总大小（字节） |
| `localFilePath` | String | 本地 mp4 文件路径 |
| `playUrl` | String | 原始 m3u8 播放地址 |
| `danmakuStatus` | Int | 弹幕下载状态：0=未下载, 1=下载中, 2=已完成, 3=失败, 4=重试中 |
| `danmakuFilePath` | String | 本地弹幕 JSON 文件路径 |
| `playProgressPercent` | Int | 离线播放进度百分比（-1=未观看, 0~99=已观看N%, 100=已看完） |
| `playPositionMs` | Long | 离线播放位置（毫秒） |
| `playDurationMs` | Long | 离线播放总时长（毫秒） |

### DAO

| DAO | 职责 |
|-----|------|
| `PlayHistoryDao` | 查询全部历史、按 `videoId` 查询、插入、更新历史、更新进度、删除和清空 |
| `SearchHistoryDao` | 查询搜索历史、按关键词查询、插入或更新、删除单条和清空 |
| `ApiCacheDao` | 按缓存键读取、写入、删除、按前缀删除、清理过期缓存 |
| `DownloadTaskDao` | 查询下载中/已完成任务、按 taskId 查询、按状态批量查询、按 videoId 查询、插入、更新状态/进度/弹幕/播放进度、删除 |
| `DownloadedVideoIndexDao` | 已下载视频索引的增删查（预留） |

### 下载引擎

下载引擎位于 `data/download/`，负责 M3U8 分片下载、合并和弹幕下载：

| 组件 | 职责 |
|------|------|
| `DownloadEngine` | 下载引擎核心，管理任务队列、解析 M3U8、并发下载分片、合并为 mp4、进度回调；**支持 AES-128 加密 HLS 流：解析 key + IV，分片整体下载后 AES/CBC/PKCS5Padding 解密再写盘，加密流禁用 Range 断点续传；首分片解密后校验容器头（TS 同步字节 / MP4 ftyp），校验失败抛 `HlsDecryptException` 直接终止任务** |
| `M3u8Parser` | M3U8 文件解析器，提取 `.ts` 分片 URL 列表；解析 `#EXT-X-KEY` 加密信息（`M3u8Playlist` 返回分片 + `HlsEncryption`），无显式 IV 时用分片序号，`SAMPLE-AES`/多 key 流抛 `HlsEncryptionException`；检测到 `#EXT-X-MAP`（fMP4/CMAF）同样拦截拒绝 |
| `DownloadService` | 前台服务，管理下载生命周期，显示通知和控制动作 |
| `DownloadNotificationManager` | 下载通知管理，创建进度通知、更新进度、处理用户操作（暂停/恢复/取消） |
| `DanmakuDownloadManager` | 弹幕下载管理器，根据视频标题下载弹幕 JSON 文件，支持重试；失败路径保留已有弹幕文件路径，不清空已下载的弹幕；**下载成功时同时保存 `{animeId}_{集数}.json` 索引文件，并回写 `DanmakuPrefs.saveAnimeId(videoId, animeId)` 关联**，供播放器开关打开时直接定位本地文件，避免重复联网搜索 |

**降低影响策略**（位于 `DownloadEngine` 常量配置）：

| 参数 | 值 | 说明 |
|------|------|------|
| `MAX_CONCURRENT_TASKS` | 3 | 最大并发任务数 |
| `MAX_CONCURRENT_SEGMENTS` | 3 | 每个任务最大并发分片数 |
| `SEGMENT_GAP_MS` | 2000ms | 分片间延迟 |
| `MAX_DOWNLOAD_SPEED_BPS` | 2MB/s | 单线程下载速度上限 |
| `MAX_SEGMENT_RETRIES` | 3 | 分片下载最大重试次数 |

### Repository

| Repository | 职责 |
|------------|------|
| `VideoRepository` | 聚合豆瓣发现源、多个播放源和本地挡板源，对上层提供首页、搜索、详情和播放相关数据；多源搜索时并行请求并插空法合并结果 |
| `PlayHistoryRepository` | 播放历史去重写入（含 sourceName）、进度更新、清空和按视频读取历史 |
| `SearchHistoryRepository` | 搜索词新增或更新、删除、清空和历史列表读取 |
| `ApiCacheRepository` | 封装 `api_cache` 的读写、失效、按前缀删除、过期清理和剩余 TTL 查询 |
| `DanmakuRepository` | 弹幕搜索、分集获取、弹幕列表获取，带缓存（搜索/分集/弹幕列表统一 1 天过期）；**自动重试已移除**（`MAX_RETRY = 1` 只试一次，失败由 UI 手动重试兜底）；空弹幕列表不写缓存，空缓存视为未命中 |
| `DownloadRepository` | 下载任务管理，封装 `DownloadTaskDao` 和 `DownloadedVideoIndexDao`，提供任务创建/查询/控制/删除、进度更新、弹幕状态更新、离线播放进度更新、存储空间查询 |
| `PermissionConfigRepository` | 从远程 JSON 文件获取 App 各项配置（搜索 `switches.myapp`、弹幕 `switches.enable_danmaku`、更新 `switches.enable_update` + `strings.force_update_url`/`update_details`/`update_sha256`），与本地 app_name/version 匹配后生效；缓存 1 天且带 `cached_for_version` 版本校验（升级后自动失效重拉）；网络获取失败默认全部放行；`checkSearchPermissionFast()` 搜索页调用，`checkDanmakuPermissionFast()` 播放器/弹幕下载调用，`checkUpdate()` 启动更新检查调用（`update_sha256` 随 `UpdateInfo.sha256` 透传给下载器做完整性校验） |

### 弹幕缓存

`DanmakuCache.kt` 使用 `SharedPreferences`（`PREF_NAME = "danmaku_cache"`）存储弹幕数据：

| 方法 | 用途 |
|------|------|
| `getSearchCache(keyword)` | 读取搜索缓存 |
| `putSearchCache(keyword, animes, expireAt)` | 写入搜索缓存 |
| `getBangumiCache(animeId)` | 读取分集缓存 |
| `putBangumiCache(animeId, bangumi, expireAt)` | 写入分集缓存 |
| `getCommentsCache(episodeId)` | 读取弹幕列表缓存 |
| `putCommentsCache(episodeId, comments, expireAt)` | 写入弹幕列表缓存 |
| `getUnifiedExpireAt(keyword, animeId)` | 统一过期时间策略 |
| `clearAll()` | 清除所有弹幕缓存 |

### 模型

| Model | 说明 |
|-------|------|
| `VideoItem` | UI 层通用影视卡片和详情数据，含 `sourceName` 字段 |
| `SearchPageResult` | 搜索分页结果，包含当前页、总页数、上下页状态和列表 |
| `DoubanMoviePageResult` | 豆瓣首页分栏分页结果，包含 `start`、`limit`、`total`、`items` 和 `hasMore` |
| `CrawlerVideoDetail` | 详情页解析结果，包含播放线路、剧集和**播放源名称** |
| `PlayLine` | 播放线路（如"高清播放"、"极速云"），包含多集 |
| `PlayEpisode` | 单集播放入口，包含标题和播放页 URL |
| `CrawlError` | 爬取错误信息，包含错误类型、消息和原始异常 |
| `DanmakuAnime` | 弹幕搜索返回的番剧信息 |
| `DanmakuBangumi` | 弹幕分集信息，包含 episodes 列表 |
| `DanmakuComment` | 单条弹幕，包含时间、内容、颜色、位置、类型 |

## 数据源

### `VideoSource`（接口）

定义所有播放源对外暴露的能力：

| 属性/方法 | 说明 |
|-----------|------|
| `sourceId` | 源的唯一标识 |
| `sourceName` | 源的显示名称 |
| `enabled` | 是否启用 |
| `searchVideos(keyword, page)` | 搜索视频（分页） |
| `fetchVideoDetail(detailUrl)` | 获取视频详情 |
| `fetchVideoUrl(detailUrl)` | 从详情页获取首个播放页 URL |
| `fetchVideoUrlByPlayPageUrl(playPageUrl)` | 从播放页解析真实播放地址 |

### `CrawlerVideoSource`（抽象基类）

实现 `VideoSource` 接口，封装所有通用逻辑：

- `client` (OkHttpClient)、`cacheRepository`、`rateLimiter`、`moshi` 及 adapters
- `fetchVideoUrl()` / `fetchVideoDetail()` / `fetchVideoUrlByPlayPageUrl()` / `searchVideos()`
- `requestDocument()` — OkHttp + Jsoup + 限流器调度；请求自动携带过盾 Cookie 缓存，检测到 Cloudflare 挑战页时自动过盾并重试一次；OkHttp 遭遇 TLS 指纹拦截（SSLException）时降级到 `WebViewHtmlFetcher` 用 WebView 抓取 HTML
- `getFirstPlayPageUrl()` — 缓存首个播放页
- `extractRealVideoUrl()` — 从 `player_aaaa` 脚本提取 m3u8
- `buildSearchUrl()` / `getSearchCacheTtlSeconds()` / `searchCacheKey()` / `cacheKey()` / `logLong()`

抽象方法和属性（子类必须实现）：

| 抽象成员 | 说明 |
|----------|------|
| `sourceId` | 源唯一标识 |
| `sourceName` | 源显示名称 |
| `baseUrl` | 源基础 URL |
| `cachePrefix` | 缓存键前缀 |
| `rateLimiterTag` | 限流器日志标识 |
| `logTag` | 日志 TAG |
| `parseVideoDetail(doc, detailUrl)` | 解析详情页 HTML |
| `parseSearchPage(doc, keyword, page)` | 解析搜索页 HTML |

### 爬虫播放源清单（`source/impl/`，共 19 个）

所有源均继承 `CrawlerVideoSource`，**已全部实现真实解析规则**（无 TODO 占位）。`sourceName` 与 `baseUrl` 不在代码里写死 —— 它们是可变属性，由 `VideoSourceConfigManager` 从远程 JSON 注入，因此站点换域名无需发版。

| 类 | `sourceId` | `cachePrefix` |
|------|------|------|
| `A38TvVideoSource` | `crawler_a38tv` | `a38tv` |
| `BaJieVideoSource` | `crawler_bajie` | `bajie` |
| `CechiVideoSource` | `crawler_cechi` | `cechi` |
| `ChongchongVideoSource` | `crawler_chongchong` | `chongchong` |
| `DaMaoVideoSource` | `crawler_damao` | `damao` |
| `DadatuVideoSource` | `crawler_dadatu` | `dadatu` |
| `DoujiaoVideoSource` | `crawler_dj` | `doujiao` |
| `HanSenVideoSource` | `crawler_hansen` | `hansen` |
| `HantvVideoSource` | `crawler_hantv` | `hantv` |
| `JujiwuVideoSource` | `crawler_jju` | `crawler` |
| `KaCheVideoSource` | `crawler_kache` | `kache` |
| `NiuerVideoSource` | `crawler_niuer` | `niuer` |
| `NongminTvVideoSource` | `crawler_nongmin_tv` | `nongmin_tv` |
| `NongmingVideoSource` | `crawler_nm` | `nongming` |
| `ShenMaVideoSource` | `crawler_shenma` | `shenma` |
| `TiantangVideoSource` | `crawler_tiantang` | `tiantang` |
| `XingChenVideoSource` | `crawler_xingchen` | `xingchen` |
| `YinghuaVideoSource` | `crawler_yinghua` | `yinghua` |
| `ZaiXianVideoSource` | `crawler_zaixian` | `zaixian` |

每个源在构造时持有独立的 `RequestRateLimiter`（如 `RequestRateLimiter("JJU", 3_000L, 3)`），并声明自己的 `rateLimiterTag` 与 `logTag`。解析规则的差异集中在 `parseVideoDetail()` / `parseSearchPage()` 两个方法中，分别适配各站点的页面结构（MyUI / 苹果CMS 等）。

> 新增一个源：新建子类 → 在 `VideoSourceConfigManager.knownSourceClasses` 登记 → 在远程 JSON 的 `video_sources` 追加条目。`proguard-rules.pro` 已 `-keep` 该包下的无参构造器。

### `DoubanDiscoverySource`

`DoubanDiscoverySource` 是内容发现层，负责首页数据。

数据来源：

```text
https://movie.douban.com/
https://movie.douban.com/explore/
https://movie.douban.com/tv/
https://m.douban.com/rexxar/api/v2/subject/recent_hot/movie
https://m.douban.com/rexxar/api/v2/subject/recent_hot/tv
```

主要方法：

| 方法 | 用途 |
|------|------|
| `fetchHomeAll()` | 获取首页"全部"内容，混排热门电视剧和热门电影 |
| `fetchExploreMoviePage()` | 获取电影分栏某个分类的分页内容 |
| `fetchExploreTvRelatedPage()` | 获取电视剧、动漫、综艺相关分页内容 |

首页"全部"的混排只在同一滑动页内部随机。电影、电视剧、综艺使用二级分类；动漫使用豆瓣 TV 页中的动画数据，不显示二级分类。

### `DanmakuApi`

弹幕数据源，封装弹幕搜索和获取的 HTTP 请求：

| 方法 | 用途 |
|------|------|
| `searchAnime(title)` | 根据标题搜索弹幕源 |
| `getBangumi(animeId)` | 获取番剧分集信息 |
| `getDanmakuComments(episodeId)` | 获取某集的弹幕列表 |

错误处理策略：服务端错误（HTTP 非 2xx、响应体为空、JSON 解析失败）抛出 `IOException`，交由上层 `DanmakuRepository` 处理 —— 注意**自动重试已移除**：`retryWithBackoff` 仍在，但 `MAX_RETRY = 1` 意味着只请求一次、失败立即返回，重试入口交给 UI 手动兜底（播放页重试/换源、下载页重试弹幕）；业务级空结果（`success=false` 或确实无数据）返回空列表/null，调用方按「无弹幕」处理。

### `RequestRateLimiter`

`RequestRateLimiter` 是每个播放源独立的限流调度器。

核心参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `sourceTag` | 各源不同 | 播放源标识，用于日志 |
| `minIntervalMs` | `3000` | 两次网络请求最小间隔（毫秒） |
| `maxQueueSize` | `3` | 队列最大持有未完成任务数 |

优先级等级：

| 优先级 | 数值 | 调用场景 |
|--------|------|----------|
| `SEARCH` | 3 | 首页爬取、搜索请求 |
| `DETAIL` | 2 | 详情页解析 |
| `PLAY` | 1 | 播放页解析 |

核心行为：

- `submit(priority, tag, block)`：在限流调度下执行 block，返回结果或抛出 `CancellationException`。
- 新任务入队时取消队列中所有优先级 ≤ 自身的旧任务（包括已开始执行的）。
- 未开始的任务直接从队列移除；已开始的任务通过 OkHttp `Call.cancel()` 终止网络层，但仍占用 3 秒间隔槽。
- `Handle.registerCall(call)`：执行体内注册 OkHttp Call，使取消操作能直接终止底层网络请求。

### `VideoSourceManager`

`VideoSourceManager` 读取本地 `assets/sample_video_source.json`，主要用于豆瓣或远程网络失败时的兜底展示，也提供本地搜索和按 ID 查询能力。

### `VideoSourceConfigManager`

`VideoSourceConfigManager` 负责从远程动态配置读取播放源名称和 URL，并据此**反射构建** `VideoSource` 实例：

| 项 | 说明 |
|------|------|
| 配置来源 | `CONFIG_URL_DEFAULT`（jsDelivr CDN），可通过 `setConfigUrl()` 覆盖并持久化 |
| 源类登记 | `knownSourceClasses` 硬编码 19 个 `impl` 子类的 `Class` 对象，逐个用反射 `newInstance()` 构建（**已取代早期废弃的 DexFile 扫描**） |
| 启动策略 | 有缓存 → 同步加载（毫秒级）并异步做每日更新（每天一次请求，内容变化才落盘）；无缓存 → 首次获取，失败重试 5 次、每次间隔 10 秒 |
| 状态 | `LiveData<ConfigState>`：`LOADING` / `READY` / `FAILED`，`FAILED` 时可由 `retryFetch()` 手动重试 |
| 远程开关 | JSON 每个源条目支持 `enabled` 字段（与 `name` 同级，缺省 `true`）：为 `false` 时该源不构建、不参与搜索/详情、不出现在源管理列表 —— 站点失效时可远程一键下线，无需发版 |
| 缓存 | SharedPreferences（`video_source_config`）：`cached_config_json` / `last_fetch_date` / `config_url` |
| 调试开关 | `USE_MOCK_CONFIG = true` 时改用内置 JSON，不联网（仅测试用） |

构建完成后通过 `MovieApplication.updateVideoSources()` 更新全局源列表并注入 `VideoRepository`。

## 缓存策略

`api_cache` 只用于网络爬取结果。本地挡板回退结果不写入首页缓存。

| 数据类型 | 缓存键前缀 | 缓存时长 | 说明 |
|----------|------------|----------|------|
| 首页全部豆瓣内容 | `home:tab:all:v1` | 1 天 | 豆瓣发现成功后写入 |
| 首页电影分页 | `home:tab:movie:v1:` | 首页 1 天，后续页跟随首页剩余 TTL | 同一分类分页同时过期 |
| 首页电视剧/动漫/综艺分页 | `home:tab:tv_related:v1:` | 首页 1 天，后续页跟随首页剩余 TTL | 三个默认分栏会一起预缓存 |
| 搜索结果页 | `crawler:search:v3` / `yinghua:search:v3` | 1 天 | 各源独立缓存；后续页跟随首页/第一页的剩余 TTL |
| 详情页首个播放页链接 | `crawler:detail:first_play_page` / `yinghua:detail:first_play_page` | 1 天 | 各源独立缓存 |
| 真实播放地址 | `crawler:play:real_url` / `yinghua:play:real_url` | 30 分钟 | 短时效真实播放地址只做短缓存 |
| 详情页元数据 | `crawler:detail:meta` / `yinghua:detail:meta` | 1 天 | 详情页 HTML 解析结果 |
| 负缓存：搜索结果为空 | `crawler:neg:empty` | 1 天 | 按错误类型写入，避免反复发无效请求 |
| 负缓存：HTTP 5xx / 连接超时 / CF 引擎不兼容 | `crawler:neg:*` | 1 小时 | 引擎不兼容项在升级系统 WebView 后自愈 |
| 负缓存：HTTP 4xx 客户端错误 | `crawler:neg:client` | 1 天 | — |
| 弹幕搜索 | `search_{keyword}` | 1 天 | SharedPreferences 存储 |
| 弹幕分集 | `bangumi_{animeId}` | 1 天 | SharedPreferences 存储 |
| 弹幕列表 | `comments_{episodeId}` | 1 天 | SharedPreferences 存储 |

`ApiCacheRepository.getRemainingTtlSeconds()` 用于让后续分页缓存跟随首页或第一页的剩余缓存时间。

多源缓存隔离：所有爬虫相关缓存键都包含源标识前缀（`crawler` / `yinghua`），确保不同源的缓存互不干扰。

## Presentation 层

```text
presentation/
├── activity/
│   ├── DetailActivity.kt
│   ├── DownloadActivity.kt
│   ├── HistoryActivity.kt
│   ├── MainActivity.kt
│   └── PlayerActivity.kt
├── adapter/
│   ├── CompletedAdapter.kt
│   ├── DownloadPagerAdapter.kt
│   ├── DownloadingAdapter.kt
│   ├── HistoryAdapter.kt
│   ├── SearchResultAdapter.kt
│   └── VideoAdapter.kt
├── challenge/
│   └── CloudflareChallengeActivity.kt
├── danmaku/
│   ├── DanmakuManager.kt
│   ├── DanmakuPrefs.kt
│   └── DanmakuView.kt
├── dialog/
│   ├── ConfirmDialog.kt
│   ├── DialogSizing.kt
│   └── EpisodeSelectDialog.kt
├── fragment/
│   ├── HistoryFragment.kt
│   ├── HomeFragment.kt
│   ├── ProfileFragment.kt
│   └── SearchFragment.kt
├── help/
│   └── HelpDialog.kt
├── settings/
│   └── ThemeManager.kt
├── source/
│   └── VideoSourceDialog.kt
├── tv/
│   ├── QrCodeGenerator.kt
│   ├── TvContentKeyHandler.kt
│   ├── TvFocus.kt
│   ├── TvInitialFocusProvider.kt
│   ├── TvSearchServer.kt
│   └── TvUiSupport.kt
├── update/
│   ├── AboutDialog.kt
│   ├── ApkDownloadManager.kt
│   ├── ApkDownloader.kt
│   ├── ApkInstaller.kt
│   ├── ApkVerifier.kt
│   └── UpdatePrefs.kt
└── viewmodel/
    ├── DownloadViewModel.kt
    ├── HistoryViewModel.kt
    ├── PlayerViewModel.kt
    ├── SearchHistoryViewModel.kt
    └── VideoViewModel.kt
```

### Activity

| Activity | 说明 |
|----------|------|
| `MainActivity` | 主页面容器，使用底部导航切换首页、搜索和我的；通过 `add + hide/show` 保留 Fragment 实例；处理返回键双击退出和搜索页状态管理 |
| `DetailActivity` | 视频详情页，展示完整视频信息、播放线路、剧集和续播提示；**提供下载入口**，支持单集下载和批量下载 |
| `PlayerActivity` | 播放器页面，使用 Media3 ExoPlayer 播放视频，支持弹幕系统、手势控制（位移超 touchSlop 即入，按轨迹角度锁定方向；水平拖拽仅预览进度、抬手才 seek 并同步弹幕，垂直拖拽调节亮度/音量）、屏幕锁定（含只读进度条）、播放生命周期和进度保存；**离线播放时保存进度到下载任务而非历史记录**；离线弹幕统一由播放器加载流水线处理（任务弹幕文件 → 弹幕源索引文件回退 → 在线搜索），不再触发后台弹幕重试，本地弹幕已加载时在线搜索失败静默处理；**弹幕开关打开时先经 `tryRestoreLocalDanmaku()` 尝试本地弹幕（本地文件 → 弹幕缓存 → 联网级联三级优先，本地命中零联网并显示「已加载本地弹幕」），仅当无已保存弹幕源时才联网搜索候选源** |
| `HistoryActivity` | 历史记录页面容器，承载 `HistoryFragment`，从"我的"页面跳转进入 |
| `DownloadActivity` | **下载管理页面**，使用 ViewPager2 分"下载中"和"已完成"两个标签页；首次进入时若下载中列表为空自动切换到已完成；支持多选删除 |
| `CloudflareChallengeActivity`（`challenge/`） | **Cloudflare 人工验证兜底窗口**：自动过盾失败时弹出，卡片内嵌 WebView + 轮询 `cf_clearance`；主题**必须不透明**（translucent 在部分华为设备上 WebView 白屏） |

> **形态与方向**：`MainActivity` / `DetailActivity` / `HistoryActivity` / `DownloadActivity` / `CloudflareChallengeActivity` 均在 `onCreate` 里按 `isTv` 设置 `requestedOrientation`（手机竖屏、电视横屏）**之后**才 inflate 布局；`PlayerActivity` 则在清单写死 `landscape`（手机与电视都保持横屏）。清单刻意不写 `screenOrientation`、不声明 `configChanges=orientation|screenSize`。

### Fragment

| Fragment | 说明 |
|----------|------|
| `HomeFragment` | 首页内容发现，九宫格展示，支持主分类、二级分类和列表末尾加载更多 |
| `SearchFragment` | 搜索页，支持外部传入关键词自动搜索、手动搜索、分页、历史 Chip 和清空历史；搜索后自动收起输入法键盘 |
| `ProfileFragment` | "我的"页面，包含视频源管理（弹框开关）、弹幕开关、历史记录入口、**下载管理入口**、**清理缓存（分类清理 + 缓存大小显示）**、帮助和关于 |
| `HistoryFragment` | 播放历史页，展示 Room 中的播放记录（含播放源名称），支持点击进入详情和一键清空 |

### ViewModel

| ViewModel | 说明 |
|-----------|------|
| `VideoViewModel` | 加载首页发现内容、豆瓣分页、搜索结果和详情相关数据 |
| `HistoryViewModel` | 读取播放历史、写入或更新历史、清空历史 |
| `PlayerViewModel` | 播放时写入历史（含 sourceName）、更新播放进度、查询续播记录、按 ID 回查视频 |
| `SearchHistoryViewModel` | 读取、写入、删除和清空搜索历史 |
| `DownloadViewModel` | **下载任务管理**，创建任务、查询下载中/已完成列表、任务控制（暂停/恢复/取消/重试/全部暂停/全部恢复）、删除任务、存储空间查询 |

### Adapter

| Adapter | 说明 |
|---------|------|
| `VideoAdapter` | 首页九宫格卡片和列表末尾 `加载更多` Footer |
| `SearchResultAdapter` | 搜索结果列表，展示封面、标题、类型、上映时间、主演、简介和**播放源名称** |
| `HistoryAdapter` | 播放历史列表，展示封面、标题、分类、播放进度、播放记录和**播放源名称** |
| `DownloadPagerAdapter` | **下载管理 ViewPager2 适配器**，管理"下载中"和"已完成"两个 Fragment |
| `DownloadingAdapter` | **下载中任务列表适配器**，展示封面、标题、集数、进度条、百分比、状态，支持暂停/恢复/取消操作 |
| `CompletedAdapter` | **已完成任务列表适配器**，展示封面、标题、集数、文件大小、**播放进度文字**（未观看/已观看N%/已看完），支持播放和删除 |

### 弹幕组件

| 组件 | 说明 |
|------|------|
| `DanmakuManager` | 弹幕渲染管理器，负责弹幕的显示、隐藏、同步、seek、暂停/恢复 |
| `DanmakuView` | 弹幕绘制 View，基于 Canvas 实现弹幕滚动渲染；扫描游标每帧按时间窗口二分重定位（时间跳变异常可自愈），墙钟前跳（NTP 校时等）时跳过当帧弹幕添加等待校准 |
| `DanmakuPrefs` | 弹幕偏好设置，管理弹幕总开关的持久化；**按 videoId 保存/读取用户选择的弹幕源 animeId（`saveAnimeId` / `getSavedAnimeId`，key=`danmaku_anime_{videoId}`）**，弹幕下载完成时也会回写该关联，供播放器开关打开时直接定位本地弹幕文件 |

### 应用内更新组件（presentation/update/）

| 组件 | 说明 |
|------|------|
| `AboutDialog` | 关于页（居中卡片 DialogFragment）：App 信息展示（BuildConfig 版本徽章）、检查更新（复用 `PermissionConfigRepository.checkUpdate()`，缓存命中时不联网）、更新详情卡片（`update_details` + 新版本号）、订阅 `ApkDownloadManager.state` 渲染下载进度/按钮文案（立即更新/下载中.../安装更新/重新下载）、Android 8.0+ 安装授权引导；APK 文件被系统清理时提示重下并重置状态；内容区超屏高 65% 可滚动 |
| `ApkDownloadManager` | 应用级 APK 下载管理器（单例）：自有 `CoroutineScope(SupervisorJob + Main)` + `StateFlow<DownloadState>`（Idle/Downloading/Completed/Failed），**下载不绑定弹窗生命周期**，弹窗重开自动恢复状态展示；`lastUpdateInfo` 全局持有最近检查到的更新信息（弹窗重开时据此恢复卡片展示）；`start()` 先做 **sha256 锚定复用检查**（本地完整 APK 锚点与远程一致 → 零流量直接进入校验，适配 URL 不变只换内容的发布流程），否则走断点续传下载；下载完成后调用 `ApkVerifier.verify()` 做安装前校验，失败删除文件和锚点并置 Failed；`resetToIdle()` 供安装包失效时重置 |
| `ApkDownloader` | 更新包下载器：OkHttp 流式下载到 `cacheDir/update/update.apk`，实时进度回调；**断点续传**（Range 请求头，206 续写 / 200 整体重下 / 416 作废断点），sidecar 文件 `update.url` 记录下载地址，URL 变化自动作废旧断点；**完整包复用**——下载完成时将远程 sha256 写入锚点文件 `update.sha256`，`reuseCompletedApk()` 据此判断进程重启后本地完整 APK 是否可零流量复用（远程未配置 sha256 时保守不复用） |
| `ApkVerifier` | APK 安装前双重校验器：**核心**——下载 APK 签名者证书 SHA-256 与硬编码常量 `EXPECTED_SIGNING_CERT_SHA256` 比对（常量为空时跳过并打日志输出当前证书值）；**辅助**——远程 `update_sha256` 非空时校验文件全量 SHA-256（流式计算）；返回 null 表示通过，否则返回用户可读的失败原因 |
| `ApkInstaller` | 安装工具：Android 8.0+ 「安装未知应用」授权检查与引导跳转，FileProvider 共享 APK 发起系统安装 |
| `UpdatePrefs` | 更新提示弹窗频率控制：「今天不再提醒」按日期记录，「知道了」（下次再说）不持久化 |

### 其他 Presentation 组件

| 组件 | 说明 |
|------|------|
| `ThemeManager`（settings/） | 主题模式管理：持久化到 SharedPreferences（`app_settings`/`theme_mode`），`applySaved()` 在 Application 创建时应用，ProfileFragment 头部按钮切换（`AppCompatDelegate.setDefaultNightMode()`） |
| `VideoSourceDialog`（source/） | 视频源管理（居中卡片 Dialog）：RecyclerView 列表勾选源、全选/全不选切换 + 已选计数、确定时校验至少一个源并持久化到 SharedPreferences |

## TV 适配（`presentation/tv/`）

`presentation/tv/` 承载电视形态的全部适配代码，**只作用于 UI 层与配置**，不改动网络 / Repository / ViewModel / 数据模型 / 播放器封装。

| 文件 | 职责 |
|------|------|
| `TvUiSupport.kt` | 形态判定 `isTelevision()`（`UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION`）；`wrapContext()` 在电视端把 `densityDpi` **放大 1.45 倍**实现 10-foot UI，并**同步重算** `screenWidthDp/screenHeightDp/smallestScreenWidthDp` |
| `TvFocus.kt` | **全 App 焦点适配的唯一入口**：`applyTo` / `applyFocusableOnly` / `setFocusable` / `attachFocusRing` / `resetAppearance` / `requestInitialFocus` / `focusFirstItem` / `scrollIntoViewOnFocus` / `applyToClickables` / `applyToDialogButtons` / `collectClickableViews` / `neutralizeCardFocusStroke`；每个公开方法首行 `if (!isActive(view)) return` 做**形态门控**（手机端一律 no-op），处理过的控件打 `tag_tv_focus_handled` 标记 |
| `TvInitialFocusProvider.kt` | 页面侧接口：向顶部导航暴露「下键时接收焦点的首个控件」，由 `MainActivity` 显式移交。**必须实时返回，禁止缓存**（`ViewPager2` 复用 Fragment） |
| `TvContentKeyHandler.kt` | 页面侧接口：方向键兜底接管 —— `RecyclerView.focusSearch()` 只在自身子树内找候选，「网格最后一行按下键」翻不出容器 |
| `TvSearchServer.kt` | 纯 `ServerSocket` 的轻量 HTTP 服务（固定端口 **8234**）：`GET /search.html`、`POST /api/search`、`GET /api/last_query`；生命周期**跟随二维码可见性**，离开搜索页或隐藏二维码立即 `stop()` 释放端口 |
| `QrCodeGenerator.kt` | ZXing 生成二维码 Bitmap，供电视搜索页展示局域网地址 `http://<ip>:8234/search.html` |

配套资源：

| 资源 | 用途 |
|------|------|
| `layout-land/activity_main.xml` 的 `tvNavBar` + `layout-land/item_tv_nav.xml` | 电视端顶部导航页签（图标 20dp + 文字 13sp + 底部橙色指示条），替代底部 `BottomNavigationView` |
| `drawable/bg_tv_focus_ring.xml` / `shape_tv_focus_ring.xml` | 普通底色的橙色焦点环（单圈 3dp、圆角 14dp） |
| `drawable/bg_tv_focus_ring_light.xml` / `shape_tv_focus_ring_light.xml` | 品牌橙底控件专用的纯白焦点环（橙压橙对比度约 1.1:1，等于没有焦点框） |
| `drawable/bg_tv_nav_focus.xml` / `bg_tv_nav_item.xml` / `bg_tv_nav_indicator.xml` | 顶部导航页签的获焦药丸 / 常态 / 选中指示条 |
| `color/card_background_tv_focusable.xml` | 结果卡片获焦时的底色状态列表（提亮为暖色 `surface_background_focused`） |
| `values/ids.xml` | `tag_tv_focus_handled` 标记 id（`TvFocus` 门控判据用，非布局资源） |
| `drawable/tv_banner.xml` | 电视启动器横幅（清单 `android:banner`） |

⚠️ **`res/layout-land/` 不是 TV 专属目录** —— 播放页对所有设备强制横屏，手机播放页与手机横屏落的都是它。因此该目录下**不得写死** `focusable` / `focusableInTouchMode` / `foreground=bg_tv_focus_ring`（电视端由代码赋予，写死在 XML 里对电视是冗余、对手机横屏是污染），改动后必须两个形态一起验算。

> 完整设计（形态判定与方向策略、焦点体系三条铁律、手机扫码搜索链路、电视缺失系统能力清单、弹幕字号分档、电视端页面形态清单、弹窗尺寸与横屏分栏）见 [`README.md` 的「Android TV 适配」](./README.md) 与 [`UI 视觉统一规范文档.md`](./UI%20视觉统一规范文档.md)。

## 资源结构

```text
res/
├── mipmap-anydpi-v26/
│   ├── ic_launcher.xml
│   └── ic_launcher_round.xml
├── mipmap-hdpi/
│   ├── ic_launcher.webp
│   ├── ic_launcher_foreground.webp
│   └── ic_launcher_round.webp
├── mipmap-mdpi/
│   ├── ic_launcher.webp
│   ├── ic_launcher_foreground.webp
│   └── ic_launcher_round.webp
├── mipmap-xhdpi/
│   ├── ic_launcher.webp
│   ├── ic_launcher_foreground.webp
│   └── ic_launcher_round.webp
├── mipmap-xxhdpi/
│   ├── ic_launcher.webp
│   ├── ic_launcher_foreground.webp
│   └── ic_launcher_round.webp
├── mipmap-xxxhdpi/
│   ├── ic_launcher.webp
│   ├── ic_launcher_foreground.webp
│   └── ic_launcher_round.webp
├── drawable/
│   ├── anim_loading_rotate.xml
│   ├── bg_badge.xml
│   ├── bg_card_rounded.xml
│   ├── bg_check_selected.xml
│   ├── bg_check_unselected.xml
│   ├── bg_chip.xml
│   ├── bg_chip_selected.xml
│   ├── bg_circle_primary.xml
│   ├── bg_detail_card.xml
│   ├── bg_detail_page.xml
│   ├── bg_dialog_rounded.xml
│   ├── bg_download_progress.xml
│   ├── bg_episode_normal.xml
│   ├── bg_episode_selected.xml
│   ├── bg_icon_chip.xml
│   ├── bg_icon_chip_ripple.xml
│   ├── bg_play_button.xml
│   ├── bg_player_gesture_tip.xml
│   ├── bg_player_top_gradient.xml
│   ├── bg_poster_round.xml
│   ├── bg_row_card.xml
│   ├── bg_row_card_ripple.xml
│   ├── bg_tv_focus_ring.xml
│   ├── bg_tv_focus_ring_light.xml
│   ├── bg_tv_nav_focus.xml
│   ├── bg_tv_nav_indicator.xml
│   ├── bg_tv_nav_item.xml
│   ├── ic_about.xml
│   ├── ic_arrow_right.xml
│   ├── ic_battery_1.xml
│   ├── ic_battery_2.xml
│   ├── ic_battery_3.xml
│   ├── ic_battery_4.xml
│   ├── ic_battery_5.xml
│   ├── ic_battery_bolt.xml
│   ├── ic_check_circle.xml
│   ├── ic_check_circle_outline.xml
│   ├── ic_clear_cache.xml
│   ├── ic_danmu.xml
│   ├── ic_download.xml
│   ├── ic_help.xml
│   ├── ic_history.xml
│   ├── ic_home.xml
│   ├── ic_launcher_background.xml
│   ├── ic_loading_film.xml
│   ├── ic_mobile_network.xml
│   ├── ic_player_back.xml
│   ├── ic_player_clear_all.xml
│   ├── ic_player_danmaku.xml
│   ├── ic_player_detail.xml
│   ├── ic_player_forward_10.xml
│   ├── ic_player_home.xml
│   ├── ic_player_lock.xml
│   ├── ic_player_pause.xml
│   ├── ic_player_pip.xml
│   ├── ic_player_play.xml
│   ├── ic_player_rewind_10.xml
│   ├── ic_player_rotate.xml
│   ├── ic_player_search.xml
│   ├── ic_player_settings.xml
│   ├── ic_player_unlock.xml
│   ├── ic_profile.xml
│   ├── ic_search.xml
│   ├── ic_source.xml
│   ├── ic_theme_moon.xml
│   ├── ic_theme_sun.xml
│   ├── ic_wifi.xml
│   ├── movie_background.png
│   ├── movie_background_light.png
│   ├── ripple_clickable.xml
│   ├── shape_tv_focus_ring.xml
│   ├── shape_tv_focus_ring_light.xml
│   └── tv_banner.xml
├── layout/
│   ├── activity_detail.xml
│   ├── activity_download.xml
│   ├── activity_history.xml
│   ├── activity_main.xml
│   ├── activity_player.xml
│   ├── dialog_about.xml
│   ├── dialog_clear_cache.xml
│   ├── dialog_confirm.xml
│   ├── dialog_episode_select.xml
│   ├── dialog_help.xml
│   ├── dialog_update_tip.xml
│   ├── dialog_video_source.xml
│   ├── exo_player_control_view.xml
│   ├── fragment_history.xml
│   ├── fragment_home.xml
│   ├── fragment_profile.xml
│   ├── fragment_search.xml
│   ├── item_clear_cache.xml
│   ├── item_completed.xml
│   ├── item_download_page.xml
│   ├── item_downloading.xml
│   ├── item_episode_select.xml
│   ├── item_history.xml
│   ├── item_home_load_more.xml
│   ├── item_search_result.xml
│   ├── item_video.xml
│   ├── item_video_source.xml
│   └── layout_loading_overlay.xml
├── layout-land/          ← 横屏（电视 + 手机横屏共用），25 个文件
│   ├── activity_detail.xml
│   ├── activity_main.xml           ← 含电视端 tvNavBar
│   ├── activity_player.xml
│   ├── dialog_about.xml / dialog_clear_cache.xml / dialog_confirm.xml
│   ├── dialog_episode_select.xml / dialog_help.xml / dialog_update_tip.xml
│   ├── dialog_video_source.xml     ← 上述 dialog_* 为左右分栏版
│   ├── exo_player_control_view.xml
│   ├── fragment_history.xml / fragment_home.xml
│   ├── fragment_profile.xml / fragment_search.xml
│   ├── item_clear_cache.xml / item_completed.xml / item_downloading.xml
│   ├── item_episode_select.xml / item_history.xml / item_home_load_more.xml
│   ├── item_search_result.xml / item_video.xml / item_video_source.xml
│   └── item_tv_nav.xml             ← 电视端顶部导航页签（仅此目录有）
├── values/
│   ├── colors.xml
│   ├── dimens.xml
│   ├── ids.xml           ← tag_tv_focus_handled（TvFocus 门控标记）
│   ├── strings.xml
│   ├── styles.xml
│   └── themes.xml
├── values-night/
│   ├── colors.xml
│   └── themes.xml
├── color/
│   ├── bottom_nav_color.xml
│   └── card_background_tv_focusable.xml
├── menu/
│   ├── bottom_nav_menu.xml
│   ├── menu_download_batch_delete.xml
│   └── menu_download_toolbar.xml
├── xml/
│   ├── backup_rules.xml
│   ├── data_extraction_rules.xml
│   └── file_paths.xml
```

### 布局对应关系

| 布局文件 | 对应组件 |
|----------|----------|
| `activity_main.xml` | `MainActivity` |
| `activity_detail.xml` | `DetailActivity` |
| `activity_player.xml` | `PlayerActivity`（含锁定进度条 `lockedProgressBar`） |
| `activity_history.xml` | `HistoryActivity` |
| `activity_download.xml` | `DownloadActivity`（ViewPager2 + TabLayout） |
| `exo_player_control_view.xml` | ExoPlayer 自定义控制栏（播放/暂停/快进/快退/进度条/弹幕控制/设置） |
| `dialog_clear_cache.xml` | 清理缓存弹框 |
| `item_clear_cache.xml` | 清理缓存弹框中的单个选项项 |
| `fragment_home.xml` | `HomeFragment` |
| `fragment_search.xml` | `SearchFragment` |
| `fragment_profile.xml` | `ProfileFragment` |
| `fragment_history.xml` | `HistoryFragment` |
| `item_video.xml` | `VideoAdapter` 的影视卡片 |
| `item_home_load_more.xml` | `VideoAdapter` 的加载更多 Footer |
| `item_search_result.xml` | `SearchResultAdapter` |
| `item_history.xml` | `HistoryAdapter` |
| `item_downloading.xml` | `DownloadingAdapter`（下载中任务卡片） |
| `item_completed.xml` | `CompletedAdapter`（已完成任务卡片，含播放进度） |
| `item_download_page.xml` | `DownloadPagerAdapter` 的 ViewPager2 页面容器 |
| `layout_loading_overlay.xml` | 全屏加载覆盖层 |
| `item_tv_nav.xml`（仅 `layout-land/`） | 电视端顶部导航页签 |

> 上表列的是 `res/layout/`（手机竖屏）。`res/layout-land/`（横屏，电视 + 手机横屏共用）提供 **25 个同名覆盖布局**，与竖屏版本同名同 id 集合；其中 `item_tv_nav.xml` 为电视专属。**未提供横屏版本**的是 `layout_loading_overlay.xml` 与 `activity_history.xml` / `activity_download.xml` / `item_download_page.xml`（下载管理与历史页在电视上仍是竖屏形态）。
>
> ⚠️ 同一页面的两份布局 **id 集合必须完全一致** —— ViewBinding 取限定符并集，缺一个字段就退化成 `@Nullable`，需要加空安全调用。

## 页面导航

底部导航菜单定义在 `res/menu/bottom_nav_menu.xml`：

```text
nav_home    → HomeFragment
nav_search  → SearchFragment
nav_profile → ProfileFragment
```

`MainActivity` 保留三个 Fragment 实例，切换时隐藏其他页面并显示目标页面。这样首页滚动位置、已加载分页、主分类和二级分类不会因为进入搜索或"我的"而重置。

首页内容发现到搜索的关联：

```text
HomeFragment 点击影视卡片
        ↓
MainActivity.navigateToSearchWithKeyword(title)
        ↓
SearchFragment.searchFromExternal(title)
        ↓
VideoRepository.searchVideosPage(title) ── 多源并行搜索
```

"我的"页面功能入口：

```text
ProfileFragment
    ├── 视频源管理 ── 弹框开关各播放源
    ├── 弹幕 ── 滑动开关（默认开启）
    ├── 历史记录 ── 跳转 HistoryActivity
    ├── 下载管理 ── 跳转 DownloadActivity
    ├── 清理缓存 ── 弹框选择性清理（显示缓存大小）
    ├── 帮助 ── 弹框展示使用说明
    └── 关于 ── 弹框展示版本信息
```

手动点击底部搜索按钮时，搜索页会调用 `resetToInitialState()`，清空搜索框和旧搜索结果，只保留搜索历史。

返回键行为：

- 从首页影视进入搜索页：返回直接回首页。
- 手动进入搜索页且有搜索结果：第一次返回清空结果恢复初始搜索页，第二次返回回首页。
- 首页：双击返回退出应用。

详情和播放页面通过显式 Intent 打开：

```text
SearchFragment / HistoryFragment
        ↓
DetailActivity
        ↓
PlayerActivity
```

下载管理页面通过"我的"跳转：

```text
ProfileFragment
        ↓
DownloadActivity
    ├── 下载中标签页 ── DownloadingAdapter
    └── 已完成标签页 ── CompletedAdapter
            ↓ 点击播放
        PlayerActivity（离线播放模式）
```

## 核心数据流

### 首页内容发现

```text
HomeFragment
        ↓
VideoViewModel.loadAllVideos() / loadHomeDoubanCategory()
        ↓
VideoRepository
        ↓
DoubanDiscoverySource
        ↓
api_cache 写入网络结果
        ↓
VideoAdapter 九宫格展示
```

豆瓣失败时：

```text
DoubanDiscoverySource 失败或返回空
        ↓
VideoSourceManager 读取本地挡板
        ↓
不写入首页 api_cache
```

### 搜索与搜索历史

```text
SearchFragment.performSearch(keyword)
        ↓
SearchHistoryViewModel.addKeyword(keyword)
        ↓
VideoViewModel.searchVideosPage(keyword, page)
        ↓
VideoRepository.searchVideosPage()
        ↓
并行请求所有 enabled 源（coroutineScope + async）
        ↓
JujiwuVideoSource.searchVideos() ── RequestRateLimiter.submit(SEARCH)
YinghuaVideoSource.searchVideos() ── RequestRateLimiter.submit(SEARCH)
TiantangVideoSource.searchVideos() ── RequestRateLimiter.submit(SEARCH)
        ↓
插空法合并结果
        ↓
SearchResultAdapter 渲染结果（显示 sourceName）
```

### 详情和播放

```text
SearchFragment 点击搜索结果
        ↓ detailUrl + sourceName
DetailActivity
        ↓
VideoRepository.getCrawlerVideoDetail()
        ↓
对应源.fetchVideoDetail() ── RequestRateLimiter.submit(DETAIL)
        ↓
用户选择播放
        ↓ playPageUrl
对应源.fetchVideoUrlByPlayPageUrl() ── RequestRateLimiter.submit(PLAY)
        ↓
PlayerActivity（传入 sourceName 保存到历史）
```

### 弹幕数据流

```text
PlayerActivity
        ↓
DanmakuRepository.searchCandidates(title)
        ↓
DanmakuApi.searchAnime() ── 带缓存和重试
        ↓
DanmakuRepository.fetchBangumi(animeId)
        ↓
DanmakuApi.getBangumi() ── 带缓存和重试
        ↓
DanmakuRepository.fetchDanmakuComments(bangumi, episode)
        ↓
DanmakuApi.getDanmakuComments() ── 带缓存和重试
        ↓
DanmakuManager.loadDanmaku(comments)
        ↓
DanmakuView 弹幕渲染
```

#### 弹幕加载优先链（开关打开 / 换弹幕源）

弹幕获取按「本地文件 → 弹幕缓存 → 联网级联」三级优先，`searchCandidates` 联网搜索仅在没有任何可复用数据时触发：

```text
弹幕开关打开 / onItemSelected 选择新源
        ↓
PlayerActivity.tryRestoreLocalDanmaku()        （换源路径直接走 loadDanmakuForAnime）
        ↓
DanmakuPrefs.getSavedAnimeId(videoId)          （弹幕下载完成时由 DanmakuDownloadManager 回写）
        ├─ savedAnimeId == 0 → launchDanmakuSearch（联网搜候选源）
        └─ savedAnimeId > 0
            ├─ ① 本地文件 Danmaku/{animeId}_{集数}.json 存在
            │    └─ loadDanmakuFromLocalFile 直接上屏 · 零联网 · 显示「已加载本地弹幕」
            └─ ② 本地不存在 → loadDanmakuForAnime
                 ├─ fetchBangumi 缓存命中 + comment 缓存有内容 → 零联网
                 ├─ 缓存缺失 → 联网级联获取（fetchBangumi → fetchDanmakuComments）
                 └─ animeId 失效（bangumi 空）→ 清搜索缓存 + saveAnimeId(0) + 强制重搜
```

弹幕下载完成时的关联回写：

```text
DanmakuDownloadManager.executeDanmakuDownload()
        ↓ 保存 Danmaku/{taskId}.json + Danmaku/{animeId}_{集数}.json
DanmakuPrefs.saveAnimeId(videoIdFromTask, anime.animeId)
        ↓
播放器开关打开 → getSavedAnimeId 命中 → 本地文件直接加载，零联网
```

### 电视扫码搜索

```text
SearchFragment.prepareQrCode()
        ↓ TvSearchServer.getLocalIpAddress()（纯 ServerSocket 取局域网 IP）
        ↓ QrCodeGenerator.generate(url, 512)（ZXing）
layout-land/fragment_search.xml 右栏显示二维码 http://<ip>:8234/search.html
        ↓ 局域网 IP 取不到 → 隐藏二维码，服务器本次会话内不再启动
手机浏览器扫码打开 GET /search.html
        ↓ POST /api/search
TvSearchServer 回调
        ↓
SearchFragment 执行搜索（等价于本地输入）
```

服务器启停由 `updateSearchServerState()` 统一驱动：**二维码可见 且 搜索页 resumed** 才启动，其余情况立即停止释放端口。

### 应用形态分流（启动期）

```text
Activity.attachBaseContext(base)
        ↓ TvUiSupport.wrapContext()（电视：densityDpi × 1.45 + 重算 dp 尺寸）
Activity.onCreate()
        ↓ isTv = TvUiSupport.isTelevision(this)
        ↓ applyOrientation()（手机竖屏 / 电视横屏）
        ↓ inflate 布局 → 系统按当前方向匹配 res/layout/ 或 res/layout-land/
        ↓ 电视端：TvFocus.applyTo(...) 赋焦 + 焦点环；MainActivity 装配 tvNavBar
```

### 播放历史与续播

```text
PlayerActivity（在线播放）
        ↓
PlayerViewModel.setVideoInfo(..., sourceName)
        ↓
PlayHistoryRepository.addOrUpdateHistory(..., sourceName)
        ↓
Room play_history
        ↓
HistoryActivity / DetailActivity / PlayerActivity
```

### 下载管理

```text
DetailActivity 选择剧集下载
        ↓
DownloadViewModel.createTasks() ── 写入 download_task 表
        ↓
DetailActivity.startDownloadForEpisodes()
        ↓ 解析 playPageUrl → m3u8Url（剧集间延迟 3~5s）
DownloadEngine.submitTask(taskId = dbTaskId)
        ↓ 解析 M3U8、并发下载分片（最多 3 线程、2MB/s 限速、分片间延迟 2s）
DownloadService（前台通知）
        ↓ 合并分片为 mp4
DanmakuDownloadManager（弹幕下载）
        ↓
数据库更新状态和进度（DownloadCallback → DownloadRepository）
        ↓
DownloadActivity（下载中/已完成标签页，Flow 实时更新）
```

### 离线播放

```text
DownloadActivity 点击已完成任务播放
        ↓
PlayerActivity.newIntent(localFilePath, danmakuFilePath, ...)
        ↓ extra_offline_task_id = task.taskId
PlayerActivity 离线模式
        ↓ 读取 playPositionMs 续播（已看完则从头开始）
initializePlayerWithLocalFile(localUri)
        ↓
弹幕：本地文件优先，不存在则在线搜索
        ↓
saveCurrentProgress() → DownloadRepository.updateOfflinePlayProgress()
        ↓ 不写入 play_history
```

### 应用内更新

```text
MainActivity 启动 / AboutDialog 检查更新
        ↓
PermissionConfigRepository.checkUpdate()   （读本地缓存，无缓存时后台拉取）
        ├─ 无更新 → null（不提示）
        └─ 有更新 → UpdateInfo(latestVersion, downloadUrl, details, sha256)
                ↓ AboutDialog「立即更新」
ApkDownloadManager.start(context, url, sha256)   （应用级单例，不绑定弹窗生命周期）
        ↓
ApkDownloader.download()   （OkHttp 流式下载 + Range 断点续传，存 cacheDir/update/）
        ↓ 下载完成
ApkVerifier.verify()   （签名证书 SHA-256 比对 + 远程 update_sha256 文件校验）
        ├─ 失败 → 删除 APK → Failed（按钮变「重新下载」，Toast 提示原因）
        └─ 通过 → Completed
                ↓ 下载中→完成瞬态 或 手动点「安装更新」
ApkInstaller.install()   （8.0+ 授权检查 + FileProvider → 系统安装器）
```

### 清理缓存

```text
ProfileFragment.showClearCacheDialog()
        ↓
用户选择清理项
        ↓
ApiCacheRepository.deleteByPrefix(prefix) ── Room 缓存
DanmakuCache.clearAll() ── SharedPreferences 缓存
SearchHistoryRepository.clearAllHistory() ── 搜索历史
        ↓
Toast 提示清理结果
```

## 构建配置

| 配置项 | 当前值 |
|--------|--------|
| namespace | `com.hpu.mymoviestore` |
| applicationId | `com.hpu.mymoviestore` |
| compileSdk | `36` |
| minSdk | `24` |
| targetSdk | `36` |
| versionCode | `1` |
| versionName | `1.3.0` |
| Java 版本 | `17` |
| Kotlin JVM Target | `17` |
| ViewBinding | 已启用 |

### 主要依赖

| 依赖 | 用途 |
|------|------|
| AndroidX Core / AppCompat / Activity | Android 基础能力 |
| Material Components | Material UI 组件 |
| ConstraintLayout | 布局 |
| RecyclerView / CardView / ViewPager2 | 列表、卡片和页面切换 |
| Lifecycle ViewModel / LiveData / Runtime | MVVM 和生命周期感知 |
| Room Runtime / KTX / Compiler | 本地数据库 |
| Kotlin Coroutines | 异步任务 |
| Coil | 图片加载 |
| Media3 ExoPlayer | 视频播放 |
| Moshi | JSON 解析 |
| OkHttp | 网络数据源 |
| Jsoup | HTML 获取与解析 |

## 清单文件

`AndroidManifest.xml` 主要内容：

- 应用入口为 `MovieApplication`。
- 启动页为 `MainActivity`。
- 注册 `DetailActivity`、`PlayerActivity`、`HistoryActivity`、`DownloadActivity`。
- 注册 `DownloadService`（前台服务，`dataSync` 类型）。
- `PlayerActivity` 使用无 ActionBar 主题，并处理方向和屏幕尺寸变化。
- 声明 `INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`、`WAKE_LOCK`、`REQUEST_INSTALL_PACKAGES` 权限。

## 当前实现边界

- 首页内容发现只适配豆瓣相关页面和接口；豆瓣失败时回退本地挡板。
- 内容播放当前支持 **19 个**爬虫播放源，通过 `VideoSource` 接口可扩展更多源；源名称/地址与可用性由 `VideoSourceConfigManager` 从远程 JSON 动态加载。
- 本地挡板不写入首页 `api_cache`。
- 搜索结果、详情播放入口和真实播放地址有独立缓存周期，各源缓存前缀不同。
- 爬虫限流器每个播放源独立，队列容量和间隔为固定值。
- 反爬应对：Cloudflare 挑战页自动过盾（引擎不兼容的设备限时跳过该类源）；TLS 指纹拦截的站点自动降级 WebView 抓取。详见「反爬应对组件」一节。
- 下载管理功能已完整实现，支持 M3U8 分片下载、弹幕下载、前台通知、离线播放和降低影响策略。
- 下载引擎已实现多层限流：并发限制、分片间延迟、速度限制、剧集间解析间隔。
- 离线播放有独立进度体系，不记录到在线播放历史。
- **双形态**：同一 APK 兼容手机与 Android TV / 盒子。电视侧适配严格限定在 UI 层与配置（形态判定、密度放大、焦点体系、顶部导航、扫码搜索、缺失能力守卫），网络 / Repository / ViewModel / 数据模型 / 播放器封装两侧共用。
- 收藏功能未实现。