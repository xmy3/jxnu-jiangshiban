package cn.jxnu.nvzhuanban.data.network

import cn.jxnu.nvzhuanban.data.network.pages.sampleJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseParseTest {

    private val client = GitHubUpdateClient()

    @Test
    fun parsesRealGitHubResponseFixture() {
        val json = sampleJson("github_release_latest.json")
        val release = client.parse(json)
        assertEquals("v1.1.0", release.tagName)
        assertEquals("1.1.0", release.versionName)
        assertEquals(
            "https://github.com/xmy3/jxnu-jiangshiban/releases/tag/v1.1.0",
            release.htmlUrl,
        )
        assertEquals("2026-05-01T12:00:00Z", release.publishedAt)
        assertTrue("body 应包含新功能描述", release.body.contains("自动更新检测"))
    }

    @Test
    fun stripsCapitalVPrefixToo() {
        val release = client.parse("""{"tag_name":"V2.5.7","html_url":"x"}""")
        assertEquals("V2.5.7", release.tagName)
        assertEquals("2.5.7", release.versionName)
    }

    @Test
    fun missingTagNameThrowsParseError() {
        val ex = assertThrows(GithubException::class.java) {
            client.parse("""{"html_url":"https://x"}""")
        }
        assertTrue(ex.error is GithubError.Parse)
    }

    @Test
    fun invalidJsonThrowsParseError() {
        val ex = assertThrows(GithubException::class.java) {
            client.parse("not json at all")
        }
        assertTrue(ex.error is GithubError.Parse)
    }

    @Test
    fun missingOptionalFieldsDefaultToBlank() {
        // body / published_at / html_url 都是可选字段，缺失时应当用 "" 兜底，不要抛
        val release = client.parse("""{"tag_name":"v0.1.0"}""")
        assertEquals("", release.htmlUrl)
        assertEquals("", release.body)
        assertEquals("", release.publishedAt)
    }
}
