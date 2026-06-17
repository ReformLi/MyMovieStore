package com.hpu.mymoviestore.data.source.impl

import android.util.Log
import com.hpu.mymoviestore.data.model.CrawlerVideoDetail
import com.hpu.mymoviestore.data.model.PlayEpisode
import com.hpu.mymoviestore.data.model.PlayLine
import com.hpu.mymoviestore.data.model.SearchPageResult
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.data.repository.ApiCacheRepository
import com.hpu.mymoviestore.data.source.CrawlerVideoSource
import com.hpu.mymoviestore.data.source.RequestRateLimiter
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import java.net.URLEncoder

/**
 * 樱花动漫播放源 —— 基于 wap.******.com 的实际页面结构解析。
 *
 * 页面结构（MyUI 模板 / 苹果CMS）：
 * - 搜索结果：`#searchList > li`，每个 li 包含 `.thumb > a`（封面+标题）和 `.detail`（分类/年份/简介）
 * - 详情页：`.myui-content__detail`（标题/分类/年份/导演/简介），`.tab-content` 内多个 `.tab-pane`（播放线路+集数）
 * - 播放页：`player_aaaa` JSON 变量，包含 `url` 字段（m3u8 地址）
 * - 分页：`ul.myui-page > li > a`，href 格式 `/vs/page/{n}/wd/{keyword}.html`
 */
class YinghuaVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("YH", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_yinghua"
    override val sourceName = "樱花动漫"
    override val baseUrl = "https://wap.******.com"
    override val cachePrefix = "yinghua"
    override val rateLimiterTag = "YH"
    override val logTag = "YinghuaVideoSource"

    // ==================== 搜索页解析 ====================

    override fun parseSearchPage(doc: Document, keyword: String, page: Int): SearchPageResult {
        val listEl = doc.select("#searchList")
        val items = listEl.select("li").mapIndexedNotNull { index, li ->
            parseSearchItem(li, index)
        }

        // 分页解析
        val paginationLinks = doc.select("ul.myui-page a")
        val totalPages = parsePaginationTotalPages(paginationLinks, page)
        val hasNext = parseHasNext(paginationLinks, page, totalPages)

        Log.d(
            logTag,
            "搜索页解析完成: keyword=$keyword, page=$page, " +
                "items=${items.size}, totalPages=$totalPages, hasNext=$hasNext"
        )

        return SearchPageResult(
            keyword = keyword,
            page = page,
            totalPages = totalPages,
            hasPrev = page > 1,
            hasNext = hasNext,
            items = items
        )
    }

    /**
     * 解析单条搜索结果。
     *
     * HTML 结构：
     * ```html
     * <li class="active clearfix">
     *   <div class="thumb">
     *     <a href="/p/18158.html" title="斗罗大陆2：绝世唐门2023"
     *        data-original="https://snzypic.vip/.../xxx.jpg">
     *       <span class="pic-tag pic-tag-top">2.0分</span>
     *       <span class="pic-text text-right">更新至157集</span>
     *     </a>
     *   </div>
     *   <div class="detail">
     *     <h4 class="title"><a href="/p/18158.html">斗罗大陆2：绝世唐门2023</a></h4>
     *     <p><span class="text-muted">导演：</span>内详</p>
     *     <p><span class="text-muted">主演：</span>内详</p>
     *     <p><span class="text-muted">分类：</span>国产动漫<span class="split-line"></span>
     *        <span class="text-muted hidden-xs">地区：</span>中国大陆<span class="split-line"></span>
     *        <span class="text-muted hidden-xs">年份：</span>2023</p>
     *     <p class="hidden-xs"><span class="text-muted">简介：</span>你我皆唐门...<a href="/p/18158.html">详情 &gt;</a></p>
     *   </div>
     * </li>
     * ```
     */
    private fun parseSearchItem(li: org.jsoup.nodes.Element, index: Int): VideoItem? {
        // 标题和详情链接：.thumb > a[href][title]
        val thumbLink = li.select(".thumb a").first()
        val detailUrl = thumbLink?.attr("abs:href").orEmpty()
        val title = thumbLink?.attr("title")?.trim()
            ?: li.select("h4.title a").first()?.text()?.trim().orEmpty()

        if (detailUrl.isBlank() || title.isBlank()) {
            Log.w(logTag, "搜索结果第 ${index + 1} 条跳过: detailUrl=$detailUrl, title=$title")
            return null
        }

        // 封面：.thumb a[data-original]
        var coverUrl = thumbLink.attr("data-original").orEmpty()
        if (coverUrl.isBlank()) {
            val style = thumbLink.attr("style").orEmpty()
            coverUrl = Regex("url\\(([^)]+)\\)").find(style)?.groupValues?.get(1).orEmpty()
        }

        // 评分：.pic-tag-top
        val rating = li.select(".pic-tag-top").first()?.text()?.trim()
            ?.replace("分", "").orEmpty()

        // 分类/年份/导演/主演：每个字段在独立的 <p> 中
        // 格式：<p><span class="text-muted">分类：</span>国产动漫<span class="split-line"></span>...</p>
        val detailDiv = li.select(".detail").first()
        val category = extractSearchFieldValue(detailDiv, "分类")
        val year = extractSearchFieldValue(detailDiv, "年份")
        val director = extractSearchFieldValue(detailDiv, "导演")
        val actors = extractSearchFieldValue(detailDiv, "主演")

        // 简介：p.hidden-xs 中 text-muted 后的文本
        val description = detailDiv?.select("p.hidden-xs")?.first()?.let { p ->
            val span = p.select("span.text-muted").first()
            if (span != null) {
                // 取 span 后面的文本节点
                val nodes = span.siblingNodes()
                val sb = StringBuilder()
                for (node in nodes) {
                    if (node.nodeName() == "a" && node.toString().contains("详情")) break
                    val txt = node.toString().trim()
                    if (txt.isNotBlank()) sb.append(txt)
                }
                sb.toString().trim()
            } else {
                p.text().replace("简介：", "").replace("详情 >", "").trim()
            }
        }.orEmpty()

        Log.d(
            logTag,
            "搜索结果第 ${index + 1} 条: title='$title', detailUrl=$detailUrl, " +
                "category='$category', year='$year', director='$director', actors='$actors'"
        )

        return VideoItem(
            id = detailUrl.hashCode().toLong(),
            title = title,
            coverUrl = coverUrl,
            playUrl = "",
            category = category,
            detailUrl = detailUrl,
            rating = rating,
            year = year,
            area = "",
            director = director,
            actors = actors,
            description = description,
            sourceName = this.sourceName
        )
    }

    // ==================== 详情页解析 ====================

    /**
     * 解析详情页。
     *
     * HTML 结构：
     * ```html
     * <div class="myui-content__detail">
     *   <h1 class="title"><a href="" title="斗罗大陆2：绝世唐门2023 在线播放">斗罗大陆2：绝世唐门2023 在线播放</a></h1>
     *   <p class="data"><span class="text-muted">分类：</span><a href="...">国产动漫</a><span class="split-line"></span>
     *      <span class="text-muted">地区：</span><a href="...">中国大陆</a><span class="split-line"></span>
     *      <span class="text-muted">年份：</span><a href="...">2023</a></p>
     *   <p class="data"><span class="text-muted">主演：</span>未知</p>
     *   <p class="data"><span class="text-muted">导演：</span>未知</p>
     *   <p class="data hidden-xs"><span class="text-muted">简介：</span>欢迎来到樱花动漫网...</p>
     * </div>
     * ```
     */
    override fun parseVideoDetail(doc: Document, detailUrl: String): CrawlerVideoDetail {
        // 标题：优先取 h1.title a 的 title 属性（纯标题），否则取 text（含"在线播放"后缀则去掉）
        Log.d(logTag, "========== parseVideoDetail 开始 ==========")
        Log.d(logTag, "解析 URL: $detailUrl")
        Log.d(logTag, "文档标题: ${doc.title()}")
        Log.d(logTag, "文档大小: ${doc.html().length} 字符")
        val titleEl = doc.select(".myui-content__detail h1.title a").first()
        var title = titleEl?.attr("title")?.trim()
            ?: titleEl?.text()?.trim() ?: ""
        title = title.replace("在线播放", "").trim()

        // 封面：og:image meta
        val coverUrl = doc.select("meta[property=og:image]").first()?.attr("content").orEmpty()

        // 分类/年份/导演/主演：p.data 中 span.text-muted 后面的兄弟节点
        val detailBlock = doc.select(".myui-content__detail").first()
        val category = extractDetailFieldValue(detailBlock, "分类")
        val year = extractDetailFieldValue(detailBlock, "年份")
        val director = extractDetailFieldValue(detailBlock, "导演")
        val actors = extractDetailFieldValue(detailBlock, "主演")

        // 简介：更健壮的提取
        val description = detailBlock?.select("p.data")?.firstOrNull {
            it.select("span.text-muted").text().contains("简介")
        }?.let { p ->
            val span = p.select("span.text-muted").first()
            if (span != null) {
                // 取 span 后面的所有兄弟节点，拼接文本，但跳过 <a> 标签
                val sb = StringBuilder()
                var node = span.nextSibling()
                while (node != null) {
                    if (node is org.jsoup.nodes.TextNode) {
                        sb.append(node.text().trim())
                    } else if (node is org.jsoup.nodes.Element) {
                        // 如果是 <a> 标签，跳过（可能包含“详情”链接）
                        if (node.tagName() != "a") {
                            sb.append(node.text().trim())
                        }
                    }
                    node = node.nextSibling()
                }
                sb.toString().trim()
            } else {
                p.text().replace("简介：", "").replace("详情 >", "").trim()
            }
        }.orEmpty()

        // 评分：.pic-tag-top
        val rating = doc.select(".pic-tag-top").first()?.text()?.trim()
            ?.replace("分", "")
            .orEmpty()

        // 播放线路和集数
        val lines = parsePlayLines(doc)

        Log.d(
            logTag,
            "详情页解析完成: title='$title', category='$category', year='$year', " +
                "director='$director', actors='$actors', lines=${lines.size}"
        )

        // 在 return CrawlerVideoDetail(...) 之前
        Log.d(logTag, "========== parseVideoDetail 完成 ==========")
        Log.d(logTag, "  title: '$title'")
        Log.d(logTag, "  coverUrl: '$coverUrl'")
        Log.d(logTag, "  category: '$category'")
        Log.d(logTag, "  year: '$year'")
        Log.d(logTag, "  rating: '$rating'")
        Log.d(logTag, "  director: '$director'")
        Log.d(logTag, "  actors: '$actors'")
        Log.d(logTag, "  description: '${description.take(50)}...'")
        Log.d(logTag, "  playLines: ${lines.size} 条")
        lines.forEachIndexed { idx, line ->
            Log.d(logTag, "    线路 $idx: '${line.name}' (${line.episodes.size} 集)")
        }

        return CrawlerVideoDetail(
            id = detailUrl.hashCode().toLong(),
            title = title,
            coverUrl = coverUrl,
            category = category,
            year = year,
            rating = rating.ifBlank { "0.0" },
            director = director,
            actors = actors,
            description = description,
            detailUrl = detailUrl,
            playLines = lines,
            sourceName = this.sourceName
        )
    }

    /**
     * 解析播放线路。
     * 结构：`.nav-tabs a[data-toggle=tab]` 为线路名称，
     * `.tab-content .tab-pane` 为对应集数列表，
     * 每个 tab-pane 内 `ul.myui-content__list a` 为集数链接。
     */
    private fun parsePlayLines(doc: Document): List<PlayLine> {
        Log.d(logTag, "========== parsePlayLines 开始 ==========")

        val tabNames = doc.select(".nav-tabs a[data-toggle=tab]")
        val tabPanes = doc.select(".tab-content .tab-pane")

        Log.d(logTag, "找到 ${tabNames.size} 个线路标签, ${tabPanes.size} 个面板")

        if (tabNames.isEmpty() || tabPanes.isEmpty()) {
            Log.w(logTag, "未找到播放线路或集数，请检查页面结构")
            return emptyList()
        }

        // 打印所有线路名称
        tabNames.forEachIndexed { idx, tab ->
            Log.d(logTag, "  线路 $idx: '${tab.text().trim()}'")
        }

        return tabNames.mapIndexedNotNull { index, tab ->
            val lineName = tab.text().trim()
            val pane = tabPanes.getOrNull(index)

            Log.d(logTag, "处理线路 '$lineName' (索引 $index)")

            if (pane == null) {
                Log.w(logTag, "  面板为空，跳过")
                return@mapIndexedNotNull null
            }

            // 选择集数链接
            val episodeLinks = pane.select("ul.myui-content__list.sort-list a")
            Log.d(logTag, "  找到 ${episodeLinks.size} 个集数链接")

            // 构建集数列表
            val episodes = episodeLinks.mapNotNull { a ->
                val episodeTitle = a.text().trim()
                val playPageUrl = a.attr("abs:href")
                if (episodeTitle.isBlank() || playPageUrl.isBlank()) {
                    null
                } else {
                    PlayEpisode(episodeTitle, playPageUrl)
                }
            }.distinctBy { it.playPageUrl }

            // 打印前3条（如果存在）
            if (episodes.isNotEmpty()) {
                episodes.take(3).forEach { ep ->
                    Log.d(logTag, "    集数示例: '${ep.title}' -> ${ep.playPageUrl}")
                }
                if (episodes.size > 3) {
                    Log.d(logTag, "    ... 还有 ${episodes.size - 3} 集")
                }
            } else {
                Log.w(logTag, "  该线路没有有效集数")
            }

            if (lineName.isBlank() || episodes.isEmpty()) {
                Log.w(logTag, "线路 '$lineName' 无有效集数")
                null
            } else {
                Log.d(logTag, "✅ 线路 '$lineName' 解析成功，共 ${episodes.size} 集")
                PlayLine(lineName, episodes)
            }
        }.also {
            Log.d(logTag, "parsePlayLines 完成，共 ${it.size} 条线路")
        }
    }

    // ==================== URL 构建 ====================

    override fun buildSearchUrl(keyword: String, page: Int): String {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        return "$baseUrl/vs/page/$page/wd/$encodedKeyword.html"
    }

    // ==================== 工具方法 ====================

    /**
     * 从搜索结果 `.detail` 区域提取字段值。
     *
     * 搜索结果中，每个字段（导演/主演/分类/年份）在独立的 `<p>` 中：
     * - 独立字段：`<p><span class="text-muted">导演：</span>内详</p>`
     * - 合并字段：`<p><span class="text-muted">分类：</span>国产动漫<span class="split-line"></span>...</p>
     *
     * 策略：找到包含目标标签的 `<p>`，取 `span.text-muted` 后面的第一个非空文本。
     * 注意：值可能是文本节点（如"国产动漫"），也可能是元素节点（如`<a>`标签），
     *       必须先检查 nextSibling（文本节点），再检查 nextElementSibling（元素节点）。
     */
    private fun extractSearchFieldValue(detailDiv: org.jsoup.nodes.Element?, label: String): String {
        if (detailDiv == null) return ""
        for (p in detailDiv.select("p")) {
            // ✅ 修改点：遍历当前 p 内所有的 span.text-muted，而不是只取第一个
            val spans = p.select("span.text-muted")
            for (mutedSpan in spans) {
                if (mutedSpan.text().contains(label)) {
                    // 1. 先检查紧跟的文本节点（如 "国产动漫" 或 "2023"）
                    val textNode = mutedSpan.nextSibling()
                    if (textNode != null) {
                        val txt = textNode.toString().trim()
                        if (txt.isNotBlank()) return txt
                    }
                    // 2. 再检查后面的元素节点（如 <a> 标签包裹的内容）
                    var sibling = mutedSpan.nextElementSibling()
                    while (sibling != null) {
                        if (sibling.hasClass("split-line")) {
                            sibling = sibling.nextElementSibling()
                            continue
                        }
                        val text = sibling.text().trim()
                        if (text.isNotBlank()) return text
                        sibling = sibling.nextElementSibling()
                    }
                }
            }
        }
        return ""
    }

    /**
     * 从详情页 `.myui-content__detail p.data` 区域提取字段值。
     *
     * 详情页中字段格式：
     * - 带链接：`<span class="text-muted">分类：</span><a href="...">国产动漫</a>`
     * - 纯文本：`<span class="text-muted">导演：</span>未知`
     *
     * 策略：遍历当前 `<p>` 内所有的 `span.text-muted`，匹配标签名，
     * 然后从 `span` 的后续兄弟节点中提取第一个非空文本（跳过空白文本节点）。
     * 返回纯文本内容（去除 HTML 标签）。
     */
    private fun extractDetailFieldValue(detailBlock: org.jsoup.nodes.Element?, label: String): String {
        if (detailBlock == null) return ""

        for (p in detailBlock.select("p.data")) {
            // 遍历当前 p 内所有的 span.text-muted
            for (mutedSpan in p.select("span.text-muted")) {
                if (mutedSpan.text().contains(label)) {
                    // 从 span 的下一个兄弟节点开始遍历，跳过空白节点
                    var node = mutedSpan.nextSibling()
                    while (node != null) {
                        when (node) {
                            is org.jsoup.nodes.TextNode -> {
                                val text = node.text().trim()
                                if (text.isNotBlank()) return text
                            }
                            is org.jsoup.nodes.Element -> {
                                // 跳过分割线等特殊元素
                                if (node.hasClass("split-line")) {
                                    node = node.nextSibling()
                                    continue
                                }
                                val text = node.text().trim()
                                if (text.isNotBlank()) return text
                            }
                        }
                        node = node.nextSibling()
                    }
                }
            }
        }
        return ""
    }

    private fun parsePaginationTotalPages(paginationLinks: Elements, currentPage: Int): Int {
        if (paginationLinks.isEmpty()) return 1

        val maxPageFromHref = paginationLinks.mapNotNull { a: org.jsoup.nodes.Element ->
            val href = a.attr("href")
            Regex("/vs/page/(\\d+)/wd/").find(href)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull()

        val maxPageFromText = paginationLinks.mapNotNull { a: org.jsoup.nodes.Element ->
            a.text().trim().toIntOrNull()
        }.maxOrNull()

        return listOfNotNull(maxPageFromHref, maxPageFromText, currentPage).maxOrNull() ?: 1
    }

    private fun parseHasNext(paginationLinks: Elements, currentPage: Int, totalPages: Int): Boolean {
        if (paginationLinks.isEmpty()) return false
        if (currentPage >= totalPages) return false

        val hasNextByText = paginationLinks.any { el: org.jsoup.nodes.Element ->
            val text = el.text().trim()
            text == "下一页" || text == "下页"
        }

        val hasNextByHref = paginationLinks.any { el: org.jsoup.nodes.Element ->
            el.attr("href").contains("/vs/page/${currentPage + 1}/")
        }

        return hasNextByText || hasNextByHref || currentPage < totalPages
    }
}
