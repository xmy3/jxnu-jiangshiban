package cn.jxnu.nvzhuanban.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RepositoryCache<T : Any> {
    private val requests = Mutex()
    private val stateLock = Any()
    private var generation = 0L
    private var cached: T? = null

    suspend fun load(refresh: Boolean = false, fetch: suspend () -> T): T {
        val expected = synchronized(stateLock) { generation }
        return requests.withLock {
            synchronized(stateLock) {
                checkGeneration(expected)
                if (!refresh) cached else null
            }?.let { return@withLock it }
            val result = fetch()
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                checkGeneration(expected)
                cached = result
                result
            }
        }
    }

    fun clear() = synchronized(stateLock) {
        generation++
        cached = null
    }

    private fun checkGeneration(expected: Long) {
        if (expected != generation) throw CancellationException("Repository cache invalidated")
    }
}
