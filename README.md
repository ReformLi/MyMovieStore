# MyMovieStore

`MyMovieStore` 是一个使用 Kotlin 开发的 Android 原生影视浏览与播放应用。应用将首页推荐和播放链路拆成两个相对独立的层：内容发现层负责从豆瓣页面发现影视内容，内容播放层负责聚合多个播放源搜索可播放资源、展示详情并播放视频。

同一份 APK **同时兼容手机与 Android TV / 盒子**：设备形态在运行时判定，手机保持触屏交互，电视启用遥控器（D-pad）焦点体系与 10-foot UI；网络层、Repository、ViewModel、数据模型与播放器封装两侧完全共用，适配范围严格限定在 UI 层与配置。详见 [Android TV 适配](#android-tv-适配)。

## 功能概览

| 模块       | 当前能力                                     | 主要实现                                                                           |
| -------- | ---------------------------------------- | ------------------------------------------------------------------------------ |
| 首页内容发现   | 九宫格展示豆瓣影视内容，支持全部、电影、电视剧、综艺、动漫分栏          | `HomeFragment`、`DoubanDiscoverySource`、`VideoAdapter`                          |
| 首页电影     | 支持全部、华语、欧美、韩国、日本二级分类，底部加载更多              | `DoubanDiscoverySource.fetchExploreMoviePage()`                                |
| 首页电视剧    | 支持综合、国产剧、欧美剧、日剧、韩剧、纪录片二级分类               | `DoubanDiscoverySource.fetchExploreTvRelatedPage()`                            |
| 首页综艺     | 支持综合、国内、国外二级分类                           | `DoubanDiscoverySource.fetchExploreTvRelatedPage()`                            |
| 首页动漫     | 使用豆瓣电视剧页中的动画数据，不展示二级分类                   | `DoubanDiscoverySource.fetchExploreTvRelatedPage()`                            |
| 搜索       | 多源并行搜索，结果插空法排序显示，支持分页、搜索历史和结果缓存          | `SearchFragment`、`VideoRepository.searchVideosPage()`                          |
| 多源播放     | 支持 19 个爬虫播放源（剧集屋、樱花动漫、电影天堂等），可独立启用/禁用；源名称与地址由远程 JSON 动态配置           | `ProfileFragment` 视频源管理                                                        |
| 首页到搜索联动  | 点击首页影视后跳转搜索页，并按影视名自动搜索                   | `MainActivity.navigateToSearchWithKeyword()`                                   |
| 详情       | 从搜索结果进入详情，解析播放线路、剧集和简介等信息                | `DetailActivity`、`CrawlerVideoSource.fetchVideoDetail()`                       |
| 播放       | 使用 Media3 ExoPlayer 播放真实视频地址，支持进度保存和续播   | `PlayerActivity`、`PlayerViewModel`                                             |
| **弹幕系统** | **支持弹幕搜索、源切换、开关控制，与播放器同步**               | **`DanmakuManager`、`DanmakuRepository`、`DanmakuCache`**                        |
| 播放历史     | 自动保存播放记录（含播放源信息），按最近播放倒序展示，支持清空          | `HistoryActivity`、`HistoryViewModel`                                           |
| 搜索历史     | 保存搜索关键词、搜索次数和最后搜索时间                      | `SearchHistoryViewModel`                                                       |
| 个人中心     | 视频源管理、弹幕开关、历史记录、下载管理、**清理缓存**、帮助、关于      | `ProfileFragment`                                                              |
| **清理缓存** | **支持分类清理（搜索/首页/详情/播放地址/弹幕/全部），显示缓存大小**   | **`ProfileFragment.showClearCacheDialog()`**                                   |
| **下载管理** | **M3U8 分片下载、弹幕下载、前台通知、离线播放、播放进度、降低影响策略** | **`DownloadActivity`、`DownloadEngine`、`DownloadService`、`DownloadRepository`** |
| 爬虫限流     | 每个播放源独立限流队列，同源请求 3 秒最小间隔，优先级抢占           | `RequestRateLimiter`、`CrawlerVideoSource`                                      |
| **反爬应对** | **Cloudflare 人机验证自动过盾（Cookie 缓存 + 人工兜底），TLS 指纹拦截自动降级 WebView 抓取** | **`CloudflareBypassManager`、`WebViewHtmlFetcher`、`CloudflareChallengeActivity`** |
| 细粒度错误提示  | 网络失败时展示具体错误原因（DNS 失败、403、验证码、空结果等）       | `CrawlError`、`CrawlErrorType`                                                  |
| **Android TV 适配** | **同一 APK 双形态：密度放大的 10-foot UI、遥控器 D-pad 焦点体系、电视顶部导航栏、手机扫码搜索、电视缺失能力守卫** | **`TvUiSupport`、`TvFocus`、`TvSearchServer`、`DialogSizing`**                     |

底部导航当前包含：首页、搜索、我的。历史记录和下载管理已移至"我的"页面内。

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
多个播放源（剧集屋、樱花动漫、电影天堂...）
        ↓
VideoSource 接口 ← CrawlerVideoSource 抽象基类
        ↓
JujiwuVideoSource / YinghuaVideoSource / TiantangVideoSource（具体实现）
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
JujiwuVideoSource       ── 剧集屋【crawler_jju】
YinghuaVideoSource      ── 樱花动漫【crawler_yinghua】
TiantangVideoSource     ── 电影天堂【crawler_tiantang】
ChongchongVideoSource / CechiVideoSource / DadatuVideoSource / DoujiaoVideoSource /
HantvVideoSource / NongminTvVideoSource / NongmingVideoSource / NiuerVideoSource /
DaMaoVideoSource / HanSenVideoSource / BaJieVideoSource / ZaiXianVideoSource /
ShenMaVideoSource / A38TvVideoSource / XingChenVideoSource / KaCheVideoSource
                        ── 其余 16 个子类，共 19 个，全部位于 data/source/impl/
```

新增播放源只需：

1. 继承 `CrawlerVideoSource`
2. 声明 `sourceId`、`cachePrefix`、`rateLimiterTag`、`logTag`（`sourceName` / `baseUrl` 由远程配置注入，**不在代码里写死**）
3. 实现 `parseVideoDetail()` 和 `parseSearchPage()` 两个解析方法
4. 在 `VideoSourceConfigManager.knownSourceClasses` 中登记该类（供反射实例化）
5. 在远程 JSON 的 `video_sources` 中追加条目（`source_id` 与代码里的 `sourceId` 一致）

> `sourceName` 与 `baseUrl` 是可变属性，由 `VideoSourceConfigManager` 在构建实例后从远程 JSON 注入 —— 站点换域名时改远程配置即可，无需发版。

## 多源搜索与排序

搜索时，所有**已启用**的播放源并行请求（`coroutineScope + async(Dispatchers.IO)`），结果通过**插空法**合并：

```text
源A结果：[A1, A2, A3, ...]
源B结果：[B1, B2, B3, ...]
源C结果：[C1, C2, C3, ...]
合并后： [A1, B1, C1, A2, B2, C2, A3, B3, C3, ...]
```

每个搜索结果和历史记录项都会显示来源播放源名称（如"剧集屋"、"樱花动漫"）。

## 视频源管理

在"我的" → "视频源管理"中：

* 查看所有已注册播放源

* 独立开关每个源（至少保留一个启用）

* 开关状态持久化到 SharedPreferences

* 禁用后的源不参与搜索

* **远程可用性控制**：远程 JSON 每个源条目支持 `enabled` 字段（与 `name` 同级，缺省视为 `true`）；为 `false` 时该源由远程标记为不可用——不构建实例、不参与搜索/详情、不出现在源管理列表，本地开关无法覆盖。适合站点失效时远程一键下线，无需发版。

## 弹幕系统

播放器内置弹幕功能，支持以下能力：

| 功能       | 说明                                                                                                                  |
| -------- | ------------------------------------------------------------------------------------------------------------------- |
| 弹幕搜索     | 根据视频标题自动搜索弹幕源，支持多源返回                                                                                                |
| 弹幕源切换    | 底部控制栏显示弹幕源（如 `弹幕源 tencent`），点击下拉选择不同番剧                                                                              |
| 弹幕开关     | 独立于"我的"页面总开关的播放器子开关                                                                                                 |
| **本地优先** | **弹幕已下载/已缓存时零联网加载；本地文件 → 弹幕缓存 → 联网级联三级优先链**                                                                         |
| **远程权限** | **弹幕联网由远程配置** **`switches.enable_danmaku`** **控制（app\_name/version 匹配才生效）；权限关闭时不做任何联网获取，无论开关状态只显示「弹幕已关闭」；获取失败默认放行** |
| 弹幕同步     | 弹幕与播放进度实时同步，支持 seek 后重新对齐
| **字号/行数自适应** | **`baseTextSize = min(屏宽 / 35, 屏高 × 行数系数)`，行数系数按设备分档：电视 0.13（≈5 行）、手机 0.16（≈4 行）。三形态实测：电视横屏 35px / 5 行、手机横屏 43px / 4 行、手机竖屏 31px / 12 行**                                                                                           |
| 弹幕缓存     | 搜索、分集、弹幕列表均缓存 1 天，统一过期时间；空弹幕列表不写缓存                                                                                  |
| 失败重试     | **自动重试已移除**（`MAX_RETRY = 1`，网络失败只请求一次即返回失败），改由 UI 手动重试兜底（播放页点击重试 / 更换弹幕源、下载页重试弹幕）；单次请求超时 20 秒；服务端错误向上抛 `IOException`，业务空结果按「无弹幕」处理                                                           |

弹幕控制位于播放器底部控制栏，与进度条融为一体，跟随播放器控制栏一起显示/隐藏。

### 弹幕加载优先链

开关打开（或换弹幕源）时，弹幕按「本地文件 → 弹幕缓存 → 联网级联」三级优先获取：

```text
① 本地文件 Danmaku/{animeId}_{集数}.json 存在
    └─ 直接上屏，零联网（左侧显示「已加载本地弹幕」）
② 弹幕分集 / 弹幕列表缓存命中
    └─ 零联网
③ 均未命中 → 联网级联获取分集和弹幕
    ├─ animeId 失效 → 自动清除搜索缓存并强制重新搜索弹幕源
    └─ 该集无弹幕 → 提示更换其他弹幕源
```

* **下载回写关联**：弹幕下载成功时，`DanmakuDownloadManager` 会把本次使用的 animeId 回写到弹幕偏好（`DanmakuPrefs.saveAnimeId`）。之后播放器开关打开时直接用该关联定位本地文件，避免"弹幕已下载却仍联网搜索"。

* **切换弹幕源**：换源后同样走以上优先链——新源已下载/已缓存不联网，否则联网级联获取；各源缓存按 animeId → episodeId 独立，互不误用。

### 弹幕远程权限

`PermissionConfigRepository` 从远程 JSON 文件（`switches` + `strings` + `metadata`）统一获取 App 各功能权限：

```json
{
  "switches": {
    "myapp": true,
    "enable_danmaku": true,
    "enable_update": true
  },
  "strings": {
    "force_update_url": "https://xxx.com/app.apk",
    "update_details": "修复了XXX的问题！",
    "update_sha256": "新 APK 的 SHA-256 值（可选，用于下载后完整性校验）"
  },
  "metadata": { "app_name": "MyMovieStore", "version": "1.2.0" }
}
```

* **弹幕权限**：`enable_danmaku` 为 true 且 `app_name`/`version` 与本地一致时弹幕可用（保持现状）；否则弹幕不做任何联网获取（搜索/分集/评论/下载均拦截），无论播放器弹幕开关是否打开，都只显示「弹幕已关闭」。

* **与搜索权限一致**：`myapp` 控制搜索功能，两个开关同源同规则。

* **失败默认放行**：联网获取权限配置失败（含解析失败）时默认全部开启，避免远程配置异常锁死本地功能；结果缓存 1 天。

* **缓存带版本校验**：本地缓存 JSON 记录 `cached_for_version`，与当前 App 版本不一致时缓存自动失效重新拉取，避免升级后带着旧版本获取的权限状态跑满 24h。

* **本地版本号**：`LOCAL_VERSION` 从 `BuildConfig.VERSION_NAME` 读取，发版时只需修改 `build.gradle.kts` 的 `versionName`。

### 应用内更新

基于同一份远程 JSON 实现更新检查与 APK 下载安装：

* **更新检查**（`checkUpdate()`）：`enable_update` 开启 + `app_name` 匹配（不要求 version 匹配，否则版本滞后的用户永远收不到提示）+ 远程 `metadata.version` 语义化比较大于本地版本 + 下载地址非空，三者同时满足才提示。

* **版本号比较**：逐段取前导数字比较（`1.10.0 > 1.9.0`）；数字相同时无后缀的正式版大于带 `-` 后缀的预发布版（`2.0.0 > 2.0.0-beta`）。

* **启动提示弹窗**（`MainActivity`）：发现新版本时弹居中卡片弹窗（`dialog_update_tip.xml`，无跳转按钮），告知「我的 → 关于 → 检查更新」；可选「知道了」（下次启动再弹）或「今天不再提醒」（当天不再弹，日期记录于 `UpdatePrefs`）。

* **关于页**（`AboutDialog`）：居中卡片 Dialog（`dialog_about.xml`，与更新提示弹窗风格统一），替换原 AlertDialog。展示 App 信息（版本从 BuildConfig 读取为 `vX.X.X` 徽章）、检查更新入口、更新详情卡片（`update_details` 文案 + 新版本号）；内容区超过屏高 65% 时可滚动。

* **全局下载管理**（`ApkDownloadManager`）：应用级单例 + `StateFlow` 暴露下载状态，**下载不绑定弹窗生命周期**——「关于」弹窗关闭后下载在后台继续，重新打开弹窗自动恢复进度/完成态展示；下载中忽略重复触发。

* **断点续传**（`ApkDownloader`）：APK 下载到 `cacheDir/update/update.apk`，通过 Range 请求头从已下载字节处继续（206）；服务器不支持 Range 返回 200 时整体重下，断点越界（416）时作废断点重下；sidecar 文件 `update.url` 记录下载地址，远程更换更新包（URL 变化）时自动作废旧断点。

* **完整包复用**（sha256 锚定）：下载完成时将远程 `update_sha256` 写入锚点文件 `update.sha256`；进程重启后重新点「立即更新」时，本地完整 APK 的锚点与当前远程 sha256 一致则**零流量复用**直接进入安装校验，不一致（远程已换包）则作废重下——适配「URL 不变、只换包内容和 sha256」的发布流程，不会装到旧版本。远程未配置 sha256 时无锚点，保守走全量下载。

* **APK 双重校验**（`ApkVerifier`，下载完成后、安装前执行）：

  * **核心：签名证书比对**——下载 APK 的签名者证书 SHA-256 必须与硬编码常量 `EXPECTED_SIGNING_CERT_SHA256` 一致，锚定在本地代码中，远程配置被篡改也无法绕过；常量为空时跳过（换签名后需同步更新常量并发版）。证书值可通过 `keytool -printcert -jarfile` 或 logcat（TAG=ApkVerifier）获取。

  * **辅助：文件完整性**——远程配置了 `update_sha256` 时校验下载文件全量 SHA-256（流式计算），防传输损坏/被替换；未配置时跳过（旧配置兼容）。

  * **校验失败处理**：删除下载文件作废，状态置为 Failed 并提示具体原因（「安装包签名校验失败」/「安装包完整性校验失败」/「安装包无效或已损坏」），按钮变「重新下载」。

* **安装授权**：`REQUEST_INSTALL_PACKAGES` 权限 + Android 8.0+ 动态检查「安装未知应用」授权，未授权时引导跳转系统设置；APK 通过 `FileProvider` 共享给系统安装器。

* **缓存清理容错**：安装时 APK 文件已被系统清理（`cacheDir` 存储紧张时可能发生）则提示「安装包已被系统清理，请重新下载」并重置状态，不抛异常。

* **数据与展示分层**：远程配置拉取跟随权限缓存 24h 一次；版本比较每次启动读缓存本地判断，无额外网络开销。

## 播放器手势与锁定

| 手势           | 功能                            |
| ------------ | ----------------------------- |
| 双击屏幕         | 暂停/播放                         |
| 按住拖动（左右）      | 快进/快退（拖动中预览进度，抬手才真正 seek） |
| 按住拖动（左半屏上下） | 调节亮度                          |
| 按住拖动（右半屏上下） | 调节音量                          |

**手势机制**（`PlayerActivity` 内部 `dispatchTouchEvent` 实现）：

* 位移超过系统 touchSlop 即进入手势（无需长按 300ms），按轨迹角度锁定方向：
  ±30° 内判定为水平（进度），±60° 外判定为垂直（亮度/音量），30°~60° 模糊区继续等待，
  累计超 3 倍 touchSlop 仍模糊时按分量大小兜底——彻底消除斜划误判

* 水平拖拽只在拖动过程中更新预览进度条和数字（`mm:ss / mm:ss` 格式），
  不实时 seek（避免网络流卡顿）；手指抬起才真正 seek 一次并同步弹幕时间轴。
  满屏滑动 = 视频总时长灵敏度

* 垂直拖拽按绝对位移线性映射：每 300px 对应满量程亮度（±100%），每 100px 对应 1 档音量

* 所有自定义控件（返回/标题/PiP/旋转/设置/状态栏/锁定按钮）在拖拽过程中隐藏，
  复用屏幕锁定的只读进度条（`lockedProgressBar`，毫秒级精度随手指滑动）

* 16ms UI 节流（约 60fps），仅限制预览重绘频率，seek 抬手只执行一次

> **双击与控制器共存的约束**：`GestureDetector` 的双击判定整段写在 `ACTION_DOWN` 分支里，而 media3 `PlayerView` 的单击走 View 点击路径（`setClickable(true)` + `performClick()`）—— 所以**第一次点击必然把控制栏唤出**。推论：`dispatchTouchEvent` 中**任何「控制栏可见就不喂 DOWN」的提前 return 都会直接废掉双击**；正确做法是把「手势原点复位」与「喂 `gestureDetector`」放在所有 return 之前，若要保留「控制栏显示时不启动拖动手势」的原设计，改用一个**本次触摸的标记**在 MOVE 分支里拦。
>
> 同一处顺带解决：手势原点（`gestureStartX/Y`、方向锁、本次触摸标记）若在那些 return 之后才赋值，会出现「轻点一下再滑动」时拿陈旧原点算位移 → 表现为进度/亮度/音量瞬间跳变。

**屏幕锁定**：左侧中间显示锁定按钮，点击后：

* 隐藏播放器控制栏和弹幕控制

* 禁用所有手势（双击、拖拽滑动等）

* 显示只读进度条（屏幕底部，含时间位置和总时长）

* 点击屏幕只显示/隐藏锁定按钮和进度条

* 返回键和解锁按钮仍然可用

进度条拖动已与拖动手势解耦，在进度条上操作不会触发快进/快退手势。

播放器控制栏自定义：

* 删除上一集/下一集按钮

* 快进/快退统一为 10 秒

* 播放/暂停、快进、快退按钮使用自定义矢量图标

## Android TV 适配

同一份 APK 同时面向手机与 Android TV / 盒子，形态在运行时判定。**网络层 / Repository / ViewModel / 数据模型 / 播放器封装零改动**，适配范围严格限定在 UI 层与配置。

### 形态判定与方向策略

| 项 | 手机 | 电视 / 盒子 |
| ------- | ---------------------------- | --------------------------------------- |
| 判定 | 默认 | `TvUiSupport.isTelevision()`：`UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION` |
| 页面方向 | 竖屏页面跟随传感器（播放页恒横屏） | 全部锁横屏 |
| 布局来源 | `res/layout/`（竖屏）；播放页与横屏走 `res/layout-land/` | `res/layout-land/` |
| 导航 | 底部 `BottomNavigationView` | 顶部 `tvNavBar`（`item_tv_nav` 页签） |

* 方向在 `onCreate` 里按 `isTv` 设置 `requestedOrientation`，**再** inflate 布局。清单刻意**不写** `android:screenOrientation`、**不声明** `configChanges=orientation|screenSize`：清单分不出形态，写死方向会让手机竖屏被强制转横屏；声明 `configChanges` 则会在方向与 inflate 时配置不一致时（如手机横握冷启动）停在错的布局上。
* 形态入口：清单中 `android.software.leanback` 与 `android.hardware.touchscreen` 均声明为 `required="false"`，`MainActivity` 同时注册 `LAUNCHER` 与 `LEANBACK_LAUNCHER`，并提供 `android:banner`。
* ⚠️ **`res/layout-land/` 不是 TV 专属目录** —— 播放页对所有设备强制横屏，手机播放页与手机横屏吃的就是这一份。改动必须两个形态一起验算。

### 10-foot UI：靠密度而非改布局

`TvUiSupport.wrapContext()` 在各 Activity 的 `attachBaseContext` 中调用，电视上把 `densityDpi` 放大 **1.45 倍**，dp 画布随之缩小，等于把布局内所有 dp/sp 尺寸（字号、按钮、间距、卡片）等比放大。这样无需逐页改写布局，手机端也就不会被改坏。

* 实测：1080p 电视 `densityDpi ≈ 320` → 画布 960dp；放大后 ≈662dp，正文 14sp 渲染约 41px，符合 3 米视距下的可读性要求。
* ⚠️ 改 `densityDpi` **必须同步重算** `screenWidthDp` / `screenHeightDp` / `smallestScreenWidthDp`，否则会留下「物理 1080p、密度 464、却仍声称宽 960dp」这种自相矛盾的 Configuration，任何读 `screenWidthDp` 的代码（含资源限定符匹配）都会拿到错值。
* ⚠️ 密度放大对**纯 px 直算的自绘 View 无效**（如 `DanmakuView`），这类尺寸必须自己按形态分档 —— 见下方「弹幕字号随形态自适应」。
* ⚠️ **高度方向的预算比宽度紧张得多**：720p / `densityDpi 213` 的电视放大后纵向只剩 `720 / 1.925 ≈ 374dp`，扣掉顶部 `tvNavBar` 只剩约 329dp 正文画布，而一张网格卡片行就要 155dp（封面按手机竖屏公式 `150dp × 3 / span` 算）。所以横屏页面上**任何常驻条带都在和 `weight=1` 的网格争高度**：搜索页曾同时有「标题行 74dp + 摘要行 25dp + 分页栏 INVISIBLE 占位 68dp」三条常驻，把网格压成恰好 1.04 行 —— 症状是只显示第一行结果，按下键换行时才滚出下一行。取舍口径：常驻条带能并进标题行的就并进去（摘要已并入标题行右侧）；必须留在底部又不能占高的就改**覆盖层**（`layout_gravity="bottom"` 叠在网格区），需要避让时按覆盖层实测高度给网格临时补 `paddingBottom`（`clipToPadding=false`，只改滚动停靠位、不改视口高度，因此不会上下跳）。取证手法：`adb shell uiautomator dump` 读各节点 bounds，比读代码猜可靠。

### 遥控器焦点体系（`TvFocus`）

电视没有触摸屏，所有交互依赖方向键 + 确定键，因此「可聚焦 + 明确视觉反馈 + 复用可还原」三件事必须成体系地解决。`presentation/tv/TvFocus.kt` 是**全 App 焦点适配的唯一入口**：

| 方法 | 用途 |
| -------------------------------------------------------- | ------------------------------------ |
| `applyTo(view, scale)` | 让可点击控件可聚焦 + 挂焦点环 + 获焦缩放（列表条目根、按钮） |
| `applyFocusableOnly(view, ringRes)` | 只挂焦点环，不改动聚焦能力 / 缩放 / 监听（已可聚焦的控件） |
| `setFocusable(view, enabled, scale)` | 按显隐**重设聚焦能力**；`false` 时清掉聚焦能力并还原外观 |
| `attachFocusRing` / `resetAppearance` | 挂环 / 还原（RecyclerView 复用必须还原，避免多项同时放大） |
| `requestInitialFocus` / `focusFirstItem` / `scrollIntoViewOnFocus` | 初始焦点、首个条目、获焦自动滚入可视区 |
| `applyToClickables` / `applyToDialogButtons` / `collectClickableViews` | 弹窗与容器级通用遍历 |
| `neutralizeCardFocusStroke` | 抹掉 Material3 卡片自带的白描边获焦态 |

**焦点环规范**：全 App 单圈 **3dp 描边**（圆角 14dp）。普通底色用品牌橙 `shape_tv_focus_ring`；**品牌橙底控件必须换纯白环** `shape_tv_focus_ring_light`（橙压橙对比度约 1.1:1，等于没有焦点框）；顶部导航页签用 `bg_tv_nav_focus`（inset 药丸形 —— 满屏宽贴边描边会糊成一整行大方框）。

**三条必须遵守的规则**：

1. **门控下沉到工具类入口**：`TvFocus` 每个公开方法首行 `if (!isActive(view)) return`，手机端一律 no-op。门控放在工具类而不是各个调用点，才能让 40+ 处调用一处覆盖、永不遗漏。
2. ⚠️ **门控挡不住调用点直接赋值**：适配器里写 `btn.isFocusable = shown` / `btn.isFocusableInTouchMode = shown` 会绕过门控 —— 而 `focusableInTouchMode = true` 在触屏上的语义是「触摸也把焦点交给该控件」，且**取焦点的这一次点击不触发 click**，手机上的表现就是**按钮要点两下**。凡「按显隐重设聚焦能力」一律走 `TvFocus.setFocusable()`。
3. **`res/layout-land/` 里不得写死** `focusable` / `focusableInTouchMode` / `foreground=bg_tv_focus_ring`：电视端由代码 `TvFocus.applyTo(root)` 赋予，写死在 XML 里对电视是冗余、对手机横屏是污染。自检：`grep -rn 'focusableInTouchMode="true"' app/src/main/res/layout-land/` 应只剩 `EditText`。

**焦点遍历的判据不是「是否 `isFocusable`」，而是「有没有被 `TvFocus` 处理过」**：处理时会在 View 上打 `tag_tv_focus_handled` 标记（声明在 `res/values/ids.xml`）。原因是 `MaterialButton` 与 XML 里写了 `focusable="true"` 的条目行，其 `isFocusable` 恒为 true；用「已可聚焦就跳过」的老判据会整批漏掉，而 `defaultFocusHighlightEnabled` 已被关成 false → 症状是「焦点能停上去、屏幕上毫无变化」。

### 电视顶部导航与焦点移交

电视端导航换成顶部横向 `tvNavBar`（`layout-land/activity_main.xml`，条目 `item_tv_nav.xml`：图标 20dp + 文字 13sp + 底部橙色指示条），采用**焦点即选中**：只有当前页签可聚焦，`MainActivity.dispatchKeyEvent` 接管左右键切换页签。

⚠️ **遥控器按键只投递给持有焦点的那个视图，且不冒泡到父容器** —— 所以跨容器 / 跨页面的焦点移交必须写在 `Activity.dispatchKeyEvent`。也不能指望 `RecyclerView.focusSearch()`：它把候选限制在自身子树内，「网格最后一行按下键」既找不到候选、也翻不出容器。为此定义了两个页面侧接口：

| 接口 | 作用 |
| ---------------------------------------------- | ------------------------------------------------------- |
| `TvInitialFocusProvider.tvInitialFocusView()` | 内容页向顶部导航暴露「下键时接收焦点的首个控件」，由 `MainActivity` 显式移交 |
| `TvContentKeyHandler.onContentDirectionKey(direction)` | 内容页对方向键的兜底接管（如从结果网格翻到网格外的分页栏） |

⚠️ 两个接口都**必须实时返回当前有效视图，禁止缓存** —— `ViewPager2` 会复用 Fragment，`onViewCreated` 只在首次调用，缓存会在切页后过期。

### 手机扫码搜索

电视遥控器输入片名极其不便，搜索页在电视端提供「手机扫码 → 手机输入 → 电视搜索」的通道：

```text
SearchFragment.prepareQrCode()
        ↓ ① TvSearchServer.getLocalIpAddress() 取局域网 IP
        ↓ ② QrCodeGenerator.generate(url, 512)（ZXing）
电视搜索页右栏显示二维码 http://<ip>:8234/search.html
        ↓ 局域网 IP 取不到 → 隐藏二维码，服务器本次会话内不再启动
手机扫码打开网页并提交关键词
        ↓ POST /api/search
TvSearchServer 回调 → SearchFragment 执行搜索
```

* `TvSearchServer` 是纯 `ServerSocket` 实现的轻量 HTTP 服务，固定端口 **8234**，提供 `GET /search.html`、`POST /api/search`、`GET /api/last_query`。
* **生命周期严格跟着二维码可见性**：只有二维码可见且搜索页处于 resumed 时才启动服务器，离开页面或隐藏二维码立即 `stop()` 释放端口（`updateSearchServerState()` 是二维码可见性的唯一改动点）。

### 电视端播放页交互

播放页在电视上先**把焦点关干净**（`playerView` 设 `FOCUS_BLOCK_DESCENDANTS`，顶部四键 `isFocusable = false`），再全部走 `dispatchKeyEvent`：

| 按键 | 行为 |
| ------- | ------------------------------- |
| 确定键 | 播放 / 暂停 |
| ← / → | 快退 / 快进 10 秒 |
| ↓ | 唤出控制栏（进度条） |
| 长按（`repeatCount > 0`） | 一律吞掉，不重复触发 |

**隐藏按钮 ≠ 隐藏功能**：`isFocusable = false` 只让按钮点不到，按钮**仍画在屏幕上**。电视上把「画中画 / 屏幕旋转 / 锁定」三个按钮直接置 `GONE`，并给对应点击逻辑加守卫。⚠️ 锁定按钮的 visibility 会被控制栏的 `ControllerVisibilityListener` 动态接管，**置 `GONE` 的同时必须给那段逻辑加 `!isTv` 守卫**，否则控制栏一显一隐它就被拉回来。

> ⚠️ 画中画按钮与代码均额外用**能力判据**守卫（`pipSupported`），因为电视虽为 Android 12（满足 `SDK >= O`）却并不支持 PiP —— 按版本判是错的。

### 电视缺失的系统能力

电视 / 盒子是「裁剪过的 Android」，部分系统服务会**直接返回 null** 或能力缺失。已实测的崩溃与处理：

| 能力 | 电视表现 | 处理 |
| ---------------------- | --------------------------- | ------------------------------------------------------------ |
| 电池 `BATTERY_SERVICE` | 接市电无电池，部分 ROM **返回 null** | `as BatteryManager` 直接崩（**Kotlin 的 `as` 对平台类型相当于 `!!`**）→ 判据 `batterySupported` = 非 TV **且** 服务在 **且** `getIntProperty(CAPACITY) in 0..100`；为 false 时不读电量、不注册 `ACTION_BATTERY_CHANGED`、电量图标 `GONE` |
| 画中画 | 系统不支持 | `pipSupported = SDK >= O && hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)`；按钮点击、`RemoteAction` 接收器注册、`setPictureInPictureParams()` **三处统一守卫** |
| 屏幕旋转 | 无意义 | 旋转按钮 `GONE` + 点击守卫 |
| 锁屏 | 无触屏，锁定后无法解锁 | 锁屏按钮 `GONE` + `setupLockButton()` 整段不装配 |
| 软键盘 `INPUT_METHOD_SERVICE` | 多数没有软键盘 | `as?` + 早返回 |
| 安装未知应用 | 很多 ROM 裁掉该设置页 / 无系统安装器组件 | `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` 与安装 Intent 一律 `runCatching`；`ApkInstaller` 改为返回 `Boolean`，关于页按返回值给出「去系统设置授权」或「用 U 盘手动安装」的差异化提示 |

⚠️ **别只写 `isTv` 判断**：部分盒子不上报 `UI_MODE_TYPE_TELEVISION`。能力缺失与形态判定是两件事，要按**能力**判。

反向地，`NotificationManager` / `PowerManager` / `AudioManager` / `ConnectivityManager` 是系统绑定服务，**任何 Android 设备上都有**，保留 `as` 强转即可，不必加一堆空安全调用制造噪音。

> 排查手法：把调用点列成清单，逐条问三件事 —— **这个能力电视有没有？没有的话走到这里会不会崩？崩之前 UI 上有没有它的入口？**

### 弹幕字号随形态自适应

弹幕是**纯 px 直算**的自绘 View，不走 density 体系（`TvUiSupport` 的放大对它无效），因此字号单独按设备分档：

```kotlin
val rowsRatio = if (isTvDevice) 0.13f else 0.16f          // 电视 ≈5 行 / 手机 ≈4 行
val baseTextSize = minOf(viewWidth / 35f, viewHeight * rowsRatio).coerceIn(18f, 50f)
```

* 横向约束「单行不臃肿」、纵向约束「行数预算」，取小者；只有横屏（宽而矮）才会触发纵向约束，竖屏恒由横向胜出。
* ⚠️ **必须按设备身份分档，不能只靠屏幕像素**：手机横屏与电视横屏像素尺寸高度重合（2340×1080 vs 1920×1080，弹幕容器同为屏高 25% ≈ 270px），物理字高却差近 10 倍，纯几何公式必然误伤。
* ⚠️ 系数要留取整余量：`maxRows = (viewHeight / rowHeight).toInt()` 是**向下取整**，`0.167 × 270 → 3.99 → 3 行`（正好踩空），故 4 行取 `0.16f`。

### 电视端页面形态清单

| 页面 | 电视 / 横屏形态 |
| -------- | ------------------------------------------------------------ |
| 主页面 | 顶部 `tvNavBar` 替代底部导航 |
| 首页 | 网格列数按 120dp 基准重算（电视约 5 列） |
| 搜索结果 | 两态互斥：**输入态** = 左搜索区 + 右栏二维码伴侣；**结果态** = 整页接管（标题行含关键词 + 摘要 + 「重新搜索」，摘要不单独占行）+ 首页式网格。网格区是一层 `FrameLayout`，分页栏以覆盖层贴在底部（不占网格高度），下键走到最后一行由 `TvContentKeyHandler` 接管 → 掀起分页栏并按其高度给网格补 `paddingBottom`，把最后一行抬到分页栏上方 |
| 详情页 | 左右分栏：左「影片信息」**纯展示不可聚焦**，右上「线路 / 选集」+ 右下三张信息卡；主操作行在电视端整行 `GONE`（播放入口即选集网格）；无播放线路时整张线路卡 `GONE` |
| 我的 | 左右 2:3 分栏；下载管理入口在电视端剔除 |
| 播放历史 | 紧凑操作条 + 网格列表 |
| 播放页 | 隐藏电量 / 画中画 / 旋转 / 锁屏，按键全走 `dispatchKeyEvent` |
| 下载管理 | **仍是竖屏形态**（`res/layout-land/` 尚未提供 `activity_download.xml`） |

> 纯只读区块（无点击、无交互）**不要给它焦点** —— 既浪费按键，又会霸占初始焦点兜底落点。

### 弹窗尺寸与横屏分栏

弹窗宽度统一走 `presentation/dialog/DialogSizing.kt`，**以屏幕短边为基准**：`min(屏宽 × 0.88, 屏高 × 0.90, 460dp)`。原因是横屏（电视 / 手机横屏）「宽而矮」，按屏宽百分比定宽会得到 596dp 宽的扁条。

视频源管理 / 清理缓存 / 帮助三个弹窗在横屏下改为**左右分栏**（左侧内容区 + 右侧固定 132dp 按钮栏），宽度单独放宽为 `min(屏宽 × 0.80, 屏高 × 1.55, 620dp)` —— 第二个约束**不是高度约束，而是宽高比上限**。⚠️ 两份布局（`layout/` 与 `layout-land/`）的 **id 集合必须完全一致**，否则 ViewBinding 取限定符并集时字段会退化成可空。详见 [`UI 视觉统一规范文档.md`](./UI%20视觉统一规范文档.md)。

## 下载管理

完整的离线下载功能，支持 M3U8 分片下载、弹幕下载和离线播放。

### 下载流程

```text
DetailActivity 选择剧集
        ↓
