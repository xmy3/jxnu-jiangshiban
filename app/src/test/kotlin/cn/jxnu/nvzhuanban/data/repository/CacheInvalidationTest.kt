package cn.jxnu.nvzhuanban.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheInvalidationTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `requests queued before signout cannot fetch in the new session`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val repo = GradeRepository {
            if (++calls == 1) {
                started.complete(Unit)
                release.await()
            }
            "<table id='_ctl6_dgContent'></table>"
        }
        val old = async { repo.fetchAll() }
        started.await()
        val queued = async { repo.refresh() }
        runCurrent()
        repo.clearCache()
        release.complete(Unit)
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { queued.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, calls)
        assertTrue(repo.fetchAll().semesters.isEmpty())
        assertEquals(2, calls)
    }

    @Test
    fun `clearing grades rejects an in-flight response`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val repo = GradeRepository {
            val name = if (++calls == 1) {
                started.complete(Unit)
                release.await()
                "Account A"
            } else "Account B"
            """<table id="_ctl6_dgContent"><tr><td>26-27第1学期</td><td>CS101</td>
                <td>$name</td><td>4</td><td>80</td><td></td><td>1.0</td><td></td></tr></table>"""
        }
        val old = async { repo.fetchAll() }
        started.await()
        repo.clearCache()
        release.complete(Unit)
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertEquals("Account B", repo.fetchAll().semesters.single().grades.single().courseName)
    }

    @Test
    fun `clearing exams rejects an in-flight alert refresh`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val repo = ExamRepository {
            val name = if (++calls == 1) {
                started.complete(Unit)
                release.await()
                "Account A"
            } else "Account B"
            """<table id="_ctl1_dgContent"><tr><td>CS101</td><td>$name</td>
                <td>2026000000</td><td>2026-10-25 00:00:00</td><td>W1203</td><td>12</td><td></td></tr></table>"""
        }
        val old = async { repo.refreshForAlerts() }
        started.await()
        repo.clearCache()
        release.complete(Unit)
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertEquals("Account B", repo.fetchAll().single().courseName)
    }
}
