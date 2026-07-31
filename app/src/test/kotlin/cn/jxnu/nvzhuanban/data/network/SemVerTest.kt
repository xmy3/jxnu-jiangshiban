package cn.jxnu.nvzhuanban.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test
    fun parsesStandardSemver() {
        assertEquals(SemVer(1, 0, 0), SemVer.fromString("1.0.0"))
        assertEquals(SemVer(1, 2, 3), SemVer.fromString("1.2.3"))
        assertEquals(SemVer(10, 20, 30), SemVer.fromString("10.20.30"))
    }

    @Test
    fun acceptsLeadingV() {
        assertEquals(SemVer(1, 0, 0), SemVer.fromString("v1.0.0"))
        assertEquals(SemVer(1, 10, 2), SemVer.fromString("V1.10.2"))
    }

    @Test
    fun rejectsPrereleaseAndMetadata() {
        // 预发版后缀：解析失败 → 调用方静默不提示。
        // 这是设计取舍：GitHub /releases/latest 默认不返回 prerelease，撞上 -rc 说明
        // tag 格式异常（手动 prerelease + 标了 latest 之类），宁可不提示也别误判。
        assertNull(SemVer.fromString("1.0.0-rc1"))
        assertNull(SemVer.fromString("v1.0.0-beta"))
        assertNull(SemVer.fromString("1.0.0+build123"))
    }

    @Test
    fun rejectsWrongSegmentCount() {
        assertNull(SemVer.fromString("1"))
        assertNull(SemVer.fromString("1.0"))
        assertNull(SemVer.fromString("1.0.0.1"))
        assertNull(SemVer.fromString(""))
        assertNull(SemVer.fromString(null))
        assertNull(SemVer.fromString("   "))
    }

    @Test
    fun rejectsNonNumericSegments() {
        assertNull(SemVer.fromString("a.b.c"))
        assertNull(SemVer.fromString("1.0.beta"))
        assertNull(SemVer.fromString("1.x.0"))
    }

    @Test
    fun rejectsDateLikeTags() {
        // 防止 "2024.05.25" 这种日期 tag 把数字溢出当 major 用，
        // 触发"装在 2024 年 app 的用户被告知有第 25 个 patch 版本"的鬼故事。
        assertNull(SemVer.fromString("2024.05.25"))
        assertNull(SemVer.fromString("1900.1.1"))
        assertNull(SemVer.fromString("v2099.12.31"))
    }

    @Test
    fun comparesCorrectly() {
        assertTrue(SemVer(2, 0, 0) > SemVer(1, 99, 99))
        assertTrue(SemVer(1, 1, 0) > SemVer(1, 0, 99))
        assertTrue(SemVer(1, 0, 1) > SemVer(1, 0, 0))
        assertEquals(0, SemVer(1, 2, 3).compareTo(SemVer(1, 2, 3)))
        assertTrue(SemVer(0, 9, 9) < SemVer(1, 0, 0))
    }
}