DownloadViewModel.createTasks() ── 写入数据库
        ↓
DetailActivity.startDownloadForEpisodes()
        ↓ 解析 playPageUrl → m3u8Url
DownloadEngine.submitTask()
        ↓ 解析 M3U8、并发下载分片
DownloadService ── 前台通知显示进度
        ↓ 合并分片为 mp4
DanmakuDownloadManager ── 下载弹幕
        ↓
数据库更新状态和进度
        ↓
DownloadActivity ── 下载管理页面（下载中/已完成标签页）
```

### 下载管理页面

| 标签页 | 内容                          |
| --- | --------------------------- |
| 下载中 | 待下载、下载中、暂停、失败的任务，显示进度条和百分比  |
| 已完成 | 已下载完成的视频，显示文件大小、播放进度、可播放/删除 |

### 离线播放

* 点击已完成列表中的视频，使用本地 mp4 文件播放

* 支持弹幕离线播放（本地弹幕 JSON 文件）

* 弹幕统一由播放器加载流水线处理（任务弹幕文件 → 弹幕源索引文件回退 → 在线搜索），在线搜索成功后写回数据库；本地弹幕已加载时，在线搜索失败不会影响已显示的弹幕

* 弹幕下载成功后回写 animeId 关联到弹幕偏好，离线播放开关打开时直接命中本地文件，全程零联网

* 独立播放进度（百分比显示：未观看 / 已观看 N% / 已看完）

* 续播：退出后重新进入自动从上次进度继续

* 已看完后点击播放从头开始

* 离线播放不记录到历史记录页面

### 加密流支持（AES-128）

* `M3u8Parser` 解析 `#EXT-X-KEY`，提取密钥地址（key URI）与显式 IV；无显式 IV 时按 RFC 8216 使用分片序号作为默认 IV

