package org.akanework.gramophone.logic.sharing

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.akanework.gramophone.logic.getFile
import org.json.JSONArray
import org.json.JSONObject
import uk.akane.libphonograph.dynamicitem.Favorite
import uk.akane.libphonograph.dynamicitem.RecentlyAdded
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Date
import uk.akane.libphonograph.items.EXTRA_ADD_DATE
import uk.akane.libphonograph.items.EXTRA_ALBUM_ID
import uk.akane.libphonograph.items.EXTRA_ALBUM_YEAR
import uk.akane.libphonograph.items.EXTRA_ARTIST_ID
import uk.akane.libphonograph.items.EXTRA_AUTHOR
import uk.akane.libphonograph.items.EXTRA_CD_TRACK_NUMBER
import uk.akane.libphonograph.items.EXTRA_FILE
import uk.akane.libphonograph.items.EXTRA_MODIFIED_DATE
import uk.akane.libphonograph.items.FileNode
import uk.akane.libphonograph.items.Genre
import uk.akane.libphonograph.items.Playlist
import uk.akane.libphonograph.items.addDate
import uk.akane.libphonograph.items.albumId
import uk.akane.libphonograph.items.albumYear
import uk.akane.libphonograph.items.artistId
import uk.akane.libphonograph.items.author
import uk.akane.libphonograph.items.cdTrackNumber
import uk.akane.libphonograph.items.modifiedDate
import uk.akane.libphonograph.reader.LibraryReader
import java.io.File
import kotlin.math.min

const val REMOTE_MEDIA_ID_PREFIX = "Remote:"
const val EXTRA_REMOTE_LIBRARY_ID = "RemoteLibraryId"
const val EXTRA_REMOTE_ORIGINAL_ID = "RemoteOriginalId"
const val EXTRA_REMOTE_MIME_TYPE = "RemoteMimeType"
const val EXTRA_REMOTE_FILE_EXTENSION = "RemoteFileExtension"
private const val REMOTE_FAVORITE_ID = -1L

fun buildRemoteMediaId(libraryId: String, originalMediaId: String): String =
    REMOTE_MEDIA_ID_PREFIX + libraryId + ":" + Uri.encode(originalMediaId)

fun MediaItem.isRemoteMediaItem(): Boolean =
    mediaMetadata.extras?.containsKey(EXTRA_REMOTE_ORIGINAL_ID) == true

fun MediaItem.remoteOriginalMediaId(): String? =
    mediaMetadata.extras?.getString(EXTRA_REMOTE_ORIGINAL_ID)

fun MediaItem.remoteLibraryId(): String? =
    mediaMetadata.extras?.getString(EXTRA_REMOTE_LIBRARY_ID)

fun MediaItem.remoteMimeType(): String? =
    mediaMetadata.extras?.getString(EXTRA_REMOTE_MIME_TYPE)

fun MediaItem.remoteFileExtension(): String? =
    mediaMetadata.extras?.getString(EXTRA_REMOTE_FILE_EXTENSION)

data class RemoteLibrary(
    val id: String,
    val deviceName: String?,
    val allowFolderTabs: Boolean,
    val songs: List<RemoteSong>,
    val playlists: List<RemotePlaylist>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("deviceName", deviceName)
        .put("allowFolderTabs", allowFolderTabs)
        .put("songs", JSONArray().also { arr -> songs.forEach { arr.put(it.toJson()) } })
        .put("playlists", JSONArray().also { arr -> playlists.forEach { arr.put(it.toJson()) } })

    fun toReader(): StaticRemoteLibraryReader {
        val mediaItems = songs.map { it.toMediaItem(id) }
        val byOriginalId = mediaItems.associateBy { it.remoteOriginalMediaId()!! }
        val staticPlaylists = playlists.mapNotNull { it.toPlaylist(byOriginalId) }
        return StaticRemoteLibraryReader(
            this,
            buildReaderResult(mediaItems, staticPlaylists)
        )
    }

    companion object {
        suspend fun fromReader(
            id: String,
            deviceName: String?,
            reader: LibraryReader,
            allowFolderTabs: Boolean
        ): RemoteLibrary =
            fromSnapshot(
                id,
                deviceName,
                reader.songListFlow.first(),
                reader.playlistListFlow.first(),
                allowFolderTabs
            )

        fun fromSnapshot(
            id: String,
            deviceName: String?,
            songItems: List<MediaItem>,
            playlistItems: List<Playlist>,
            allowFolderTabs: Boolean
        ): RemoteLibrary {
            val songs = songItems.map {
                RemoteSong.fromMediaItem(it, allowFolderTabs)
            }
            val playlists = playlistItems
                .filter { it !is RecentlyAdded }
                .map { RemotePlaylist.fromPlaylist(it) }
            return RemoteLibrary(id, deviceName, allowFolderTabs, songs, playlists)
        }

        fun fromJson(json: JSONObject): RemoteLibrary =
            RemoteLibrary(
                json.getString("id"),
                json.optStringOrNull("deviceName"),
                json.optBoolean("allowFolderTabs", false),
                json.getJSONArray("songs").mapObjects { RemoteSong.fromJson(it) },
                json.getJSONArray("playlists").mapObjects { RemotePlaylist.fromJson(it) }
            )
    }
}

