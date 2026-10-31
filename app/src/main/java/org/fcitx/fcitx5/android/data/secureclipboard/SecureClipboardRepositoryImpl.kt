/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.ClipboardCrypto
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.ClipboardCryptoException
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.ClipboardKeyManager
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.ClipboardKeyResult
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.EncryptedPayload
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.SecureClipboardAad
import timber.log.Timber
import javax.crypto.SecretKey

/**
 * Encrypted storage of the privacy-first clipboard.
 *
 * - The Room database is created lazily on first use, so constructing the repository (from
 *   [SecureClipboardVault]) never opens a file, touches the Keystore or throws.
 * - [save] validates the request, rejects sensitive content by default, de-duplicates by keyed
 *   HMAC tag, encrypts payload **and** metadata with AES-256-GCM and inserts inside a transaction
 *   that also enforces the history limit.
 * - A failed save never deletes or overwrites an existing record: encryption happens before any
 *   database write and [replaceText] only updates after the new ciphertext was produced.
 * - [read] decrypts exactly one row. Failures (missing key, tampered ciphertext) return `null`
 *   instead of throwing, so the UI can degrade gracefully.
 * - Time comes from the injected [clock], which makes duration accounting testable.
 *
 * Logging never includes plaintext, IV or key material; only the exception class name is logged.
 */
