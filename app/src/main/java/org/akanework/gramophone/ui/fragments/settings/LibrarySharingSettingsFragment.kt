package org.akanework.gramophone.ui.fragments.settings

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.sharing.LibrarySharingManager
import org.akanework.gramophone.ui.MainActivity
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingsActivity

class LibrarySharingSettingsActivity : BaseSettingsActivity(
    R.string.settings_library_sharing,
    { LibrarySharingSettingsFragment() })

class LibrarySharingSettingsFragment : BasePreferenceFragment() {
    companion object {
        private const val LAST_HOST_KEY = "library_sharing_last_host"
    }

    private var acceptDialog: AlertDialog? = null
    private var connectDialog: AlertDialog? = null
    private var connectJob: Job? = null

    private val clientsPreference: Preference
        get() = findPreference("library_sharing_clients")!!
    private val savedServersPreference: Preference
        get() = findPreference("library_sharing_saved_servers")!!
    private val clearCachePreference: Preference
        get() = findPreference("library_sharing_clear_cache")!!
    private val addressPreference: Preference
        get() = findPreference("library_sharing_address")!!
    private val portPreference: Preference
        get() = findPreference("library_sharing_port")!!
    private val keepAlivePreference: SwitchPreferenceCompat
        get() = findPreference("library_sharing_keep_alive")!!

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_library_sharing, rootKey)
        val sharingSwitch = findPreference<SwitchPreferenceCompat>("library_sharing_enabled")!!
        val keepAliveSwitch = keepAlivePreference
        sharingSwitch
            .setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                LibrarySharingManager.setSharingEnabled(enabled)
                updateKeepAliveIndicator(
                    keepAlive = keepAliveSwitch.isChecked,
                    sharing = enabled
                )
                true
            }
        keepAliveSwitch
            .setOnPreferenceChangeListener { _, newValue ->
                updateKeepAliveIndicator(
                    keepAlive = newValue as Boolean,
                    sharing = LibrarySharingManager.sharingEnabled.value
                )
                true
            }
        findPreference<Preference>("library_sharing_load")!!.setOnPreferenceClickListener {
            showConnectInputDialog()
            true
        }
        portPreference.setOnPreferenceClickListener {
            showPortDialog()
            true
        }
        findPreference<Preference>("library_sharing_debug_local")!!.apply {
            setOnPreferenceClickListener {
                connectToHost("127.0.0.1")
                true
            }
        }
        clientsPreference.setOnPreferenceClickListener {
            showClientsDialog()
            true
        }
        savedServersPreference.setOnPreferenceClickListener {
            showSavedServersDialog()
            true
        }
        clearCachePreference.setOnPreferenceClickListener {
            showClearCacheDialog()
            true
        }
        updateAddress()
        updatePort()
        updateClients()
        updateSavedServers()
        updateKeepAliveIndicator()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    LibrarySharingManager.pendingClient.collect { pending ->
                        updateAcceptDialog(pending)
                    }
                }
                launch {
                    LibrarySharingManager.connectedClients.collect {
                        updateClients()
                    }
                }
                launch {
                    LibrarySharingManager.sharingEnabled.collect { enabled ->
                        updateKeepAliveIndicator(sharing = enabled)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LibrarySharingManager.setAcceptWindowVisible(true)
        updateAddress()
        updatePort()
        updateClients()
        updateSavedServers()
        updateKeepAliveIndicator()
    }

    override fun onStop() {
        acceptDialog?.dismiss()
        acceptDialog = null
        LibrarySharingManager.setAcceptWindowVisible(false)
        super.onStop()
    }

    override fun onDestroyView() {
        connectJob?.cancel()
        connectDialog?.dismiss()
        acceptDialog?.dismiss()
        super.onDestroyView()
    }

    private fun showConnectInputDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val lastHost = prefs.getString(LAST_HOST_KEY, "").orEmpty()
            .takeUnless { it.isLocalDebugHost() }
            .orEmpty()
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "192.168.1.10:${LibrarySharingManager.serverPort()}"
            setSingleLine(true)
            if (lastHost.isNotBlank()) {
                setText(lastHost)
                selectAll()
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_sharing_enter_ip)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val host = input.text?.toString()?.trim().orEmpty()
                if (host.isNotBlank()) {
                    if (!host.isLocalDebugHost()) {
                        prefs.edit { putString(LAST_HOST_KEY, host) }
                    }
                    connectToHost(host)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun showPortDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(LibrarySharingManager.serverPort().toString())
            selectAll()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_sharing_port)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val port = input.text?.toString()?.trim()?.toIntOrNull()
                if (port == null || port !in 1..65535) {
                    Toast.makeText(
                        requireContext(),
                        R.string.library_sharing_invalid_port,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                LibrarySharingManager.setServerPort(port)
                updatePort()
                updateAddress()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun String.isLocalDebugHost(): Boolean =
        substringBeforeLast(':').trim().let {
            it == "127.0.0.1" || it == "localhost" || it == "::1"
        } || trim() == "::1"

    private fun connectToHost(host: String) {
        connectDialog?.dismiss()
        connectJob?.cancel()
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_sharing_connecting)
            .setMessage(host)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                connectJob?.cancel()
                LibrarySharingManager.cancelConnecting()
            }
            .create()
        connectDialog = dialog
        dialog.setOnCancelListener {
            connectJob?.cancel()
            LibrarySharingManager.cancelConnecting()
        }
        dialog.show()
        connectJob = viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                LibrarySharingManager.connectTo(host)
            }.onSuccess {
                dialog.dismiss()
                startActivity(
                    Intent(requireContext(), MainActivity::class.java)
                        .setAction(MainActivity.ACTION_SHOW_REMOTE_LIBRARY)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                requireActivity().finish()
            }.onFailure {
                dialog.dismiss()
                if (it !is CancellationException) {
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
    }

    private fun updateAcceptDialog(pending: LibrarySharingManager.PendingClient?) {
        if (pending == null) {
            acceptDialog?.dismiss()
            acceptDialog = null
            return
        }
        if (acceptDialog?.isShowing == true) return
        val label = pending.deviceName?.let { "$it (${pending.address})" } ?: pending.address
        acceptDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_sharing_accept_title)
            .setMessage(getString(R.string.library_sharing_accept_message, label))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                LibrarySharingManager.acceptPendingClient()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                LibrarySharingManager.rejectPendingClient()
            }
            .create()
            .also { it.show() }
    }

    private fun showClientsDialog() {
        val entries = clientEntries()
        if (entries.isEmpty()) {
            Toast.makeText(requireContext(), R.string.library_sharing_no_clients, Toast.LENGTH_SHORT)
                .show()
            return
        }
        val connectedCount = LibrarySharingManager.connectedClients.value.size
        val savedCount = LibrarySharingManager.savedServerSessions().size
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.library_sharing_clients_title, connectedCount, savedCount))
            .setItems(entries.map { it.displayLabel() }.toTypedArray()) { _, which ->
                showClientActionDialog(entries[which])
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun showClientActionDialog(entry: ClientEntry) {
        val connected = entry.connected
        val saved = entry.saved
        val message = entry.detailLabel()
        if (connected != null) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.library_sharing_disconnect_client_title)
                .setMessage(message)
                .setPositiveButton(R.string.library_sharing_disconnect_revoke) { _, _ ->
                    LibrarySharingManager.disconnectServerClient(connected.id)
                    updateClients()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
        } else if (saved != null) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.library_sharing_revoke_client_title)
                .setMessage(message)
                .setPositiveButton(R.string.library_sharing_revoke_client) { _, _ ->
                    LibrarySharingManager.revokeServerSessionById(saved.sessionId)
                    updateClients()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
        }
    }

    private fun showSavedServersDialog() {
        val servers = LibrarySharingManager.savedClientServers()
        if (servers.isEmpty()) {
            Toast.makeText(requireContext(), R.string.library_sharing_no_saved_servers, Toast.LENGTH_SHORT)
                .show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_sharing_saved_servers)
            .setItems(servers.map { it.serverKey }.toTypedArray()) { _, which ->
                val server = servers[which]
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.library_sharing_forget_server_title)
                    .setMessage(server.serverKey)
                    .setPositiveButton(R.string.library_sharing_forget_server) { _, _ ->
                        LibrarySharingManager.forgetClientServer(server.serverKey)
                        updateSavedServers()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .show()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun showClearCacheDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_sharing_clear_cache_title)
            .setMessage(R.string.library_sharing_clear_cache_message)
            .setPositiveButton(R.string.library_sharing_clear_cache) { _, _ ->
                clearCachePreference.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        LibrarySharingManager.clearDownloadedCache()
                    } finally {
                        clearCachePreference.isEnabled = true
                    }
                    val safeContext = context ?: return@launch
                    Toast.makeText(
                        safeContext,
                        R.string.library_sharing_cache_cleared,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun updateClients() {
        val connectedCount = LibrarySharingManager.connectedClients.value.size
        val savedCount = LibrarySharingManager.savedServerSessions().size
        clientsPreference.title = getString(
            R.string.library_sharing_clients_title,
            connectedCount,
            savedCount
        )
        clientsPreference.summary = getString(
            R.string.library_sharing_clients_summary,
            connectedCount,
            savedCount
        )
    }

    private fun updateSavedServers() {
        savedServersPreference.summary = getString(
            R.string.library_sharing_saved_servers_summary,
            LibrarySharingManager.savedClientServers().size
        )
    }

    private fun updateAddress() {
        val addresses = LibrarySharingManager.localLanAddresses()
        addressPreference.summary = if (addresses.isEmpty()) {
            getString(R.string.library_sharing_no_address)
        } else {
            addresses.joinToString { "$it:${LibrarySharingManager.serverPort()}" }
        }
    }

    private fun updatePort() {
        portPreference.summary = LibrarySharingManager.serverPort().toString()
    }

    private fun updateKeepAliveIndicator(
        keepAlive: Boolean = keepAlivePreference.isChecked,
        sharing: Boolean = LibrarySharingManager.sharingEnabled.value
    ) {
        keepAlivePreference.summary = when {
            keepAlive && sharing &&
                    NotificationManagerCompat.from(requireContext()).areNotificationsEnabled() ->
                getString(R.string.library_sharing_keep_alive_notification_summary)
            keepAlive && sharing ->
                getString(R.string.library_sharing_keep_alive_notification_blocked_summary)
            else ->
                getString(R.string.library_sharing_keep_alive_summary)
        }
    }

    private fun clientEntries(): List<ClientEntry> {
        val connected = LibrarySharingManager.connectedClients.value
        val connectedBySession = connected.mapNotNull { client ->
            client.sessionId?.let { it to client }
        }.toMap()
        val savedEntries = LibrarySharingManager.savedServerSessions().map { saved ->
            ClientEntry(saved, connectedBySession[saved.sessionId])
        }
        val savedSessionIds = savedEntries.map { it.saved?.sessionId }.toSet()
        val connectedOnlyEntries = connected
            .filter { it.sessionId == null || it.sessionId !in savedSessionIds }
            .map { ClientEntry(null, it) }
        return savedEntries + connectedOnlyEntries
    }

    private fun ClientEntry.displayLabel(): String {
        val base = label()
        return if (connected != null) {
            getString(R.string.library_sharing_client_connected_label, base)
        } else {
            base
        }
    }

    private fun ClientEntry.detailLabel(): String {
        val savedClient = saved
        val connectedClient = connected
        return when {
            savedClient != null -> savedClient.deviceName?.let {
                "$it (${savedClient.address ?: savedClient.sessionId})"
            } ?: savedClient.address ?: savedClient.sessionId
            connectedClient != null -> connectedClient.deviceName?.let {
                "$it (${connectedClient.address})"
            } ?: connectedClient.address
            else -> ""
        }
    }

    private fun ClientEntry.label(): String {
        val savedClient = saved
        val connectedClient = connected
        return savedClient?.deviceName
            ?: connectedClient?.deviceName
            ?: savedClient?.address
            ?: connectedClient?.address
            ?: savedClient?.sessionId
            ?: ""
    }

    private data class ClientEntry(
        val saved: LibrarySharingManager.SavedServerSession?,
        val connected: LibrarySharingManager.ConnectedClient?
    )
}
