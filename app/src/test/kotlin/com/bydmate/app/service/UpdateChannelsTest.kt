package com.bydmate.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelsTest {

    // (a) The chosen channel leads; every channel appears exactly once.
    @Test
    fun sources_put_choice_first_without_duplicates() {
        for (choice in listOf(
            UpdateChecker.CHANNEL_GITHUB, UpdateChecker.CHANNEL_GITEE,
            UpdateChecker.CHANNEL_GHPROXY, UpdateChecker.CHANNEL_GHPROXY2,
        )) {
            val sources = UpdateChannels.sources(choice)
            assertEquals(4, sources.size)
            assertEquals(sources.size, sources.distinct().size)
            assertEquals(choice, firstChannelOf(sources[0]))
        }
    }

    // (b) Unknown values fall back to github (the historical default).
    @Test
    fun unknown_choice_falls_back_to_github() {
        assertEquals(UpdateChannels.GITHUB_API, UpdateChannels.sources("nope")[0].api)
    }

    // (c) ghproxy channels keep the GitHub API for checks but prefix the APK download URL;
    // the plain channels never rewrite it.
    @Test
    fun apk_url_prefix_applies_only_to_mirrors() {
        val mirrors = UpdateChannels.sources(UpdateChecker.CHANNEL_GHPROXY2)
        assertTrue(mirrors[0].apkUrl("https://github.com/x/BYDMate-3.2.apk")
            .startsWith("https://ghproxy.net/https://github.com/"))
        assertTrue(mirrors[1].apkUrl("https://github.com/x/a.apk").startsWith("https://github.com/"))
    }

    // (d) GitHub shape: tag_name strips the v prefix, first *.apk asset wins.
    @Test
    fun parseRelease_github_shape() {
        val release = UpdateChannels.parseRelease("""
            {"tag_name":"v3.2.1","body":"notes here","assets":[
              {"name":"sources.zip","browser_download_url":"https://github.com/x/s.zip"},
              {"name":"BYDMate-3.2.1.apk","browser_download_url":"https://github.com/x/BYDMate-3.2.1.apk"}]}
        """.trimIndent())
        assertEquals("3.2.1", release?.tag)
        assertEquals("https://github.com/x/BYDMate-3.2.1.apk", release?.apkUrl)
        assertEquals("notes here", release?.notes)
    }

    // (e) Gitee shape parses the same way (tag_name + assets named *.apk).
    @Test
    fun parseRelease_gitee_shape() {
        val release = UpdateChannels.parseRelease("""
            {"tag_name":"4.0.0","body":"cn release","assets":[
              {"name":"BYDMate-cn-4.0.0.apk","browser_download_url":"https://gitee.com/x/y/releases/download/4.0.0/a.apk"}]}
        """.trimIndent())
        assertEquals("4.0.0", release?.tag)
        assertEquals("https://gitee.com/x/y/releases/download/4.0.0/a.apk", release?.apkUrl)
    }

    // (f) Release without an APK asset: tag survives, apkUrl is null (the checker records the
    // failure and moves to the next channel); a tagless or unparseable body yields null.
    @Test
    fun parseRelease_without_apk_or_tag() {
        val noApk = UpdateChannels.parseRelease("""{"tag_name":"v9","assets":[]}""")
        assertEquals("9", noApk?.tag)
        assertNull(noApk?.apkUrl)
        assertNull(UpdateChannels.parseRelease("""{"body":"no tag"}"""))
        assertNull(UpdateChannels.parseRelease("not json"))
    }

    private fun firstChannelOf(source: UpdateChannels.Source): String = when (source.api) {
        UpdateChannels.GITEE_API -> UpdateChecker.CHANNEL_GITEE
        UpdateChannels.GITHUB_API -> if (source.apkPrefix.startsWith("https://gh-proxy.com/")) {
            UpdateChecker.CHANNEL_GHPROXY
        } else if (source.apkPrefix.startsWith("https://ghproxy.net/")) {
            UpdateChecker.CHANNEL_GHPROXY2
        } else UpdateChecker.CHANNEL_GITHUB
        else -> "?"
    }
}
