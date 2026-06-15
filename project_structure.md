# 项目结构说明

本文档描述 `MyMovieStore` 当前代码结构、分层职责和主要数据流。项目是单模块 Android 应用，主包名为 `com.hpu.mymoviestore`。

## 架构概览

项目采用 MVVM + Repository + Data Source 的轻量分层结构。当前实现把数据能力拆成内容发现和内容播放两条链路。

```text
Presentation
Activity / Fragment / Adapter
        ↓
ViewModel
        ↓
Repository
        ↓
Data Source / Room DAO
        ↓
DoubanDiscoverySource / CrawlerVideoSource / VideoSourceManager / SQLite
```

内容发现层由 `DoubanDiscoverySource` 负责，主要服务首页。内容播放层由 `CrawlerVideoSource` 负责，主要服务搜索、详情和播放。`CrawlerVideoSource` 内部通过 `RequestRateLimiter` 对所有网络请求进行统一限流。`VideoSourceManager` 读取本地 `assets/sample_video_source.json`，作为首页兜底挡板和本地数据补充。

## 顶层目录

```text
MyMovieStore/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
├── example/
│   └── douban/
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── README.md
└── project_structure.md
```

| 文件或目录 | 说明 |
|------------|------|
| `app/` | Android 应用模块 |
| `example/` | 页面源代码示例，用于辅助爬虫解析 |
| `gradle/libs.versions.toml` | 统一管理依赖和插件版本 |
| `README.md` | 项目功能、构建和使用说明 |
| `project_structure.md` | 当前架构与文件职责说明 |

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
    ├── layout/
    ├── menu/
    ├── mipmap-*/
    ├── values/
    ├── values-night/
    └── xml/
```

## 应用入口

### `MovieApplication.kt`

`MovieApplication` 是应用级初始化入口：

- 初始化 Room 数据库 `MovieDatabase`。
- 初始化 `PlayHistoryRepository`、`SearchHistoryRepository`、`ApiCacheRepository`。
- 创建本地挡板源 `VideoSourceManager`。
- 创建播放链路爬虫源 `CrawlerVideoSource`（内含 `RequestRateLimiter` 限流器）。
- 创建首页发现源 `DoubanDiscoverySource`。
- 创建 `VideoRepository`，供视频相关 ViewModel 使用。
- 启动时清理过期的 `api_cache` 记录。
- 提供全局 Coil `ImageLoader`，为豆瓣图片自动补充 `Referer`、`Origin` 和浏览器 `User-Agent`，处理图片防盗链。

当前全局依赖通过 `MovieApplication.get()` 获取。

## Data 层

```text
data/
├── dao/
│   ├── ApiCacheDao.kt
│   ├── PlayHistoryDao.kt
│   └── SearchHistoryDao.kt
├── database/
│   └── MovieDatabase.kt
├── entity/
│   ├── ApiCacheEntity.kt
│   ├── PlayHistoryEntity.kt
│   └── SearchHistoryEntity.kt
├── model/
│   ├── CrawlerVideoDetail.kt
│   ├── DoubanMoviePageResult.kt
│   ├── SearchPageResult.kt
│   ├── VideoItem.kt
│   └── remote/
│       ├── RemoteCategory.kt
│       ├── RemoteVideo.kt
│       ├── RemoteVideoMapper.kt
│       └── RemoteVideoResponse.kt
├── repository/
│   ├── ApiCacheRepository.kt
│   ├── PlayHistoryRepository.kt
│   ├── SearchHistoryRepository.kt
│   └── VideoRepository.kt
└── source/
    ├── CrawlerVideoSource.kt
    ├── DoubanDiscoverySource.kt
    ├── RequestRateLimiter.kt
    └── VideoSourceManager.kt
