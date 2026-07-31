package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.AppRelease
import cn.jxnu.nvzhuanban.data.network.GitHubUpdateClient
import cn.jxnu.nvzhuanban.data.network.GithubError
import cn.jxnu.nvzhuanban.data.network.GithubException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [UpdateRepository.forceCheck] 的版本决策逻辑。用 fake [GitHubUpdateClient] 注入 release，
 * 脱离网络验证 SemVer 比较 + [UpdateRepository.latestRelease] StateFlow 推送。
 *
 * 注：checkIfDue 的 24h 限频与 skippedVersion 分支依赖 UpdatePrefs 单例（lateinit + Context），
 * 纯 JVM 无法置状态，故此处只覆盖 forceCheck —— 它的 ignoreSkip=true 会短路掉对 UpdatePrefs
 * 的访问，是可纯逻辑测试的那条路径。applyRelease 的核心 isNewer 判定即由这些用例锁定。
 */
class UpdateRepositoryTest {

    private fun release(version: String, tag: String = "v$version") = AppRelease(
        tagName = tag,
        versionName = version,
        htmlUrl = "https://example.com/$tag",
        body = "notes",
        publishedAt = "2026-01-01T00:00:00Z",
    )

    /** override fetchLatest，父类的 OkHttpClient 字段虽被构造但永不使用。 */
    private class FakeClient(
        private val supplier: suspend () -> AppRelease,
    ) : GitHubUpdateClient() {
        override suspend fun fetchLatest(): AppRelease = supplier()
    }

    private fun repoReturning(rel: AppRelease) = UpdateRepository(FakeClient { rel })

    @Test
    fun `newer version is returned and pushed to flow`() = runBlocking {
        val rel = release("2.0.0")
        val repo = repoReturning(rel)
        assertSame(rel, repo.forceCheck("1.2.1"))
        assertSame(rel, repo.latestRelease.value)
    }

    @Test
    fun `same version returns null and clears flow`() = runBlocking {
        val repo = repoReturning(release("1.2.1"))
        assertNull(repo.forceCheck("1.2.1"))
        assertNull(repo.latestRelease.value)
    }

    @Test
    fun `older version returns null`() = runBlocking {
        val repo = repoReturning(release("1.0.0"))
        assertNull(repo.forceCheck("1.2.1"))
        assertNull(repo.latestRelease.value)
    }

    @Test
    fun `unparseable release version is treated as not newer`() = runBlocking {
        // prerelease 后缀 → SemVer.fromString 返回 null → 不当作更新版本
        val repo = repoReturning(release("2.0.0-rc1", tag = "v2.0.0-rc1"))
        assertNull(repo.forceCheck("1.2.1"))
        assertNull(repo.latestRelease.value)
    }

    @Test
    fun `unparseable current version is treated as not newer`() = runBlocking {
        val repo = repoReturning(release("2.0.0"))
        assertNull(repo.forceCheck("not-a-version"))
        assertNull(repo.latestRelease.value)
    }

    @Test
    fun `client error propagates from forceCheck`() {
        val repo = UpdateRepository(FakeClient { throw GithubException(GithubError.NotFound) })
        assertThrows(GithubException::class.java) {
            runBlocking { repo.forceCheck("1.2.1") }
        }
    }
}