class StaticRemoteLibraryReader internal constructor(
    val remoteLibrary: RemoteLibrary,
    private val result: RemoteReaderResult
) : LibraryReader {
    override val hadFirstRefresh = true
    override val idMapFlow: Flow<Map<Long, MediaItem>> = MutableStateFlow(emptyMap())
    override val pathMapFlow: Flow<Map<String, MediaItem>> = MutableStateFlow(result.pathMap)
    override val songListFlow: Flow<List<MediaItem>> = MutableStateFlow(result.songList)
    override val albumListFlow: Flow<List<Album>> = MutableStateFlow(result.albumList)
    override val albumArtistListFlow: Flow<List<Artist>> = MutableStateFlow(result.albumArtistList)
    override val artistListFlow: Flow<List<Artist>> = MutableStateFlow(result.artistList)
    override val genreListFlow: Flow<List<Genre>> = MutableStateFlow(result.genreList)
    override val dateListFlow: Flow<List<Date>> = MutableStateFlow(result.dateList)
    override val playlistListFlow: Flow<List<Playlist>> = MutableStateFlow(result.playlistList)
    override val folderStructureFlow: Flow<FileNode> = MutableStateFlow(result.folderStructure)
    override val shallowFolderFlow: Flow<FileNode> = MutableStateFlow(result.shallowFolder)
    override val foldersFlow: Flow<Set<String>> = MutableStateFlow(result.folders)
    override val foldersForWhitelistFlow: Flow<Set<String>> =
        MutableStateFlow(result.foldersForWhitelist)

    override suspend fun refresh() = Unit
}