```

### 数据库

`MovieDatabase.kt` 是 Room 数据库入口：

| 配置 | 当前值 |
|------|--------|
| 数据库名 | `movie_database` |
| 当前版本 | `4` |
| 表 | `play_history`、`search_history`、`api_cache` |
| 迁移策略 | `fallbackToDestructiveMigration()` |
| Schema 导出 | `exportSchema = false` |

### Entity

| Entity | 表名 | 主要用途 |
|--------|------|----------|
| `PlayHistoryEntity` | `play_history` | 保存播放历史、播放地址、播放进度、总时长和最后播放时间 |
| `SearchHistoryEntity` | `search_history` | 保存搜索关键词、搜索次数和最后搜索时间 |
| `ApiCacheEntity` | `api_cache` | 保存 JSON 响应缓存，包含 TTL、创建时间和过期时间 |

### DAO

| DAO | 职责 |
|-----|------|
| `PlayHistoryDao` | 查询全部历史、按 `videoId` 查询、插入、更新历史、更新进度、删除和清空 |
| `SearchHistoryDao` | 查询搜索历史、按关键词查询、插入或更新、删除单条和清空 |
| `ApiCacheDao` | 按缓存键读取、写入、删除、清理过期缓存 |

### Repository

| Repository | 职责 |
|------------|------|
| `VideoRepository` | 聚合豆瓣发现源、剧集屋播放源和本地挡板源，对上层提供首页、搜索、详情和播放相关数据 |
| `PlayHistoryRepository` | 播放历史去重写入、进度更新、清空和按视频读取历史 |
| `SearchHistoryRepository` | 搜索词新增或更新、删除、清空和历史列表读取 |
| `ApiCacheRepository` | 封装 `api_cache` 的读写、失效、过期清理和剩余 TTL 查询 |

### 模型

| Model | 说明 |
|-------|------|
| `VideoItem` | UI 层通用影视卡片和详情数据 |
| `SearchPageResult` | 剧集屋搜索分页结果，包含当前页、总页数、上下页状态和列表 |
| `DoubanMoviePageResult` | 豆瓣首页分栏分页结果，包含 `start`、`limit`、`total`、`items` 和 `hasMore` |
| `CrawlerVideoDetail` | 剧集屋详情页解析结果，包含播放线路和剧集 |

## 数据源

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

### `CrawlerVideoSource`

`CrawlerVideoSource` 是内容播放层，负责搜索、详情和真实播放地址解析。所有网络请求通过内置的 `RequestRateLimiter` 统一限流。

主要流程：

```text
搜索页 HTML
        ↓
RequestRateLimiter 排队（SEARCH 优先级）
        ↓
解析搜索结果和分页
        ↓ 点击搜索结果
详情页 HTML
        ↓
RequestRateLimiter 排队（DETAIL 优先级）
        ↓
解析播放线路和剧集
        ↓ 点击播放
播放页 HTML
        ↓
RequestRateLimiter 排队（PLAY 优先级）
        ↓
提取真实 .m3u8 / mp4 地址
        ↓
PlayerActivity
```

主要方法：

| 方法 | 用途 |
|------|------|
| `searchVideos()` | 按关键词爬取搜索结果页并解析分页 |
| `fetchVideoDetail()` | 解析详情页中的元信息、播放线路和剧集 |
| `fetchVideoUrl()` | 从详情页找到首个播放页并解析真实播放地址 |
| `fetchVideoUrlByPlayPageUrl()` | 直接从播放页解析真实播放地址 |

### `RequestRateLimiter`

`RequestRateLimiter` 是内容播放层的单源限流调度器，每个播放源持有一个独立实例。

核心参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `sourceTag` | `"JJU"` | 播放源标识，用于日志 |
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

## 缓存策略

`api_cache` 只用于网络爬取结果。本地挡板回退结果不写入首页缓存。

| 数据类型 | 缓存键前缀 | 缓存时长 | 说明 |
|----------|------------|----------|------|
| 首页全部豆瓣内容 | `home:tab:all:v1` | 1 天 | 豆瓣发现成功后写入 |
| 首页电影分页 | `home:tab:movie:v1:` | 首页 1 天，后续页跟随首页剩余 TTL | 同一分类分页同时过期 |
| 首页电视剧/动漫/综艺分页 | `home:tab:tv_related:v1:` | 首页 1 天，后续页跟随首页剩余 TTL | 三个默认分栏会一起预缓存 |
| 搜索结果页 | `crawler:search:v3` | 30 分钟 | 同一搜索词下分页一起过期 |
| 详情页首个播放页链接 | `crawler:detail:first_play_page` | 1 天 | 复用详情页解析出的播放入口 |
| 真实播放地址 | `crawler:play:real_url` | 30 分钟 | 短时效真实播放地址只做短缓存 |

`ApiCacheRepository.getRemainingTtlSeconds()` 用于让后续分页缓存跟随首页或第一页的剩余缓存时间。

## Presentation 层

```text
presentation/
├── activity/
│   ├── DetailActivity.kt
│   ├── MainActivity.kt
│   └── PlayerActivity.kt
├── adapter/
│   ├── HistoryAdapter.kt
│   ├── SearchResultAdapter.kt
│   └── VideoAdapter.kt
├── fragment/
│   ├── HistoryFragment.kt
│   ├── HomeFragment.kt
│   └── SearchFragment.kt
└── viewmodel/
    ├── HistoryViewModel.kt
    ├── PlayerViewModel.kt
    ├── SearchHistoryViewModel.kt
    └── VideoViewModel.kt
