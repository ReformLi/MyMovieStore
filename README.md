# MyMovieStore

`MyMovieStore` 是一个使用 Kotlin 开发的 Android 原生影视浏览与播放应用。应用将首页推荐和播放链路拆成两个相对独立的层：内容发现层负责从豆瓣页面发现影视内容，内容播放层负责聚合多个播放源搜索可播放资源、展示详情并播放视频。

## 功能概览

| 模块 | 当前能力 | 主要实现 |
|------|----------|----------|
| 首页内容发现 | 九宫格展示豆瓣影视内容，支持全部、电影、电视剧、综艺、动漫分栏 | `HomeFragment`、`DoubanDiscoverySource`、`VideoAdapter` |
| 首页电影 | 支持全部、华语、欧美、韩国、日本二级分类，底部加载更多 | `DoubanDiscoverySource.fetchExploreMoviePage()` |
| 首页电视剧 | 支持综合、国产剧、欧美剧、日剧、韩剧、纪录片二级分类 | `DoubanDiscoverySource.fetchExploreTvRelatedPage()` |
| 首页综艺 | 支持综合、国内、国外二级分类 | `DoubanDiscoverySource.fetchExploreTvRelatedPage()` |
| 首页动漫 | 使用豆瓣电视剧页中的动画数据，不展示二级分类 | `DoubanDiscoverySource.fetchExploreTvRelatedPage()` |
| 搜索 | 多源并行搜索，结果插空法排序显示，支持分页、搜索历史和结果缓存 | `SearchFragment`、`VideoRepository.searchVideosPage()` |
| 多源播放 | 支持剧集屋、樱花动漫等多个播放源，可独立启用/禁用 | `ProfileFragment` 视频源管理 |
| 首页到搜索联动 | 点击首页影视后跳转搜索页，并按影视名自动搜索 | `MainActivity.navigateToSearchWithKeyword()` |
| 详情 | 从搜索结果进入详情，解析播放线路、剧集和简介等信息 | `DetailActivity`、`CrawlerVideoSource.fetchVideoDetail()` |
| 播放 | 使用 Media3 ExoPlayer 播放真实视频地址，支持进度保存和续播 | `PlayerActivity`、`PlayerViewModel` |
| **弹幕系统** | **支持弹幕搜索、源切换、开关控制，与播放器同步** | **`DanmakuManager`、`DanmakuRepository`、`DanmakuCache`** |
| 播放历史 | 自动保存播放记录（含播放源信息），按最近播放倒序展示，支持清空 | `HistoryActivity`、`HistoryViewModel` |
| 搜索历史 | 保存搜索关键词、搜索次数和最后搜索时间 | `SearchHistoryViewModel` |
| 个人中心 | 视频源管理、弹幕开关、历史记录、下载管理（占位）、**清理缓存**、帮助、关于 | `ProfileFragment` |
| **清理缓存** | **支持分类清理（搜索/首页/详情/播放地址/弹幕/全部），显示缓存大小** | **`ProfileFragment.showClearCacheDialog()`** |
| 爬虫限流 | 每个播放源独立限流队列，同源请求 3 秒最小间隔，优先级抢占 | `RequestRateLimiter`、`CrawlerVideoSource` |
| 细粒度错误提示 | 网络失败时展示具体错误原因（DNS 失败、403、验证码、空结果等） | `CrawlError`、`CrawlErrorType` |

底部导航当前包含：首页、搜索、我的。历史记录已移至"我的"页面内。

## 分层设计

当前代码把影视 App 的数据能力拆成两个方向：

```text
内容发现层
豆瓣首页 / 豆瓣电影页 / 豆瓣电视剧页
        ↓
DoubanDiscoverySource
        ↓
首页九宫格展示
        ↓ 点击影视名
搜索页自动搜索

内容播放层
多个播放源（剧集屋、樱花动漫...）
        ↓
VideoSource 接口 ← CrawlerVideoSource 抽象基类
        ↓
JujiwuVideoSource / YinghuaVideoSource（具体实现）
        ↓
RequestRateLimiter（每个源独立限流）
        ↓
VideoRepository（多源并行搜索 + 插空法排序）
        ↓
SearchFragment / DetailActivity / PlayerActivity
```

内容发现层只负责"发现用户可能想看的影视"；它不直接提供播放地址。内容播放层负责"根据片名搜索可播放资源，再进入详情和播放"，通过 `VideoSource` 接口统一管理多个播放源，每个源持有独立的 `RequestRateLimiter` 限流器。