* 解析到加密流后，`DownloadEngine` 先下载 16 字节密钥，每个分片整体下载后执行 **AES-128-CBC 解密**再写盘，合并产物为明文 TS/MP4，可直接离线播放

* 加密流分片无法断点续传（密文整片处理），暂停恢复时该分片重新完整下载

* 不支持的加密方式明确拒绝并提示原因：`SAMPLE-AES`（帧内加密）、包含多个不同密钥的流

* 密钥请求带 `Referer` 头（指向密钥同目录），兼容部分源站的防盗链校验

* **解密结果校验**：首分片解密后校验明文容器头（MPEG-TS 同步字节 `0x47` / MP4 `ftyp`），key 或 IV 与源不匹配时解密会静默产出乱码（AES-CBC 通常不抛异常），校验失败直接判下载失败并提示「解密结果无效（密钥或 IV 与源不匹配）」

* **fMP4/CMAF 流拦截**：检测到 `#EXT-X-MAP`（init 段单独声明，当前引擎无法正确拼接）时拒绝下载并提示原因

### 降低影响下载策略

为保护源站 Web 服务器和 CDN，设计了分层限流策略：

| 策略       | 配置       | 说明                      |
| -------- | -------- | ----------------------- |
| 最大并发任务数  | 3        | 同时最多下载 3 个视频            |
| 最大并发分片数  | 3        | 每个任务同时最多 3 个线程下载 .ts 分片 |
| 分片间延迟    | 2000ms   | 每个分片下载完成后等待 2 秒         |
| 下载速度限制   | 2MB/s    | 单线程下载速度上限，不影响手机正常上网     |
| 剧集间解析间隔  | 3\~5 秒随机 | 批量下载时模拟人工逐集点击           |
| 下载全部复用缓存 | 已实现      | 使用详情页缓存的剧集列表，不重复请求      |

