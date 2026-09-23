package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.pages.TrainingPlanSearchPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody

/** 保留最近一次 WebForms 响应作为回传表单，支持学院和专业联动。 */
class TrainingPlanSearchRepository {
    private val mutex = Mutex()
    @Volatile private var donor: TrainingPlanSearchPage.Parsed? = null

    suspend fun fetchForm(): TrainingPlanSearchPage.Parsed = mutex.withLock {
        donor ?: fetchSeed().also { donor = it }
    }

    suspend fun select(name: String, values: Map<String, String>): TrainingPlanSearchPage.Parsed = mutex.withLock {
        val seed = donor ?: fetchSeed()
        val filter = seed.filters.firstOrNull { it.name == name }
            ?: throw JwcException(JwcError.Unknown("筛选项已变化，请重新打开培养方案查询"))
        val next = post(seed, values, if (filter.postsBack) name.replace(':', '$') else "", null)
        donor = next
        next
    }

    suspend fun search(values: Map<String, String>): TrainingPlanSearchPage.Parsed = mutex.withLock {
        val seed = donor ?: fetchSeed()
        val next = post(seed, values, "", seed.submitName)
        donor = next
        next
    }

    fun clearCache() { donor = null }

    private suspend fun fetchSeed(): TrainingPlanSearchPage.Parsed {
        val page = TrainingPlanSearchPage.parse(
            JwcClient.getHtmlAuth(JxnuUrls.PAGE_TRAINING_PLAN_SEARCH, "培养方案查询页返回空响应"),
        )
        if (page.filters.isEmpty()) throw JwcException(JwcError.Unknown("教务系统未返回可用的培养方案筛选项"))
        return page
    }

    private suspend fun post(
        seed: TrainingPlanSearchPage.Parsed,
        values: Map<String, String>,
        eventTarget: String,
        submitName: String?,
    ): TrainingPlanSearchPage.Parsed {
        val body = FormBody.Builder(Charsets.UTF_8)
        seed.hiddenFields.forEach { (key, value) ->
            if (key != "__EVENTTARGET" && key != "__EVENTARGUMENT") body.add(key, value)
        }
        body.add("__EVENTTARGET", eventTarget).add("__EVENTARGUMENT", "")
        seed.filters.forEach { body.add(it.name, values[it.name] ?: it.selectedValue) }
        if (submitName != null) body.add(submitName, seed.submitValue)
        val html = JwcClient.postHtmlAuth(JxnuUrls.PAGE_TRAINING_PLAN_SEARCH, body.build(), "培养方案查询页返回空响应")
        if (html.trimStart().startsWith("Error:")) {
            donor = null
            throw JwcException(JwcError.Unknown("教务系统处理培养方案查询时报错，请重试"))
        }
        return TrainingPlanSearchPage.parse(html).also {
            if (it.filters.isEmpty()) throw JwcException(JwcError.Unknown("教务系统未返回培养方案结果"))
        }
    }

    companion object { val instance: TrainingPlanSearchRepository by lazy { TrainingPlanSearchRepository() } }
}
