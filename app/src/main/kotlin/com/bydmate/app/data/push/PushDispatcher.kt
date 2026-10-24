package com.bydmate.app.data.push

import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.backup.TelegramError
import com.bydmate.app.data.backup.TelegramSinkException
import com.bydmate.app.data.repository.SettingsRepository
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends reports and notifications through the channel chosen in the settings ([PushChannel]).
 * Telegram keeps the whole existing bot flow (the sink's calls are only routed from here); the
 * four text-only channels get the report flattened by [htmlToText]. The dispatcher never logs a
 * token, a webhook url or a secret — the clients log masked urls only.
 */
@Singleton
class PushDispatcher @Inject constructor(
    okHttpClient: OkHttpClient,
    private val sink: TelegramBackupSink,
    private val settings: SettingsRepository,
) {
    companion object {
        private const val TAG = "Push"
        private const val PARSE_MODE = "HTML"
        private const val DEFAULT_TEST_TEXT = "<b>BYDMate</b>"
    }

    private val post = PushHttpPost(okHttpClient, TAG)
    private val bark = BarkClient(post)
    private val wecom = WecomClient(post)
    private val feishu = FeishuClient(post)
    private val ding = DingClient(post)

    /** The channel reports go to now; read per send, so a switch never needs a restart. */
    suspend fun channel(): PushChannel = PushChannel.fromKey(settings.getPushChannel())

    /** Whether the selected channel has everything it needs to send. */
    suspend fun isConfigured(): Boolean = when (channel()) {
        PushChannel.TELEGRAM -> settings.getTgBackupConfig().configured
        PushChannel.BARK -> settings.getBarkDeviceKey().isNotBlank()
        PushChannel.WECOM -> settings.getWecomWebhookUrl().isNotBlank()
        PushChannel.FEISHU -> settings.getFeishuWebhookUrl().isNotBlank()
        PushChannel.DINGTALK -> settings.getDingWebhookUrl().isNotBlank()
    }

    /**
     * The chat id an outbox entry is queued under: the bot's chat on Telegram, a fixed 0 on the
     * other channels (they have no chat, so the drain skips its chat-changed check for them).
     */
    suspend fun reportChatId(): Long =
        if (channel() == PushChannel.TELEGRAM) settings.getTgBackupConfig().chatId ?: 0L else 0L

    /** [html] (Telegram HTML) through the selected channel: markup on Telegram, plain text elsewhere. */
    suspend fun sendReportText(html: String): Result<Unit> = when (channel()) {
        PushChannel.TELEGRAM -> {
            val config = settings.getTgBackupConfig()
            val chatId = config.chatId
            if (config.configured && chatId != null) sink.sendMessage(config.token, chatId, html, PARSE_MODE, linkPreview = false)
            else Result.failure(TelegramSinkException(TelegramError.NO_CHAT))
        }
        PushChannel.BARK -> {
            // Bark splits a notification into a title and a body: the report's first line leads.
            val lines = htmlToText(html).trim().lines()
            val title = lines.firstOrNull().orEmpty()
            val body = lines.drop(1).joinToString("\n").trim().ifBlank { title }
            bark.send(settings.getBarkServer(), settings.getBarkDeviceKey(), title, body)
        }
        PushChannel.WECOM -> wecom.send(settings.getWecomWebhookUrl(), htmlToText(html))
        PushChannel.FEISHU -> feishu.send(settings.getFeishuWebhookUrl(), settings.getFeishuSecret(), htmlToText(html))
        PushChannel.DINGTALK -> ding.send(settings.getDingWebhookUrl(), settings.getDingSecret(), htmlToText(html))
    }

    /** A location pin: native and silent on Telegram, a text with a map link on the other channels. */
    suspend fun sendLocation(latitude: Double, longitude: Double): Result<Unit> = when (channel()) {
        PushChannel.TELEGRAM -> {
            val config = settings.getTgBackupConfig()
            val chatId = config.chatId
            if (config.configured && chatId != null) sink.sendLocation(config.token, chatId, latitude, longitude)
            else Result.failure(TelegramSinkException(TelegramError.NO_CHAT))
        }
        else -> sendReportText(locationText(latitude, longitude))
    }

    /**
     * Only Telegram takes files; on the other channels this fails with UNSUPPORTED and the caller
     * degrades to a text note ([sendReportText]) saying the file stayed on the head unit.
     */
    suspend fun sendDocument(file: File, caption: String): Result<Unit> = when (channel()) {
        PushChannel.TELEGRAM -> {
            val config = settings.getTgBackupConfig()
            val chatId = config.chatId
            if (config.configured && chatId != null) sink.sendDocument(config.token, chatId, file, caption)
            else Result.failure(TelegramSinkException(TelegramError.NO_CHAT))
        }
        else -> Result.failure(PushException(PushError.UNSUPPORTED))
    }

    /** «Отправить тестовое уведомление» in Settings: one message, end to end through [channel]. */
    suspend fun sendTest(text: String = DEFAULT_TEST_TEXT): Result<Unit> = sendReportText(text)

    /** «📍 53.900000, 27.560000 https://…» — the amap pin opens on the head unit's own map app. */
    private fun locationText(latitude: Double, longitude: Double): String {
        val la = String.format(Locale.US, "%.6f", latitude)
        val lo = String.format(Locale.US, "%.6f", longitude)
        return "📍 $la, $lo https://uri.amap.com/marker?position=$lo,$la"
    }
}