data class RemoteSong(
    val mediaId: String,
    val path: String?,
    val mimeType: String?,
    val title: String?,
    val artist: String?,
    val artistId: Long?,
    val album: String?,
    val albumId: Long?,
    val albumArtist: String?,
    val albumYear: Long?,
    val author: String?,
    val composer: String?,
    val writer: String?,
    val compilation: String?,
    val genre: String?,
    val durationMs: Long?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val cdTrackNumber: String?,
    val releaseYear: Int?,
    val recordingYear: Int?,
    val recordingMonth: Int?,
    val recordingDay: Int?,
    val addDate: Long?,
    val modifiedDate: Long?,
    val fileExtension: String?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mediaId", mediaId)
        .put("path", path)
        .put("mimeType", mimeType)
        .put("title", title)
        .put("artist", artist)
        .put("artistId", artistId)
        .put("album", album)
        .put("albumId", albumId)
        .put("albumArtist", albumArtist)
        .put("albumYear", albumYear)
        .put("author", author)
        .put("composer", composer)
        .put("writer", writer)
        .put("compilation", compilation)
        .put("genre", genre)
        .put("durationMs", durationMs)
        .put("trackNumber", trackNumber)
        .put("discNumber", discNumber)
        .put("cdTrackNumber", cdTrackNumber)
        .put("releaseYear", releaseYear)
        .put("recordingYear", recordingYear)
        .put("recordingMonth", recordingMonth)
        .put("recordingDay", recordingDay)
        .put("addDate", addDate)
        .put("modifiedDate", modifiedDate)
        .put("fileExtension", fileExtension)

    fun toMediaItem(libraryId: String): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_REMOTE_LIBRARY_ID, libraryId)
            putString(EXTRA_REMOTE_ORIGINAL_ID, mediaId)
            mimeType?.let { putString(EXTRA_REMOTE_MIME_TYPE, it) }
            fileExtension?.let { putString(EXTRA_REMOTE_FILE_EXTENSION, it) }
            path?.let { putString(EXTRA_FILE, it) }
            artistId?.let { putLong(EXTRA_ARTIST_ID, it) }
            albumId?.let { putLong(EXTRA_ALBUM_ID, it) }
            albumYear?.let { putLong(EXTRA_ALBUM_YEAR, it) }
            author?.let { putString(EXTRA_AUTHOR, it) }
            addDate?.let { putLong(EXTRA_ADD_DATE, it) }
            modifiedDate?.let { putLong(EXTRA_MODIFIED_DATE, it) }
            cdTrackNumber?.let { putString(EXTRA_CD_TRACK_NUMBER, it) }
        }
        return MediaItem.Builder()
            .setMediaId(buildRemoteMediaId(libraryId, mediaId))
            .setMimeType(mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setDurationMs(durationMs)
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setAlbumArtist(albumArtist)
                    .setWriter(writer)
                    .setCompilation(compilation)
                    .setComposer(composer)
                    .setTrackNumber(trackNumber)
                    .setDiscNumber(discNumber)
                    .setGenre(genre)
                    .setRecordingDay(recordingDay)
                    .setRecordingMonth(recordingMonth)
                    .setRecordingYear(recordingYear)
                    .setReleaseYear(releaseYear)
                    .setUserRating(HeartRating(false))
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    companion object {
        fun fromMediaItem(item: MediaItem, includePath: Boolean): RemoteSong {
            val metadata = item.mediaMetadata
            return RemoteSong(
                item.mediaId,
                item.getFile()?.path.takeIf { includePath },
                item.localConfiguration?.mimeType,
                metadata.title?.toString(),
                metadata.artist?.toString(),
                metadata.artistId,
                metadata.albumTitle?.toString(),
                metadata.albumId,
                metadata.albumArtist?.toString(),
                metadata.albumYear,
                metadata.author,
                metadata.composer?.toString(),
                metadata.writer?.toString(),
                metadata.compilation?.toString(),
                metadata.genre?.toString(),
                metadata.durationMs,
                metadata.trackNumber,
                metadata.discNumber,
                metadata.cdTrackNumber,
                metadata.releaseYear,
                metadata.recordingYear,
                metadata.recordingMonth,
                metadata.recordingDay,
                metadata.addDate,
                metadata.modifiedDate,
                item.getFile()?.extension?.takeIf { ext -> ext.isNotBlank() }
            )
        }

        fun fromJson(json: JSONObject): RemoteSong =
            RemoteSong(
                json.getString("mediaId"),
                json.optStringOrNull("path"),
                json.optStringOrNull("mimeType"),
                json.optStringOrNull("title"),
                json.optStringOrNull("artist"),
                json.optLongOrNull("artistId"),
                json.optStringOrNull("album"),
                json.optLongOrNull("albumId"),
                json.optStringOrNull("albumArtist"),
                json.optLongOrNull("albumYear"),
                json.optStringOrNull("author"),
                json.optStringOrNull("composer"),
                json.optStringOrNull("writer"),
                json.optStringOrNull("compilation"),
                json.optStringOrNull("genre"),
                json.optLongOrNull("durationMs"),
                json.optIntOrNull("trackNumber"),
                json.optIntOrNull("discNumber"),
                json.optStringOrNull("cdTrackNumber"),
                json.optIntOrNull("releaseYear"),
                json.optIntOrNull("recordingYear"),
                json.optIntOrNull("recordingMonth"),
                json.optIntOrNull("recordingDay"),
                json.optLongOrNull("addDate"),
                json.optLongOrNull("modifiedDate"),
                json.optStringOrNull("fileExtension")
            )
    }
}

