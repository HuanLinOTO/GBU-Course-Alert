package me.huanlin.gbuca.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** queryYxkc 响应（仅声明用到的字段，其余忽略）。 */
@Serializable
data class YxkcResponse(
    @SerialName("yxkcList") val yxkcList: List<YxkcItem> = emptyList(),
    @SerialName("kbjclist") val kbjclist: List<KbjcItem> = emptyList(),
    @SerialName("xsxkPage") val page: Page? = null,
) {
    @Serializable
    data class Page(
        @SerialName("pageNum") val pageNum: Int = 1,
        @SerialName("pageSize") val pageSize: Int = 15,
        @SerialName("total") val total: Long = 0,
    )
}

@Serializable
data class YxkcItem(
    @SerialName("rwh") val rwh: String = "",
    @SerialName("kcmc") val kcmc: String = "",
    @SerialName("kcmc_en") val kcmcEn: String? = null,
    @SerialName("kcdm") val kcdm: String? = null,
    @SerialName("kxh") val kxh: String? = null,
    @SerialName("rwmc") val rwmc: String? = null,
    @SerialName("xf") val xf: Double = 0.0,
    @SerialName("xs") val xs: Double = 0.0,
    @SerialName("kcxzmc") val kcxzmc: String? = null,
    @SerialName("kclbmc") val kclbmc: String? = null,
    @SerialName("kkyxmc") val kkyxmc: String? = null,
    @SerialName("kcxx") val kcxx: String = "",
    @SerialName("xksj") val xksj: String? = null,
    @SerialName("dnrl") val dnrl: Int? = null,
    @SerialName("dnyxrs") val dnyxrs: Int? = null,
)

@Serializable
data class KbjcItem(
    @SerialName("XJ") val xj: Int? = null,
    @SerialName("KSSJ") val kssj: String? = null,
    @SerialName("JSSJ") val jssj: String? = null,
    @SerialName("DJ") val dj: Int? = null,
    @SerialName("SXW") val sxw: Int? = null,
)

/** GET component/getXnxqByRq 响应（只取需要的字段）。zc：未开学为 0，第 1 周 = 1。 */
@Serializable
data class XnxqByRqResponse(
    val code: Int = 0,
    val content: Content? = null,
) {
    @Serializable
    data class Content(val rqxnxq: Rqxnxq? = null)

    @Serializable
    data class Rqxnxq(
        val xn: String? = null,
        val xq: String? = null,
        val zc: String? = null,
    )
}

/** 宽松解析：queryXnxq 响应结构未完全确认，按常见键名提取 [{xnxq, xn, xq, mc}]。 */
object XnxqParser {

    fun parse(body: String): List<Term> {
        val element = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) }.getOrNull()
            ?: return emptyList()
        val root = element as? JsonObject ?: return emptyList()
        val arr: List<JsonObject> = root.entries.firstNotNullOfOrNull { (k, v) ->
            if (k.contains("xnxq", ignoreCase = true) && v is JsonArray && v.jsonArray.all { it is JsonObject }) {
                @Suppress("UNCHECKED_CAST")
                v as List<JsonObject>
            } else null
        } ?: return emptyList()

        return arr.mapNotNull { o ->
            val xnxq = o.str("xnxq") ?: o.str("XNXQ") ?: o.str("dm") ?: o.str("DM") ?: return@mapNotNull null
            val xn = o.str("xn") ?: o.str("XN")
            val xq = o.str("xq") ?: o.str("XQ")
            val mc = o.str("mc") ?: o.str("MC") ?: o.str("xnxqmc")
            Term(xnxq, xn, xq, mc)
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotEmpty() }?.content?.takeIf { it.isNotBlank() }

    data class Term(val xnxq: String, val xn: String?, val xq: String?, val mc: String?)
}
