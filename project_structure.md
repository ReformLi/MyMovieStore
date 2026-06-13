# 项目结构说明

本文档根据当前代码结构整理，描述 `MyMovieStore` 的分层设计、主要目录、核心类职责和数据流。当前项目是单模块 Android 应用，主包名为 `com.hpu.mymoviestore`。

## 架构概览

项目采用 MVVM + Repository + 数据源管理的轻量分层结构：

```text
Presentation Layer
Activity / Fragment / Adapter
        ↓ observe / call
ViewModel
        ↓
Repository
        ↓
Data Source / Room DAO
        ↓
assets JSON / SQLite
```

当前视频主数据不存入 Room，而是从 `assets/sample_video_source.json` 读取，经 Moshi 解析后映射为 `VideoItem`。Room 负责保存播放历史、搜索历史和 JSON 缓存。

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
├── README.md
└── project_structure.md
```

| 文件或目录 | 说明 |
|------------|------|
| `app/` | Android 应用模块 |
| `gradle/libs.versions.toml` | 统一管理依赖和插件版本 |
| `settings.gradle.kts` | Gradle 项目配置 |
| `build.gradle.kts` | 根项目构建配置 |
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

`MovieApplication` 是应用级初始化入口，主要职责：

- 初始化 Room 数据库 `MovieDatabase`。
- 初始化 `PlayHistoryRepository`、`SearchHistoryRepository`、`ApiCacheRepository`。
- 创建 `VideoSourceManager`，连接本地 JSON 数据源和 `api_cache` 缓存。
- 创建 `VideoRepository`，供视频相关 ViewModel 使用。
- 启动时清理过期的 `api_cache` 记录。

当前全局依赖通过 `MovieApplication.get()` 获取，后续如引入 Hilt，可将这些单例迁移到依赖注入容器。

## Data 层

`data` 目录负责数据模型、数据访问、缓存、Repository 和视频源解析。

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

版本注释中记录了历史结构变化：早期包含视频、收藏、视频源和分类表；当前版本已精简为播放历史、搜索历史和缓存三张表。

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
| `VideoRepository` | 对上层提供视频列表、分类过滤、关键词搜索和按 ID 查询 |
| `PlayHistoryRepository` | 播放历史去重写入、进度更新、清空和按视频读取历史 |
| `SearchHistoryRepository` | 搜索词新增或更新、删除、清空和历史列表读取 |
| `ApiCacheRepository` | 封装 `api_cache` 的读写、失效和过期清理 |

### 视频源解析

`VideoSourceManager` 是视频主数据入口，数据加载顺序为：

```text
内存缓存 cachedVideos
        ↓ 未命中
Room api_cache
        ↓ 未命中或过期
assets/sample_video_source.json
        ↓
Moshi 解析 RemoteVideoResponse
        ↓
RemoteVideoMapper 转为 List<VideoItem>
        ↓
写入内存缓存和 api_cache
```

它还提供：

- `loadAllVideos()`：获取全部视频。
- `loadVideosByCategory(category)`：按分类过滤。
- `searchVideos(keyword)`：按标题、演员、导演、简介搜索。
- `getVideoById(id)`：按视频 ID 回查详情。
- `clearCache()`：清空内存缓存和接口缓存。

## Presentation 层

`presentation` 目录负责页面展示、交互、ViewModel 状态和列表适配。

```text
presentation/
├── activity/
│   ├── DetailActivity.kt
│   ├── MainActivity.kt
│   └── PlayerActivity.kt
├── adapter/
│   ├── HistoryAdapter.kt
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
| `MainActivity` | 主页面容器，使用底部导航切换首页、搜索和历史三个 Fragment |
| `DetailActivity` | 视频详情页，展示完整视频信息，支持从历史记录回查缺失字段，并显示续播提示 |
| `PlayerActivity` | 播放器页面，使用 Media3 ExoPlayer 播放视频，负责播放生命周期和进度保存 |

### Fragment

| Fragment | 说明 |
|----------|------|
| `HomeFragment` | 首页视频列表，使用 `TabLayout` 实现全部、电影、电视剧、综艺、动漫、纪录片分类切换 |
| `SearchFragment` | 搜索页，支持输入实时过滤、提交搜索词、展示搜索历史 Chip、清空搜索历史 |
| `HistoryFragment` | 播放历史页，展示 Room 中的播放记录，支持点击进入详情和一键清空 |

当前没有 `FavoriteFragment`，收藏模块未在当前代码中实现。

### ViewModel

| ViewModel | 说明 |
|-----------|------|
| `VideoViewModel` | 加载全部视频、分类视频、搜索结果和按 ID 查询视频 |
| `HistoryViewModel` | 读取播放历史、写入或更新历史、清空历史 |
| `PlayerViewModel` | 播放时写入历史、更新播放进度、查询续播记录、按 ID 回查视频 |
| `SearchHistoryViewModel` | 读取、写入、删除和清空搜索历史 |

### Adapter

| Adapter | 说明 |
|---------|------|
| `VideoAdapter` | 渲染首页和搜索页的视频卡片 |
| `HistoryAdapter` | 渲染播放历史列表 |

## 资源结构