## 清理缓存

"我的" → "清理缓存"提供美观的自定义弹框，支持选择性清理：

| 选项       | 清理内容                   |
| -------- | ---------------------- |
| 清理搜索缓存   | 爬虫搜索缓存 + 本地搜索历史        |
| 清理首页缓存   | 首页列表缓存数据               |
| 清理详情页缓存  | 详情页元数据                 |
| 清理播放地址缓存 | 真实播放地址 + 首个播放页缓存       |
| 清理弹幕缓存   | 本地弹幕 JSON 文件 + 弹幕源选择记录 |
| 清理全部缓存   | 以上所有（保留下载的视频和弹幕）       |

弹框顶部显示当前缓存总大小（自动计算 Room 数据库 + SharedPreferences + 图片缓存）。

## 爬虫限流机制

每个播放源持有独立的 `RequestRateLimiter` 实例，独立管理自己的请求队列和限流策略。

### 设计要点

* **单源独立**：每个播放源一个 `RequestRateLimiter` 实例，互不影响。

* **最小间隔**：同一源下两次实际网络请求之间至少间隔 3 秒。

* **队列容量**：最大同时持有 3 个未完成任务（含正在执行和等待中的）。

* **优先级抢占**：新任务入队时，取消队列中所有优先级 ≤ 自身的旧任务（包括已开始执行的）。