## 多源播放架构

```text
VideoSource（接口）
    ├── sourceId / sourceName / enabled
    ├── searchVideos() / fetchVideoDetail() / fetchVideoUrl() / fetchVideoUrlByPlayPageUrl()
    ↓
CrawlerVideoSource（抽象基类）
    ├── 通用流程：网络请求、缓存、限流、错误处理
    ├── 通用方法：requestDocument()、extractRealVideoUrl()、buildSearchUrl()
    └── 抽象方法：parseVideoDetail()、parseSearchPage()
    ↓
JujiwuVideoSource ── 剧集屋（www.******.com）
YinghuaVideoSource ── 樱花动漫（wap.******.com）
```

新增播放源只需：
1. 继承 `CrawlerVideoSource`
2. 配置 `sourceId`、`sourceName`、`baseUrl` 等属性
3. 实现 `parseVideoDetail()` 和 `parseSearchPage()` 两个解析方法

## 多源搜索与排序

搜索时，所有**已启用**的播放源并行请求（`coroutineScope + async(Dispatchers.IO)`），结果通过**插空法**合并：

```text
源A结果：[A1, A2, A3, ...]
源B结果：[B1, B2, B3, ...]
合并后： [A1, B1, A2, B2, A3, B3, ...]
```

每个搜索结果和历史记录项都会显示来源播放源名称（如"剧集屋"、"樱花动漫"）。

## 视频源管理

在"我的" → "视频源管理"中：
- 查看所有已注册播放源
- 独立开关每个源（至少保留一个启用）
- 开关状态持久化到 SharedPreferences
- 禁用后的源不参与搜索

## 弹幕系统

播放器内置弹幕功能，支持以下能力：

| 功能 | 说明 |
|------|------|
| 弹幕搜索 | 根据视频标题自动搜索弹幕源，支持多源返回 |
| 弹幕源切换 | 底部控制栏显示弹幕源（如 `弹幕源 tencent`），点击下拉选择不同番剧 |
| 弹幕开关 | 独立于"我的"页面总开关的播放器子开关 |
| 弹幕同步 | 弹幕与播放进度实时同步，支持 seek 后重新对齐 |
| 弹幕缓存 | 搜索、分集、弹幕列表均缓存 1 天，统一过期时间 |
| 失败重试 | 弹幕获取失败自动重试 10 次，间隔 10 秒，超时 10 秒 |

弹幕控制位于播放器底部控制栏，与进度条融为一体，跟随播放器控制栏一起显示/隐藏。

## 播放器手势与锁定

| 手势 | 功能 |
|------|------|
| 双击屏幕 | 暂停/播放 |
| 长按 + 左右滑动 | 快进/快退（最小 10 秒，滑动距离越大 seek 越多） |
| 长按 + 左半屏上下滑动 | 调节亮度 |
| 长按 + 右半屏上下滑动 | 调节音量 |

**屏幕锁定**：左侧中间显示锁定按钮，点击后：
- 隐藏播放器控制栏和弹幕控制
- 禁用所有手势（双击、长按滑动等）
- 点击屏幕只显示/隐藏锁定按钮
- 返回键和解锁按钮仍然可用

播放器控制栏自定义：
- 删除上一集/下一集按钮
- 快进/快退统一为 10 秒
- 播放/暂停、快进、快退按钮使用自定义矢量图标

## 清理缓存

"我的" → "清理缓存"提供美观的自定义弹框，支持选择性清理：

| 选项 | 清理内容 |
|------|----------|
| 清理搜索缓存 | 爬虫搜索缓存 + 本地搜索历史 |
| 清理首页缓存 | 首页列表缓存数据 |
| 清理详情页缓存 | 详情页元数据 |
| 清理播放地址缓存 | 真实播放地址 + 首个播放页缓存 |
| 清理弹幕缓存 | 本地弹幕 SharedPreferences 缓存 |
| 清理全部缓存 | 以上所有（保留下载的视频和弹幕） |

弹框顶部显示当前缓存总大小（自动计算 Room 数据库 + SharedPreferences + 图片缓存）。

## 爬虫限流机制

每个播放源持有独立的 `RequestRateLimiter` 实例，独立管理自己的请求队列和限流策略。

### 设计要点