class SecureClipboardRepositoryImpl(
    context: Context,
    private val crypto: ClipboardCrypto = ClipboardCrypto(),
    private val keyManager: ClipboardKeyManager = ClipboardKeyManager(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val historyLimitProvider: () -> Int = { SecureClipboardLimits.DEFAULT_HISTORY_LIMIT },
) : SecureClipboardRepository {

    private val appContext: Context = context.applicationContext

    @Volatile
    private var initialized: Boolean = false

    private val database: SecureClipboardDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SecureClipboardDatabase.create(appContext).also { initialized = true }
    }

    private val dao: SecureClipboardDao by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        database.secureClipboardDao()
    }

    private val saveMutex = Mutex()

    private val migrationStore: SecureClipboardMigrationStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomMigrationStore()
    }

    private val migration: SecureClipboardMigration by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SecureClipboardMigration(
            crypto = crypto,
            store = migrationStore,
            encryptionKeyProvider = { encryptionKeyOrNull() },
            dedupKeyProvider = { dedupKeyOrNull() },
            clock = clock,
            keyVersion = ClipboardKeyManager.CURRENT_KEY_VERSION,
        )
    }

    /* =================== listing =================== */

    override fun allRows(): PagingSource<Int, SecureClipboardEntry> = dao.allRows()

    override suspend fun itemCount(): Int = withContext(Dispatchers.IO) {
        try {
            dao.itemCount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Secure clipboard itemCount failed: ${e.javaClass.simpleName}")
            0
        }
    }

    override suspend fun haveUnpinned(): Boolean = withContext(Dispatchers.IO) {
        try {
            dao.haveUnpinned()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Secure clipboard haveUnpinned failed: ${e.javaClass.simpleName}")
            false
        }
    }

    override suspend fun getRow(id: Int): SecureClipboardEntry? = withContext(Dispatchers.IO) {
        try {
            dao.getActive(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Secure clipboard getRow failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /* =================== capture =================== */

    override suspend fun save(request: CaptureRequest): SaveResult = withContext(Dispatchers.IO) {
        val startedAt = clock()
        SecureClipboardPolicy.validate(request)?.let { return@withContext SaveResult.Failure(it) }

        val encryptionKeyResult = keyManager.encryptionKey()
        keyFailure(encryptionKeyResult)?.let { return@withContext it }
        val encryptionKey = (encryptionKeyResult as ClipboardKeyResult.Ready).key

        val dedupKeyResult = keyManager.dedupKey()
        keyFailure(dedupKeyResult)?.let { return@withContext it }
        val dedupKey = (dedupKeyResult as ClipboardKeyResult.Ready).key

        try {
            saveMutex.withLock {
                val plaintext = request.text.toByteArray(Charsets.UTF_8)
                val dedupTag = SecureClipboardPolicy.dedupTag(dedupKey, plaintext)
                val existing = dao.findByDedupTag(dedupTag)
                if (existing != null) {
                    // Same content is already stored: refresh its position instead of storing a
                    // second ciphertext (the plaintext is not kept, so re-encryption is pointless).
                    dao.updateCreatedAt(existing.id, clock())
                    return@withLock SaveResult.Success(existing.id, elapsed(startedAt))
                }

                val createdAt = if (request.timestamp > 0L) request.timestamp else clock()
                val entry = buildEntry(
                    key = encryptionKey,
                    plaintext = plaintext,
                    textLength = request.text.length,
                    sourceType = request.source.storageValue,
                    createdAt = createdAt,
                    dedupTag = dedupTag,
                    sensitive = request.sensitive,
                    sourceApp = request.sourceApp,
                    readOnly = request.readOnly,
                )
                val id = database.withTransaction {
                    val rowId = dao.insert(entry).toInt()
                    enforceLimitInternal(
                        historyLimitProvider().coerceIn(0, SecureClipboardLimits.MAX_HISTORY_LIMIT)
                    )
                    rowId
                }
                SaveResult.Success(id, elapsed(startedAt))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClipboardCryptoException) {
            SaveResult.Failure(SecureClipboardError.EncryptionFailed, e)
        } catch (e: Exception) {
            Timber.w("Secure clipboard save failed: ${e.javaClass.simpleName}")
            SaveResult.Failure(SecureClipboardError.StorageFailed, e)
        }
    }

    /* =================== reading =================== */

    override suspend fun read(id: Int): SecureClipboardRecord? = withContext(Dispatchers.IO) {
        try {
            val entry = dao.getActive(id) ?: return@withContext null
            val encryptionKey = encryptionKeyOrNull() ?: return@withContext null
            decryptEntry(encryptionKey, entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Authentication failure, missing/invalidated key or unreadable row.
            null
        }
    }

    /* =================== privacy history / search / import =================== */

    override suspend fun recentRows(limit: Int): List<SecureClipboardEntry> =
        withContext(Dispatchers.IO) {
            val bounded = limit.coerceIn(0, SecureClipboardLimits.MAX_HISTORY_LIMIT)
            if (bounded == 0) return@withContext emptyList()
            try {
                // Raw ciphertext rows only: the history list is rendered masked from metadata.
                dao.recentActiveRows(bounded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Secure clipboard recentRows failed: ${e.javaClass.simpleName}")
                emptyList()
            }
        }

    override suspend fun readAllRecords(
        onProgress: ((done: Int, total: Int) -> Unit)?
    ): List<SecureClipboardRecord> = withContext(Dispatchers.IO) {
        try {
            // At most MAX_HISTORY_LIMIT (5000) rows are ever kept, so the scan is bounded.
            val rows = dao.recentActiveRows(SecureClipboardLimits.MAX_HISTORY_LIMIT)
            val total = rows.size
            onProgress?.invoke(0, total)
            if (total == 0) return@withContext emptyList()
            val encryptionKey = encryptionKeyOrNull() ?: return@withContext emptyList()
            val records = ArrayList<SecureClipboardRecord>(total)
            for (index in rows.indices) {
                try {
                    records.add(decryptEntry(encryptionKey, rows[index]))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Skip a single unreadable row instead of aborting a user-initiated export.
                }
                onProgress?.invoke(index + 1, total)
                if (index % SCAN_YIELD_INTERVAL == SCAN_YIELD_INTERVAL - 1) yield()
            }
            records
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Secure clipboard readAllRecords failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    override suspend fun search(query: String, limit: Int): List<SecureClipboardRecord> =
        withContext(Dispatchers.IO) {
            val needle = query.trim()
            val bounded = limit.coerceIn(0, SecureClipboardLimits.MAX_HISTORY_LIMIT)
            if (needle.isEmpty() || bounded == 0) return@withContext emptyList()
            try {
                val encryptionKey = encryptionKeyOrNull() ?: return@withContext emptyList()
                // Decrypt newest-first and stop as soon as enough matches were collected.
                val rows = dao.recentActiveRows(SecureClipboardLimits.MAX_HISTORY_LIMIT)
                val matches = ArrayList<SecureClipboardRecord>(minOf(bounded, 64))
                for (index in rows.indices) {
                    if (matches.size >= bounded) break
                    val record = try {
                        decryptEntry(encryptionKey, rows[index])
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null // unreadable row: skip it, never abort the search
                    }
                    if (record != null && record.text.contains(needle, ignoreCase = true)) {
                        matches.add(record)
                    }
                    if (index % SCAN_YIELD_INTERVAL == SCAN_YIELD_INTERVAL - 1) yield()
                }
                matches
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Secure clipboard search failed: ${e.javaClass.simpleName}")
                emptyList()
            }
        }

    override suspend fun importRecords(records: List<SecureClipboardRecord>): ImportResult =
        withContext(Dispatchers.IO) {
            if (records.isEmpty()) {
                return@withContext ImportResult(imported = 0, skipped = 0, failed = 0)
            }

            val encryptionKeyResult = keyManager.encryptionKey()
            if (keyFailure(encryptionKeyResult) != null) {
                return@withContext ImportResult(imported = 0, skipped = 0, failed = records.size)
            }
            val encryptionKey = (encryptionKeyResult as ClipboardKeyResult.Ready).key
            val dedupKeyResult = keyManager.dedupKey()
            if (keyFailure(dedupKeyResult) != null) {
                return@withContext ImportResult(imported = 0, skipped = 0, failed = records.size)
            }
            val dedupKey = (dedupKeyResult as ClipboardKeyResult.Ready).key

            var imported = 0
            var skipped = 0
            var failed = 0

            fun accountUnprocessed() {
                failed += (records.size - imported - skipped - failed).coerceAtLeast(0)
            }

            try {
                saveMutex.withLock {
                    for (chunk in records.chunked(IMPORT_BATCH_SIZE)) {
                        // Encrypt outside the transaction; the transaction only re-checks dedup
                        // and inserts, so no plaintext is ever visible to the database layer.
                        val prepared = ArrayList<Pair<String, SecureClipboardEntry>>(chunk.size)
                        for (record in chunk) {
                            try {
                                // Same validation as a normal capture. Imported records are never
                                // stored as sensitive: the flag is not part of the export format
                                // and the user explicitly asked to import the file.
                                if (SecureClipboardPolicy.validateText(record.text) != null) {
                                    failed++
                                    continue
                                }
                                val plaintext = record.text.toByteArray(Charsets.UTF_8)
                                val dedupTag = SecureClipboardPolicy.dedupTag(dedupKey, plaintext)
                                val entry = buildEntry(
                                    key = encryptionKey,
                                    plaintext = plaintext,
                                    textLength = record.text.length,
                                    sourceType = record.source.storageValue,
                                    createdAt = if (record.timestamp > 0L) record.timestamp else clock(),
                                    dedupTag = dedupTag,
                                    sensitive = false,
                                    sourceApp = null,
                                    readOnly = false,
                                ).copy(isPinned = record.pinned)
                                prepared.add(dedupTag to entry)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: ClipboardCryptoException) {
                                failed++
                            } catch (e: Exception) {
                                failed++
                            }
                        }
                        if (prepared.isEmpty()) continue
                        try {
                            val batch = database.withTransaction {
                                var batchImported = 0
                                var batchSkipped = 0
                                for ((dedupTag, entry) in prepared) {
                                    if (dao.findByDedupTag(dedupTag) != null) {
                                        // Keyed dedup tag already present: same content, skip.
                                        batchSkipped++
                                    } else {
                                        dao.insert(entry)
                                        batchImported++
                                    }
                                }
                                batchImported to batchSkipped
                            }
                            imported += batch.first
                            skipped += batch.second
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Transaction rolled back: every prepared row of the batch failed.
                            failed += prepared.size
                        }
                    }
                    // Enforce the configured history limit exactly once after all batches.
                    try {
                        database.withTransaction {
                            enforceLimitInternal(
                                historyLimitProvider().coerceIn(0, SecureClipboardLimits.MAX_HISTORY_LIMIT)
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w("Secure clipboard import limit failed: ${e.javaClass.simpleName}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Secure clipboard import failed: ${e.javaClass.simpleName}")
                accountUnprocessed()
                return@withContext ImportResult(imported = imported, skipped = skipped, failed = failed)
            }
            // Defensive: every input row must be imported, skipped or failed.
            accountUnprocessed()
            ImportResult(imported = imported, skipped = skipped, failed = failed)
        }

    /* =================== pin / edit / delete =================== */

    override suspend fun pin(id: Int, pinned: Boolean) = runStorageOperation("pin") {
        dao.updatePinStatus(id, pinned)
    }

    override suspend fun replaceText(id: Int, request: CaptureRequest): SaveResult =
        withContext(Dispatchers.IO) {
            val startedAt = clock()
            SecureClipboardPolicy.validate(request)?.let { return@withContext SaveResult.Failure(it) }

            val encryptionKeyResult = keyManager.encryptionKey()
            keyFailure(encryptionKeyResult)?.let { return@withContext it }
            val encryptionKey = (encryptionKeyResult as ClipboardKeyResult.Ready).key

            val dedupKeyResult = keyManager.dedupKey()
            keyFailure(dedupKeyResult)?.let { return@withContext it }
            val dedupKey = (dedupKeyResult as ClipboardKeyResult.Ready).key

            try {
                saveMutex.withLock {
                    val existing = dao.getActive(id)
                        ?: return@withLock SaveResult.Failure(SecureClipboardError.StorageFailed)
                    val plaintext = request.text.toByteArray(Charsets.UTF_8)
                    val dedupTag = SecureClipboardPolicy.dedupTag(dedupKey, plaintext)
                    // Editing the content must not reclassify the capture source nor reorder the
                    // history, so the original sourceType / createdAt are preserved.
                    val entry = buildEntry(
                        key = encryptionKey,
                        plaintext = plaintext,
                        textLength = request.text.length,
                        sourceType = existing.sourceType,
                        createdAt = existing.createdAt,
                        dedupTag = dedupTag,
                        sensitive = request.sensitive,
                        sourceApp = request.sourceApp,
                        readOnly = request.readOnly,
                    )
                    val updated = dao.updateEncryptedContent(
                        id = id,
                        ciphertext = entry.ciphertext,
                        iv = entry.iv,
                        encryptedMetadata = entry.encryptedMetadata,
                        keyVersion = entry.keyVersion,
                        sourceType = entry.sourceType,
                        dedupTag = entry.dedupTag,
                    )
                    // The old ciphertext was only replaced when the UPDATE matched a live row.
                    if (updated <= 0) {
                        SaveResult.Failure(SecureClipboardError.StorageFailed)
                    } else {
                        SaveResult.Success(id, elapsed(startedAt))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClipboardCryptoException) {
                SaveResult.Failure(SecureClipboardError.EncryptionFailed, e)
            } catch (e: Exception) {
                Timber.w("Secure clipboard replaceText failed: ${e.javaClass.simpleName}")
                SaveResult.Failure(SecureClipboardError.StorageFailed, e)
            }
        }

    override suspend fun delete(id: Int) = runStorageOperation("delete") {
        dao.markAsDeleted(id)
    }

    override suspend fun deleteAll(skipPinned: Boolean): IntArray = withContext(Dispatchers.IO) {
        try {
            val ids = if (skipPinned) dao.findUnpinnedIds() else dao.findActiveIds()
            if (ids.isNotEmpty()) dao.markAsDeleted(*ids)
            ids
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Secure clipboard deleteAll failed: ${e.javaClass.simpleName}")
            IntArray(0)
        }
    }

    override suspend fun undoDelete(vararg ids: Int) = runStorageOperation("undoDelete") {
        if (ids.isNotEmpty()) dao.undoDelete(*ids)
    }

    override suspend fun realDelete() = runStorageOperation("realDelete") {
        dao.realDelete()
        vacuum()
    }

    override suspend fun enforceLimit(limit: Int) = withContext(Dispatchers.IO) {
        try {
            database.withTransaction {
                enforceLimitInternal(limit.coerceIn(0, SecureClipboardLimits.MAX_HISTORY_LIMIT))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Secure clipboard enforceLimit failed: ${e.javaClass.simpleName}")
        }
    }

    /* =================== migration =================== */

    override suspend fun migrateLegacy(rows: List<LegacyClipboardRow>): MigrationResult =
        withContext(Dispatchers.IO) {
            try {
                migration.migrate(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Secure clipboard migration failed: ${e.javaClass.simpleName}")
                MigrationResult(total = rows.size, migrated = 0, failed = rows.size)
            }
        }

    override suspend fun clearAll() = runStorageOperation("clearAll") {
        database.withTransaction { dao.clearAll() }
        vacuum()
    }

    /** Best-effort page compaction so deleted ciphertext does not stay in free pages. */
    private fun vacuum() {
        runCatching {
            val db = database.openHelper.writableDatabase
            // execSQL() rejects row-returning PRAGMAs; they must run as queries.
            db.query("PRAGMA secure_delete=ON").use { it.moveToFirst() }
            db.execSQL("VACUUM")
        }.onFailure { Timber.w("Secure clipboard vacuum failed: ${it.javaClass.simpleName}") }
    }

    /* =================== status =================== */

    override suspend fun status(): StorageStatus = withContext(Dispatchers.IO) {
        val keyAvailable = try {
            keyManager.isEncryptionKeyAvailable()
        } catch (e: Exception) {
            false
        }
        var databaseAccessible = false
        var count = 0
        try {
            count = dao.itemCount()
            databaseAccessible = true
            initialized = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Secure clipboard status check failed: ${e.javaClass.simpleName}")
        }
        StorageStatus(
            initialized = initialized,
            keyAvailable = keyAvailable,
            keyVersion = ClipboardKeyManager.CURRENT_KEY_VERSION,
            databaseAccessible = databaseAccessible,
            itemCount = count,
        )
    }

    /* =================== internals =================== */

    private fun encryptionKeyOrNull(): SecretKey? =
        (keyManager.encryptionKey() as? ClipboardKeyResult.Ready)?.key

    private fun dedupKeyOrNull(): SecretKey? =
        (keyManager.dedupKey() as? ClipboardKeyResult.Ready)?.key

    private fun keyFailure(result: ClipboardKeyResult): SaveResult.Failure? =
        if (result is ClipboardKeyResult.Failure) {
            SaveResult.Failure(result.error, result.cause)
        } else {
            null
        }

    private fun elapsed(startedAt: Long): Long = (clock() - startedAt).coerceAtLeast(0L)

    /** Decrypt one stored row. Throws when the key is gone or the tag does not authenticate. */
    private fun decryptEntry(
        encryptionKey: SecretKey,
        entry: SecureClipboardEntry,
    ): SecureClipboardRecord {
        val plaintext = crypto.decrypt(
            encryptionKey,
            EncryptedPayload(entry.iv, entry.ciphertext),
            SecureClipboardAad.payload(entry.keyVersion, entry.sourceType),
        )
        return SecureClipboardRecord(
            id = entry.id,
            text = String(plaintext, Charsets.UTF_8),
            source = CaptureSource.fromStorageValue(entry.sourceType),
            timestamp = entry.createdAt,
            pinned = entry.isPinned,
        )
    }

    /**
     * Encrypt one record. The payload and the metadata are encrypted separately with independent
     * random IVs (the metadata IV is prefixed to its ciphertext), both authenticated with AAD
     * bound to the key version and the capture source.
     */
    private fun buildEntry(
        key: SecretKey,
        plaintext: ByteArray,
        textLength: Int,
        sourceType: String,
        createdAt: Long,
        dedupTag: String,
        sensitive: Boolean,
        sourceApp: String?,
        readOnly: Boolean,
    ): SecureClipboardEntry {
        val keyVersion = ClipboardKeyManager.CURRENT_KEY_VERSION
        val payload = crypto.encrypt(
            key,
            plaintext,
            SecureClipboardAad.payload(keyVersion, sourceType),
        )
        val metadataPlain = SecureClipboardMetadata.encode(
            length = textLength,
            sensitive = sensitive,
            sourceApp = sourceApp,
            readOnly = readOnly,
        )
        val metadataPayload = crypto.encrypt(
            key,
            metadataPlain,
            SecureClipboardAad.metadata(keyVersion, sourceType),
        )
        return SecureClipboardEntry(
            ciphertext = payload.ciphertext,
            iv = payload.iv,
            encryptedMetadata = SecureClipboardMetadata.packEncrypted(
                metadataPayload.iv,
                metadataPayload.ciphertext,
            ),
            keyVersion = keyVersion,
            createdAt = createdAt,
            sourceType = sourceType,
            isPinned = false,
            isDeleted = false,
            dedupTag = dedupTag,
        )
    }

    /** Soft-delete the oldest unpinned rows until at most [limit] unpinned rows remain. */
    private suspend fun enforceLimitInternal(limit: Int) {
        val unpinned = dao.getAllUnpinned()
        val excess = unpinned.size - limit
        if (excess <= 0) return
        val oldestIds = unpinned.take(excess).map { it.id }.toIntArray()
        if (oldestIds.isNotEmpty()) dao.markAsDeleted(*oldestIds)
    }

    /** Log-only error handling for operations whose contract returns `Unit`. */
    private suspend fun runStorageOperation(
        operation: String,
        block: suspend () -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Secure clipboard $operation failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        /** Rows per transaction when importing an export file. */
        private const val IMPORT_BATCH_SIZE = 50

        /** Yield to the dispatcher every N decrypted rows during bounded history scans. */
        private const val SCAN_YIELD_INTERVAL = 32
    }

    private inner class RoomMigrationStore : SecureClipboardMigrationStore {
        override suspend fun totalCount(): Int = dao.totalCount()

        override suspend fun findByDedupTag(dedupTag: String): SecureClipboardEntry? =
            dao.findByDedupTag(dedupTag)

        override suspend fun insert(entry: SecureClipboardEntry): Long = dao.insert(entry)

        override suspend fun <T> inTransaction(block: suspend () -> T): T =
            database.withTransaction { block() }
    }
}