* **优先级等级**：`SEARCH(3) > DETAIL(2) > PLAY(1)`，搜索最高，播放最低。

### 取消行为

| 任务状态            | 处理方式                                        |
| --------------- | ------------------------------------------- |
| 未开始（等待中）        | 直接从队列移除，调用方收到 `CancellationException`       |
| 已开始（HTTP 请求已发出） | 通过 OkHttp `Call.cancel()` 终止网络层，但仍占用 3 秒间隔槽 |

### 调用点优先级分配

| 调用场景      | 优先级      | 说明               |
| --------- | -------- | ---------------- |
| 首页爬取 / 搜索 | `SEARCH` | 用户主动触发的搜索行为优先级最高 |
| 详情页解析     | `DETAIL` | 搜索结果点击后获取详情      |
| 播放页解析     | `PLAY`   | 获取真实播放地址优先级最低    |

## 技术栈

| 类型      | 技术                                                                          |
| ------- | --------------------------------------------------------------------------- |
| 开发语言    | Kotlin 2.0.0                                                                |
| 构建工具    | Gradle 8.9（腾讯镜像）、Android Gradle Plugin 8.5.0、KSP 2.0.0-1.0.21            |
| 最低版本    | minSdk 24                                                                   |
| 目标版本    | targetSdk 36                                                                |
| UI      | XML Layout、ViewBinding、Material Components、RecyclerView、CardView、ViewPager2 |
| 双形态布局   | `res/layout/`（手机竖屏）+ `res/layout-land/`（横屏：电视 + 手机横屏共用），密度放大约 1.45 倍实现 10-foot UI |
| 架构      | MVVM + Repository + Data Source                                             |
| 异步      | Kotlin Coroutines、LiveData、Flow                                             |
| 本地存储    | Room 2.6.1                                                                  |
| 播放器     | AndroidX Media3 ExoPlayer 1.4.0                                             |
| 图片加载    | Coil 2.7.0                                                                  |
| JSON 解析 | Moshi 1.15.1、org.json                                                       |
| 网络与解析   | OkHttp 4.12.0、Jsoup                                                         |
| 代码生成    | KSP                                                                         |
| 二维码生成   | ZXing 3.5.3（电视端「手机扫码搜索」）                                                      |

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

