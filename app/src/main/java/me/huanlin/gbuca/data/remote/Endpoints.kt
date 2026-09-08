package me.huanlin.gbuca.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 用户配置的服务端点（OOBE 中填写，存于 SettingsStore）。
 * 仅存裸主机名，所有 URL 现场以 https 拼接。
 */
data class Endpoints(
    val jwxtHost: String,
    val iaaaHost: String,
) {
    val jwxtBase: String get() = "https://$jwxtHost"
    val iaaaBase: String get() = "https://$iaaaHost/iaaa/"
    val redirUrl: String get() = "$jwxtBase/oauth/login/code"
}

/** 主机名规范化：接受裸主机名或完整 URL（含 scheme/路径/端口），剥出裸主机名；非法返回 null。 */
object HostNormalizer {

    /** 返回规范化主机名（小写），无效或不含点（如 localhost）时返回 null。 */
    fun normalize(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val withScheme = if (s.contains("://")) s else "https://$s"
        val host = withScheme.toHttpUrlOrNull()?.host?.takeIf { it.isNotEmpty() } ?: return null
        return host.takeIf { it.contains('.') }
    }
}
