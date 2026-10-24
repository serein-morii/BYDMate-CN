package com.bydmate.app.service

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.bydmate.app.R
import com.bydmate.app.util.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateChecker @Inject constructor(
    private val httpClient: OkHttpClient,
    private val appStrings: AppStrings,
) {
    companion object {
        private const val PREFS_NAME = "update_prefs"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_AUTO_CHECK = "auto_check_enabled"
        private const val KEY_LAST_SEEN_VERSION = "last_seen_version"
        private const val KEY_UPDATE_CHANNEL = "update_channel"
        private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes (protects only against repeated launches within one session)

        /** Default release channel; see [UpdateChannels]. */
        const val DEFAULT_UPDATE_CHANNEL = UpdateChannels.DEFAULT_CHANNEL

        /** Channel ids for the settings UI, in display order. */
        const val CHANNEL_GITHUB = UpdateChannels.CHANNEL_GITHUB
        const val CHANNEL_GITEE = UpdateChannels.CHANNEL_GITEE
        const val CHANNEL_GHPROXY = UpdateChannels.CHANNEL_GHPROXY
        const val CHANNEL_GHPROXY2 = UpdateChannels.CHANNEL_GHPROXY2

        fun isAutoCheckEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_CHECK, true)

        fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
        }

        /** Release channel the update check tries first (UpdateChannels.*). */
        fun getUpdateChannel(context: Context): String =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_UPDATE_CHANNEL, DEFAULT_UPDATE_CHANNEL)
                ?: DEFAULT_UPDATE_CHANNEL

        fun setUpdateChannel(context: Context, channel: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_UPDATE_CHANNEL, channel).apply()
        }

        fun getLastSeenVersion(context: Context): String? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_SEEN_VERSION, null)

        fun setLastSeenVersion(context: Context, version: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_SEEN_VERSION, version).apply()
        }
    }

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    /**
     * Checks the release channels in order — the user's choice first, the rest as fallbacks —
     * and returns the first NEWER release found. A channel that cannot answer (network, HTTP,
     * parse, release without an APK) just moves the check to the next one; a channel that did
     * answer settles "is there an update" even when later channels failed, so only a check
     * where every channel failed throws. A walled-off GitHub does not blind the updater while
     * Gitee answers.
     */
    suspend fun checkForUpdate(context: Context, forceCheck: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        val now = System.currentTimeMillis()

        if (!forceCheck && now - lastCheck < CHECK_INTERVAL_MS) return@withContext null

        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        val currentVersion = getAppVersion(context)
        var lastFailure: Exception? = null
        var answered = false
        for (source in UpdateChannels.sources(getUpdateChannel(context))) {
            try {
                val release = fetchRelease(source) ?: continue
                answered = true
                if (!isNewer(release.tag, currentVersion)) continue
                if (release.apkUrl == null) throw Exception("Нет APK в релизе ${release.tag}")
                return@withContext UpdateInfo(
                    version = release.tag,
                    downloadUrl = source.apkUrl(release.apkUrl),
                    releaseNotes = release.notes
                )
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        if (answered) return@withContext null // every answer agreed: up to date
        throw lastFailure ?: Exception("Нет доступных источников обновлений")
    }

    /** One channel API call; throws on network/HTTP problems, null only for a tagless body. */
    private fun fetchRelease(source: UpdateChannels.Source): UpdateChannels.Release? {
        val request = Request.Builder()
            .url(source.api)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "BYDMate-UpdateCheck")
            .build()
        val body = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Releases API: HTTP ${response.code}")
            }
            response.body?.string() ?: throw Exception("Пустой ответ от Releases API")
        }
        return UpdateChannels.parseRelease(body)
    }

    /**
     * Download APK and report progress via [onProgress] callback.
     * Calls [onProgress] with status strings like "Скачивание: 45%".
     * When done, triggers the install dialog.
     */
    suspend fun downloadAndInstall(
        context: Context,
        update: UpdateInfo,
        onProgress: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Delete old file if exists
        val destFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "BYDMate-${update.version}.apk"
        )
        if (destFile.exists()) destFile.delete()

        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("BYDMate ${update.version}")
            .setDescription(appStrings.get(R.string.update_download_notification_description))
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "BYDMate-${update.version}.apk"
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        val downloadId = downloadManager.enqueue(request)
        onProgress(appStrings.get(R.string.update_downloading_start))

        // Poll download progress
        var finished = false
        while (!finished) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            // .use{} closes the cursor on every path — the old code only closed it inside the
            // moveToFirst() block, leaking one cursor per poll when the row was momentarily absent.
            val rowPresent = downloadManager.query(query)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                when (status) {
                    DownloadManager.STATUS_RUNNING -> {
                        val total = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        )
                        val downloaded = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        )
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            onProgress(appStrings.get(R.string.update_downloading_progress, pct))
                        }
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        finished = true
                        onProgress(appStrings.get(R.string.update_downloading_done))
                    }
                    DownloadManager.STATUS_FAILED -> {
                        finished = true
                        val reason = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                        )
                        throw Exception(appStrings.get(R.string.update_download_error, reason))
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        onProgress(appStrings.get(R.string.update_downloading_paused))
                    }
                }
                true
            } ?: false
            // Download row vanished (query null or cleared by the user): stop instead of spinning
            // forever every 500 ms with no row that could ever flip `finished`.
            if (!rowPresent) {
                throw Exception(appStrings.get(R.string.update_download_error, -1))
            }
            if (!finished) {
                kotlinx.coroutines.delay(500)
            }
        }

        // Trigger install
        withContext(Dispatchers.Main) {
            installApk(context, update.version)
        }
    }

    private fun installApk(context: Context, version: String) {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "BYDMate-$version.apk"
        )
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun getAppVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val l = local.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }
}
