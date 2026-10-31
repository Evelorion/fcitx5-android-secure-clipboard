/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import kotlinx.coroutines.CancellationException
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.ClipboardCrypto
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.SecureClipboardAad
import javax.crypto.SecretKey

/**
 * Storage operations required by [SecureClipboardMigration].
 *
 * Implemented on top of Room by [SecureClipboardRepositoryImpl]; unit tests provide an in-memory
 * fake so the migration can be tested without Android / Room.
 */
interface SecureClipboardMigrationStore {

    /** Number of stored rows, including soft-deleted ones (used for the write verification). */
    suspend fun totalCount(): Int

    /** Find an active row by its keyed de-duplication tag, for idempotent re-runs. */
    suspend fun findByDedupTag(dedupTag: String): SecureClipboardEntry?

    /** Insert an already-encrypted row. */
    suspend fun insert(entry: SecureClipboardEntry): Long

    /** Run [block] atomically; the implementation must roll back on failure. */
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/**
 * One-shot migration of the legacy *plaintext* clipboard history into the encrypted vault.
 *
 * Behaviour:
 *  - Rows are encrypted one by one and written in batches of [BATCH_SIZE] inside a database
 *    transaction. A failing batch is rolled back and counted as failed, the migration continues.
 *  - A row that fails to encrypt (provider error, ...) is counted as failed and does not abort
 *    the whole migration.
 *  - Re-running the migration after a partial failure is idempotent: rows already present
 *    (matched by their keyed dedup tag) are skipped and counted as migrated.
 *  - After writing, the actual row count delta is verified against the number of inserts; lost
 *    writes are reported as failures so the caller never wipes the legacy table on a short
 *    migration.
 *  - The returned [MigrationResult] always satisfies `migrated + failed == total`.
 */
class SecureClipboardMigration(
    private val crypto: ClipboardCrypto,
    private val store: SecureClipboardMigrationStore,
    private val encryptionKeyProvider: () -> SecretKey?,
    private val dedupKeyProvider: () -> SecretKey? = { null },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val keyVersion: Int = 1,
) {

    companion object {
        /** Number of rows written per transaction. */
        const val BATCH_SIZE = 50
    }

    /**
     * Encrypt all [rows] and write them into the secure store.
     *
     * Never throws for individual row / batch failures. A cancellation is rethrown so cooperative
     * cancellation keeps working.
     */
    suspend fun migrate(rows: List<LegacyClipboardRow>): MigrationResult {
        val total = rows.size
        if (total == 0) return MigrationResult(total = 0, migrated = 0, failed = 0)

        val encryptionKey = encryptionKeyProvider()
            ?: return MigrationResult(total = total, migrated = 0, failed = total)

        val dedupKey = dedupKeyProvider()
        val sourceType = CaptureSource.SystemClipboard.storageValue

        val countBefore = try {
            store.totalCount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return MigrationResult(total = total, migrated = 0, failed = total)
        }

        var inserted = 0
        var alreadyPresent = 0
        val pending = ArrayList<SecureClipboardEntry>(BATCH_SIZE)

        suspend fun flush() {
            if (pending.isEmpty()) return
            inserted += flushBatch(pending)
            pending.clear()
        }

        for (row in rows) {
            try {
                val plaintext = row.text.toByteArray(Charsets.UTF_8)
                val dedupTag = if (dedupKey != null) {
                    SecureClipboardPolicy.dedupTag(dedupKey, plaintext)
                } else {
                    ""
                }
                if (dedupTag.isNotEmpty() && store.findByDedupTag(dedupTag) != null) {
                    // Already migrated by a previous (partial) run: keep the migration idempotent.
                    alreadyPresent++
                    continue
                }
                pending.add(encryptRow(encryptionKey, row, plaintext, dedupTag, sourceType))
                if (pending.size >= BATCH_SIZE) flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Row-level encryption failure: the row is not added, failed accounting below.
            }
        }
        flush()

        val countAfter = try {
            store.totalCount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            -1
        }
        // Verify that the inserts really became visible; count mismatches are failures.
        val persistedNew = if (countAfter < 0) {
            inserted
        } else {
            (countAfter - countBefore).coerceIn(0, inserted)
        }
        val migrated = (persistedNew + alreadyPresent).coerceIn(0, total)
        return MigrationResult(total = total, migrated = migrated, failed = total - migrated)
    }

    /** Write one batch inside a transaction; returns the number of rows actually committed. */
    private suspend fun flushBatch(batch: List<SecureClipboardEntry>): Int = try {
        store.inTransaction {
            for (entry in batch) store.insert(entry)
        }
        batch.size
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Transaction rolled back: every row of the batch counts as failed.
        0
    }

    private fun encryptRow(
        encryptionKey: SecretKey,
        row: LegacyClipboardRow,
        plaintext: ByteArray,
        dedupTag: String,
        sourceType: String,
    ): SecureClipboardEntry {
        val payload = crypto.encrypt(
            encryptionKey,
            plaintext,
            SecureClipboardAad.payload(keyVersion, sourceType),
        )
        val metadataPlain = SecureClipboardMetadata.encode(
            length = row.text.length,
            sensitive = row.sensitive,
            sourceApp = null,
            readOnly = false,
        )
        val metadataPayload = crypto.encrypt(
            encryptionKey,
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
            createdAt = if (row.timestamp > 0L) row.timestamp else clock(),
            sourceType = sourceType,
            isPinned = row.pinned,
            isDeleted = false,
            dedupTag = dedupTag,
        )
    }
}
