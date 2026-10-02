package com.alananasss.kittytune.data.ytmusic

import android.content.Context
import android.util.Log
import com.alananasss.kittytune.data.LikeRepository
import com.alananasss.kittytune.data.local.AppDatabase
import com.alananasss.kittytune.data.local.DownloadDao
import com.alananasss.kittytune.data.local.LocalArtist
import com.alananasss.kittytune.data.local.LocalPlaylist
import com.alananasss.kittytune.data.local.LocalTrack
import com.alananasss.kittytune.data.local.PlaylistTrackCrossRef
import com.alananasss.kittytune.domain.Track
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Two-way like sync between the app and YouTube Music. YouTube tracks sync with YouTube Music and
 * SoundCloud tracks with SoundCloud (see LikeRepository); the app's library shows both.
 *
 * Three sets decide every change: what YouTube has now (remote), what the app has (local) and what
 * YouTube had at the last sync (synced). A like in remote-only is new on YouTube; one in synced but no
 * longer in remote was removed there; one in local only was made in the app and still has to be sent.
 */
object YtmSync {
    private const val MIN_INTERVAL_MS = 30 * 60 * 1000L
    // A sudden mass change is far more likely a bad read than the user's intent: do nothing instead.
    private const val MAX_BULK_CHANGE = 20
    private const val TAG = "YtmSync"

    val mutex = Mutex()

    private fun videoIdOf(url: String?): String? =
        url?.let { Regex("[?&]v=([\\w-]+)").find(it)?.groupValues?.get(1) }

    fun videoId(track: Track): String? = videoIdOf(track.permalinkUrl)

    /** The user liked or un-liked a YouTube track in the app: mirror it. A failure is repaired by the next [syncLikes]. */
    suspend fun pushLike(context: Context, track: Track, like: Boolean) {
        if (!YtmSession.isLoggedIn(context)) return
        val id = videoId(track) ?: return
        YouTube.likeVideo(id, like)
            .onSuccess {
                val synced = YtmSession.syncedLikes(context)
                YtmSession.setSyncedLikes(context, if (like) synced + id else synced - id)
            }
            .onFailure { Log.w(TAG, "like push failed, will retry at next sync", it) }
    }

    /** Reconciles the liked songs in both directions. Returns how many songs YouTube Music has liked. */
    suspend fun syncLikes(context: Context): Int {
        val remote = YtmImporter.allSongs("LM") // newest first
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        val local = LikeRepository.likedTracks.value
            .filter { it.source == "youtube" }
            .mapNotNull { t -> videoId(t)?.let { it to t } }
            .toMap()
        val synced = YtmSession.syncedLikes(context)
        val now = System.currentTimeMillis()

        // YouTube -> app: liked there, unknown here. Ids in `synced` are excluded on purpose: those the
        // app already dropped and hasn't told YouTube about yet.
        val toAdd = remote.filter { it.id !in local && it.id !in synced }
        LikeRepository.applyRemoteLikesBatch(
            toAdd.mapIndexed { i, s -> with(YtmImporter) { s.toTrack() } to now - i * 1000L }
        )

        // YouTube -> app: un-liked there since the last sync. An empty answer with history is a bad read.
        val trustRemote = remote.isNotEmpty() || synced.isEmpty()
        val removedOnYoutube = if (trustRemote) local.filterKeys { it in synced && it !in remoteIds } else emptyMap()
        if (removedOnYoutube.size <= MAX_BULK_CHANGE) {
            LikeRepository.applyRemoteUnlikesBatch(removedOnYoutube.values.mapTo(HashSet()) { it.id })
        } else Log.w(TAG, "skipped ${removedOnYoutube.size} local removals, looks like a bad read")

        // app -> YouTube: liked here, never seen there.
        val pushed = HashSet<String>()
        for (id in local.keys) {
            if (id in remoteIds || id in synced) continue
            if (YouTube.likeVideo(id, true).isSuccess) pushed += id
        }

        // app -> YouTube: un-liked here while it is still liked there.
        val unlikedHere = remoteIds.filter { it in synced && it !in local }
        val stillLiked = HashSet(remoteIds)
        if (unlikedHere.size <= MAX_BULK_CHANGE) {
            for (id in unlikedHere) if (YouTube.likeVideo(id, false).isSuccess) stillLiked -= id
        } else Log.w(TAG, "skipped ${unlikedHere.size} remote un-likes, looks like a bad read")

        YtmSession.setSyncedLikes(context, stillLiked + pushed)
        return remote.size
    }

    /**
     * Playlists already linked to YouTube Music (the imported ones), in both directions: songs added or
     * removed on either side are copied to the other. Same three-set rule as the likes, per playlist.
     * Playlists created only in the app are not created on YouTube.
     */
    suspend fun syncPlaylists(context: Context): Int {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val playlists = YouTube.likedPlaylists().getOrThrow()
        for (p in playlists) {
            // One unreadable playlist must not abort the rest.
            val songs = runCatching { YtmImporter.allSongs(p.id) }.getOrNull() ?: continue
            runCatching { syncPlaylist(context, dao, p, songs) }
                .onFailure { Log.w(TAG, "playlist ${p.id} not synced", it) }
        }
        return playlists.size
    }

