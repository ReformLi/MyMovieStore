package com.hpu.mymoviestore.data.model.remote

import com.hpu.mymoviestore.data.model.VideoItem

/**
 * 将 JSON 数据源中的 RemoteVideo 模型转换为 UI 使用的 VideoItem 模型
 */
fun RemoteVideo.toVideoItem(): VideoItem {
    return VideoItem(
        id = this.vodId,
        title = this.vodName,
        coverUrl = this.vodPic,
        category = this.typeName,
        rating = this.vodRemarks,
        year = this.vodYear,
        area = this.vodArea,
        director = this.vodDirector,
        actors = this.vodActor,
        description = this.vodContent,
        playUrl = this.vodPlayUrl
    )
}

fun List<RemoteVideo>.toVideoItemList(): List<VideoItem> {
    return this.map { it.toVideoItem() }
}
