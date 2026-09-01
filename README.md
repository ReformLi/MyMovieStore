# MyMovieStore

`MyMovieStore` 是一个使用 Kotlin 开发的 Android 原生影视浏览与播放应用。应用将首页推荐和播放链路拆成两个相对独立的层：内容发现层负责从豆瓣页面发现影视内容，内容播放层负责聚合多个播放源搜索可播放资源、展示详情并播放视频。

## 功能概览

| 模块       | 当前能力                                     | 主要实现                                                                           |
| -------- | ---------------------------------------- | ------------------------------------------------------------------------------ |
| 首页内容发现   | 九宫格展示豆瓣影视内容，支持全部、电影、电视剧、综艺、动漫分栏          | `HomeFragment`、`DoubanDiscoverySource`、`VideoAdapter`                          |
| 首页电影     | 支持全部、华语、欧美、韩国、日本二级分类，底部加载更多              | `DoubanDiscoverySource.fetchExploreMoviePage()`                                |
| 首页电视剧    | 支持综合、国产剧、欧美剧、日剧、韩剧、纪录片二级分类               | `DoubanDiscoverySource.fetchExploreTvRelatedPage()`                            |
| 首页综艺     | 支持综合、国内、国外二级分类                           | `DoubanDiscoverySource.fetchExploreTvRelatedPage()`                            |
| 首页动漫     | 使用豆瓣电视剧页中的动画数据，不展示二级分类                   | `DoubanDiscoverySource.fetchExploreTvRelatedPage()`                            |
| 搜索       | 多源并行搜索，结果插空法排序显示，支持分页、搜索历史和结果缓存          | `SearchFragment`、`VideoRepository.searchVideosPage()`                          |
| 多源播放     | 支持剧集屋、樱花动漫、电影天堂等多个播放源，可独立启用/禁用           | `ProfileFragment` 视频源管理                                                        |
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
| 细粒度错误提示  | 网络失败时展示具体错误原因（DNS 失败、403、验证码、空结果等）       | `CrawlError`、`CrawlErrorType`                                                  |

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
JujiwuVideoSource     ── 剧集屋（www.******.com）
YinghuaVideoSource    ── 樱花动漫（www.******.com）
TiantangVideoSource   ── 电影天堂（www.******.com）
```

新增播放源只需：

1. 继承 `CrawlerVideoSource`
2. 配置 `sourceId`、`sourceName`、`baseUrl` 等属性
3. 实现 `parseVideoDetail()` 和 `parseSearchPage()` 两个解析方法
4. 在 `MovieApplication` 中注册实例

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

## 弹幕系统

播放器内置弹幕功能，支持以下能力：

| 功能       | 说明                                                                                                                  |
| -------- | ------------------------------------------------------------------------------------------------------------------- |
| 弹幕搜索     | 根据视频标题自动搜索弹幕源，支持多源返回                                                                                                |
| 弹幕源切换    | 底部控制栏显示弹幕源（如 `弹幕源 tencent`），点击下拉选择不同番剧                                                                              |
| 弹幕开关     | 独立于"我的"页面总开关的播放器子开关                                                                                                 |
| **本地优先** | **弹幕已下载/已缓存时零联网加载；本地文件 → 弹幕缓存 → 联网级联三级优先链**                                                                         |
| **远程权限** | **弹幕联网由远程配置** **`switches.enable_danmaku`** **控制（app\_name/version 匹配才生效）；权限关闭时不做任何联网获取，无论开关状态只显示「弹幕已关闭」；获取失败默认放行** |
| 弹幕同步     | 弹幕与播放进度实时同步，支持 seek 后重新对齐                                                                                           |
| 弹幕缓存     | 搜索、分集、弹幕列表均缓存 1 天，统一过期时间；空弹幕列表不写缓存                                                                                  |
| 失败重试     | 弹幕接口网络失败自动重试最多 3 次，间隔 10 秒，单次请求超时 15 秒；服务端错误自动重试，业务空结果不重试                                                           |

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
| 长按 + 左右滑动    | 快进/快退（暂停播放，进度条和毫秒级时间实时跟随手指滑动） |
| 长按 + 左半屏上下滑动 | 调节亮度                          |
| 长按 + 右半屏上下滑动 | 调节音量                          |

**手势机制**（`PlayerActivity` 内部 `dispatchTouchEvent` 实现）：

* 长按 300ms 触发手势方向锁定，立即执行对应调节（不浪费 MOVE 事件）

* 水平拖拽时自动暂停播放，进度条和数字（`mm:ss / mm:ss` 格式）实时跟随手指；
  满屏滑动 = 视频总时长灵敏度，手指抬起后恢复播放（仅当拖拽前正在播放）

* 垂直拖拽每 100px 对应 1 档音量，每 300px 对应 ±100% 亮度

* 所有自定义控件（返回/标题/PiP/旋转/设置/状态栏/锁定按钮）在拖拽过程中隐藏，
  复用屏幕锁定的只读进度条（`lockedProgressBar`，毫秒级精度随手指滑动）

* 50ms 手势节流，避免每帧 IPC 卡顿

**屏幕锁定**：左侧中间显示锁定按钮，点击后：

* 隐藏播放器控制栏和弹幕控制

* 禁用所有手势（双击、长按滑动等）

* 显示只读进度条（屏幕底部，含时间位置和总时长）

* 点击屏幕只显示/隐藏锁定按钮和进度条

* 返回键和解锁按钮仍然可用

进度条拖动已与长按手势解耦，在进度条上操作不会触发长按快进/快退。

播放器控制栏自定义：

* 删除上一集/下一集按钮

* 快进/快退统一为 10 秒

* 播放/暂停、快进、快退按钮使用自定义矢量图标

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
| 开发语言    | Kotlin 2.0                                                                  |
| 构建工具    | Gradle、Android Gradle Plugin 8.5.0                                          |
| 最低版本    | minSdk 24                                                                   |
| 目标版本    | targetSdk 36                                                                |
| UI      | XML Layout、ViewBinding、Material Components、RecyclerView、CardView、ViewPager2 |
| 架构      | MVVM + Repository + Data Source                                             |
| 异步      | Kotlin Coroutines、LiveData、Flow                                             |
| 本地存储    | Room 2.6.1                                                                  |
| 播放器     | AndroidX Media3 ExoPlayer 1.4.0                                             |
| 图片加载    | Coil 2.7.0                                                                  |
| JSON 解析 | Moshi 1.15.1、org.json                                                       |
| 网络与解析   | OkHttp 4.12.0、Jsoup                                                         |
| 代码生成    | KSP                                                                         |

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
│       ├── danmaku/
│       ├── fragment/
│       ├── settings/
│       ├── source/
│       ├── update/
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

| 权限                                                | 用途                                    |
| ------------------------------------------------- | ------------------------------------- |
| `android.permission.INTERNET`                     | 访问豆瓣、播放源网站、封面图片和播放地址                  |
| `android.permission.ACCESS_NETWORK_STATE`         | 判断网络状态，配合网络播放与远程数据源                   |
| `android.permission.FOREGROUND_SERVICE`           | 下载时保持前台服务运行                           |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ 前台服务类型声明                  |
| `android.permission.POST_NOTIFICATIONS`           | Android 13+ 下载通知权限                    |
| `android.permission.WAKE_LOCK`                    | 下载时保持 CPU 唤醒                          |
| `android.permission.REQUEST_INSTALL_PACKAGES`     | 应用内更新安装 APK（Android 8.0+ 需「安装未知应用」授权） |

## 当前版本说明

* 版本号：`1.0`

* applicationId：`com.hpu.mymoviestore`

* compileSdk：`36`

* minSdk：`24`

* targetSdk：`36`

* Java / Kotlin JVM Target：`17`

## 后续可扩展方向

* 增加更多播放源（只需继承 `CrawlerVideoSource` 并实现两个解析方法，在 `MovieApplication` 注册）。

* 增加首页下拉刷新，用于主动刷新已过期或手动清空的发现缓存。

* 增加收藏功能。

### 应用内更新：已确认暂缓的优化项

以下三个问题已讨论确认，当前行为可接受（各有兜底机制），实现时按此方案推进：

**1. 完成未安装的 APK 无生命周期管理**

下载完成但用户一直未安装的 APK（`cacheDir/update/update.apk`，几十 MB）无主动清理机制（无 TTL、无启动检测、无安装成功删除）。现状兜底：固定文件名死文件最多一份；sha256 锚定复用使其在远程未换包期间可零流量复用。建议方案：安装成功或启动时检测本地版本已 ≥ 该更新包版本 → 删除 APK 及 sha256 锚点（删除时须同步删 `update.sha256`，`invalidateShaMeta()` 已提供作废方法）。

**2. 后台下载无保活**

APK 下载只是 `ApkDownloadManager` 单例内的内存协程，无前台服务/通知，进程被杀下载即断、无通知栏进度。现状兜底：断点续传 + sha256 锚点复用，重下损失可控。建议方案：参考 `DownloadService` 模式增加前台服务 + 进度通知（注意 Android 14+ 前台服务类型限制及应用现有 `dataSync` 类型）；保持 `ApkDownloadManager` 的 StateFlow 订阅接口不变。

**3. 清理缓存统计/清理范围不一致**

缓存大小计算包含整个 `cacheDir`，但「清理全部缓存」不删 `cacheDir/update/`（APK 更新包），用户会看到清理后大小对不上。建议方案（三选一或组合）：① 清理全部时纳入 `cacheDir/update/`，但下载中（`ApkDownloadManager.isDownloading`）跳过；② 清理缓存弹框增加独立选项「清理更新安装包」；③ 大小统计单独标注「含更新安装包 XX MB」不纳入一键清理。

### 发版流程纪律（持续有效）

* 每次发版远程 JSON 的 `version` + `update_sha256` 必须同步更新（`update_sha256` 忘改且 URL 未变 → 复用机制会命中旧包）。

* `update_sha256` 建议视为必填项（空值 = 复用失效 + 完整性校验失效，两道防线同时失效）。

* 换签名（keystore 丢失重建）时必须同步更新 `ApkVerifier.EXPECTED_SIGNING_CERT_SHA256` 常量并发版。

