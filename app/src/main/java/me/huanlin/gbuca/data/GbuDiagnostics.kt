package me.huanlin.gbuca.data

/**
 * 失败诊断文本的纯函数集合：无 Android 依赖，可直接 JVM 单测。
 *
 * 只处理**响应**片段，永不接触请求表单与凭据 —— 隐私约定见 README。
 */
object GbuDiagnostics {

    /** 响应片段保留的最大字符数。 */
    const val SNIPPET_MAX = 400

    /**
     * 规范化响应片段：去控制字符、折叠连续空白、去首尾空白、超长截断加省略号。
     * 空白或 null 返回 null（调用方据此省略整行）。
     */
    fun normalizeSnippet(raw: String?, max: Int = SNIPPET_MAX): String? {
        if (raw == null) return null
        val cleaned = buildString(raw.length) {
            raw.forEach { c -> if (!c.isISOControl() || c == '\n' || c == '\t') append(c) }
        }
        val collapsed = cleaned.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return null
        return if (collapsed.length <= max) collapsed else collapsed.take(max) + "…"
    }

    /**
     * 技术细节多行文本；空字段整行省略，永不含请求参数与凭据。形如：
     * ```
     * 环节：iAAA 登录
     * 地址：https://iaaa.example.edu.cn/iaaa/oauthlogin.do
     * HTTP：404
     * 响应片段：<!DOCTYPE html>…
     * ```
     */
    fun detailOf(
        stage: GbuException.ApiError.Stage,
        url: String? = null,
        httpStatus: Int? = null,
        snippet: String? = null,
    ): String = buildList {
        add("环节：${stage.label}")
        url?.takeIf { it.isNotBlank() }?.let { add("地址：$it") }
        httpStatus?.let { add("HTTP：$it") }
        normalizeSnippet(snippet)?.let { add("响应片段：$it") }
    }.joinToString("\n")

    /** 「复制错误详情」的完整报告：用户可读消息 + 技术细节 + 版本与时间。 */
    fun reportOf(
        message: String,
        detail: String?,
        versionName: String,
        timestampMillis: Long,
    ): String = buildString {
        append(message)
        detail?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
        append("\n\n版本：").append(versionName)
        append("\n时间：").append(
            java.time.Instant.ofEpochMilli(timestampMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        )
    }
}