data class RemotePlaylist(
    val kind: String,
    val id: Long?,
    val title: String?,
    val path: String?,
    val dateAdded: Long?,
    val dateModified: Long?,
    val songIds: List<String>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("id", id)
        .put("title", title)
        .put("path", path)
        .put("dateAdded", dateAdded)
        .put("dateModified", dateModified)
        .put("songIds", JSONArray().also { arr -> songIds.forEach { arr.put(it) } })

    fun toPlaylist(byOriginalId: Map<String, MediaItem>): Playlist? {
        val songs = songIds.mapNotNull { byOriginalId[it] }
        return when (kind) {
            "favorite" -> Favorite(
                id ?: REMOTE_FAVORITE_ID,
                path?.let { File(it) },
                dateAdded,
                dateModified,
                songs
            )
            "playlist" -> Playlist(
                id,
                title,
                path?.let { File(it) },
                null,
                dateAdded,
                dateModified,
                songs
            )
            else -> null
        }
    }

    companion object {
        fun fromPlaylist(playlist: Playlist): RemotePlaylist =
            RemotePlaylist(
                if (playlist is Favorite) "favorite" else "playlist",
                playlist.id,
                playlist.title,
                playlist.path?.path,
                playlist.dateAdded,
                playlist.dateModified,
                playlist.songList.map { it.mediaId }
            )

        fun fromJson(json: JSONObject): RemotePlaylist =
            RemotePlaylist(
                json.getString("kind"),
                json.optLongOrNull("id"),
                json.optStringOrNull("title"),
                json.optStringOrNull("path"),
                json.optLongOrNull("dateAdded"),
                json.optLongOrNull("dateModified"),
                json.getJSONArray("songIds").mapStrings()
            )
    }
}

internal data class RemoteReaderResult(
    val songList: List<MediaItem>,
    val albumList: List<Album>,
    val albumArtistList: List<Artist>,
    val artistList: List<Artist>,
    val genreList: List<Genre>,
    val dateList: List<Date>,
    val playlistList: List<Playlist>,
    val pathMap: Map<String, MediaItem>,
    val folderStructure: FileNode,
    val shallowFolder: FileNode,
    val folders: Set<String>,
    val foldersForWhitelist: Set<String>
)

private fun buildReaderResult(
    songList: List<MediaItem>,
    staticPlaylists: List<Playlist>
): RemoteReaderResult {
    val albumMap = linkedMapOf<Long?, RemoteAlbum>()
    val artistMap = linkedMapOf<Long?, ArtistAccumulator>()
    val albumArtistMap = linkedMapOf<Long?, ArtistAccumulator>()
    val genreMap = linkedMapOf<String?, MutableList<MediaItem>>()
    val dateMap = linkedMapOf<Int?, MutableList<MediaItem>>()
    val pathMap = linkedMapOf<String, MediaItem>()
    val folders = linkedSetOf<String>()
    val foldersForWhitelist = linkedSetOf<String>()
    val root = RemoteFileNode("storage")
    val shallowRoot = RemoteFileNode("shallow")

    songList.forEach { item ->
        val metadata = item.mediaMetadata
        val albumId = metadata.albumId
        val artistId = metadata.artistId
        val year = metadata.releaseYear
        val path = item.getFile()?.path
        if (path != null) {
            pathMap[path] = item
            val file = File(path)
            val parent = file.parentFile
            if (parent != null) {
                addToFullFolder(root, parent.path, item, albumId)
                addToShallowFolder(shallowRoot, parent.name, item, albumId)
                var tmp: File? = parent
                while (tmp != null) {
                    folders.add(tmp.path)
                    foldersForWhitelist.add(tmp.path)
                    tmp = tmp.parentFile
                }
            }
        }
        albumMap.getOrPut(albumId) {
            RemoteAlbum(
                albumId,
                metadata.albumTitle?.toString(),
                metadata.albumArtist?.toString(),
                null,
                null,
                metadata.albumYear?.toInt(),
                null,
                null,
                mutableListOf()
            )
        }.songList.add(item)
        artistMap.getOrPut(artistId) {
            ArtistAccumulator(artistId, metadata.artist?.toString())
        }.songList.add(item)
        genreMap.getOrPut(metadata.genre?.toString()) { mutableListOf() }.add(item)
        dateMap.getOrPut(year) { mutableListOf() }.add(item)
    }

    albumMap.values.forEach { album ->
        album.albumAddDate = album.songList
            .minOfOrNull { it.mediaMetadata.addDate ?: Long.MAX_VALUE }
            ?.takeIf { it != Long.MAX_VALUE }
        album.albumModifiedDate = album.songList
            .maxOfOrNull { it.mediaMetadata.modifiedDate ?: Long.MIN_VALUE }
            ?.takeIf { it != Long.MIN_VALUE }
        val artistId = album.albumArtistId
            ?: album.songList.firstOrNull { it.mediaMetadata.artist?.toString() == album.albumArtist }
                ?.mediaMetadata?.artistId
            ?: album.albumArtist?.hashCode()?.toLong()
        album.albumArtistId = artistId
        albumArtistMap.getOrPut(artistId) {
            ArtistAccumulator(artistId, album.albumArtist)
        }.let {
            it.albumList.add(album)
            it.songList.addAll(album.songList)
        }
        artistMap[artistId]?.albumList?.add(album)
    }

    val recent = RecentlyAdded(
        (System.currentTimeMillis() / 1000L) - 1_209_600L,
        songList
    )

    return RemoteReaderResult(
        songList,
        albumMap.values.toList(),
        albumArtistMap.values.map { it.toArtist() },
        artistMap.values.map { it.toArtist() },
        genreMap.map { Genre(it.key?.hashCode()?.toLong() ?: 0L, it.key, it.value) },
        dateMap.map { Date(it.key?.toLong() ?: 0L, it.key?.toString(), it.value) },
        staticPlaylists + recent,
        pathMap,
        root,
        shallowRoot,
        folders,
        foldersForWhitelist
    )
}

