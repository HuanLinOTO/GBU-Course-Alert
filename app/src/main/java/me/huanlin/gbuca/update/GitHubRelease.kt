package me.huanlin.gbuca.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GitHub Release 自更新的 DTO：仅声明用到的字段，其余由 ignoreUnknownKeys 忽略。
 * 响应样例见 docs/plans/2026-09-09-github-self-update-design.md（已实测核实）。
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("name") val name: String = "",
    /** 更新说明（Markdown 原文），可能为 null。 */
    @SerialName("body") val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    @SerialName("name") val name: String = "",
    @SerialName("size") val size: Long = 0,
    /** GitHub 官方校验和（如 "sha256:…"），旧资产可能没有；存在时下载后强制校验。 */
    @SerialName("digest") val digest: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String = "",
    @SerialName("content_type") val contentType: String? = null,
) {
    val isApk: Boolean
        get() = name.endsWith(".apk", ignoreCase = true) || contentType == APK_CONTENT_TYPE

    companion object {
        const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"
    }
}

/** 版本比较与 APK 资产选择：纯 JVM 逻辑，供单元测试。 */
object AppUpdateChecks {

    /** 复用同一 Json 实例（重复创建代价高）。 */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 解析版本标签为数字段列表："v0.0.8" → [0,0,8]，"1.2" → [1,2]。
     * 非 `v?数字(.数字)*` 形态（如 "beta-1"、"nightly"）返回 null。
     */
    fun parseVersion(tag: String): List<Int>? {
        val m = Regex("""v?(\d+(?:\.\d+)*)""").matchEntire(tag.trim()) ?: return null
        return m.groupValues[1].split('.').map { it.toInt() }
    }

    /**
     * [current]（versionName，如 "0.0.8"）是否旧于 release 标签（如 "v0.0.9"）。
     * 缺位按 0 补齐（1.0 == 1.0.0）；任一侧无法解析 → false：宁可漏报更新，绝不误弹窗。
     */
    fun isNewerVersion(current: String, tag: String): Boolean {
        val c = parseVersion(current) ?: return false
        val t = parseVersion(tag) ?: return false
        for (i in 0 until maxOf(c.size, t.size)) {
            val cur = c.getOrElse(i) { 0 }
            val rel = t.getOrElse(i) { 0 }
            if (cur != rel) return rel > cur
        }
        return false
    }

    /**
     * 从 release 资产挑安装包：优先 CI 命名的 *-release.apk；
     * 排除 debug 包——其签名与正式版不同，覆盖安装必然失败，绝不推给用户。
     */
    fun pickApkAsset(assets: List<GitHubAsset>): GitHubAsset? {
        val apks = assets.filter { it.isApk }
        return apks.firstOrNull { it.name.endsWith("-release.apk", ignoreCase = true) }
            ?: apks.firstOrNull { !it.name.contains("debug", ignoreCase = true) }
    }

    /** 解析 /releases/latest 响应体。 */
    fun parseRelease(body: String): GitHubRelease = json.decodeFromString(body)
}