```text
res/
├── color/
│   └── bottom_nav_color.xml
├── drawable/
│   ├── bg_chip.xml
│   ├── bg_rating.xml
│   ├── ic_history.xml
│   ├── ic_home.xml
│   └── ic_search.xml
├── layout/
│   ├── activity_detail.xml
│   ├── activity_main.xml
│   ├── activity_player.xml
│   ├── fragment_history.xml
│   ├── fragment_home.xml
│   ├── fragment_search.xml
│   ├── item_history.xml
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
| `item_video.xml` | `VideoAdapter` |
| `item_history.xml` | `HistoryAdapter` |

## 页面导航

底部导航菜单定义在 `res/menu/bottom_nav_menu.xml`：

```text
nav_home    → HomeFragment
nav_search  → SearchFragment
nav_history → HistoryFragment
```

详情和播放页面通过显式 Intent 打开：

```text
HomeFragment / SearchFragment / HistoryFragment
        ↓
DetailActivity
        ↓
PlayerActivity
```

`DetailActivity` 使用一组 `EXTRA_VIDEO_*` 常量接收视频字段。来自首页和搜索页时通常携带完整字段；来自历史页时可能只携带历史表冗余字段，详情页会通过 `videoId` 回查 JSON 数据补全导演、演员、简介、评分等信息。

## 核心数据流

### 首页分类浏览

```text
HomeFragment.onViewCreated()
        ↓
VideoViewModel.loadAllVideos()
        ↓
VideoRepository.getAllVideos()
        ↓
VideoSourceManager.loadAllVideos()
        ↓
VideoAdapter.submitList()
```

切换分类时调用：

```text
VideoViewModel.loadVideosByCategory(category)
        ↓
VideoSourceManager.loadVideosByCategory(category)
```

### 搜索与搜索历史

```text
SearchFragment 文本变化
        ↓
VideoViewModel.searchVideos(keyword)
        ↓
VideoSourceManager.searchVideos(keyword)
        ↓
列表刷新
```

用户提交搜索时：

```text
SearchHistoryViewModel.addKeyword(keyword)
        ↓
SearchHistoryRepository.addOrUpdateKeyword(keyword)
        ↓
Room search_history
        ↓
SearchFragment 渲染历史 Chip
```

### 播放与续播

```text
DetailActivity 点击播放
        ↓
PlayerActivity
        ↓
PlayerViewModel.getHistoryByVideoId(videoId)
        ↓
若存在 playProgressSeconds，则 ExoPlayer seekTo()
        ↓
PlayerViewModel.setVideoInfo()
        ↓
PlayHistoryRepository.addOrUpdateHistory()
        ↓
Room play_history
```

播放过程中：

```text
PlayerActivity 定时读取 currentPosition
        ↓
PlayerViewModel.updateProgress()
        ↓
PlayHistoryRepository.updateProgress()
        ↓
Room play_history
```

## 构建配置

### `app/build.gradle.kts`

关键配置：

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
| SwipeRefreshLayout | 下拉刷新能力预留 |
| Lifecycle ViewModel / LiveData / Runtime | MVVM 和生命周期感知 |
| Navigation Fragment / UI | 导航能力依赖，当前主要使用手动 Fragment 切换 |
| Room Runtime / KTX / Compiler | 本地数据库 |
| Kotlin Coroutines | 异步任务 |
| Coil | 图片加载 |
| Media3 ExoPlayer | 视频播放 |
| Moshi | JSON 解析 |
| OkHttp | 网络数据源 |

## 清单文件

`AndroidManifest.xml` 主要内容：

- 应用入口为 `MovieApplication`。
- 启动页为 `MainActivity`。
- 注册 `DetailActivity` 和 `PlayerActivity`。
- `PlayerActivity` 使用无 ActionBar 主题，并处理方向和屏幕尺寸变化。
- 声明 `INTERNET` 和 `ACCESS_NETWORK_STATE` 权限。

## 当前实现边界

- 视频主数据来自本地 JSON 示例文件，不是真实远程接口。
- 视频列表未持久化到 Room；Room 只保存历史、搜索记录和缓存。
- 收藏功能相关字符串仍存在于资源文件中，但当前页面和数据层未实现收藏模块。
- 数据库升级使用破坏式迁移，适合开发阶段；正式版本应增加 Migration。
- `api_cache` 当前主要缓存本地 JSON 解析结果，后续接入网络源后可复用同一缓存机制。

## 新增功能建议

新增一个完整功能模块时，可按以下顺序补齐：

1. 在 `data/entity` 新增 Entity。
2. 在 `data/dao` 新增 DAO。
3. 在 `MovieDatabase` 注册 Entity 和 DAO。
4. 在 `data/repository` 新增 Repository。
5. 在 `MovieApplication` 初始化 Repository。
6. 在 `presentation/viewmodel` 新增 ViewModel。
7. 在 `presentation/fragment` 或 `presentation/activity` 新增页面。
8. 在 `res/layout` 新增布局。
9. 如需底部入口，在 `bottom_nav_menu.xml` 和 `MainActivity` 中注册。

如果新增远程视频源，优先扩展 `VideoSourceManager` 和 `VideoRepository`，避免让 UI 直接感知数据来自本地 JSON 还是网络接口。