- **单源独立**：每个播放源一个 `RequestRateLimiter` 实例，互不影响。
- **最小间隔**：同一源下两次实际网络请求之间至少间隔 3 秒。
- **队列容量**：最大同时持有 3 个未完成任务（含正在执行和等待中的）。
- **优先级抢占**：新任务入队时，取消队列中所有优先级 ≤ 自身的旧任务（包括已开始执行的）。
- **优先级等级**：`SEARCH(3) > DETAIL(2) > PLAY(1)`，搜索最高，播放最低。

### 取消行为

| 任务状态 | 处理方式 |
|----------|----------|
| 未开始（等待中） | 直接从队列移除，调用方收到 `CancellationException` |
| 已开始（HTTP 请求已发出） | 通过 OkHttp `Call.cancel()` 终止网络层，但仍占用 3 秒间隔槽 |

### 调用点优先级分配

| 调用场景 | 优先级 | 说明 |
|----------|--------|------|
| 首页爬取 / 搜索 | `SEARCH` | 用户主动触发的搜索行为优先级最高 |
| 详情页解析 | `DETAIL` | 搜索结果点击后获取详情 |
| 播放页解析 | `PLAY` | 获取真实播放地址优先级最低 |

## 技术栈

| 类型 | 技术 |
|------|------|
| 开发语言 | Kotlin 2.0 |
| 构建工具 | Gradle、Android Gradle Plugin 8.5.0 |
| 最低版本 | minSdk 24 |
| 目标版本 | targetSdk 36 |
| UI | XML Layout、ViewBinding、Material Components、RecyclerView、CardView |
| 架构 | MVVM + Repository + Data Source |
| 异步 | Kotlin Coroutines、LiveData |
| 本地存储 | Room 2.6.1 |
| 播放器 | AndroidX Media3 ExoPlayer 1.4.0 |
| 图片加载 | Coil 2.7.0 |
| JSON 解析 | Moshi 1.15.1、org.json |
| 网络与解析 | OkHttp 4.12.0、Jsoup |
| 代码生成 | KSP |

## 首页内容发现

首页采用三列九宫格展示，每个卡片包含封面、影视名和评分。评分为空时显示 `暂无评分`。

### 全部

`全部` 默认从豆瓣电影首页相关接口获取最近热门电视剧和最近热门电影。

展示顺序按豆瓣滑动页分组：

```text
第 1 个滑动页：热门电视剧第一页 + 热门电影第一页，页内随机
第 2 个滑动页：热门电视剧第二页 + 热门电影第二页，页内随机
第 3 个滑动页：热门电视剧第三页 + 热门电影第三页，页内随机
...
```

随机只发生在每个滑动页内部，后续页不会插入到前一页之前。

### 电影

电影分栏来自：

```text
https://movie.douban.com/explore/
https://m.douban.com/rexxar/api/v2/subject/recent_hot/movie
```

支持二级分类：

```text
全部 / 华语 / 欧美 / 韩国 / 日本
```

列表底部有 `加载更多` Footer。只有滑动到列表末尾才能看到，点击后继续追加下一页内容，Footer 会移动到新列表的末尾。

### 电视剧、动漫、综艺

这三个分栏来自同一个页面和接口：

```text
https://movie.douban.com/tv/
https://m.douban.com/rexxar/api/v2/subject/recent_hot/tv
```

电视剧二级分类：

```text
综合 / 国产剧 / 欧美剧 / 日剧 / 韩剧 / 纪录片
```

动漫使用网页上的 `动画` 数据，不展示二级分类按钮。

综艺二级分类：

```text
综合 / 国内 / 国外
```

点击电视剧、动漫、综艺任意一个分栏时，会预缓存这三个默认分栏的数据，让它们在同一组缓存周期内过期。

## 搜索与播放

搜索页聚合多个播放源的搜索结果。首页影视点击后只把影视名传给搜索页，搜索页并行搜索所有启用的播放源。

搜索结果展示字段包括：封面、标题、类型、上映时间、主演、剧情简介和**播放源名称**。点击搜索结果后进入详情页，详情页继续解析播放线路和剧集，播放器只处理真实播放地址。

搜索时点击搜索按钮或输入法搜索键后，自动收起输入法键盘。

## 导航行为

`MainActivity` 使用 `add + hide/show` 保留首页、搜索页和我的页实例。

当前行为：

- 点击首页影视：跳转搜索页，自动填入影视名并搜索。
- 搜索页点击返回：回到首页，并保留首页滚动位置、Tab、二级分类和已加载数据。
- 手动点击底部搜索按钮：搜索页恢复初始状态，只显示搜索历史，不保留上一次搜索框内容和搜索结果。
- 手动点击"我的"按钮后再回首页：首页状态不重置。
- 首页点击返回：第一次提示 `再按一次退出应用`，短时间内第二次返回才退出。