```

### Activity

| Activity | 说明 |
|----------|------|
| `MainActivity` | 主页面容器，使用底部导航切换首页、搜索和历史；通过 `add + hide/show` 保留 Fragment 实例；处理返回键双击退出和搜索页状态管理 |
| `DetailActivity` | 视频详情页，展示完整视频信息、播放线路、剧集和续播提示 |
| `PlayerActivity` | 播放器页面，使用 Media3 ExoPlayer 播放视频，负责播放生命周期和进度保存 |

### Fragment

| Fragment | 说明 |
|----------|------|
| `HomeFragment` | 首页内容发现，九宫格展示，支持主分类、二级分类和列表末尾加载更多 |
| `SearchFragment` | 搜索页，支持外部传入关键词自动搜索、手动搜索、分页、历史 Chip 和清空历史；提供 `isShowingSearchResult()` 和 `resetToInitialState()` 供 MainActivity 调用 |
| `HistoryFragment` | 播放历史页，展示 Room 中的播放记录，支持点击进入详情和一键清空 |

### ViewModel

| ViewModel | 说明 |
|-----------|------|
| `VideoViewModel` | 加载首页发现内容、豆瓣分页、搜索结果和详情相关数据 |
| `HistoryViewModel` | 读取播放历史、写入或更新历史、清空历史 |
| `PlayerViewModel` | 播放时写入历史、更新播放进度、查询续播记录、按 ID 回查视频 |
| `SearchHistoryViewModel` | 读取、写入、删除和清空搜索历史 |

### Adapter

| Adapter | 说明 |
|---------|------|
| `VideoAdapter` | 首页九宫格卡片和列表末尾 `加载更多` Footer |
| `SearchResultAdapter` | 搜索结果列表，展示封面、标题、类型、上映时间、主演和简介 |
| `HistoryAdapter` | 播放历史列表 |

## 资源结构

```text
res/
├── color/
│   └── bottom_nav_color.xml
├── drawable/
│   ├── bg_chip.xml
│   ├── bg_chip_selected.xml
│   ├── bg_detail_card.xml
│   ├── bg_detail_page.xml
│   ├── bg_episode_normal.xml
│   ├── bg_episode_selected.xml
│   ├── bg_play_button.xml
│   ├── bg_player_gesture_tip.xml
│   ├── bg_player_round_button.xml
│   ├── bg_player_top_gradient.xml
│   ├── bg_poster_round.xml
│   ├── bg_rating.xml
│   ├── ic_history.xml
│   ├── ic_home.xml
│   ├── ic_launcher_background.xml
│   ├── ic_launcher_foreground.xml
│   ├── ic_player_back.xml
│   ├── ic_player_rotate.xml
│   └── ic_search.xml
├── layout/
│   ├── activity_detail.xml
│   ├── activity_main.xml
│   ├── activity_player.xml
│   ├── fragment_history.xml
│   ├── fragment_home.xml
│   ├── fragment_search.xml
│   ├── item_history.xml
│   ├── item_home_load_more.xml
│   ├── item_search_result.xml
│   └── item_video.xml
├── menu/
│   └── bottom_nav_menu.xml
├── values/
│   ├── colors.xml
│   ├── strings.xml
│   └── themes.xml
├── values-night/
│   └── themes.xml
└── xml/
    ├── backup_rules.xml
    └── data_extraction_rules.xml
