package com.bydmate.app.service

import org.json.JSONObject

/**
 * CN-friendly release channels, DiPlay-style: the user's choice is tried first, the rest
 * follow as fallbacks. github/gitee answer the API themselves; the ghproxy pair only mirror
 * the APK download (the API check stays on GitHub; a dead GitHub API falls through to gitee).
 */
internal object UpdateChannels {
    const val CHANNEL_GITHUB = "github"
    const val CHANNEL_GITEE = "gitee"
    const val CHANNEL_GHPROXY = "ghproxy"
    const val CHANNEL_GHPROXY2 = "ghproxy2"
    const val DEFAULT_CHANNEL = CHANNEL_GITHUB

    const val GITHUB_API = "https://api.github.com/repos/AndyShaman/BYDMate/releases/latest"
    const val GITEE_API = "https://gitee.com/api/v5/repos/serein-morii/BYDMate-CN/releases/latest"

    /** One release source: an API endpoint plus an optional prefix put before the APK URL. */
    data class Source(val api: String, val apkPrefix: String = "") {
        fun apkUrl(url: String): String = apkPrefix + url
    }

    private val github = Source(GITHUB_API)
    private val gitee = Source(GITEE_API)
    private val mirrorOne = Source(GITHUB_API, "https://gh-proxy.com/")
    private val mirrorTwo = Source(GITHUB_API, "https://ghproxy.net/")

    /** [choice] first (unknown values fall back to github), then every other source once. */
    fun sources(choice: String): List<Source> {
        val first = when (choice) {
            CHANNEL_GITEE -> gitee
            CHANNEL_GHPROXY -> mirrorOne
            CHANNEL_GHPROXY2 -> mirrorTwo
            else -> github
        }
        return listOf(first, github, gitee, mirrorOne, mirrorTwo).distinct()
    }

    /** The release fields both APIs carry; [apkUrl] is null when no *.apk asset exists. */
    data class Release(val tag: String, val apkUrl: String?, val notes: String)

    /** Parses a latest-release JSON of the GitHub or Gitee shape: tag_name, body, assets[]
     *  with name/browser_download_url; the first asset named *.apk wins. Unparseable or
     *  tagless bodies yield null so the next channel is tried. */
    fun parseRelease(body: String): Release? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val tag = json.optString("tag_name", "").removePrefix("v")
        if (tag.isEmpty()) return null
        val assets = json.optJSONArray("assets") ?: return Release(tag, null, json.optString("body", ""))
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name", "").endsWith(".apk")) {
                val url = asset.optString("browser_download_url", "")
                if (url.isNotBlank()) return Release(tag, url, json.optString("body", ""))
            }
        }
        return Release(tag, null, json.optString("body", ""))
    }
}
