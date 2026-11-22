package com.chencohen.ptt.update

/**
 * A release of the app, as the server read it from GitHub (`appRelease`): its version, the APK's download address,
 * size and SHA-256.
 */
data class AppRelease(
    val version: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
    val notes: String,
) {
    /** The name the download is kept under: talk-<version>.apk. */
    val apkName: String get() = "talk-$version.apk"
}

/**
 * App updates (docs/design.md, "App updates"): the server watches the GitHub releases and pushes `app_update` when a
 * new one is out; the phone reads `appRelease` and compares it with itself. The download and the install are the
 * platform's.
 */
object AppUpdate {
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

    /**
     * What the server answered, if it is a release to offer: a version, an https APK address and a 64-hex SHA-256
     * (lowercase), newer than [current]. Anything else is ignored.
     */
    fun offer(release: AppRelease?, current: String): AppRelease? {
        release ?: return null
        val sha = release.sha256.lowercase()
        if (sha.length != 64 || !sha.all { it.isDigit() || it in 'a'..'f' }) return null
        if (!release.apkUrl.startsWith("https://")) return null
        if (!isNewer(release.version, current)) return null
        return release.copy(sha256 = sha)
    }

    /** Whether an `app_update` push shows a notification: an offer not notified before ([notified], its version). */
    fun notify(offer: AppRelease?, notified: String?): Boolean = offer != null && offer.version != notified

    private fun parts(version: String): List<Int>? {
        val core = version.substringBefore('-').substringBefore('+')
        val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
        return numbers.takeIf { it.isNotEmpty() }
    }
}
