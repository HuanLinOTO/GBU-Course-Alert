package me.huanlin.gbuca.data

sealed class GbuException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : GbuException("网络错误: ${cause.message}", cause)
    class BadCredentials(val message0: String) : GbuException(message0)
    class NeedCaptcha : GbuException("需要验证码，请改用网页登录")
    class NeedSms : GbuException("需要短信验证码，请改用网页登录")
    class SessionExpired : GbuException("会话已过期")

    /**
     * 接口 / 协议层失败。
     *
     * [summary] 面向用户：说明失败环节与最可能原因，直接展示，不套「同步失败：」前缀。
     * [detail] 面向排查：环节 / 地址 / HTTP 状态 / 响应片段，**永不含请求表单与凭据**。
     */
    class ApiError(
        val stage: Stage,
        val summary: String,
        val url: String? = null,
        val httpStatus: Int? = null,
        val snippet: String? = null,
    ) : GbuException(summary) {

        /** 失败环节；[label] 直接进用户可见的诊断文本。 */
        enum class Stage(val label: String) {
            IaaaLogin("iAAA 登录"),
            JwxtSession("教务会话"),
            CourseApi("教务接口"),
            Parse("数据解析"),
        }

        /** 技术细节（多行）；由纯函数生成，可单测。 */
        val detail: String get() = GbuDiagnostics.detailOf(stage, url, httpStatus, snippet)
    }
}
