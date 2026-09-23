package cn.jxnu.nvzhuanban.ui.screens.trainingplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.network.pages.TrainingPlanSearchPage
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.TrainingPlanSearchRepository
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TrainingPlanSearchViewModel(
    private val repo: TrainingPlanSearchRepository = TrainingPlanSearchRepository.instance,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<TrainingPlanSearchPage.Parsed>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _searched = MutableStateFlow(false)
    val searched = _searched.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try { UiState.Success(repo.fetchForm()) }
            catch (t: Throwable) { UiState.Error(t.toUserMessage()) }
        }
    }

    fun select(name: String, value: String) {
        val page = (_state.value as? UiState.Success)?.data ?: return
        val filter = page.filters.firstOrNull { it.name == name } ?: return
        val updated = page.copy(filters = page.filters.map {
            if (it.name == name) it.copy(selectedValue = value) else it
        })
        _state.value = UiState.Success(updated)
        _searched.value = false
        _error.value = null
        if (!filter.postsBack) return
        _busy.value = true
        viewModelScope.launch {
            try { _state.value = UiState.Success(repo.select(name, updated.filters.associate { it.name to it.selectedValue })) }
            catch (t: Throwable) { _error.value = t.toUserMessage() }
            finally { _busy.value = false }
        }
    }

    fun search() {
        if (_busy.value) return
        val page = (_state.value as? UiState.Success)?.data ?: return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(repo.search(page.filters.associate { it.name to it.selectedValue }))
                _searched.value = true
            } catch (t: Throwable) { _error.value = t.toUserMessage() }
            finally { _busy.value = false }
        }
    }
}
