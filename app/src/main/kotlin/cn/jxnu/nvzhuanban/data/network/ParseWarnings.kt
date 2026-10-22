package cn.jxnu.nvzhuanban.data.network

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 解析告警通道：解析器发现"部分数据被丢弃"时（列数不符、时间无法解析、缺少必需字段……）由
 * Repository 汇总成一句人话报到这里，UI 层（AppNav 的 Snackbar）消费。
 *
 * 与 [SessionEvents] 同样是进程内轻量事件：不落盘、不排队，消费者不在就直接丢。区别是这里用
 * [MutableSharedFlow] 而不是 StateFlow —— 告警是"一次性提示"，不需要粘性重放（否则每次回到前台
 * 都会重新弹一次已经看过的告警）。
 *
 * 溢出策略 [BufferOverflow.DROP_OLDEST] + `extraBufferCapacity = 8`：并发拉多页时最多丢最旧的几条，
 * 绝不阻塞、绝不抛异常。`replay = 0` 保证 `tryEmit` 在无订阅者也安全返回。
 */
object ParseWarnings {

    private const val BUFFER_SIZE = 8

    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = BUFFER_SIZE,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * 上报一条告警。空串（含全空白）忽略 —— 解析器可以无脑把"没有告警"传成 `""`。
     * 用 [MutableSharedFlow.tryEmit]，永不阻塞调用线程（解析发生在 IO 线程，卡不住 UI）。
     */
    fun report(message: String) {
        if (message.isBlank()) return
        _messages.tryEmit(message)
    }
}
