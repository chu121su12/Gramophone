package org.akanework.gramophone.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.getBitrate
import org.akanework.gramophone.logic.getFile
import org.akanework.gramophone.logic.hasImprovedMediaStore
import org.akanework.gramophone.logic.sharing.isRemoteMediaItem
import org.akanework.gramophone.logic.sharing.remoteMimeType
import org.akanework.gramophone.logic.toLocaleString
import org.akanework.gramophone.logic.toMediaStoreId
import org.akanework.gramophone.logic.ui.placeholderScaleToFit
import org.akanework.gramophone.logic.utils.CalculationUtils.convertDurationToTimeStamp

class DetailDialogFragment : BaseFragment(false) {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_info_song, container, false)
        rootView.findViewById<AppBarLayout>(R.id.appbarlayout).enableEdgeToEdgePaddingListener()
        rootView.findViewById<View>(R.id.scrollView).enableEdgeToEdgePaddingListener()
        rootView.findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        val rawId = requireArguments().getString("Id")
        val id = rawId?.toMediaStoreId()
        val mediaItem = runBlocking {
            if (id != null) {
                mainActivity.reader.idMapFlow.map { it[id] }.first()
            } else {
                mainActivity.reader.songListFlow.map { list ->
                    list.find { it.mediaId == rawId }
                }.first()
            }
        }
        if (mediaItem == null) {
            parentFragmentManager.popBackStack()
            return null
        }
        val mediaMetadata = mediaItem.mediaMetadata
        val albumCoverImageView = rootView.findViewById<ImageView>(R.id.album_cover)
        val titleTextView = rootView.findViewById<TextView>(R.id.title)
        val artistTextView = rootView.findViewById<TextView>(R.id.artist)
        val albumArtistTextView = rootView.findViewById<TextView>(R.id.album_artist)
        val discNumberTextView = rootView.findViewById<TextView>(R.id.disc_number)
        val trackNumberTextView = rootView.findViewById<TextView>(R.id.track_num)
        val genreTextView = rootView.findViewById<TextView>(R.id.genre)
        val genreBox = rootView.findViewById<View>(R.id.genre_box)
        val yearTextView = rootView.findViewById<TextView>(R.id.date)
        val albumTextView = rootView.findViewById<TextView>(R.id.album)
        val durationTextView = rootView.findViewById<TextView>(R.id.duration)
        val mimeTypeTextView = rootView.findViewById<TextView>(R.id.mime)
        val pathBox = rootView.findViewById<View>(R.id.path_box)
        val pathTextView = rootView.findViewById<TextView>(R.id.path)
        val bitRateTextView = rootView.findViewById<TextView>(R.id.bit_rate)
        val isRemote = mediaItem.isRemoteMediaItem()
        albumCoverImageView.load(mediaMetadata.artworkUri) {
            placeholderScaleToFit(R.drawable.ic_default_cover)
            crossfade(true)
            error(R.drawable.ic_default_cover)
        }
        titleTextView.text = mediaMetadata.title
        artistTextView.text = mediaMetadata.artist
        albumTextView.text = mediaMetadata.albumTitle
        if (mediaMetadata.albumArtist != null) {
            albumArtistTextView.text = mediaMetadata.albumArtist
        }
        discNumberTextView.text = mediaMetadata.discNumber?.toLocaleString()
        trackNumberTextView.text = mediaMetadata.trackNumber?.toLocaleString()
        if (hasImprovedMediaStore()) {
            if (mediaMetadata.genre != null) {
                genreTextView.text = mediaMetadata.genre
            }
        } else genreBox.visibility = View.GONE
        if (mediaMetadata.releaseYear != null || mediaMetadata.recordingYear != null) {
            yearTextView.text =
                (mediaMetadata.releaseYear ?: mediaMetadata.recordingYear)?.toLocaleString()
        }
        mediaMetadata.durationMs?.let { durationTextView.text = convertDurationToTimeStamp(it) }
        mimeTypeTextView.text = mediaItem.localConfiguration?.mimeType
            ?: mediaItem.remoteMimeType()
            ?: "(null)"
        if (isRemote) {
            pathBox.visibility = View.GONE
            bitRateTextView.text = getString(R.string.library_sharing_playback_only_metadata)
        } else {
            pathTextView.text = mediaItem.getFile()?.path
                ?: mediaItem.requestMetadata.mediaUri?.toString() ?: "(null)"
            val context = requireContext().applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                val bitrate = mediaItem.getBitrate(context) // disk access
                withContext(Dispatchers.Main) {
                    bitRateTextView.text = if (bitrate != null) {
                        getString(R.string.bitrate_format, bitrate / 1000)
                    } else {
                        getString(R.string.bitrate_unknown)
                    }
                }
            }
        }
        return rootView
    }
}