* 点击首页影视：跳转搜索页，自动填入影视名并搜索。

* 搜索页点击返回：回到首页，并保留首页滚动位置、Tab、二级分类和已加载数据。

* 手动点击底部搜索按钮：搜索页恢复初始状态，只显示搜索历史，不保留上一次搜索框内容和搜索结果。

* 手动点击"我的"按钮后再回首页：首页状态不重置。

* 首页点击返回：第一次提示 `再按一次退出应用`，短时间内第二次返回才退出。

## 缓存策略

缓存统一写入 Room 表 `api_cache`，通过 `ApiCacheRepository` 读写。只缓存网络爬取结果，不缓存本地挡板结果。

| 数据类型          | 缓存键前缀                                                               | 缓存时长               | 说明                           |
| ------------- | ------------------------------------------------------------------- | ------------------ | ---------------------------- |
| 首页全部豆瓣内容      | `home:tab:all:v1`                                                   | 1 天                | 豆瓣内容发现成功后缓存                  |
| 首页电影分页        | `home:tab:movie:v1:`                                                | 首页 1 天，后续页跟随首页剩余时间 | 同一电影分类分页一起过期                 |
| 首页电视剧/动漫/综艺分页 | `home:tab:tv_related:v1:`                                           | 首页 1 天，后续页跟随首页剩余时间 | 电视剧、动漫、综艺默认页会一起预缓存           |
| 搜索结果页         | `crawler:search:v3` / `yinghua:search:v3`                           | 1 天                | 各源独立缓存，同一关键词下后续页跟随首页剩余时间     |
| 详情页首个播放页链接    | `crawler:detail:first_play_page` / `yinghua:detail:first_play_page` | 1 天                | 各源独立缓存                       |
| 真实播放地址        | `crawler:play:real_url` / `yinghua:play:real_url`                   | 30 分钟              | `.m3u8` / `mp4` 可能带短时效 token |
| 弹幕搜索          | `search_{keyword}`                                                  | 1 天                | SharedPreferences 存储         |
| 弹幕分集          | `bangumi_{animeId}`                                                 | 1 天                | SharedPreferences 存储         |
| 弹幕列表          | `comments_{episodeId}`                                              | 1 天                | SharedPreferences 存储         |

