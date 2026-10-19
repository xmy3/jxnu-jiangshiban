package cn.jxnu.nvzhuanban.ui.screens.announcement

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.jxnu.nvzhuanban.data.model.ArticleDetail
import cn.jxnu.nvzhuanban.data.repository.ArticleDetailRepository
import cn.jxnu.nvzhuanban.ui.components.UiState
import cn.jxnu.nvzhuanban.ui.components.UiStateViewModel

class AnnouncementDetailViewModel(
    val articleId: String,
    /**
     * 正文抓取入口。默认走 [ArticleDetailRepository]；debug 预览页注入一份固定正文，
     * 这样不登录取不到真数据时也能把阅读排版（进度条 / 表格 / 字号面板）拉到真机上看。
     */
    private val fetchDetail: suspend (String) -> ArticleDetail = { ArticleDetailRepository.instance.fetch(it) },
) : UiStateViewModel<ArticleDetail>() {

    init { load() }

    override fun load() {
        if (!articleId.matches(VALID_ID)) {
            _state.value = UiState.Error("文章编号无效")
            return
        }
        super.load()
    }

    override suspend fun fetch(refresh: Boolean): ArticleDetail = fetchDetail(articleId)

    companion object {
        private val VALID_ID = Regex("""\d+""")

        fun factory(articleId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { AnnouncementDetailViewModel(articleId) }
        }
    }
}
