package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.Exam
import cn.jxnu.nvzhuanban.data.model.toDatedExam

class ExamAlertRepository(
    private val regular: ExamRepository = ExamRepository.instance,
    private val makeup: MakeupExamRepository = MakeupExamRepository.instance,
) {
    private val cache = RepositoryCache<List<Exam>>()

    suspend fun refresh(): List<Exam> = cache.load(refresh = true) {
        val exams = regular.refreshForAlerts()
        exams + makeup.refreshForAlerts().mapNotNull { it.toDatedExam() }
    }

    fun clearCache() {
        cache.clear()
    }

    companion object {
        val instance: ExamAlertRepository by lazy { ExamAlertRepository() }
    }
}