本地 `assets/sample_video_source.json` 仍作为首页和分类的兜底挡板。豆瓣失败时可以回退本地挡板，但回退结果不写入 `api_cache`。

多源缓存隔离：所有爬虫相关缓存键都包含源标识前缀（`crawler` / `yinghua`），确保不同源的缓存互不干扰。

爬虫请求失败时按**错误类型写入负缓存**（`NEG_TYPE_*`），避免反复发无效请求：搜索结果为空 → 1 天；HTTP 5xx 服务端错误 → 1 小时；HTTP 4xx 客户端错误 → 1 天；连接超时 / 网络不可达 → 1 小时；WebView 引擎不兼容导致 CF 过盾失败 → 1 小时（升级系统 WebView 后自愈）。

## 数据存储

Room 当前持久化五张表：

| 表名                       | Entity                       | 用途                             |
| ------------------------ | ---------------------------- | ------------------------------ |
| `play_history`           | `PlayHistoryEntity`          | 播放历史、播放地址冗余、续播进度、总时长、**播放源名称** |
| `search_history`         | `SearchHistoryEntity`        | 最近搜索关键词、搜索次数、最后搜索时间            |
| `api_cache`              | `ApiCacheEntity`             | 网络响应和解析结果缓存，支持 TTL 过期          |
| `download_task`          | `DownloadTaskEntity`         | 下载任务状态、进度、本地文件路径、弹幕状态、离线播放进度   |
| `downloaded_video_index` | `DownloadedVideoIndexEntity` | 已下载视频索引（预留）                    |