private data class RemoteAlbum(
    override val id: Long?,
    override val title: String?,
    override var albumArtist: String?,
    override var albumArtistId: Long?,
    override val cover: Uri?,
    override var albumYear: Int?,
    override var albumAddDate: Long?,
    override var albumModifiedDate: Long?,
    override val songList: MutableList<MediaItem>
) : Album

private class ArtistAccumulator(
    val id: Long?,
    val title: String?
) {
    val songList = mutableListOf<MediaItem>()
    val albumList = mutableListOf<Album>()
    fun toArtist() = Artist(id, title, songList, albumList)
}

private class RemoteFileNode(
    override val folderName: String
) : FileNode {
    private val mutableFolderList = linkedMapOf<String, FileNode>()
    private val mutableSongList = mutableListOf<MediaItem>()
    override val folderList: Map<String, FileNode>
        get() = mutableFolderList
    override val songList: List<MediaItem>
        get() = mutableSongList
    override var albumId: Long? = null

    fun child(name: String): RemoteFileNode =
        mutableFolderList.getOrPut(name) { RemoteFileNode(name) } as RemoteFileNode

    fun addSong(item: MediaItem, id: Long?) {
        if (albumId != null && id != albumId) {
            albumId = null
        } else if (albumId == null && mutableSongList.isEmpty()) {
            albumId = id
        }
        mutableSongList.add(item)
    }

    override val addDate: Long?
        get() = min(
            songList.minOfOrNull { it.mediaMetadata.addDate ?: Long.MAX_VALUE } ?: Long.MAX_VALUE,
            folderList.minOfOrNull { it.value.addDate ?: Long.MAX_VALUE } ?: Long.MAX_VALUE
        ).let { if (it == Long.MAX_VALUE) null else it }
}

private fun addToFullFolder(root: RemoteFileNode, path: String, item: MediaItem, albumId: Long?) {
    var node = root
    path.trim('/').split('/').filter { it.isNotBlank() }.forEach {
        node = node.child(it)
    }
    node.addSong(item, albumId)
}

private fun addToShallowFolder(
    root: RemoteFileNode,
    folderName: String,
    item: MediaItem,
    albumId: Long?
) {
    root.child(folderName).addSong(item, albumId)
}

private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

private fun JSONArray.mapStrings(): List<String> =
    (0 until length()).map { getString(it) }

private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name)

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (isNull(name) || !has(name)) null else optLong(name)

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (isNull(name) || !has(name)) null else optInt(name)