```

### 布局对应关系

| 布局文件 | 对应组件 |
|----------|----------|
| `activity_main.xml` | `MainActivity` |
| `activity_detail.xml` | `DetailActivity` |
| `activity_player.xml` | `PlayerActivity` |
| `fragment_home.xml` | `HomeFragment` |
| `fragment_search.xml` | `SearchFragment` |
| `fragment_history.xml` | `HistoryFragment` |
| `item_video.xml` | `VideoAdapter` 的影视卡片 |
| `item_home_load_more.xml` | `VideoAdapter` 的加载更多 Footer |
| `item_search_result.xml` | `SearchResultAdapter` |
| `item_history.xml` | `HistoryAdapter` |

## 页面导航

底部导航菜单定义在 `res/menu/bottom_nav_menu.xml`：

```text
nav_home    → HomeFragment
nav_search  → SearchFragment
nav_history → HistoryFragment
```

`MainActivity` 保留三个 Fragment 实例，切换时隐藏其他页面并显示目标页面。这样首页滚动位置、已加载分页、主分类和二级分类不会因为进入搜索或历史而重置。

首页内容发现到搜索的关联：

```text
HomeFragment 点击影视卡片
        ↓
MainActivity.navigateToSearchWithKeyword(title)
        ↓
SearchFragment.searchFromExternal(title)
        ↓
CrawlerVideoSource.searchVideos(title)
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
CrawlerVideoSource.searchVideos()
        ↓
RequestRateLimiter.submit(SEARCH, url)
        ↓
SearchResultAdapter 渲染结果
```

### 详情和播放

```text
SearchFragment 点击搜索结果
        ↓ detailUrl
DetailActivity
        ↓
VideoRepository.getCrawlerVideoDetail()
        ↓
CrawlerVideoSource.fetchVideoDetail()
        ↓
RequestRateLimiter.submit(DETAIL, url)
        ↓
用户选择播放
        ↓ playPageUrl
CrawlerVideoSource.fetchVideoUrlByPlayPageUrl()
        ↓
RequestRateLimiter.submit(PLAY, url)
        ↓
PlayerActivity
```

### 播放历史与续播

```text
PlayerActivity
        ↓
PlayerViewModel
        ↓
PlayHistoryRepository
        ↓
Room play_history
        ↓
HistoryFragment / DetailActivity / PlayerActivity
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
| versionName | `1.0` |
| Java 版本 | `17` |
| Kotlin JVM Target | `17` |
| ViewBinding | 已启用 |

### 主要依赖

| 依赖 | 用途 |
|------|------|
| AndroidX Core / AppCompat / Activity | Android 基础能力 |
| Material Components | Material UI 组件 |
| ConstraintLayout | 布局 |
| RecyclerView / CardView | 列表和卡片 |
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
- 注册 `DetailActivity` 和 `PlayerActivity`。
- `PlayerActivity` 使用无 ActionBar 主题，并处理方向和屏幕尺寸变化。
- 声明 `INTERNET` 和 `ACCESS_NETWORK_STATE` 权限。

## 当前实现边界

- 首页内容发现只适配豆瓣相关页面和接口；豆瓣失败时回退本地挡板。
- 内容播放只适配当前剧集屋搜索、详情和播放页结构。
- 本地挡板不写入首页 `api_cache`。
- 搜索结果、详情播放入口和真实播放地址有独立缓存周期。
- 爬虫限流器当前只服务于剧集屋单一播放源，队列容量和间隔为固定值。
- 收藏功能未实现。
- 数据库升级使用破坏式迁移，适合开发阶段；正式版本应增加 Migration。
