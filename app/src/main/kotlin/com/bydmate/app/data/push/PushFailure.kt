package com.bydmate.app.data.push

import java.io.IOException

/**
 * Why a push send failed, shared by [com.bydmate.app.data.backup.TelegramSinkException] and
 * [PushException], so call sites and the settings screen translate one set of keys per channel.
 */
interface PushFailure {
    /** Worth retrying later; a bad url or an unsupported request will not heal by itself. */
    val transient: Boolean

    /** Stable key for settings and the dump: `BAD_URL`, `HTTP:502`, … */
    val key: String
}

/** Why a non-Telegram push-channel call failed; semantics mirror TelegramError one to one. */
enum class PushError { NO_NETWORK, BAD_RESPONSE, BAD_URL, UNSUPPORTED, HTTP }

/**
 * A failed webhook push. [httpCode] is null when the request never got an HTTP answer. [transient]
 * = worth retrying later (no network, garbled answer, rate limit, server error); a bad url or an
 * unsupported call will not heal by itself.
 */
class PushException(val error: PushError, val httpCode: Int? = null, cause: Throwable? = null) :
    IOException("$error${httpCode?.let { " $it" }.orEmpty()}", cause), PushFailure {

    override val transient: Boolean
        get() = when (error) {
            PushError.NO_NETWORK, PushError.BAD_RESPONSE -> true
            PushError.HTTP -> httpCode == HTTP_TOO_MANY_REQUESTS || (httpCode ?: 0) >= HTTP_SERVER_ERROR
            else -> false
        }

    /** Stable key for settings and the dump: `BAD_URL`, `HTTP:502`, … */
    override val key: String get() = if (error == PushError.HTTP) "HTTP:$httpCode" else error.name

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
