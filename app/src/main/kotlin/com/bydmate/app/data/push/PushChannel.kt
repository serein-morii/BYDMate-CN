package com.bydmate.app.data.push

import androidx.annotation.StringRes
import com.bydmate.app.R

/**
 * Where reports and notifications go. `telegram` is the pre-push default and keeps the whole
 * existing bot flow untouched; the other four are text-only (files never leave via them).
 */
enum class PushChannel(
    val key: String,
    /** The chip label in Settings; the channel names are product names, so they barely translate. */
    @StringRes val labelRes: Int,
) {
    TELEGRAM("telegram", R.string.settings_push_channel_telegram),
    BARK("bark", R.string.settings_push_channel_bark),
    WECOM("wecom", R.string.settings_push_channel_wecom),
    FEISHU("feishu", R.string.settings_push_channel_feishu),
    DINGTALK("dingtalk", R.string.settings_push_channel_dingtalk);

    companion object {
        /** Unknown or absent stored value = telegram, the behavior every install had before this. */
        fun fromKey(key: String?): PushChannel = entries.firstOrNull { it.key == key } ?: TELEGRAM
    }
}
