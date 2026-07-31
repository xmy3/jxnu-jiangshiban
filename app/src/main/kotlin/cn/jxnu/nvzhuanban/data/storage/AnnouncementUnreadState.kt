package cn.jxnu.nvzhuanban.data.storage

import cn.jxnu.nvzhuanban.data.repository.AnnouncementRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 派生状态：底部导航「通知」tab 是否应该显示小红点。
 *
 * 判定：[AnnouncementRepository.latestList] 顶部条目的 uniqueKey 与
 * [AnnouncementReadAnchor.anchor] 不一致即视为「有未读」。
 *
 * - latestList 为空（启动期预热未完成 / 无网）→ false
 * - anchor 为 null（首次安装但已拉到列表）→ true，引导用户进 tab 一次
 * - 两者一致 → false
 */
object AnnouncementUnreadState {

    val hasUnread: Flow<Boolean> = combine(
        AnnouncementRepository.instance.latestList,
        AnnouncementReadAnchor.anchor,
    ) { latest, anchor ->
        val topKey = latest.firstOrNull()?.uniqueKey ?: return@combine false
        topKey != anchor
    }
}
