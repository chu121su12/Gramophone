/*
 *     Copyright (C) 2024 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.ui.fragments

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil3.SingletonImageLoader
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.clone
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.needsManualSnackBarInset
import org.akanework.gramophone.logic.sharing.LibrarySharingManager
import org.akanework.gramophone.logic.updateMargin
import org.akanework.gramophone.logic.utils.SdScanner
import org.akanework.gramophone.logic.queueWithTitle
import org.akanework.gramophone.ui.MainActivity
import org.akanework.gramophone.ui.adapters.ViewPager2Adapter
import org.akanework.gramophone.ui.components.PlayerBottomSheet
import org.akanework.gramophone.ui.fragments.settings.MainSettingsActivity
import org.nift4.mediastorecompat.MediaStoreCompat

/**
 * ViewPagerFragment:
 *   A fragment that's in charge of displaying tabs
 * and is connected to the drawer.
 *
 * @author AkaneTan
 */
class ViewPagerFragment : BaseFragment(true) {
    lateinit var appBarLayout: AppBarLayout
        private set
    val recycledViewPool = RecyclerView.RecycledViewPool()
    private lateinit var viewPager2: ViewPager2
    private lateinit var adapter: ViewPager2Adapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_viewpager, container, false)
        val tabLayout = rootView.findViewById<TabLayout>(R.id.tab_layout)
        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)
        val collapsingToolbar = rootView.findViewById<CollapsingToolbarLayout>(R.id.collapsingtoolbar)
        viewPager2 = rootView.findViewById(R.id.fragment_viewpager)

        appBarLayout = rootView.findViewById(R.id.appbarlayout)
        appBarLayout.enableEdgeToEdgePaddingListener()
        topAppBar.overflowIcon =
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_more_vert_alt_topappbar)
        val activity = requireActivity() as MainActivity
        topAppBar.menu.findItem(R.id.library_sharing_disconnect).isVisible =
            activity.hasConnectedRemoteLibrary
        if (activity.isRemoteLibrary) {
            collapsingToolbar.title = getString(R.string.library_sharing_shared_library)
            topAppBar.menu.findItem(R.id.quick_refresh).isVisible = false
            topAppBar.menu.findItem(R.id.refresh).isVisible = false
            requireActivity().onBackPressedDispatcher.addCallback(
                viewLifecycleOwner,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        val activity = requireActivity() as MainActivity
                        if (!isVisible || activity.playerBottomSheet.visibleAndExpanded) {
                            isEnabled = false
                            activity.onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                            return
                        }
                        showLibrarySwitcherDialog()
                    }
                }
            )
        }

        topAppBar.setOnMenuItemClickListener { it ->
            val activity = requireActivity() as MainActivity
            when (it.itemId) {
                R.id.search -> {
                    activity.startFragment(SearchFragment())
                }

                R.id.equalizer -> {
                    val intent =
                        Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                            // EXTRA_PACKAGE_NAME is probably not needed but might as well add for good measure
                            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, requireContext().packageName)
                            putExtra(
                                AudioEffect.EXTRA_AUDIO_SESSION,
                                activity.getPlayer()?.audioSessionId
                            )
                            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                        }
                    try {
                        if (Settings.System.getString(
                                requireContext().contentResolver,
                                "firebase.test.lab"
                            ) != "true"
                        ) {
                            activity.startingActivity.launch(intent)
                        }
                    } catch (_: ActivityNotFoundException) {
                        // Let's show a toast here if no system inbuilt EQ was found.
                        Toast.makeText(
                            requireContext(),
                            R.string.equalizer_not_found,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                R.id.quick_refresh -> {
                    val imageLoader = SingletonImageLoader.get(requireContext())
                    imageLoader.memoryCache?.clear()
                    val playerLayout = activity.playerBottomSheet
                    activity.updateLibrary {
                        showRefreshDoneSnackBar(
                            playerLayout,
                            runBlocking { activity.reader.songListFlow.first().size })
                    }
                }

                R.id.library_sharing_disconnect -> {
                    showLibrarySwitcherDialog()
                }

                R.id.refresh -> {
                    val context = requireContext()
                    val imageLoader = SingletonImageLoader.get(context)
                    imageLoader.memoryCache?.clear()
                    val playerLayout = activity.playerBottomSheet
                    MaterialAlertDialogBuilder(context)
                        .setIcon(R.drawable.ic_refresh)
                        .setTitle(R.string.did_you_know)
                        .setMessage(R.string.refresh_did_you_know)
                        .setPositiveButton(android.R.string.ok) { _, _ -> }
                        .show()
                    Toast.makeText(context, R.string.refreshing_wait, Toast.LENGTH_LONG).show()
                    CoroutineScope(Dispatchers.Default).launch {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            SdScanner.scanEverything(context, 5000) { progress ->
                                if (progress.step != SdScanner.SimpleProgress.Step.DONE) {
                                    val str = if (progress.percentage == null)
                                        context.getString(R.string.refreshing_wait)
                                    else context.getString(
                                        R.string.still_refreshing,
                                        progress.step.ordinal,
                                        SdScanner.SimpleProgress.Step.DONE.ordinal - 1,
                                        "${progress.percentage}%"
                                    )
                                    CoroutineScope(Dispatchers.Main).launch {
                                        Toast.makeText(context, str, Toast.LENGTH_SHORT).show()
                                    }
                                    return@scanEverything
                                }
                                activity.updateLibrary(false) {
                                    showRefreshDoneSnackBar(
                                        playerLayout,
                                        runBlocking { activity.reader.songListFlow.first().size })
                                }
                            }
                        } else {
                            val job = launch(Dispatchers.IO) {
                                MediaStoreCompat.scanEverything(context)
                            }
                            while (!job.isCompleted) {
                                delay(5000)
                                CoroutineScope(Dispatchers.Main).launch {
                                    Toast.makeText(context, context.getString(
                                        R.string.refreshing_wait),
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }

                R.id.settings -> {
                    activity.startActivity(Intent(activity, MainSettingsActivity::class.java))
                }

                R.id.shuffle -> {
                    ShortcutManagerCompat.reportShortcutUsed(requireContext(), "shuffle_all")
                    val controller = activity.getPlayer()
                    runBlocking { activity.reader.songListFlow.first() }.takeIf { it.isNotEmpty() }
                        ?.also {
                            controller?.shuffleModeEnabled = true
                            controller?.setMediaItems(
                                queueWithTitle(it, context?.getString(R.string.category_songs))
                            )
                            controller?.prepare()
                            controller?.play()
                        } ?: controller?.setMediaItems(listOf())
                }

                else -> throw IllegalStateException()
            }
            true
        }

        // Connect ViewPager2.

        viewPager2.offscreenPageLimit = 99999 // TODO is 99999 a good value?
        adapter =
            ViewPager2Adapter(
                childFragmentManager,
                viewLifecycleOwner.lifecycle,
                requireContext(),
                viewPager2
            )
        viewPager2.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager2) { tab, position ->
            tab.text = getString(adapter.getLabelResId(position))
            tab.view.post {
                try {
                    /*
                     * Add margin to last and first tab.
                     * There's no attribute to let you set margin
                     * to the last tab.
                     */
                    val lp = tab.view.layoutParams as ViewGroup.MarginLayoutParams
                    lp.marginStart = if (position == 0)
                        resources.getDimension(R.dimen.tab_layout_content_padding).toInt() else 0
                    lp.marginEnd = if (position == tabLayout.tabCount - 1)
                        resources.getDimension(R.dimen.tab_layout_content_padding).toInt() else 0
                    tab.view.layoutParams = lp
                } catch (_: IllegalStateException) {
                }
            }
        }.attach()
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(p0: TabLayout.Tab?) {
                // do nothing
            }

            override fun onTabUnselected(p0: TabLayout.Tab?) {
                // do nothing
            }

            override fun onTabReselected(p0: TabLayout.Tab?) {
                if (p0 != null)
                    (childFragmentManager.findFragmentByTag("f${adapter.getItemId(p0.position)}")
                            as AdapterFragment?)?.onTabReselected()
            }
        })

        if (adapter.itemCount < 2) {
            tabLayout.visibility = View.GONE
            viewPager2.isUserInputEnabled = false
        } else {
            tabLayout.visibility = View.VISIBLE
            viewPager2.isUserInputEnabled = true
        }

        return rootView
    }

    private fun showLibrarySwitcherDialog() {
        val activity = requireActivity() as MainActivity
        val remoteReader = LibrarySharingManager.connectedRemoteReader() ?: run {
            activity.showLocalLibraryRoot()
            return
        }
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        var dialog: AlertDialog? = null
        container.addView(
            libraryRow(getString(R.string.library_sharing_local_library)) {
                dialog?.dismiss()
                activity.showLocalLibraryRoot()
            }
        )
        container.addView(
            remoteLibraryRow(remoteReader.remoteLibrary.deviceName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.library_sharing_shared_library), activity) {
                dialog?.dismiss()
            }
        )
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.library_sharing_libraries)
            .setView(container)
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun libraryRow(label: String, onClick: () -> Unit): View =
        TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            text = label
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            setBackgroundResource(selectableItemBackground())
            setOnClickListener { onClick() }
        }

    private fun remoteLibraryRow(label: String, activity: MainActivity, onDismiss: () -> Unit): View =
        LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setBackgroundResource(selectableItemBackground())

            val title = TextView(context).apply {
                text = label
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(8), 0)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                setOnClickListener {
                    onDismiss()
                    activity.showRemoteLibraryRoot()
                }
            }
            addView(
                title,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )

            val more = ImageButton(context).apply {
                setImageResource(R.drawable.ic_more_vert_alt)
                contentDescription = getString(R.string.more_actions)
                background = null
                setBackgroundResource(selectableItemBackgroundBorderless())
                setOnClickListener { anchor ->
                    showRemoteLibraryMenu(anchor, activity, onDismiss)
                }
            }
            addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
        }

    private fun showRemoteLibraryMenu(anchor: View, activity: MainActivity, onDismiss: () -> Unit) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, R.id.refresh, 0, R.string.library_sharing_refresh_library)
            menu.add(0, R.id.library_sharing_disconnect, 1, R.string.library_sharing_disconnect)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.refresh -> {
                        onDismiss()
                        refreshSharedLibrary(activity)
                    }
                    R.id.library_sharing_disconnect -> {
                        onDismiss()
                        activity.disconnectSharedLibrary()
                    }
                }
                true
            }
            show()
        }
    }

    private fun refreshSharedLibrary(activity: MainActivity) {
        val playerLayout = activity.playerBottomSheet
        Toast.makeText(requireContext(), R.string.refreshing_wait, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { LibrarySharingManager.refreshRemoteLibrary() }
                .onSuccess { reader ->
                    if (activity.isRemoteLibrary) {
                        activity.showRemoteLibraryRoot()
                    }
                    showRefreshDoneSnackBar(playerLayout, reader.songListFlow.first().size)
                }
                .onFailure {
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.library_sharing_connection_failed,
                            it.message ?: it.javaClass.name
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun selectableItemBackground(): Int {
        val attrs = requireContext().obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackground)
        )
        return attrs.getResourceId(0, 0).also { attrs.recycle() }
    }

    private fun selectableItemBackgroundBorderless(): Int {
        val attrs = requireContext().obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        )
        return attrs.getResourceId(0, 0).also { attrs.recycle() }
    }

    fun maybeReportFullyDrawn(itemId: Int) {
        if (view != null && adapter.getItemId(viewPager2.currentItem).toInt() == itemId)
            mainActivity.maybeReportFullyDrawn()
    }

    private fun showRefreshDoneSnackBar(playerLayout: PlayerBottomSheet, count: Int) {
        val view = view
        if (view == null) return
        val snackBar =
            Snackbar.make(
                view,
                getString(
                    R.string.refreshed_songs,
                    count,
                ),
                Snackbar.LENGTH_LONG,
            )
        snackBar.setAction(R.string.dismiss) {
            snackBar.dismiss()
        }

        /*
		 * Let's override snack bar's color here so it would
		 * adapt dark mode.
		 */
        snackBar.setBackgroundTint(
            MaterialColors.getColor(
                snackBar.view,
                com.google.android.material.R.attr.colorSurface,
            ),
        )
        snackBar.setActionTextColor(
            MaterialColors.getColor(
                snackBar.view,
                androidx.appcompat.R.attr.colorPrimary,
            ),
        )
        snackBar.setTextColor(
            MaterialColors.getColor(
                snackBar.view,
                com.google.android.material.R.attr.colorOnSurface,
            ),
        )

        // Set an anchor for snack bar.
        if (playerLayout.visible && playerLayout.actuallyVisible)
            snackBar.anchorView = playerLayout
        else if (needsManualSnackBarInset()) {
            val activity = requireActivity() as MainActivity
            // snack bar only implements proper insets handling for Q+
            snackBar.view.updateMargin {
                val i = ViewCompat.getRootWindowInsets(activity.window.decorView)
                if (i != null) {
                    bottom += i.clone()
                        .getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                }
            }
        }
        snackBar.show()
    }
}
