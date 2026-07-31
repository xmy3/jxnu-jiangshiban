package cn.jxnu.nvzhuanban.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Lightweight app-wide auth events raised by the network layer. */
object SessionEvents {
    private val _expiredSignal = MutableStateFlow(0)
    val expiredSignal: StateFlow<Int> = _expiredSignal.asStateFlow()

    fun notifyExpired() {
        _expiredSignal.update { it + 1 }
    }
}
