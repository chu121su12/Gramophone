package uk.akane.libphonograph.reader

import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.Flow
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Date
import uk.akane.libphonograph.items.FileNode
import uk.akane.libphonograph.items.Genre
import uk.akane.libphonograph.items.Playlist

interface LibraryReader {
    val hadFirstRefresh: Boolean
    val idMapFlow: Flow<Map<Long, MediaItem>>
    val pathMapFlow: Flow<Map<String, MediaItem>>
    val songListFlow: Flow<List<MediaItem>>
    val albumListFlow: Flow<List<Album>>
    val albumArtistListFlow: Flow<List<Artist>>
    val artistListFlow: Flow<List<Artist>>
    val genreListFlow: Flow<List<Genre>>
    val dateListFlow: Flow<List<Date>>
    val playlistListFlow: Flow<List<Playlist>>
    val folderStructureFlow: Flow<FileNode>
    val shallowFolderFlow: Flow<FileNode>
    val foldersFlow: Flow<Set<String>>
    val foldersForWhitelistFlow: Flow<Set<String>>

    suspend fun refresh()
}
