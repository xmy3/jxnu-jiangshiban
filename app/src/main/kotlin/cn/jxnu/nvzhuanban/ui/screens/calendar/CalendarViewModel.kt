package cn.jxnu.nvzhuanban.ui.screens.calendar

import cn.jxnu.nvzhuanban.data.model.CalendarEntry
import cn.jxnu.nvzhuanban.data.repository.CalendarRepository
import cn.jxnu.nvzhuanban.ui.components.UiStateViewModel

/**
 * 校历列表 ViewModel。数据是 jwc 的纯静态索引页，更新很慢，单 Repository 直拉即可。
 */
class CalendarViewModel(
    private val repo: CalendarRepository = CalendarRepository.instance,
) : UiStateViewModel<List<CalendarEntry>>() {

    init { load() }

    override suspend fun fetch(refresh: Boolean): List<CalendarEntry> =
        if (refresh) repo.refresh() else repo.fetchAll()
}
