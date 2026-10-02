package com.alananasss.kittytune.data

import android.content.Context
import android.util.Log
import com.alananasss.kittytune.R
import com.alananasss.kittytune.data.local.AppDatabase
import com.alananasss.kittytune.data.local.LocalPlaylist
import com.alananasss.kittytune.data.local.LocalTrack
import com.alananasss.kittytune.data.local.PlaylistTrackCrossRef
import com.alananasss.kittytune.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A real playlist holding only the SoundCloud likes, next to the "Liked tracks" list that merges every
 * provider and the YouTube Music one. It mirrors [LikeRepository]: liking or un-liking a SoundCloud
 * track changes it, editing the playlist itself does not change the likes.
 */
object SoundCloudLikesPlaylist {
    private const val ID = -7_000_000_000_001L // negative: never collides with a SoundCloud playlist id
    private const val TAG = "ScLikesPlaylist"

    @OptIn(FlowPreview::class)
    fun start(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch {
            // Debounced: a like sync from the server replaces the whole list in a burst.
            LikeRepository.likedTracks.debounce(3_000).collect { likes ->
                runCatching { mirror(app, likes) }.onFailure { Log.w(TAG, "mirror failed", it) }
            }
        }
    }

    private suspend fun mirror(context: Context, likes: List<Track>) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val liked = likes.filter { (it.source ?: "soundcloud") == "soundcloud" && it.id > 0 }
        val existing = dao.getPlaylist(ID)
        if (liked.isEmpty() && existing == null) return@withContext

        val wanted = liked.mapTo(HashSet()) { it.id }
        val current = if (existing == null) emptySet() else dao.getTracksForPlaylistSync(ID).mapTo(HashSet()) { it.id }

        val playlist = (existing ?: LocalPlaylist(
            id = ID,
            title = context.getString(R.string.sc_likes_playlist_title),
            artist = "SoundCloud",
            artworkUrl = "",
            trackCount = 0,
            isUserCreated = true
        ))
        if (existing == null) dao.insertPlaylist(playlist)

        val now = System.currentTimeMillis()
        for (t in liked) {
            if (t.id in current) continue
            // Never overwrite a row that already exists: it may hold a downloaded file.
            if (dao.getTrack(t.id) == null) {
                dao.insertTrack(
                    LocalTrack(
                        id = t.id,
                        title = t.title ?: "",
                        artist = t.displayArtist.ifBlank { t.user?.username ?: "" },
                        artworkUrl = t.fullResArtwork,
                        duration = t.durationMs ?: 0L,
                        localAudioPath = "",
                        localArtworkPath = "",
                        source = "soundcloud",
                        permalinkUrl = t.permalinkUrl,
                        ownerId = t.user?.id ?: 0L,
                        secretToken = t.secretToken
                    )
                )
            }
            // Playlists read oldest-first: the inverted time puts the most recent like on top.
            dao.insertPlaylistTrackRef(PlaylistTrackCrossRef(ID, t.id, Long.MAX_VALUE - (t.likedAt ?: now)))
        }
        for (gone in current - wanted) dao.removeTrackFromPlaylist(ID, gone)

        dao.updatePlaylist(
            playlist.copy(
                trackCount = wanted.size,
                artworkUrl = liked.firstOrNull()?.fullResArtwork ?: ""
            )
        )
    }
}
