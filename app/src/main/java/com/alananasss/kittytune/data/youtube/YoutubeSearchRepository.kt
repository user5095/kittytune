package com.alananasss.kittytune.data.youtube

import com.alananasss.kittytune.data.ytmusic.YtmImporter
import com.alananasss.kittytune.domain.Playlist
import com.alananasss.kittytune.domain.Track
import com.alananasss.kittytune.domain.User
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class YoutubeSearchResult(
    val tracks: List<Track> = emptyList(),
    val albums: List<Playlist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val artists: List<User> = emptyList()
)

/**
 * YouTube Music search and detail pages in the same shape the other providers (Deezer, Tidal, Qobuz)
 * use, so the search screen and the playlist screen treat it like them. Ids look like
 * `youtube:artist:<channelId>`, `youtube:album:<browseId>` and `youtube:playlist:<playlistId>`.
 */
object YoutubeSearchRepository {
    private fun SongItem.asTrack(coverFallback: String? = null): Track =
        with(YtmImporter) { toTrack() }.let { t ->
            if (t.artworkUrl.isNullOrBlank() && coverFallback != null) t.copy(artworkUrl = coverFallback) else t
        }

    private fun ArtistItem.asUser() = User(
        id = abs("youtube:artist:$id".hashCode().toLong()),
        username = title,
        avatarUrl = thumbnail,
        permalink = id,
        urn = "youtube:artist:$id"
    )

    private fun AlbumItem.asPlaylist() = Playlist(
        id = abs("youtube:album:$browseId".hashCode().toLong()),
        title = title,
        artworkUrl = thumbnail,
        calculatedArtworkUrl = thumbnail,
        trackCount = 0,
        user = User(0L, artists?.joinToString(", ") { it.name } ?: "YouTube Music", null),
        isAlbum = true,
        releaseDate = year?.toString(),
        permalink = browseId,
        permalinkUrl = shareLink,
        urn = "youtube:album:$browseId"
    )

    private fun PlaylistItem.asPlaylist() = Playlist(
        id = abs("youtube:playlist:$id".hashCode().toLong()),
        title = title,
        artworkUrl = thumbnail,
        calculatedArtworkUrl = thumbnail,
        trackCount = songCountText?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
        user = User(0L, author?.name ?: "YouTube Music", null),
        isAlbum = false,
        permalink = id,
        permalinkUrl = shareLink,
        urn = "youtube:playlist:$id"
    )

    suspend fun search(query: String): YoutubeSearchResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext YoutubeSearchResult()
        coroutineScope {
            fun find(filter: YouTube.SearchFilter) = async { YouTube.search(query, filter).getOrNull()?.items.orEmpty() }
            val songs = find(YouTube.SearchFilter.FILTER_SONG)
            val videos = find(YouTube.SearchFilter.FILTER_VIDEO)
            val artists = find(YouTube.SearchFilter.FILTER_ARTIST)
            val albums = find(YouTube.SearchFilter.FILTER_ALBUM)
            val featured = find(YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST)
            val community = find(YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST)

            YoutubeSearchResult(
                // Songs first, then plain videos (covers, live versions), as the old search returned.
                tracks = (songs.await() + videos.await()).filterIsInstance<SongItem>().distinctBy { it.id }.map { it.asTrack() },
                artists = artists.await().filterIsInstance<ArtistItem>().map { it.asUser() },
                albums = albums.await().filterIsInstance<AlbumItem>().map { it.asPlaylist() },
                playlists = (featured.await() + community.await()).filterIsInstance<PlaylistItem>()
                    .distinctBy { it.id }.map { it.asPlaylist() }
            )
        }
    }

    suspend fun getAlbum(albumId: String): Playlist? = withContext(Dispatchers.IO) {
        val page = YouTube.album(albumId.removePrefix("youtube:album:").trim()).getOrNull() ?: return@withContext null
        page.album.asPlaylist().copy(
            trackCount = page.songs.size,
            tracks = page.songs.map { it.asTrack(coverFallback = page.album.thumbnail) }
        )
    }

    suspend fun getPlaylist(playlistId: String): Playlist? = withContext(Dispatchers.IO) {
        val id = playlistId.removePrefix("youtube:playlist:").trim()
        // Songs come from allSongs, which reads only the songs: playlist() insists on header buttons some playlists lack.
        val songs = runCatching { YtmImporter.allSongs(id) }.getOrNull() ?: return@withContext null
        val header = YouTube.playlist(id).getOrNull()?.playlist
        (header?.asPlaylist() ?: Playlist(
            id = abs("youtube:playlist:$id".hashCode().toLong()),
            title = "YouTube Music",
            artworkUrl = songs.firstOrNull()?.thumbnail,
            calculatedArtworkUrl = songs.firstOrNull()?.thumbnail,
            trackCount = 0,
            user = User(0L, "YouTube Music", null),
            isAlbum = false,
            permalink = id,
            permalinkUrl = "https://music.youtube.com/playlist?list=$id",
            urn = "youtube:playlist:$id"
        )).copy(trackCount = songs.size, tracks = songs.map { it.asTrack() })
    }

    suspend fun getArtist(artistId: String): Playlist? = withContext(Dispatchers.IO) {
        val page = YouTube.artist(artistId.removePrefix("youtube:artist:").trim()).getOrNull() ?: return@withContext null
        val songSection = page.sections.firstOrNull { s -> s.items.any { it is SongItem } }
        val songs = songSection?.moreEndpoint
            ?.let { YouTube.artistItems(it).getOrNull()?.items?.filterIsInstance<SongItem>() }
            ?.takeIf { it.isNotEmpty() }
            ?: songSection?.items?.filterIsInstance<SongItem>().orEmpty()
        val artist = page.artist.asUser()
        Playlist(
            id = artist.id,
            title = page.artist.title,
            artworkUrl = page.artist.thumbnail,
            calculatedArtworkUrl = page.artist.thumbnail,
            trackCount = songs.size,
            user = artist,
            tracks = songs.map { it.asTrack() },
            isAlbum = false,
            description = page.description,
            permalink = page.artist.id,
            permalinkUrl = page.artist.shareLink,
            urn = "youtube:artist:${page.artist.id}"
        )
    }
}