## 缓存策略

缓存统一写入 Room 表 `api_cache`，通过 `ApiCacheRepository` 读写。只缓存网络爬取结果，不缓存本地挡板结果。

| 数据类型 | 缓存键前缀 | 缓存时长 | 说明 |
|----------|------------|----------|------|
| 首页全部豆瓣内容 | `home:tab:all:v1` | 1 天 | 豆瓣内容发现成功后缓存 |
| 首页电影分页 | `home:tab:movie:v1:` | 首页 1 天，后续页跟随首页剩余时间 | 同一电影分类分页一起过期 |
| 首页电视剧/动漫/综艺分页 | `home:tab:tv_related:v1:` | 首页 1 天，后续页跟随首页剩余时间 | 电视剧、动漫、综艺默认页会一起预缓存 |
| 搜索结果页 | `crawler:search:v3` / `yinghua:search:v3` | 30 分钟 | 各源独立缓存，同一关键词下后续页跟随首页剩余时间 |
| 详情页首个播放页链接 | `crawler:detail:first_play_page` / `yinghua:detail:first_play_page` | 1 天 | 各源独立缓存 |
| 真实播放地址 | `crawler:play:real_url` / `yinghua:play:real_url` | 30 分钟 | `.m3u8` / `mp4` 可能带短时效 token |
| 弹幕搜索 | `search_{keyword}` | 1 天 | SharedPreferences 存储 |
| 弹幕分集 | `bangumi_{animeId}` | 1 天 | SharedPreferences 存储 |
| 弹幕列表 | `comments_{episodeId}` | 1 天 | SharedPreferences 存储 |

本地 `assets/sample_video_source.json` 仍作为首页和分类的兜底挡板。豆瓣失败时可以回退本地挡板，但回退结果不写入 `api_cache`。

多源缓存隔离：所有爬虫相关缓存键都包含源标识前缀（`crawler` / `yinghua`），确保不同源的缓存互不干扰。

## 数据存储

Room 当前持久化三张表：

| 表名 | Entity | 用途 |
|------|--------|------|
| `play_history` | `PlayHistoryEntity` | 播放历史、播放地址冗余、续播进度、总时长、**播放源名称** |
| `search_history` | `SearchHistoryEntity` | 最近搜索关键词、搜索次数、最后搜索时间 |
| `api_cache` | `ApiCacheEntity` | 网络响应和解析结果缓存，支持 TTL 过期 |

数据库版本：`6`（含 `sourceName` 字段迁移）。

## 项目结构

```text
app/src/main/
├── assets/
│   └── sample_video_source.json
├── java/com/hpu/mymoviestore/
│   ├── MovieApplication.kt
│   ├── data/
│   │   ├── cache/
│   │   │   └── DanmakuCache.kt
│   │   ├── dao/
│   │   ├── database/
│   │   ├── entity/
│   │   ├── model/
│   │   │   └── danmaku/
│   │   ├── repository/
│   │   └── source/
│   │       └── impl/
│   └── presentation/
│       ├── activity/
│       ├── adapter/
│       ├── danmaku/
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

使用 Android Studio 打开项目根目录，等待 Gradle Sync 完成后运行 `app` 模块。

命令行构建：

```powershell
.\gradlew.bat assembleDebug
```

Debug APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 权限说明

| 权限 | 用途 |
|------|------|
| `android.permission.INTERNET` | 访问豆瓣、播放源网站、封面图片和播放地址 |
| `android.permission.ACCESS_NETWORK_STATE` | 判断网络状态，配合网络播放与远程数据源 |

## 当前版本说明

- 版本号：`1.0`
- applicationId：`com.hpu.mymoviestore`
- compileSdk：`36`
- minSdk：`24`
- targetSdk：`36`
- Java / Kotlin JVM Target：`17`

## 后续可扩展方向

- 增加更多播放源（只需继承 `CrawlerVideoSource` 并实现两个解析方法）。
- 增加首页下拉刷新，用于主动刷新已过期或手动清空的发现缓存。
- 扩展 `RequestRateLimiter` 支持可配置的限流策略（间隔时间、队列容量）。
- 实现下载管理功能（当前为 UI 占位）。
- 增加收藏功能。
- 替换 `fallbackToDestructiveMigration()` 为正式的 Room Migration 策略。
