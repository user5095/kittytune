package com.alananasss.kittytune.data.ytmusic

import android.content.Context
import com.zionhuang.innertube.YouTube

/**
 * Login state of the user's YouTube Music account: the browser cookie from music.youtube.com,
 * handed to innertube (which signs requests with its SAPISID) plus the account name for display.
 */
object YtmSession {
    private const val PREFS = "ytmusic_session"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_NAME = "account_name"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Pushes the stored cookie into innertube. Call once at startup. */
    fun init(context: Context) {
        YouTube.cookie = prefs(context).getString(KEY_COOKIE, null)
    }

    fun isLoggedIn(context: Context): Boolean =
        prefs(context).getString(KEY_COOKIE, null)?.contains("SAPISID=") == true

    fun accountName(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    fun save(context: Context, cookie: String, name: String?) {
        prefs(context).edit().putString(KEY_COOKIE, cookie).putString(KEY_NAME, name).apply()
        YouTube.cookie = cookie
    }

    private const val KEY_SYNCED_LIKES = "synced_like_ids"
    private const val KEY_LAST_SYNC = "last_sync_ms"

    /** Video ids known to be liked on YouTube Music at the last sync. */
    fun syncedLikes(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SYNCED_LIKES, emptySet()) ?: emptySet()

    fun setSyncedLikes(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SYNCED_LIKES, HashSet(ids)).apply()
    }

    private const val KEY_SYNCED_ARTISTS = "synced_artist_ids"

    /** Channel ids of the artists followed on YouTube Music at the last sync. */
    fun syncedArtists(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SYNCED_ARTISTS, emptySet()) ?: emptySet()

    fun setSyncedArtists(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SYNCED_ARTISTS, HashSet(ids)).apply()
    }

    private fun playlistKey(playlistId: String) = "synced_playlist_$playlistId"

    /** Video ids a playlist held on YouTube Music at the last sync, or null if it was never synced. */
    fun syncedPlaylist(context: Context, playlistId: String): Set<String>? =
        prefs(context).getStringSet(playlistKey(playlistId), null)

    fun setSyncedPlaylist(context: Context, playlistId: String, ids: Set<String>) {
        prefs(context).edit().putStringSet(playlistKey(playlistId), HashSet(ids)).apply()
    }

    fun lastSync(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC, 0L)

    fun markSynced(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
    }

    fun logout(context: Context) {
        prefs(context).edit().clear().apply()
        YouTube.cookie = null
    }
}