数据库版本：`1`（无迁移，应用重装即重建）。

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
│   │   ├── download/
│   │   ├── entity/
│   │   ├── model/
│   │   │   ├── danmaku/
│   │   │   └── remote/
│   │   ├── repository/
│   │   └── source/
│   │       └── impl/
│   └── presentation/
│       ├── activity/
│       ├── adapter/
│       ├── challenge/     ← Cloudflare 人工验证兜底窗口
│       ├── danmaku/
│       ├── dialog/        ← DialogSizing / ConfirmDialog / EpisodeSelectDialog
│       ├── fragment/
│       ├── help/          ← HelpDialog
│       ├── settings/
│       ├── source/
│       ├── tv/            ← TV 适配（TvUiSupport / TvFocus / TvSearchServer / QrCodeGenerator …）
│       ├── update/
│       └── viewmodel/
└── res/
    ├── color/
    ├── drawable/
    ├── layout/           ← 手机竖屏
    ├── layout-land/      ← 横屏（电视 + 手机横屏共用，**非 TV 专属**）
    ├── menu/
    ├── values/
    ├── values-night/
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

Release 包签名使用根目录的 `MovieStore_key.jks`（已 gitignore），**v1 + v2 双签**：`ApkVerifier` 校验走 v1（`GET_SIGNATURES` 仅支持 v1），而 minSdk 24 时 AGP 默认关闭 v1，会导致应用内更新误报「无法解析签名」。

> ⚠️ `:app:assembleRelease` 在**命令行**会失败于 `packageRelease`（keystore 密码取自环境变量或默认值，命令行环境下读不到），与代码改动无关 —— 正式包请在 Android Studio 中构建。

## 权限说明

| 权限                                                | 用途                                    |
| ------------------------------------------------- | ------------------------------------- |
| `android.permission.INTERNET`                     | 访问豆瓣、播放源网站、封面图片和播放地址                  |
| `android.permission.ACCESS_NETWORK_STATE`         | 判断网络状态，配合网络播放与远程数据源                   |
| `android.permission.FOREGROUND_SERVICE`           | 下载时保持前台服务运行                           |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ 前台服务类型声明                  |
| `android.permission.POST_NOTIFICATIONS`           | Android 13+ 下载通知权限                    |
| `android.permission.WAKE_LOCK`                    | 下载时保持 CPU 唤醒                          |
| `android.permission.REQUEST_INSTALL_PACKAGES`     | 应用内更新安装 APK（Android 8.0+ 需「安装未知应用」授权） |

## 设备形态声明

清单不申请任何额外敏感权限，仅通过 `uses-feature` 声明双形态能力（均为 `required="false"`，不会把手机或电视排除在安装范围之外）：

| 声明                                | 取值                 | 含义                    |
| --------------------------------- | ------------------ | --------------------- |
| `android.software.leanback`       | `required="false"` | 声明支持 Android TV 形态    |
| `android.hardware.touchscreen`    | `required="false"` | 声明不依赖触摸屏（电视无触摸输入）     |

配套清单项：`MainActivity` 同时注册 `LAUNCHER` 与 `LEANBACK_LAUNCHER` 入口，`application` 提供 `android:banner`（电视启动器横幅图 `tv_banner`），播放页声明 `supportsPictureInPicture="true"`（运行时仍按能力守卫）。

## 当前版本说明

* 版本号：`1.3.0`（`versionCode = 1`）

* applicationId：`com.hpu.mymoviestore`

* 设备形态：手机 + Android TV / 盒子（同一 APK）

* compileSdk：`36`

* minSdk：`24`

* targetSdk：`36`

* Java / Kotlin JVM Target：`17`

## 后续可扩展方向

* 增加更多播放源（只需继承 `CrawlerVideoSource` 并实现两个解析方法，在 `MovieApplication` 注册）。

* 增加首页下拉刷新，用于主动刷新已过期或手动清空的发现缓存。

* 增加收藏功能。

* 补齐电视端页面形态：`layout-land/` 目前仍缺 `activity_history.xml` / `activity_download.xml`（下载管理页在电视上仍为竖屏布局）。

* 弹幕字号设置项（小 / 中 / 大）：当前字号按「设备 × 形态」自动分档，参数算得再准也不如让用户自己调，换设备或换视距都不必再改代码。

### 应用内更新：已确认暂缓的优化项

以下三个问题已讨论确认，当前行为可接受（各有兜底机制），实现时按此方案推进：

**1. 完成未安装的 APK 无生命周期管理**

下载完成但用户一直未安装的 APK（`cacheDir/update/update.apk`，几十 MB）无主动清理机制（无 TTL、无启动检测、无安装成功删除）。现状兜底：固定文件名死文件最多一份；sha256 锚定复用使其在远程未换包期间可零流量复用。建议方案：安装成功或启动时检测本地版本已 ≥ 该更新包版本 → 删除 APK 及 sha256 锚点（删除时须同步删 `update.sha256`，`invalidateShaMeta()` 已提供作废方法）。

**2. 后台下载无保活**

APK 下载只是 `ApkDownloadManager` 单例内的内存协程，无前台服务/通知，进程被杀下载即断、无通知栏进度。现状兜底：断点续传 + sha256 锚点复用，重下损失可控。建议方案：参考 `DownloadService` 模式增加前台服务 + 进度通知（注意 Android 14+ 前台服务类型限制及应用现有 `dataSync` 类型）；保持 `ApkDownloadManager` 的 StateFlow 订阅接口不变。

**3. 清理缓存统计/清理范围不一致**

~~缓存大小计算包含整个~~ ~~`cacheDir`，但「清理全部缓存」不删~~ ~~`cacheDir/image_cache/`（Coil 图片缓存）~~ 已解决：清理全部缓存现在通过 `Coil.imageLoader(ctx).diskCache?.clear()` 清理图片磁盘缓存，并修复了统计中 WebView 目录被重复计算的问题；Coil 磁盘缓存上限也从默认 250MB 收敛到 128MB。剩余问题：「清理全部缓存」仍不删 `cacheDir/update/`（APK 更新包），用户会看到清理后大小对不上。建议方案（三选一或组合）：① 清理全部时纳入 `cacheDir/update/`，但下载中（`ApkDownloadManager.isDownloading`）跳过；② 清理缓存弹框增加独立选项「清理更新安装包」；③ 大小统计单独标注「含更新安装包 XX MB」不纳入一键清理。

### 发版流程纪律（持续有效）

* 每次发版远程 JSON 的 `version` + `update_sha256` 必须同步更新（`update_sha256` 忘改且 URL 未变 → 复用机制会命中旧包）。

* `update_sha256` 建议视为必填项（空值 = 复用失效 + 完整性校验失效，两道防线同时失效）。

* 换签名（keystore 丢失重建）时必须同步更新 `ApkVerifier.EXPECTED_SIGNING_CERT_SHA256` 常量并发版。

