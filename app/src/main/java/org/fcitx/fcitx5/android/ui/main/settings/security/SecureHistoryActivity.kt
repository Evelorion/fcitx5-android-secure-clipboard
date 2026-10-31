/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.security

import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.secureclipboard.CaptureSource
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardEntry
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardRecord
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardVault
import org.fcitx.fcitx5.android.data.secureclipboard.export.SecureClipboardExportCrypto
import org.fcitx.fcitx5.android.data.secureclipboard.export.SecureClipboardExportException
import org.fcitx.fcitx5.android.databinding.ActivitySecureHistoryBinding
import org.fcitx.fcitx5.android.databinding.ItemSecureHistoryBinding
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.toast
import splitties.resources.styledColor
import splitties.views.topPadding
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Privacy history": masked list of the encrypted secure clipboard with on-demand search,
 * reveal, pin/delete and passphrase-protected backup export/import.
 *
 * Privacy rules implemented here:
 *  - The list itself is never decrypted: it only shows the masked label, the capture source,
 *    the timestamp and the pinned marker.
 *  - Plaintext is obtained for exactly one record on explicit user action (reveal) or for a
 *    user-initiated search/export, inside [Dispatchers.IO], and dropped as soon as it has been
 *    shown/written. Nothing is logged or written to the system clipboard.
 *  - Export/import use the authenticated [SecureClipboardExportCrypto] file format and wipe the
 *    passphrase CharArray in a finally block.
 *  - The activity window is FLAG_SECURE, and so are reveal dialogs, so plaintext never appears
 *    in screenshots or the recents thumbnail.
 */
class SecureHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecureHistoryBinding

    private val historyAdapter by lazy { HistoryAdapter() }

    private var busy = false

    /** Non-null while the list shows search results; used to refresh in the same mode. */
    private var currentQuery: String? = null

    private var revealDialog: AlertDialog? = null

    private val repository get() = SecureClipboardVault.repository

    private val contentPadding by lazy { (16 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ClipboardManager.isSecureMode) {
            toast(R.string.secure_clipboard_need_enable)
            finish()
            return
        }
        if (!SecureClipboardVault.isInitialized) {
            toast(R.string.secure_clipboard_status_unknown)
            finish()
            return
        }
        binding = ActivitySecureHistoryBinding.inflate(layoutInflater)
        // The list is masked, but reveal dialogs show plaintext: keep screenshots and the
        // recents thumbnail out of the picture for the whole screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            binding.toolbar.topPadding = statusBars.top
            binding.historyList.updatePadding(bottom = navBars.bottom)
            windowInsets
        }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.privacy_history)
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = historyAdapter
        binding.searchButton.setOnClickListener { performSearch() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }
        loadRecent()
    }

    override fun onDestroy() {
        super.onDestroy()
        revealDialog?.dismiss()
        revealDialog = null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val iconTint = styledColor(android.R.attr.colorControlNormal)
        menu.item(R.string.export_secure_backup, R.drawable.ic_baseline_save_24, iconTint, true) {
            if (!busy) showExportPassphraseDialog()
            true
        }
        menu.item(
            R.string.import_secure_backup,
            R.drawable.ic_baseline_settings_backup_restore_24,
            iconTint,
            true
        ) {
            if (!busy) showImportFilePicker()
            true
        }
        menu.item(R.string.secure_history_clear, R.drawable.ic_baseline_delete_24, iconTint, true) {
            if (!busy) confirmClearAll()
            true
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == android.R.id.home) {
            finish()
            true
        } else {
            super.onOptionsItemSelected(item)
        }

    /* =================== list loading / search =================== */

    private fun loadRecent() {
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val entries = try {
                withContext(Dispatchers.IO) { repository.recentRows(RECENT_LIMIT) }
            } catch (e: Exception) {
                Timber.w("Secure history list failed: " + e.javaClass.simpleName)
                emptyList<SecureClipboardEntry>()
            }
            if (isFinishing || isDestroyed) return@launch
            historyAdapter.submit(entries.map { rowFrom(it) })
            binding.emptyText.setText(R.string.secure_history_empty)
            binding.emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            setBusy(false)
        }
    }

    private fun performSearch() {
        if (busy) return
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            // Empty query: go back to the recent list instead of scanning everything.
            currentQuery = null
            loadRecent()
            return
        }
        currentQuery = query
        runSearch(query)
    }

    private fun runSearch(query: String) {
        if (busy) return
        setBusy(true, getString(R.string.secure_history_searching))
        lifecycleScope.launch {
            val records = try {
                withContext(Dispatchers.IO) { repository.search(query, SEARCH_LIMIT) }
            } catch (e: Exception) {
                Timber.w("Secure history search failed: " + e.javaClass.simpleName)
                emptyList<SecureClipboardRecord>()
            }
            if (isFinishing || isDestroyed) return@launch
            // Only masked rows are kept in memory; the decrypted list is dropped right here.
            val rows = records.map { rowFrom(it) }
            historyAdapter.submit(rows)
            binding.emptyText.visibility = View.GONE
            val status = if (rows.isEmpty()) {
                getString(R.string.secure_history_no_results)
            } else {
                getString(R.string.secure_history_results, rows.size)
            }
            setBusy(false, status)
        }
    }

    private fun reload() {
        if (busy) return
        val query = currentQuery
        if (query.isNullOrEmpty()) loadRecent() else runSearch(query)
    }

    private fun setBusy(value: Boolean, status: CharSequence? = null) {
        busy = value
        binding.searchButton.isEnabled = !value
        updateStatus(status)
    }

    private fun updateStatus(text: CharSequence?) {
        if (isFinishing || isDestroyed) return
        if (text.isNullOrEmpty()) {
            binding.statusText.text = ""
            binding.statusText.visibility = View.GONE
        } else {
            binding.statusText.text = text
            binding.statusText.visibility = View.VISIBLE
        }
    }

    /* =================== per-record actions =================== */

    /** Reveal one record after an explicit user request; the dialog holds the only reference. */
    private fun revealRecord(id: Int) {
        if (busy) return
        lifecycleScope.launch {
            val record = try {
                withContext(Dispatchers.IO) { repository.read(id) }
            } catch (e: Exception) {
                Timber.w("Secure history reveal failed: " + e.javaClass.simpleName)
                null
            }
            if (isFinishing || isDestroyed) return@launch
            if (record == null) {
                toast(R.string.secure_history_reveal_failed)
                return@launch
            }
            showRevealDialog(record)
        }
    }

    private fun showRevealDialog(record: SecureClipboardRecord) {
        revealDialog?.dismiss()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.secure_entry_reveal)
            .setMessage(record.text)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        // The revealed plaintext must not be visible in screenshots or the recents thumbnail.
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener {
            // Drop the decrypted message as soon as the dialog goes away.
            dialog.setMessage("")
            if (revealDialog === dialog) revealDialog = null
        }
        dialog.show()
        revealDialog = dialog
    }

    private fun showRowMenu(anchor: View, row: HistoryRow) {
        if (busy) return
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        val iconTint = styledColor(android.R.attr.colorControlNormal)
        menu.item(R.string.secure_entry_reveal, R.drawable.ic_shield_24, iconTint) {
            revealRecord(row.id)
        }
        if (row.pinned) {
            menu.item(R.string.unpin, R.drawable.ic_outline_push_pin_24, iconTint) {
                setPinned(row.id, false)
            }
        } else {
            menu.item(R.string.pin, R.drawable.ic_baseline_push_pin_24, iconTint) {
                setPinned(row.id, true)
            }
        }
        menu.item(R.string.delete, R.drawable.ic_baseline_delete_24, iconTint) {
            deleteRecord(row.id)
        }
        popup.show()
    }

    private fun setPinned(id: Int, pinned: Boolean) {
        if (busy) return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (pinned) ClipboardManager.pin(id) else ClipboardManager.unpin(id)
                }
                reload()
            } catch (e: Exception) {
                Timber.w("Secure history pin failed: " + e.javaClass.simpleName)
            }
        }
    }

    private fun deleteRecord(id: Int) {
        if (busy) return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ClipboardManager.delete(id) }
                reload()
            } catch (e: Exception) {
                Timber.w("Secure history delete failed: " + e.javaClass.simpleName)
            }
        }
    }

    private fun confirmClearAll() {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle(R.string.secure_history_clear)
            .setMessage(R.string.secure_history_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> clearAllRecords() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearAllRecords() {
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ClipboardManager.deleteAll(skipPinned = false)
                    ClipboardManager.realDelete()
                }
                currentQuery = null
                binding.searchInput.setText("")
                toast(R.string.secure_clipboard_clear_history_done)
            } catch (e: Exception) {
                Timber.w("Secure history clear failed: " + e.javaClass.simpleName)
                toast(R.string.secure_clipboard_status_failed)
            } finally {
                if (!isFinishing && !isDestroyed) {
                    setBusy(false)
                    loadRecent()
                }
            }
        }
    }

    /* =================== encrypted backup: export =================== */

    private fun showExportPassphraseDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(contentPadding, contentPadding / 2, contentPadding, 0)
        }
        val passphraseInput = passwordEditText(R.string.export_passphrase_hint)
        val confirmInput = passwordEditText(R.string.export_passphrase_confirm_hint)
        container.addView(passphraseInput)
        container.addView(confirmInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.export_passphrase_title)
            .setMessage(R.string.secure_history_export_dir_note)
            .setView(container)
            .setPositiveButton(R.string.export, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            // Never leave the typed passphrase behind in a dismissed dialog view.
            clearText(passphraseInput, confirmInput)
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val first = passphraseInput.text ?: return@setOnClickListener
                val second = confirmInput.text ?: return@setOnClickListener
                when {
                    first.isEmpty() -> toast(R.string.passphrase_required)
                    first.length < MIN_PASSPHRASE_LENGTH -> toast(R.string.passphrase_too_short)
                    !TextUtils.equals(first, second) -> toast(R.string.passphrase_mismatch)
                    else -> {
                        // Copy to a CharArray and clear the EditTexts instead of keeping Strings.
                        val passphrase = CharArray(first.length)
                        TextUtils.getChars(first, 0, first.length, passphrase, 0)
                        clearText(passphraseInput, confirmInput)
                        dialog.dismiss()
                        startExport(passphrase)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun startExport(passphrase: CharArray) {
        if (busy) {
            passphrase.fill('\u0000')
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            try {
                val records = withContext(Dispatchers.IO) {
                    repository.readAllRecords { _, total ->
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                updateStatus(getString(R.string.export_progress, total))
                            }
                        }
                    }
                }
                if (records.isEmpty()) {
                    toast(R.string.export_no_records)
                    return@launch
                }
                val path = withContext(Dispatchers.IO) { writeExportFile(records, passphrase) }
                toast(getString(R.string.export_success, path))
            } catch (e: Exception) {
                // Never log the exception message: it could contain data derived from plaintext.
                Timber.w("Secure history export failed: " + e.javaClass.simpleName)
                toast(getString(R.string.export_failed, e.javaClass.simpleName))
            } finally {
                passphrase.fill('\u0000')
                if (!isFinishing && !isDestroyed) setBusy(false)
            }
        }
    }

    private fun writeExportFile(records: List<SecureClipboardRecord>, passphrase: CharArray): String {
        val directory = getExternalFilesDir(EXPORT_DIR_NAME)
            ?: throw IllegalStateException("External files directory is unavailable")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Cannot create the export directory")
        }
        val encrypted = SecureClipboardExportCrypto.encrypt(passphrase, records)
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(directory, "secure-clipboard-" + timestamp + BACKUP_EXTENSION)
        file.writeBytes(encrypted)
        return file.absolutePath
    }

    /* =================== encrypted backup: import =================== */

    private fun showImportFilePicker() {
        if (busy) return
        val directory = getExternalFilesDir(EXPORT_DIR_NAME)
        val files = directory?.listFiles { file ->
            file.isFile && file.name.endsWith(BACKUP_EXTENSION, ignoreCase = true)
        }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            toast(R.string.import_no_files)
            return
        }
        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.import_secure_backup)
            .setItems(names) { _, which -> showImportPassphraseDialog(files[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showImportPassphraseDialog(file: File) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(contentPadding, contentPadding / 2, contentPadding, 0)
        }
        val passphraseInput = passwordEditText(R.string.import_passphrase_hint)
        container.addView(passphraseInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.import_passphrase_title)
            .setView(container)
            .setPositiveButton(R.string.import_, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            // Never leave the typed passphrase behind in a dismissed dialog view.
            clearText(passphraseInput)
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val text = passphraseInput.text ?: return@setOnClickListener
                if (text.isEmpty()) {
                    toast(R.string.passphrase_required)
                    return@setOnClickListener
                }
                val passphrase = CharArray(text.length)
                TextUtils.getChars(text, 0, text.length, passphrase, 0)
                clearText(passphraseInput)
                dialog.dismiss()
                startImport(file, passphrase)
            }
        }
        dialog.show()
    }

    private fun startImport(file: File, passphrase: CharArray) {
        if (busy) {
            passphrase.fill('\u0000')
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val data = file.readBytes()
                    val records = try {
                        SecureClipboardExportCrypto.decrypt(passphrase, data)
                    } finally {
                        data.fill(0)
                    }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            updateStatus(getString(R.string.import_progress, records.size))
                        }
                    }
                    repository.importRecords(records)
                }
                toast(getString(R.string.import_success, result.imported, result.skipped))
            } catch (e: SecureClipboardExportException.BadPassphraseOrCorrupted) {
                toast(R.string.backup_wrong_passphrase)
            } catch (e: SecureClipboardExportException.InvalidFormat) {
                Timber.w("Secure history import rejected the file: " + e.javaClass.simpleName)
                toast(getString(R.string.import_failed, e.javaClass.simpleName))
            } catch (e: Exception) {
                Timber.w("Secure history import failed: " + e.javaClass.simpleName)
                toast(getString(R.string.import_failed, e.javaClass.simpleName))
            } finally {
                passphrase.fill('\u0000')
                if (!isFinishing && !isDestroyed) {
                    setBusy(false)
                    reload()
                }
            }
        }
    }

    /* =================== helpers =================== */

    private fun passwordEditText(@StringRes hint: Int): EditText = EditText(this).apply {
        setHint(hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        maxLines = 1
        setSingleLine()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
    }

    private fun clearText(vararg inputs: EditText) {
        inputs.forEach { it.text?.clear() }
    }

    private fun rowFrom(entry: SecureClipboardEntry): HistoryRow = HistoryRow(
        id = entry.id,
        pinned = entry.isPinned,
        source = sourceLabel(entry.sourceType),
        time = formatTime(entry.createdAt),
    )

    private fun rowFrom(record: SecureClipboardRecord): HistoryRow = HistoryRow(
        id = record.id,
        pinned = record.pinned,
        source = sourceLabel(record.source.storageValue),
        time = formatTime(record.timestamp),
    )

    /** Localized capture-source label; no plaintext involved. */
    private fun sourceLabel(sourceType: String): String =
        when (CaptureSource.fromStorageValue(sourceType)) {
            CaptureSource.ProcessText -> getString(R.string.secure_capture)
            CaptureSource.ImeSelection -> getString(R.string.save_selected_text)
            CaptureSource.Share -> getString(R.string.share)
            CaptureSource.SystemClipboard -> getString(R.string.clipboard)
        }

    private fun formatTime(timestamp: Long): String {
        val date = Date(timestamp)
        return DateFormat.getMediumDateFormat(this).format(date) +
                " " + DateFormat.getTimeFormat(this).format(date)
    }

    /** One masked row. It never contains plaintext, only display metadata. */
    private data class HistoryRow(
        val id: Int,
        val pinned: Boolean,
        val source: String,
        val time: String,
    )

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.Holder>() {

        private val items = mutableListOf<HistoryRow>()

        fun submit(newItems: List<HistoryRow>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                ItemSecureHistoryBinding.inflate(
                    this@SecureHistoryActivity.layoutInflater,
                    parent,
                    false
                )
            )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        inner class Holder(private val itemBinding: ItemSecureHistoryBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(row: HistoryRow) {
                // Never render decrypted content in the list; the row is a masked label.
                itemBinding.secureHistoryItemTitle.text = getString(R.string.secure_entry_masked)
                itemBinding.secureHistoryItemMeta.text =
                    getString(R.string.secure_capture_source, row.source) + " \u00b7 " + row.time
                itemBinding.secureHistoryItemPin.visibility =
                    if (row.pinned) View.VISIBLE else View.GONE
                itemBinding.root.setOnClickListener { revealRecord(row.id) }
                itemBinding.root.setOnLongClickListener {
                    showRowMenu(itemBinding.root, row)
                    true
                }
            }
        }
    }

    private companion object {
        const val RECENT_LIMIT = 500
        const val SEARCH_LIMIT = 200
        const val MIN_PASSPHRASE_LENGTH = 8
        const val EXPORT_DIR_NAME = "secure-exports"
        const val BACKUP_EXTENSION = ".fcsc"
    }
}
