package cn.jxnu.nvzhuanban.ui.screens.exams

import cn.jxnu.nvzhuanban.data.model.Exam
import cn.jxnu.nvzhuanban.data.model.MakeupExam
import cn.jxnu.nvzhuanban.data.repository.ExamRepository
import cn.jxnu.nvzhuanban.data.repository.MakeupExamRepository
import cn.jxnu.nvzhuanban.ui.components.UiStateViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 考试 Tab 同时承载「常规考试」和「补缓考」两份数据。
 *
 * 两个接口互相独立，并行拉以省一个 RTT；任意一边失败都把整个页面置为 Error，避免半填半空、
 * 用户以为没有补缓考实际是网络炸了的歧义。
 */
data class ExamsBundle(
    val regular: List<Exam>,
    val makeup: List<MakeupExam>,
)

class ExamsViewModel(
    private val repo: ExamRepository = ExamRepository.instance,
    private val makeupRepo: MakeupExamRepository = MakeupExamRepository.instance,
) : UiStateViewModel<ExamsBundle>() {

    init { load() }

    override suspend fun fetch(refresh: Boolean): ExamsBundle = coroutineScope {
        val exams = async {
            if (refresh) repo.refresh() else repo.getUpcomingExams()
        }
        val makeups = async {
            if (refresh) makeupRepo.refresh() else makeupRepo.fetchAll()
        }
        val (e, m) = awaitAll(exams, makeups)
        @Suppress("UNCHECKED_CAST")
        ExamsBundle(
            regular = (e as List<Exam>).sortedBy { it.startTime },
            makeup = m as List<MakeupExam>,
        )
    }
}
