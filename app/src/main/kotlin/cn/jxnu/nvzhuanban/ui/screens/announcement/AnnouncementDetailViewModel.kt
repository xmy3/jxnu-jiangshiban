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
    private val repo: ArticleDetailRepository = ArticleDetailRepository.instance,
) : UiStateViewModel<ArticleDetail>() {

    init { load() }

    override fun load() {
        if (!articleId.matches(VALID_ID)) {
            _state.value = UiState.Error("文章编号无效")
            return
        }
        super.load()
    }

    override suspend fun fetch(refresh: Boolean): ArticleDetail = repo.fetch(articleId)

    companion object {
        private val VALID_ID = Regex("""\d+""")

        fun factory(articleId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { AnnouncementDetailViewModel(articleId) }
        }
    }
}
