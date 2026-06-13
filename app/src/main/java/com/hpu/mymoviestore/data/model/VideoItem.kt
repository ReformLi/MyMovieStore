package com.hpu.mymoviestore.data.model

/**
 * 视频条目 —— 内存数据模型（不存 Room）
 *
 * 由 VideoSourceManager 解析 JSON 后生成，直接用于 RecyclerView 列表展示。
 *
 * @param id       视频 ID（从 vod_id 映射）
 * @param title    视频标题
 * @param coverUrl 封面图 URL（Coil 加载）
 * @param category 分类名称（如「电影」「电视剧」），用于 Tab 过滤和详情页展示
 * @param rating   评分（如 "8.3"），可为空
 * @param year     年份
 * @param area     地区
 * @param director 导演
 * @param actors   演员列表，逗号分隔
 * @param description 剧情简介
 * @param playUrl  视频播放地址（Media3 ExoPlayer 播放）
 */
data class VideoItem(
    val id: Long,
    val title: String,
    val coverUrl: String,
    val category: String,
    val rating: String,
    val year: String,
    val area: String,
    val director: String,
    val actors: String,
    val description: String,
    val playUrl: String,
    val detailUrl: String = ""   // 新增
) {
    companion object {
        /** 分类常量，与首页 TabLayout 索引保持一致 */
        const val CATEGORY_ALL = "全部"
        const val CATEGORY_MOVIE = "电影"
        const val CATEGORY_TV = "电视剧"
        const val CATEGORY_VARIETY = "综艺"
        const val CATEGORY_ANIME = "动漫"
        const val CATEGORY_DOCUMENTARY = "纪录片"
    }
}
