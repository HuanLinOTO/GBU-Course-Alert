package me.huanlin.gbuca

import me.huanlin.gbuca.data.GbuDiagnostics
import me.huanlin.gbuca.data.GbuException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GbuDiagnosticsTest {

    @Test fun `null snippet stays null`() = assertNull(GbuDiagnostics.normalizeSnippet(null))

    @Test fun `blank snippet becomes null`() =
        assertNull(GbuDiagnostics.normalizeSnippet("  \n\t  "))

    @Test fun `whitespace collapsed`() =
        assertEquals("a b c", GbuDiagnostics.normalizeSnippet("a\n\n  b\t c"))

    @Test fun `control characters removed`() =
        assertEquals("ab", GbuDiagnostics.normalizeSnippet("a\u0000\u0007b"))

    @Test fun `long snippet truncated with ellipsis`() {
        val out = GbuDiagnostics.normalizeSnippet("x".repeat(500), max = 10)
        assertEquals("xxxxxxxxxx…", out)
    }

    @Test fun `detail lists stage url status and snippet`() {
        val d = GbuDiagnostics.detailOf(
            GbuException.ApiError.Stage.CourseApi,
            url = "https://jwxt.example.edu.cn/Xsxk/queryYxkc",
            httpStatus = 404,
            snippet = "<html>not found</html>",
        )
        assertTrue(d.contains("环节：教务接口"))
        assertTrue(d.contains("地址：https://jwxt.example.edu.cn/Xsxk/queryYxkc"))
        assertTrue(d.contains("HTTP：404"))
        assertTrue(d.contains("响应片段：<html>not found</html>"))
    }

    @Test fun `detail omits empty fields`() {
        val d = GbuDiagnostics.detailOf(GbuException.ApiError.Stage.IaaaLogin)
        assertEquals("环节：iAAA 登录", d)
    }

    @Test fun `detail never contains credentials`() {
        val d = GbuDiagnostics.detailOf(
            GbuException.ApiError.Stage.IaaaLogin,
            url = "https://iaaa.example.edu.cn/iaaa/oauthlogin.do",
            httpStatus = 200,
            snippet = "{\"success\":false}",
        )
        assertFalse(d.contains("password"))
        assertFalse(d.contains("userName"))
    }

    @Test fun `report appends version and time`() {
        val r = GbuDiagnostics.reportOf(
            message = "课表接口返回 HTTP 404（教务地址或学期可能不正确）",
            detail = "环节：教务接口",
            versionName = "0.0.6",
            timestampMillis = 1_700_000_000_000L,
        )
        assertTrue(r.startsWith("课表接口返回 HTTP 404（教务地址或学期可能不正确）\n\n环节：教务接口"))
        assertTrue(r.contains("版本：0.0.6"))
        assertTrue(r.lines().last().startsWith("时间："))
    }

    @Test fun `report without detail still carries version`() {
        val r = GbuDiagnostics.reportOf("网络错误", null, "0.0.6", 1_700_000_000_000L)
        assertTrue(r.startsWith("网络错误\n\n版本：0.0.6"))
    }

    @Test fun `api error message equals summary and detail is built`() {
        val e = GbuException.ApiError(
            stage = GbuException.ApiError.Stage.Parse,
            summary = "课表接口返回的数据无法解析（接口可能已变更）",
            url = "https://jwxt.example.edu.cn/Xsxk/queryYxkc",
            httpStatus = 200,
            snippet = "not-json",
        )
        assertEquals("课表接口返回的数据无法解析（接口可能已变更）", e.message)
        assertTrue(e.detail.contains("环节：数据解析"))
        assertTrue(e.detail.contains("响应片段：not-json"))
    }

    @Test fun `api error stage labels are user facing`() {
        assertEquals("iAAA 登录", GbuException.ApiError.Stage.IaaaLogin.label)
        assertEquals("教务会话", GbuException.ApiError.Stage.JwxtSession.label)
        assertEquals("教务接口", GbuException.ApiError.Stage.CourseApi.label)
        assertEquals("数据解析", GbuException.ApiError.Stage.Parse.label)
    }
}
