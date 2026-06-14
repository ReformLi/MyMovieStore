# MyMovieStore

`MyMovieStore` 是一个使用 Kotlin 开发的 Android 原生影视浏览与播放示例应用。当前版本支持本地 JSON 视频源和网页爬虫视频源，具备首页浏览、关键词搜索、视频详情、Media3 播放、播放历史、续播进度、搜索历史和爬虫结果缓存能力。

## 功能概览

| 模块 | 当前能力 | 主要实现 |
|------|----------|----------|
| 首页 | 展示全部视频，按电影、电视剧、综艺、动漫、纪录片分类切换 | `HomeFragment`、`VideoViewModel`、`VideoAdapter` |
| 搜索 | 按标题、演员、导演、简介实时过滤；提交搜索词后记录搜索历史 | `SearchFragment`、`SearchHistoryViewModel` |
| 详情 | 展示封面、标题、分类、评分、年份、地区、导演、演员、简介和续播提示 | `DetailActivity` |
| 播放 | 使用 Media3 ExoPlayer 播放 MP4、HLS、DASH、RTSP、SmoothStreaming 等地址 | `PlayerActivity`、`PlayerViewModel` |
| 播放历史 | 自动去重写入历史，按最近播放倒序展示，支持一键清空 | `HistoryFragment`、`HistoryViewModel` |
| 续播进度 | 播放中定期保存进度，再次播放时从历史进度续播 | `PlayHistoryRepository`、`PlayHistoryEntity` |
| 爬虫缓存 | 首页列表、详情页播放入口和真实播放地址按不同 TTL 缓存，减少源站访问 | `CrawlerVideoSource`、`ApiCacheRepository` |

当前代码中没有独立的收藏页面和收藏表；底部导航实际包含：首页、搜索、历史。

## 技术栈

| 类型 | 技术 |
|------|------|
| 开发语言 | Kotlin 2.0 |
| 构建工具 | Gradle、Android Gradle Plugin 8.5.0 |
| 最低版本 | minSdk 24 |
| 目标版本 | targetSdk 36 |
| UI | XML Layout、ViewBinding、Material Components、RecyclerView、CardView |
| 架构 | MVVM + Repository + 数据源管理 |
| 异步 | Kotlin Coroutines、LiveData |
| 本地存储 | Room 2.6.1 |
| 播放器 | AndroidX Media3 ExoPlayer 1.4.0 |
| 图片加载 | Coil 2.7.0 |
| JSON 解析 | Moshi 1.15.1 |
| 网络能力 | OkHttp 4.12.0 |
| 代码生成 | KSP |

## 数据来源

应用视频数据支持两类来源：

```text
app/src/main/assets/sample_video_source.json
CrawlerVideoSource（网页爬虫源）
```

本地 JSON 文件采用类似影视接口的字段格式，例如 `vod_id`、`vod_name`、`vod_pic`、`vod_remarks`、`vod_actor`、`vod_director`、`vod_play_url`、`type_name` 等。`VideoSourceManager` 使用 Moshi 解析 JSON，并通过 `RemoteVideoMapper` 转换为 UI 层使用的 `VideoItem`。

爬虫源由 `CrawlerVideoSource` 实现：先爬取首页 `.r-item` 视频卡片，再从详情页解析首个播放页链接，最后从播放页的 `player_aaaa` 脚本中提取真实 `.m3u8` / `mp4` 播放地址。当前 `MovieApplication` 中开启了 `preferCrawler = true`，首页优先使用爬虫数据，失败时回退本地 JSON 数据源。

视频列表本身不再写入 Room。Room 当前只持久化三类数据：

| 表名 | Entity | 用途 |
|------|--------|------|
| `play_history` | `PlayHistoryEntity` | 播放历史、播放地址冗余、续播进度、总时长 |
| `search_history` | `SearchHistoryEntity` | 最近搜索关键词、搜索次数、最后搜索时间 |
| `api_cache` | `ApiCacheEntity` | JSON 响应缓存，支持 TTL 过期 |