    private suspend fun syncPlaylist(context: Context, dao: DownloadDao, p: PlaylistItem, songs: List<SongItem>) {
        val id = YtmImporter.localId("ytm-playlist:${p.id}")
        val remoteIds = songs.mapTo(HashSet()) { it.id }
        val synced = YtmSession.syncedPlaylist(context, p.id) ?: emptySet()
        val existing = dao.getPlaylist(id)
        val local = (if (existing == null) emptyList() else dao.getTracksForPlaylistSync(id))
            .mapNotNull { t -> videoIdOf(t.permalinkUrl)?.let { it to t } }
            .toMap()

        // YouTube -> app: songs added there.
        val row = (existing ?: LocalPlaylist(
            id = id, title = p.title, artist = p.author?.name ?: "YouTube Music", artworkUrl = p.thumbnail,
            trackCount = 0, isUserCreated = true, permalinkUrl = "https://music.youtube.com/playlist?list=${p.id}"
        )).copy(title = p.title, artworkUrl = p.thumbnail)
        if (existing == null) dao.insertPlaylist(row)
        for (song in songs.filter { it.id !in local && it.id !in synced }) {
            val t = with(YtmImporter) { song.toTrack() }
            // Never overwrite a row that already exists: it may hold a downloaded file.
            if (dao.getTrack(t.id) == null) {
                dao.insertTrack(
                    LocalTrack(
                        id = t.id, title = t.title ?: "", artist = t.user?.username ?: "YouTube",
                        artworkUrl = t.artworkUrl ?: "", duration = t.durationMs ?: 0L,
                        localAudioPath = "", localArtworkPath = "", source = "youtube", permalinkUrl = t.permalinkUrl
                    )
                )
            }
            dao.insertPlaylistTrackRef(PlaylistTrackCrossRef(id, t.id))
        }

        // YouTube -> app: songs removed there since the last sync.
        val trustRemote = remoteIds.isNotEmpty() || synced.isEmpty()
        val removedOnYoutube = if (trustRemote) local.filterKeys { it in synced && it !in remoteIds } else emptyMap()
        if (removedOnYoutube.size <= MAX_BULK_CHANGE) {
            removedOnYoutube.values.forEach { dao.removeTrackFromPlaylist(id, it.id) }
        } else Log.w(TAG, "playlist ${p.id}: skipped ${removedOnYoutube.size} local removals, looks like a bad read")

        // app -> YouTube: songs added here / removed here.
        val pushAdd = local.keys.filter { it !in remoteIds && it !in synced }
        val pushRemove = songs.filter { it.id in synced && it.id !in local }
        val addOk = pushAdd.isEmpty() ||
            (pushAdd.size <= MAX_BULK_CHANGE && YouTube.editPlaylist(p.id, pushAdd, emptyList()).isSuccess)
        val removeOk = pushRemove.isEmpty() ||
            (pushRemove.size <= MAX_BULK_CHANGE && YouTube.editPlaylist(p.id, emptyList(), pushRemove).isSuccess)

        // What YouTube holds now; anything that failed stays pending for the next sync.
        val holds = HashSet(remoteIds)
        if (addOk) holds += pushAdd
        if (removeOk) holds -= pushRemove.mapTo(HashSet()) { it.id }
        YtmSession.setSyncedPlaylist(context, p.id, holds)

        dao.updatePlaylist(row.copy(trackCount = dao.getTracksForPlaylistSync(id).size))
    }

    /** Followed artists, both ways: following or unfollowing on either side is copied to the other. */
    suspend fun syncArtists(context: Context): Int {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val remote = YouTube.libraryArtists().getOrThrow()
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        val synced = YtmSession.syncedArtists(context)
        val local = dao.getAllSavedArtists().first().associateBy { it.id }
        fun localIdOf(channelId: String) = YtmImporter.localId("ytm-artist:$channelId")

        // YouTube -> app: followed there.
        dao.insertArtists(
            remote.filter { localIdOf(it.id) !in local && it.id !in synced }.map {
                LocalArtist(id = localIdOf(it.id), username = it.title, avatarUrl = it.thumbnail, trackCount = 0)
            }
        )

        // YouTube -> app: unfollowed there since the last sync.
        val trustRemote = remote.isNotEmpty() || synced.isEmpty()
        val removedOnYoutube = if (trustRemote) synced.filter { it !in remoteIds && localIdOf(it) in local } else emptyList()
        if (removedOnYoutube.size <= MAX_BULK_CHANGE) {
            removedOnYoutube.forEach { dao.deleteArtist(localIdOf(it)) }
        } else Log.w(TAG, "skipped ${removedOnYoutube.size} artist removals, looks like a bad read")

        // app -> YouTube: unfollowed here while still followed there.
        val stillFollowed = HashSet(remoteIds)
        val unfollowedHere = remoteIds.filter { it in synced && localIdOf(it) !in local }
        if (unfollowedHere.size <= MAX_BULK_CHANGE) {
            for (cid in unfollowedHere) if (YouTube.subscribe(cid, false).isSuccess) stillFollowed -= cid
        } else Log.w(TAG, "skipped ${unfollowedHere.size} unfollows, looks like a bad read")

        YtmSession.setSyncedArtists(context, stillFollowed)
        return remote.size
    }

    /** Runs a full sync when the app comes to the foreground and the last one is old enough. */
    suspend fun autoSync(context: Context) {
        if (!YtmSession.isLoggedIn(context)) return
        if (System.currentTimeMillis() - YtmSession.lastSync(context) < MIN_INTERVAL_MS) return
        if (mutex.isLocked) return
        try {
            withContext(Dispatchers.IO) { YtmImporter.import(context) {} }
        } catch (e: Exception) {
            Log.e(TAG, "auto sync failed", e)
        }
    }
}
