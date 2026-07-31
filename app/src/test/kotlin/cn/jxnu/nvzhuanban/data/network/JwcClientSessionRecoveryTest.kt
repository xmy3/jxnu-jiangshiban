package cn.jxnu.nvzhuanban.data.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 锁定 [withSessionRecovery] 的三态容错契约 —— 13/15 个登录态 Repository 经
 * [JwcClient.getHtmlAuth] / [JwcClient.postHtmlAuth] 间接依赖它。这里注入 fake 的
 * reauth / notify，脱离 OkHttp 与 AuthRepository 单例，纯逻辑验证
 * 「只重放一次 / 按 [ReauthOutcome] 决定去留」这套语义。
 *
 * 三态语义（替代旧的 Boolean）：
 *  - [ReauthOutcome.Success]    → 重放一次；重放仍过期才 notify（不二次 reauth）
 *  - [ReauthOutcome.AuthRejected] → notify 踢登录页（除非 notifyOnAuthRejected=false）并透传原异常
 *  - [ReauthOutcome.Transient]  → **不 notify**，转成 Network 错误（不透传「登录已过期」误导用户）
 */
class JwcClientSessionRecoveryTest {

    private fun sessionExpired() = JwcException(JwcError.SessionExpired)
    private fun otherJwcError() = JwcException(JwcError.EmptyResponse)

    // #1 保留：block 成功不 reauth / 不 notify
    @Test
    fun `block succeeds - no reauth, no notify`() = runBlocking {
        var reauthCalls = 0
        var notifyCalls = 0
        val result = withSessionRecovery(
            reauth = { reauthCalls++; ReauthOutcome.Success },
            notifyExpired = { notifyCalls++ },
            block = { "ok" },
        )
        assertEquals("ok", result)
        assertEquals(0, reauthCalls)
        assertEquals(0, notifyCalls)
    }

    // #2 保留：非 SessionExpired 原样透传、不 reauth
    @Test
    fun `non session-expired error is rethrown without reauth`() {
        var reauthCalls = 0
        var notifyCalls = 0
        val ex = assertThrows(JwcException::class.java) {
            runBlocking {
                withSessionRecovery(
                    reauth = { reauthCalls++; ReauthOutcome.Success },
                    notifyExpired = { notifyCalls++ },
                    block = { throw otherJwcError() },
                )
            }
        }
        assertEquals(JwcError.EmptyResponse, ex.error)
        assertEquals(0, reauthCalls)
        assertEquals(0, notifyCalls)
    }

    // #3a 新：AuthRejected → notify once and rethrow original SessionExpired
    @Test
    fun `session expired then auth rejected - notify once and rethrow`() {
        var reauthCalls = 0
        var notifyCalls = 0
        val ex = assertThrows(JwcException::class.java) {
            runBlocking {
                withSessionRecovery(
                    reauth = { reauthCalls++; ReauthOutcome.AuthRejected },
                    notifyExpired = { notifyCalls++ },
                    block = { throw sessionExpired() },
                )
            }
        }
        assertEquals(JwcError.SessionExpired, ex.error)
        assertEquals(1, reauthCalls)
        assertEquals(1, notifyCalls)
    }

    // #3b 新：Transient → 不 notify，转成 Network 错误（绝不透传 SessionExpired 文案）
    @Test
    fun `session expired then transient - no notify, converts to network error`() {
        var reauthCalls = 0
        var notifyCalls = 0
        val ex = assertThrows(JwcException::class.java) {
            runBlocking {
                withSessionRecovery(
                    reauth = { reauthCalls++; ReauthOutcome.Transient },
                    notifyExpired = { notifyCalls++ },
                    block = { throw sessionExpired() },
                )
            }
        }
        assertEquals(JwcError.Network(), ex.error)
        assertEquals(1, reauthCalls)
        assertEquals(0, notifyCalls) // 瞬时失败不踢人
    }

    // #3c 新：AuthRejected 但 notifyOnAuthRejected=false（图片路径）→ 不 notify，仍透传
    @Test
    fun `auth rejected with notify suppressed - no notify, rethrow`() {
        var notifyCalls = 0
        val ex = assertThrows(JwcException::class.java) {
            runBlocking {
                withSessionRecovery(
                    reauth = { ReauthOutcome.AuthRejected },
                    notifyExpired = { notifyCalls++ },
                    notifyOnAuthRejected = false,
                    block = { throw sessionExpired() },
                )
            }
        }
        assertEquals(JwcError.SessionExpired, ex.error)
        assertEquals(0, notifyCalls)
    }

    // #4 保留语义（返回类型改 ReauthOutcome）：Success + 重放成功 → 不 notify
    @Test
    fun `session expired then success then replay succeeds`() = runBlocking {
        var reauthCalls = 0
        var notifyCalls = 0
        var blockCalls = 0
        val result = withSessionRecovery(
            reauth = { reauthCalls++; ReauthOutcome.Success },
            notifyExpired = { notifyCalls++ },
            block = {
                blockCalls++
                if (blockCalls == 1) throw sessionExpired() else "replayed"
            },
        )
        assertEquals("replayed", result)
        assertEquals(2, blockCalls)
        assertEquals(1, reauthCalls)
        assertEquals(0, notifyCalls)
    }

    // #5 保留语义：Success 后重放仍过期 → notify 一次、不二次 reauth
    @Test
    fun `replay still session expired - notify once and no second reauth`() {
        var reauthCalls = 0
        var notifyCalls = 0
        var blockCalls = 0
        val ex = assertThrows(JwcException::class.java) {
            runBlocking {
                withSessionRecovery(
                    reauth = { reauthCalls++; ReauthOutcome.Success },
                    notifyExpired = { notifyCalls++ },
                    block = { blockCalls++; throw sessionExpired() },
                )
            }
        }
        assertEquals(JwcError.SessionExpired, ex.error)
        assertEquals(2, blockCalls)
        assertEquals(1, reauthCalls)
        assertEquals(1, notifyCalls)
    }

    // #5b 新：Success 后重放仍过期、但 notifyOnAuthRejected=false → 不 notify
    @Test
    fun `replay still expired with notify suppressed - no notify`() {
        var notifyCalls = 0
        val ex = assertThrows(JwcException::class.java) {
            runBlocking {
                withSessionRecovery(
                    reauth = { ReauthOutcome.Success },
                    notifyExpired = { notifyCalls++ },
                    notifyOnAuthRejected = false,
                    block = { throw sessionExpired() },
                )
            }
        }
        assertEquals(JwcError.SessionExpired, ex.error)
        assertEquals(0, notifyCalls)
    }

    // #6 保留：Success 后重放抛其它异常 → 原样透传、不 notify
    @Test
    fun `replay throws other error - rethrow without notify`() {
        var notifyCalls = 0
        var blockCalls = 0
        val ex = assertThrows(JwcException::class.java) {
            runBlocking {
                withSessionRecovery(
                    reauth = { ReauthOutcome.Success },
                    notifyExpired = { notifyCalls++ },
                    block = {
                        blockCalls++
                        if (blockCalls == 1) throw sessionExpired() else throw otherJwcError()
                    },
                )
            }
        }
        assertEquals(JwcError.EmptyResponse, ex.error)
        assertEquals(2, blockCalls)
        assertEquals(0, notifyCalls)
    }
}
