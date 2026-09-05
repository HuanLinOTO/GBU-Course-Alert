package me.huanlin.gbuca.data

sealed class GbuException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : GbuException("网络错误: ${cause.message}", cause)
    class BadCredentials(val message0: String) : GbuException(message0)
    class NeedCaptcha : GbuException("需要验证码，请改用网页登录")
    class NeedSms : GbuException("需要短信验证码，请改用网页登录")
    class SessionExpired : GbuException("会话已过期")
    class ApiError(val body: String) : GbuException("接口异常")
}