### 爬虫缓存策略

`CrawlerVideoSource` 已接入 `ApiCacheRepository`，按数据时效采用不同缓存时长：

| 数据类型 | 缓存键前缀 | 缓存时长 | 说明 |
|----------|------------|----------|------|
| 首页视频列表 | `crawler:home:list` | 1 天 | 推荐位、热播榜等列表变化较慢，强缓存以降低访问频率 |
| 详情页首个播放页链接 | `crawler:detail:first_play_page` | 1 天 | 当前版本只播放第一集，可视作剧集播放入口缓存 |
| 真实播放地址 | `crawler:play:real_url` | 30 分钟 | `.m3u8` / `mp4` 可能带短时效 token，只做短缓存 |

播放页 HTML 不做 1 天缓存，避免把其中的短时效真实视频地址间接长期缓存。

## 应用流程

```text
MainActivity
├── HomeFragment
│   └── DetailActivity
│       └── PlayerActivity
├── SearchFragment
│   └── DetailActivity
│       └── PlayerActivity
└── HistoryFragment
    └── DetailActivity
        └── PlayerActivity
```

典型数据流：

```text
CrawlerVideoSource / assets/sample_video_source.json
        ↓
VideoRepository
        ↓
VideoViewModel
        ↓
HomeFragment / SearchFragment / DetailActivity
```

爬虫播放地址解析数据流：

```text
DetailActivity
        ↓ detailUrl
VideoRepository.getVideoByDetailUrl()
        ↓
CrawlerVideoSource.fetchVideoUrl()
        ↓
api_cache：30分钟内命中真实播放地址则直接返回
        ↓ 未命中
api_cache：1天内命中首个播放页链接则跳过详情页请求
        ↓ 未命中
请求详情页并解析播放页链接
        ↓
请求播放页并提取真实播放地址
        ↓
PlayerActivity
```

播放历史与续播数据流：

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

## 项目结构

```text
app/src/main/
├── assets/
│   └── sample_video_source.json
├── java/com/hpu/mymoviestore/
│   ├── MovieApplication.kt
│   ├── data/
│   │   ├── dao/
│   │   ├── database/
│   │   ├── entity/
│   │   ├── model/
│   │   ├── repository/
│   │   └── source/
│   └── presentation/
│       ├── activity/
│       ├── adapter/
│       ├── fragment/
│       └── viewmodel/
└── res/
    ├── drawable/
    ├── layout/
    ├── menu/
    ├── values/
    └── xml/
```

更详细的代码分层和文件职责见 [`project_structure.md`](./project_structure.md)。

## 构建与运行

### 使用 Android Studio

1. 使用 Android Studio 打开项目根目录。
2. 等待 Gradle Sync 完成。
3. 连接模拟器或真机。
4. 运行 `app` 模块。

### 命令行构建

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

macOS / Linux：

```bash
./gradlew assembleDebug
```

Debug APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 权限说明

`AndroidManifest.xml` 中声明了以下权限：

| 权限 | 用途 |
|------|------|
| `android.permission.INTERNET` | 加载封面图片和播放远程视频地址 |
| `android.permission.ACCESS_NETWORK_STATE` | 判断网络状态，配合网络播放与数据源能力 |

## 当前版本说明

- 版本号：`1.0`
- applicationId：`com.hpu.mymoviestore`
- compileSdk：`36`
- minSdk：`24`
- targetSdk：`36`
- Java / Kotlin JVM Target：`17`

## 后续可扩展方向

- 将 `sample_video_source.json` 替换为真实网络影视源接口。
- 增加视频源管理页面，支持添加、删除和切换本地 JSON 源、爬虫源和远程接口源。
- 增加收藏模块时，需要新增收藏 Entity、DAO、Repository、ViewModel、Fragment 和底部导航入口。
- 增加更完整的播放器错误提示、加载状态和播放格式兼容处理。
- 增加 Room Migration，替代当前 `fallbackToDestructiveMigration()` 的破坏式迁移策略。
