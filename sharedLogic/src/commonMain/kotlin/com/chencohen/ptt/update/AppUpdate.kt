package com.chencohen.ptt.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A release of the app on GitHub: its version, its APK and the SHA256SUMS that lists the APK's checksum. */
data class AppRelease(
    val version: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sumsUrl: String,
    val page: String,
    val notes: String,
)

/**
 * App updates from the GitHub releases (docs/design.md, "App updates"): the latest release (not a draft or a
 * pre-release) that has talk-<version>.apk and SHA256SUMS, whether it is newer than this app, the APK's checksum, and
 * when to look again. The download and the install are the platform's.
 */
object AppUpdate {
    const val LATEST_URL = "https://api.github.com/repos/chencohensolutions/push-to-talk-kmp/releases/latest"

    /** At most this often when the app comes to the screen: 6 hours. Check now in Settings looks at once. */
    const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    @Serializable
    private class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
    )

    @Serializable
    private class GitHubRelease(
        @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val page: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** GitHub's answer for the latest release, or null when it isn't a release this app can update to. */
    fun parseLatest(body: String): AppRelease? {
        val release = runCatching { json.decodeFromString<GitHubRelease>(body) }.getOrNull() ?: return null
        if (release.draft || release.prerelease) return null
        val version = release.tag.removePrefix("v")
        if (parts(version) == null) return null
        val apkName = "talk-$version.apk"
        val apk = release.assets.firstOrNull { it.name == apkName } ?: return null
        val sums = release.assets.firstOrNull { it.name == "SHA256SUMS" } ?: return null
        return AppRelease(version, apkName, apk.url, apk.size, sums.url, release.page, release.body.orEmpty())
    }

    /** Whether [candidate] ("1.2.0") is a later version than [current] ("1.1.0", or "1.1.0-debug"). */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate) ?: return false
        val b = parts(current) ?: return true
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The checksum SHA256SUMS lists for [file] (`<64 hex>  <name>`, or `*<name>`), lowercase; null if it isn't there. */
    fun sha256For(sums: String, file: String): String? = sums.lineSequence()
        .map { it.trim().split(Regex("\\s+"), limit = 2) }
        .firstOrNull { it.size == 2 && it[1].removePrefix("*") == file && it[0].length == 64 && it[0].all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
        ?.get(0)?.lowercase()

    /** Whether to look again: never looked, or [CHECK_EVERY_MS] since the last time (or a clock that went back). */
    fun due(lastCheck: Long, now: Long): Boolean = lastCheck <= 0 || now - lastCheck >= CHECK_EVERY_MS || now < lastCheck

    private fun parts(version: String): List<Int>? {
        val core = version.substringBefore('-').substringBefore('+')
        val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
        return numbers.takeIf { it.isNotEmpty() }
    }
}
