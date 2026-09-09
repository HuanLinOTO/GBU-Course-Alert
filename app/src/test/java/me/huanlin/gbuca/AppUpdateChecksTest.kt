package me.huanlin.gbuca

import me.huanlin.gbuca.update.AppUpdateChecks
import me.huanlin.gbuca.update.GitHubAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** 应用自更新的纯逻辑：版本解析/比较、APK 资产选择、release JSON 解析。 */
class AppUpdateChecksTest {

    // ---- parseVersion ----

    @Test
    fun parseVersion_stripsVPrefix() {
        assertEquals(listOf(0, 0, 8), AppUpdateChecks.parseVersion("v0.0.8"))
    }

    @Test
    fun parseVersion_acceptsPlainAndShortForms() {
        assertEquals(listOf(0, 0, 8), AppUpdateChecks.parseVersion("0.0.8"))
        assertEquals(listOf(1, 2), AppUpdateChecks.parseVersion("1.2"))
        assertEquals(listOf(1), AppUpdateChecks.parseVersion("v1"))
    }

    @Test
    fun parseVersion_rejectsNonNumericTags() {
        assertEquals(null, AppUpdateChecks.parseVersion("nightly"))
        assertEquals(null, AppUpdateChecks.parseVersion("beta-1"))
        assertEquals(null, AppUpdateChecks.parseVersion("release v1"))
        assertEquals(null, AppUpdateChecks.parseVersion(""))
    }

    // ---- isNewerVersion ----

    @Test
    fun newerPatchTagIsNewer() {
        assertTrue(AppUpdateChecks.isNewerVersion("0.0.8", "v0.0.9"))
        assertTrue(AppUpdateChecks.isNewerVersion("0.0.8", "0.0.9"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(AppUpdateChecks.isNewerVersion("0.0.8", "v0.0.8"))
        assertFalse(AppUpdateChecks.isNewerVersion("0.0.8", "v0.0.8"))
    }

    @Test
    fun olderTagIsNotNewer() {
        assertFalse(AppUpdateChecks.isNewerVersion("0.0.9", "v0.0.8"))
    }

    @Test
    fun higherComponentBeatsLower() {
        assertTrue(AppUpdateChecks.isNewerVersion("0.0.9", "v0.1.0"))
        assertTrue(AppUpdateChecks.isNewerVersion("0.9.9", "v1.0.0"))
    }

    @Test
    fun missingComponentsPadWithZero() {
        assertFalse(AppUpdateChecks.isNewerVersion("1.0", "v1.0.0"))
        assertTrue(AppUpdateChecks.isNewerVersion("1.0", "v1.0.1"))
        assertFalse(AppUpdateChecks.isNewerVersion("1.0.1", "v1.0"))
    }

    @Test
    fun unparseableSidesNeverReportNewer() {
        // 宁可漏报，绝不误弹窗
        assertFalse(AppUpdateChecks.isNewerVersion("0.0.8", "beta-1"))
        assertFalse(AppUpdateChecks.isNewerVersion("dev", "v0.0.9"))
    }

    // ---- pickApkAsset ----

    private fun asset(name: String, contentType: String? = GitHubAsset.APK_CONTENT_TYPE) =
        GitHubAsset(name = name, size = 1024L, downloadUrl = "https://example.com/$name", contentType = contentType)

    @Test
    fun prefersReleaseApkOverDebug() {
        val assets = listOf(
            asset("GBU-Course-Alert-v0.0.9-debug.apk"),
            asset("GBU-Course-Alert-v0.0.9-release.apk"),
        )
        assertTrue(AppUpdateChecks.pickApkAsset(assets)!!.name.endsWith("-release.apk"))
    }

    @Test
    fun debugOnlyReleaseHasNoInstallableAsset() {
        // debug 包签名与正式版不同，覆盖安装必然失败，不推给用户
        assertEquals(null, AppUpdateChecks.pickApkAsset(listOf(asset("GBU-Course-Alert-v0.0.9-debug.apk"))))
    }

    @Test
    fun ignoresNonApkAssets() {
        val assets = listOf(asset("source.zip", "application/zip"), asset("notes.txt", "text/plain"))
        assertEquals(null, AppUpdateChecks.pickApkAsset(assets))
    }

    @Test
    fun fallsBackToPlainApkWithoutReleaseSuffix() {
        val plain = asset("app.apk")
        assertEquals(plain.name, AppUpdateChecks.pickApkAsset(listOf(plain))!!.name)
    }

    @Test
    fun emptyAssetsReturnsNull() {
        assertEquals(null, AppUpdateChecks.pickApkAsset(emptyList()))
    }

    // ---- parseRelease ----

    @Test
    fun parsesRealLatestReleasePayload() {
        // 取自 2026-09-09 对 /releases/latest 的实测响应（裁剪无关字段）
        val body = """
            {
              "url": "https://api.github.com/repos/HuanLinOTO/GBU-Course-Alert/releases/385247549",
              "tag_name": "v0.0.8",
              "name": "GBU课表 v0.0.8",
              "draft": false,
              "prerelease": false,
              "body": "## 更新内容\n\n- 课表课块按真实时间绘制\n",
              "assets": [
                {
                  "name": "GBU-Course-Alert-v0.0.8-debug.apk",
                  "size": 16858634,
                  "digest": "sha256:e786c42eef68b221d8e4f3adcf1aedf12ee91db1e539210c2b4e8bc943e9c427",
                  "content_type": "application/vnd.android.package-archive",
                  "browser_download_url": "https://github.com/HuanLinOTO/GBU-Course-Alert/releases/download/v0.0.8/GBU-Course-Alert-v0.0.8-debug.apk"
                },
                {
                  "name": "GBU-Course-Alert-v0.0.8-release.apk",
                  "size": 2781692,
                  "digest": "sha256:32271fd0c60eb3a64484b49a3de0240545c82eaff393eebf87cd471caddc6ea2",
                  "content_type": "application/vnd.android.package-archive",
                  "browser_download_url": "https://github.com/HuanLinOTO/GBU-Course-Alert/releases/download/v0.0.8/GBU-Course-Alert-v0.0.8-release.apk"
                }
              ]
            }
        """.trimIndent()
        val release = AppUpdateChecks.parseRelease(body)
        assertTrue(AppUpdateChecks.isNewerVersion("0.0.7", release.tagName))
        assertEquals(2, release.assets.size)
        val apk = AppUpdateChecks.pickApkAsset(release.assets)!!
        assertTrue(apk.name.endsWith("-release.apk"))
        assertTrue(apk.digest!!.startsWith("sha256:"))
        assertTrue(apk.downloadUrl.endsWith("GBU-Course-Alert-v0.0.8-release.apk"))
    }

    @Test
    fun parseToleratesUnknownFieldsAndMissingDigest() {
        val release = AppUpdateChecks.parseRelease(
            """{"tag_name":"v9.9.9","unknown_field":123,"uploader":{"login":"bot"},"assets":[]}""",
        )
        assertEquals("v9.9.9", release.tagName)
        assertEquals(0, release.assets.size)
    }
}
