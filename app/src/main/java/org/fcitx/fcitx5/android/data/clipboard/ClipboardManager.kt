/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.annotation.Keep
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.secureclipboard.CaptureRequest
import org.fcitx.fcitx5.android.data.secureclipboard.CaptureSource
import org.fcitx.fcitx5.android.data.secureclipboard.ClipboardSecurityStatus
import org.fcitx.fcitx5.android.data.secureclipboard.ClipboardSecurityStatusHolder
import org.fcitx.fcitx5.android.data.secureclipboard.LegacyClipboardRow
import org.fcitx.fcitx5.android.data.secureclipboard.MigrationResult
import org.fcitx.fcitx5.android.data.secureclipboard.SaveResult
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardEntry
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardError
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardRecord
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardVault
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import timber.log.Timber

/**
 * Facade over both clipboard storage backends.
 *
 * - Legacy mode (default): the original plaintext Room database "clbdb" is used and a normal
 *   system clipboard listener copies text into it. Behaviour is unchanged from upstream.
 * - Secure mode (opt-in in settings): history lives in the AES-256-GCM encrypted database
 *   owned by [SecureClipboardVault]. The system clipboard listener is replaced by
 *   [SecureClipboardController] which encrypts, persists and then clears the system clipboard.
 *
 * The facade never decrypts history in bulk. Secure rows are exposed to the clipboard panel as
 * masked [ClipboardEntry] items (secure = true); plaintext is only obtained through
 * [readText]/[readEntry] for a single record on demand.
 */
object ClipboardManager : ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {

    private lateinit var legacyDb: ClipboardDatabase
    private lateinit var legacyDao: ClipboardDao
    private lateinit var secureController: SecureClipboardController

    fun interface OnClipboardUpdateListener {
        fun onUpdate(entry: ClipboardEntry)
    }

    private val systemClipboard = appContext.clipboardManager

    private val clipboardPrefs get() = AppPrefs.getInstance().clipboard
    private val securityPrefs get() = AppPrefs.getInstance().security

    private val mutex = Mutex()
    private val migrationMutex = Mutex()

    private val maskedLabel: String by lazy { appContext.getString(R.string.secure_entry_masked) }

    var itemCount: Int = 0
        private set

    private suspend fun updateItemCount() {
        itemCount = if (isSecureMode) {
            if (SecureClipboardVault.isInitialized) SecureClipboardVault.repository.itemCount() else 0
        } else {
            legacyDao.itemCount()
        }
    }

    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()

    /**
     * Legacy clipboard transformer hook. Third-party plugins register through
     * [org.fcitx.fcitx5.android.FcitxRemoteService]. In secure mode raw text is only handed to it
     * when the user explicitly allowed plugins in the security settings.
     */
    var transformer: ((String) -> String)? = null

    /** Whether the encrypted secure clipboard mode is currently enabled. */
    val isSecureMode: Boolean
        get() = securityPrefs.secureClipboardEnabled.getValue()

    fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.add(listener)
    }

    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.remove(listener)
    }

    var lastEntry: ClipboardEntry? = null
        private set

    private fun updateLastEntry(entry: ClipboardEntry) {
        lastEntry = entry
        onUpdateListeners.forEach { it.onUpdate(entry) }
    }

    @Keep
    private val listeningListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        updateListenerRegistration()
    }

    @Keep
    private val limitListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        launch {
            if (isSecureMode) {
                if (SecureClipboardVault.isInitialized) {
                    SecureClipboardVault.repository.enforceLimit(
                        securityPrefs.secureClipboardHistoryLimit.getValue()
                    )
                }
            } else {
                removeOutdated()
            }
            updateItemCount()
        }
    }

    @Keep
    private val secureModeListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        onSecureModeChanged(enabled)
    }

    fun init(context: Context) {
        legacyDb = Room.databaseBuilder(context, ClipboardDatabase::class.java, "clbdb")
            // allow wipe the database instead of crashing when downgrade
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
        legacyDao = legacyDb.clipboardDao()
        // Zero freed pages of the legacy plaintext table on a best-effort basis. This cannot
        // erase data already written to flash, but it limits what stays in the SQLite file.
        runCatching {
            legacyDb.openHelper.writableDatabase.execPragma("PRAGMA secure_delete=ON")
        }.onFailure {
            Timber.w("Cannot enable secure_delete on legacy clipboard db: ${it.javaClass.simpleName}")
        }

        // Never touch the credential-protected encrypted vault before first unlock.
        val directBoot = FcitxApplication.getInstance().isDirectBootMode
        if (!directBoot) {
            SecureClipboardVault.init(context)
        } else {
            Timber.i("Direct boot mode: secure clipboard vault is not initialized")
        }

        secureController = SecureClipboardController(
            context = context.applicationContext,
            scope = this,
            repositoryProvider = { SecureClipboardVault.repository },
            onEntryPersisted = ::onSecureEntryPersisted,
            transformText = ::transformForSecureCaptureResult
        )

        clipboardPrefs.clipboardListening.registerOnChangeListener(listeningListener)
        clipboardPrefs.clipboardHistoryLimit.registerOnChangeListener(limitListener)
        securityPrefs.secureClipboardEnabled.registerOnChangeListener(secureModeListener)
        updateListenerRegistration()
        launch {
            updateItemCount()
            if (isSecureMode) migrateLegacyIfNeeded()
        }
    }

    /**
     * Registers exactly one system clipboard listener: either the legacy plaintext listener or
     * the encrypted secure controller. Never both.
     */
    private fun updateListenerRegistration() {
        val listening = clipboardPrefs.clipboardListening.getValue()
        if (isSecureMode) {
            systemClipboard.removePrimaryClipChangedListener(this)
            secureController.setListening(listening)
        } else {
            secureController.setListening(false)
            if (listening) {
                systemClipboard.addPrimaryClipChangedListener(this)
            } else {
                systemClipboard.removePrimaryClipChangedListener(this)
            }
        }
    }

    private fun onSecureModeChanged(enabled: Boolean) {
        Timber.i("Secure clipboard mode changed: enabled=$enabled")
        updateListenerRegistration()
        lastEntry = null
        if (enabled) {
            launch {
                migrateLegacyIfNeeded()
                updateItemCount()
            }
        }
    }

    /**
     * Apply third-party clipboard transformers to channel B captures.
     * Channel A captures (secure capture / share / IME selection) never go through plugins.
     */
    fun transformForSecureCaptureResult(text: String): String {
        if (!securityPrefs.secureClipboardAllowPlugins.getValue()) return text
        return try {
            transformer?.invoke(text) ?: text
        } catch (e: Exception) {
            Timber.w("Clipboard transformer failed: ${e.javaClass.simpleName}")
            text
        }
    }

    /** Called by [SecureClipboardController] after a record has been committed to the vault. */
    fun onSecureEntryPersisted(id: Int, timestamp: Long, source: CaptureSource) {
        updateLastEntry(
            ClipboardEntry(
                id = id,
                text = maskedLabel,
                pinned = false,
                timestamp = timestamp,
                type = "text/plain"
            ).apply { secure = true }
        )
        launch {
            updateItemCount()
            if (SecureClipboardVault.isInitialized) {
                SecureClipboardVault.repository.enforceLimit(
                    securityPrefs.secureClipboardHistoryLimit.getValue()
                )
            }
        }
    }

    /* =================== capturing (channel A) =================== */

    /**
     * Re-read the system clipboard once, e.g. when the clipboard panel opens. This recovers a
     * copy that happened while the IME process was not listening (or a rich-text clip that also
     * carries text/plain). No-op outside secure mode or when auto-capture is disabled.
     */
    suspend fun syncSecureClipboard() {
        if (!isSecureMode) return
        if (!SecureClipboardVault.isInitialized) return
        if (!::secureController.isInitialized) return
        if (!securityPrefs.secureClipboardAutoCapture.getValue()) return
        try {
            secureController.captureCurrentClipboard()
        } catch (e: Exception) {
            Timber.w("Secure clipboard: manual sync failed (${e.javaClass.simpleName})")
        }
    }

    /** Unified entry point for all safe-capture channels (PROCESS_TEXT, SEND, IME selection). */
    suspend fun saveSecureCapture(request: CaptureRequest): SaveResult {
        if (!isSecureMode) return SaveResult.Failure(SecureClipboardError.Disabled)
        if (!SecureClipboardVault.isInitialized) {
            return SaveResult.Failure(SecureClipboardError.NotReady)
        }
        val result = SecureClipboardVault.repository.save(request)
        if (result is SaveResult.Success) {
            onSecureEntryPersisted(result.id, request.timestamp, request.source)
        }
        return result
    }

    /* =================== reading =================== */

    /** Display item without decrypting its content. */
    suspend fun get(id: Int): ClipboardEntry? =
        if (isSecureMode) {
            if (!SecureClipboardVault.isInitialized) null
            else SecureClipboardVault.repository.getRow(id)?.toDisplayEntry(maskedLabel)
        } else {
            legacyDao.get(id)
        }

    /** Single record with plaintext in memory. Callers must not retain or log the text. */
    suspend fun readEntry(id: Int): ClipboardEntry? =
        if (isSecureMode) {
            if (!SecureClipboardVault.isInitialized) null
            else SecureClipboardVault.repository.read(id)?.toDisplayEntry()
        } else {
            legacyDao.get(id)
        }

    suspend fun readText(id: Int): String? = readEntry(id)?.text

    /* =================== listing =================== */

    fun allEntries(): PagingSource<Int, ClipboardEntry> =
        if (isSecureMode) {
            if (!SecureClipboardVault.isInitialized) EmptyPagingSource()
            else SecureDisplayPagingSource(
                SecureClipboardVault.repository.allRows(),
                appContext.getString(R.string.secure_entry_masked),
                // Show real text in the panel by default; masking stays available as an option.
                if (securityPrefs.secureClipboardMaskHistory.getValue()) null else { id ->
                    SecureClipboardVault.repository.read(id)?.text
                }
            )
        } else {
            legacyDao.allEntries()
        }

    /* =================== pin / edit / delete =================== */

    suspend fun haveUnpinned(): Boolean =
        if (isSecureMode) {
            if (!SecureClipboardVault.isInitialized) false
            else SecureClipboardVault.repository.haveUnpinned()
        } else {
            legacyDao.haveUnpinned()
        }

    suspend fun pin(id: Int) {
        if (isSecureMode && SecureClipboardVault.isInitialized) {
            SecureClipboardVault.repository.pin(id, true)
        } else {
            legacyDao.updatePinStatus(id, true)
        }
    }

    suspend fun unpin(id: Int) {
        if (isSecureMode && SecureClipboardVault.isInitialized) {
            SecureClipboardVault.repository.pin(id, false)
        } else {
            legacyDao.updatePinStatus(id, false)
        }
    }

    suspend fun updateText(id: Int, text: String) {
        if (isSecureMode && SecureClipboardVault.isInitialized) {
            SecureClipboardVault.repository.replaceText(
                id,
                CaptureRequest(
                    text = text,
                    source = CaptureSource.ImeSelection,
                    timestamp = System.currentTimeMillis()
                )
            )
        } else {
            lastEntry?.let {
                if (id == it.id) updateLastEntry(it.copy(text = text))
            }
            legacyDao.updateText(id, text)
        }
    }

    suspend fun delete(id: Int) {
        if (isSecureMode && SecureClipboardVault.isInitialized) {
            SecureClipboardVault.repository.delete(id)
        } else {
            legacyDao.markAsDeleted(id)
        }
        updateItemCount()
    }

    suspend fun deleteAll(skipPinned: Boolean = true): IntArray {
        val ids = if (isSecureMode && SecureClipboardVault.isInitialized) {
            SecureClipboardVault.repository.deleteAll(skipPinned)
        } else {
            val ids = if (skipPinned) legacyDao.findUnpinnedIds() else legacyDao.findAllIds()
            legacyDao.markAsDeleted(*ids)
            ids
        }
        updateItemCount()
        return ids
    }

    suspend fun undoDelete(vararg ids: Int) {
        if (isSecureMode && SecureClipboardVault.isInitialized) {
            SecureClipboardVault.repository.undoDelete(*ids)
        } else {
            legacyDao.undoDelete(*ids)
        }
        updateItemCount()
    }

    suspend fun realDelete() {
        if (isSecureMode && SecureClipboardVault.isInitialized) {
            SecureClipboardVault.repository.realDelete()
        } else {
            withContext(Dispatchers.IO) {
                legacyDao.realDelete()
                runCatching {
                    legacyDb.openHelper.writableDatabase.execSQL("VACUUM")
                }
            }
        }
    }

    suspend fun nukeTable() {
        if (isSecureMode && SecureClipboardVault.isInitialized) {
            SecureClipboardVault.repository.clearAll()
            updateItemCount()
        } else {
            withContext(Dispatchers.IO) {
                legacyDb.clearAllTables()
                runCatching {
                    legacyDb.openHelper.writableDatabase.execSQL("VACUUM")
                }
                updateItemCount()
            }
        }
    }

    /* =================== migration =================== */

    /**
     * One-shot migration of the legacy plaintext clipboard history into the encrypted vault.
     * The legacy table is only wiped after a fully verified migration. Returns null when there
     * is nothing to do.
     */
    suspend fun migrateLegacyIfNeeded(force: Boolean = false): MigrationResult? =
        withContext(Dispatchers.IO) {
            if (!isSecureMode || !SecureClipboardVault.isInitialized) return@withContext null
            val migrated = AppPrefs.getInstance().internal.secureClipboardLegacyMigrated
            migrationMutex.withLock {
                // Always look at the actual table instead of trusting the preference flag: a
                // restored backup can bring plaintext rows back together with an already-set
                // flag, and a partial wipe can leave rows behind. Migration is idempotent.
                val rows = legacyDao.allActiveRows().map {
                    LegacyClipboardRow(
                        id = it.id,
                        text = it.text,
                        pinned = it.pinned,
                        timestamp = it.timestamp,
                        type = it.type,
                        sensitive = it.sensitive
                    )
                }
                if (rows.isEmpty()) {
                    migrated.setValue(true)
                    return@withLock if (force) MigrationResult(0, 0, 0) else null
                }
                val result = SecureClipboardVault.repository.migrateLegacy(rows)
                if (result.success) {
                    try {
                        val db = legacyDb.openHelper.writableDatabase
                        // Overwrite deleted content with zeros before freeing pages, then
                        // shrink the file and truncate the WAL. Physical flash residue on
                        // SSD/UFS cannot be guaranteed to be erased by this.
                        // NOTE: execSQL() rejects PRAGMA statements that return a row
                        // ("Queries can be performed using ... rawQuery only"), which is why
                        // pragmas go through rawQuery(). The DELETE must run even if setting
                        // the pragma is not supported, hence the independent runCatching.
                        runCatching { db.execPragma("PRAGMA secure_delete=ON") }
                            .onFailure {
                                Timber.w("secure_delete pragma unsupported: ${it.javaClass.simpleName}")
                            }
                        db.execSQL("DELETE FROM ${ClipboardEntry.TABLE_NAME}")
                        runCatching { db.execSQL("VACUUM") }
                            .onFailure {
                                Timber.w("legacy clipboard VACUUM failed: ${it.javaClass.simpleName}")
                            }
                        runCatching { db.execPragma("PRAGMA wal_checkpoint(TRUNCATE)") }
                            .onFailure {
                                Timber.w("legacy clipboard checkpoint failed: ${it.javaClass.simpleName}")
                            }
                        val remaining = legacyDao.rawRowCount()
                        if (remaining != 0) {
                            Timber.w("Legacy plaintext table not fully wiped: $remaining row(s) left")
                        }
                    } catch (e: Exception) {
                        Timber.w("Legacy plaintext table wipe incomplete: ${e.javaClass.simpleName}")
                    }
                    migrated.setValue(true)
                    updateItemCount()
                } else {
                    ClipboardSecurityStatusHolder.update {
                        it.copy(
                            lastError = SecureClipboardError.LegacyMigrationFailed,
                            lastErrorTime = System.currentTimeMillis()
                        )
                    }
                    Timber.w("Legacy clipboard migration incomplete: migrated=${result.migrated}/${result.total}")
                }
                result
            }
        }

    /* =================== legacy listener (channel B, plaintext mode) =================== */

    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    override fun onPrimaryClipChanged() {
        val clip = systemClipboard.primaryClip ?: return
        /**
         * skip duplicate ClipData
         * https://developer.android.com/reference/android/content/ClipboardManager.OnPrimaryClipChangedListener#onPrimaryClipChanged()
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timestamp = clip.description.timestamp
            if (timestamp == lastClipTimestamp) return
            lastClipTimestamp = timestamp
        } else {
            val timestamp = System.currentTimeMillis()
            val hash = clip.hashCode()
            if (timestamp - lastClipTimestamp < 100L && hash == lastClipHash) return
            lastClipTimestamp = timestamp
            lastClipHash = hash
        }
        launch {
            mutex.withLock {
                val entry = ClipboardEntry.fromClipData(clip, transformer) ?: return@withLock
                if (entry.text.isBlank()) return@withLock
                try {
                    legacyDao.find(entry.text, entry.sensitive)?.let {
                        updateLastEntry(it.copy(timestamp = entry.timestamp))
                        legacyDao.updateTime(it.id, entry.timestamp)
                        return@withLock
                    }
                    val insertedEntry = legacyDb.withTransaction {
                        val rowId = legacyDao.insert(entry)
                        removeOutdated()
                        // new entry can be deleted immediately if clipboard limit == 0
                        legacyDao.get(rowId) ?: entry
                    }
                    updateLastEntry(insertedEntry)
                    updateItemCount()
                } catch (exception: Exception) {
                    Timber.w("Failed to update clipboard database: ${exception.javaClass.simpleName}")
                    updateLastEntry(entry)
                }
            }
        }
    }

    private suspend fun removeOutdated() {
        val limit = clipboardPrefs.clipboardHistoryLimit.getValue()
        val unpinned = legacyDao.getAllUnpinned()
        if (unpinned.size > limit) {
            // the last one we will keep
            val last = unpinned
                .sortedBy { it.id }
                .getOrNull(unpinned.size - limit)
            // delete all unpinned before that, or delete all when limit <= 0
            legacyDao.markUnpinnedAsDeletedEarlierThan(last?.timestamp ?: System.currentTimeMillis())
        }
    }
}

/* =================== mapping helpers =================== */

private fun SecureClipboardEntry.toDisplayEntry(label: String): ClipboardEntry = ClipboardEntry(
    id = id,
    text = label,
    pinned = isPinned,
    timestamp = createdAt,
    type = "text/plain",
    deleted = isDeleted,
    sensitive = false
).apply { secure = true }

private fun SecureClipboardRecord.toDisplayEntry(): ClipboardEntry = ClipboardEntry(
    id = id,
    text = text,
    pinned = pinned,
    timestamp = timestamp,
    type = "text/plain"
).apply { secure = true }

/**
 * Paging source that maps encrypted rows to display items. Decrypts a single visible row on
 * demand unless the user opted into masked history; the encrypted database is never bulk-decrypted
 * outside of the current page.
 */
private class SecureDisplayPagingSource(
    private val delegate: PagingSource<Int, SecureClipboardEntry>,
    private val label: String,
    private val decrypt: (suspend (Int) -> String?)? = null
) : PagingSource<Int, ClipboardEntry>() {

    init {
        delegate.registerInvalidatedCallback { invalidate() }
    }

    override fun getRefreshKey(state: PagingState<Int, ClipboardEntry>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ClipboardEntry> =
        when (val result = delegate.load(params)) {
            is LoadResult.Page -> LoadResult.Page(
                data = result.data.map { entry ->
                    val text = if (decrypt == null) {
                        label
                    } else {
                        try {
                            decrypt(entry.id) ?: label
                        } catch (e: Exception) {
                            label
                        }
                    }
                    entry.toDisplayEntry(text)
                },
                prevKey = result.prevKey,
                nextKey = result.nextKey
            )

            is LoadResult.Error -> LoadResult.Error(result.throwable)
            is LoadResult.Invalid -> LoadResult.Invalid()
        }
}

/**
 * Android's `execSQL()` refuses statements that return rows, which some PRAGMAs do
 * (`secure_delete`, `wal_checkpoint`). They must be executed as queries instead.
 */
private fun SupportSQLiteDatabase.execPragma(pragma: String) {
    query(pragma).use { it.moveToFirst() }
}

/** Used before the vault is available (direct boot) so the panel simply stays empty. */
private class EmptyPagingSource : PagingSource<Int, ClipboardEntry>() {
    override fun getRefreshKey(state: PagingState<Int, ClipboardEntry>): Int? = null
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ClipboardEntry> =
        LoadResult.Page(emptyList(), null, null)
}
