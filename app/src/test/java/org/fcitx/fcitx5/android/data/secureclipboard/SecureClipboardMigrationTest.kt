/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.ClipboardCrypto
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.EncryptedPayload
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.SecureClipboardAad
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * JVM tests for [SecureClipboardMigration] using an in-memory fake store: no Android / Room
 * classes are involved.
 */
class SecureClipboardMigrationTest {

    private val crypto = ClipboardCrypto()
    private val encryptionKey: SecretKey = ClipboardCrypto.generateAesKey()
    private val dedupKey: SecretKey = SecretKeySpec(ByteArray(32) { (it * 31).toByte() }, "HmacSHA256")

    private fun row(
        id: Int,
        text: String,
        pinned: Boolean = false,
        timestamp: Long = 1_000L + id,
        sensitive: Boolean = false,
    ): LegacyClipboardRow = LegacyClipboardRow(
        id = id,
        text = text,
        pinned = pinned,
        timestamp = timestamp,
        type = "text/plain",
        sensitive = sensitive,
    )

    private fun migration(
        store: FakeStore,
        key: SecretKey? = encryptionKey,
    ): SecureClipboardMigration = SecureClipboardMigration(
        crypto = crypto,
        store = store,
        encryptionKeyProvider = { key },
        dedupKeyProvider = { dedupKey },
        clock = { 42L },
    )

    @Test
    fun emptyInputIsANoOp() {
        runBlocking {
            val store = FakeStore()
            val result = migration(store).migrate(emptyList())
            assertEquals(MigrationResult(0, 0, 0), result)
            assertTrue(result.success)
            assertEquals(0, store.entries.size)
            assertEquals(0, store.transactionCount)
        }
    }

    @Test
    fun allRowsAreMigratedAndDecryptable() {
        runBlocking {
            val store = FakeStore()
            val rows = listOf(
                row(id = 1, text = "hello world", pinned = true),
                row(id = 2, text = "你好，世界", sensitive = true),
                row(id = 3, text = "line1\nline2\ttabbed"),
            )
            val result = migration(store).migrate(rows)
            assertEquals(3, result.total)
            assertEquals(3, result.migrated)
            assertEquals(0, result.failed)
            assertTrue(result.success)
            assertEquals(3, store.entries.size)

            rows.forEachIndexed { index, expected ->
                val entry = store.entries[index]
                assertEquals(ClipboardCrypto.IV_SIZE_BYTES, entry.iv.size)
                assertEquals(
                    expected.text.toByteArray(Charsets.UTF_8).size + ClipboardCrypto.TAG_SIZE_BYTES,
                    entry.ciphertext.size
                )
                assertEquals(64, entry.dedupTag.length)
                assertTrue(entry.dedupTag.all { it in '0'..'9' || it in 'a'..'f' })
                assertEquals(1, entry.keyVersion)
                assertEquals(CaptureSource.SystemClipboard.storageValue, entry.sourceType)
                assertEquals(expected.pinned, entry.isPinned)
                assertEquals(expected.timestamp, entry.createdAt)
                assertFalse(entry.isDeleted)

                val plaintext = crypto.decrypt(
                    encryptionKey,
                    EncryptedPayload(entry.iv, entry.ciphertext),
                    SecureClipboardAad.payload(1, CaptureSource.SystemClipboard.storageValue),
                )
                assertArrayEquals(expected.text.toByteArray(Charsets.UTF_8), plaintext)

                val metadataBlobs = SecureClipboardMetadata.splitEncrypted(entry.encryptedMetadata)
                assertTrue(metadataBlobs != null)
                val (metadataIv, metadataCiphertext) = metadataBlobs!!
                val metadataPlain = crypto.decrypt(
                    encryptionKey,
                    EncryptedPayload(metadataIv, metadataCiphertext),
                    SecureClipboardAad.metadata(1, CaptureSource.SystemClipboard.storageValue),
                )
                val metadata = SecureClipboardMetadata.decode(metadataPlain)
                assertTrue(metadata != null)
                assertEquals(expected.text.length, metadata!!.length)
                assertEquals(expected.sensitive, metadata.sensitive)
            }
        }
    }

    @Test
    fun rowsAreWrittenInBatches() {
        runBlocking {
            val store = FakeStore()
            val rows = (1..120).map { row(id = it, text = "row-$it") }
            val result = migration(store).migrate(rows)
            assertEquals(120, result.total)
            assertEquals(120, result.migrated)
            assertEquals(0, result.failed)
            assertEquals(120, store.entries.size)
            // 50 + 50 + 20 -> three transactions.
            assertEquals(3, store.transactionCount)
        }
    }

    @Test
    fun failedBatchCountsAsFailedAndMigrationContinues() {
        runBlocking {
            val store = FakeStore().apply { failTransactionCall = 2 }
            val rows = (1..120).map { row(id = it, text = "row-$it") }
            val result = migration(store).migrate(rows)
            assertEquals(120, result.total)
            assertEquals(70, result.migrated)
            assertEquals(50, result.failed)
            assertFalse(result.success)
            assertEquals(70, store.entries.size)
        }
    }

    @Test
    fun lostWritesAreDetectedByCountVerification() {
        runBlocking {
            val store = FakeStore().apply { dropInsertAtIndex = 1 }
            val rows = (1..3).map { row(id = it, text = "row-$it") }
            val result = migration(store).migrate(rows)
            assertEquals(3, result.total)
            assertEquals(2, result.migrated)
            assertEquals(1, result.failed)
            assertEquals(2, store.entries.size)
        }
    }

    @Test
    fun migrationIsIdempotentWithDedupTag() {
        runBlocking {
            val store = FakeStore()
            val rows = listOf(row(id = 1, text = "alpha"), row(id = 2, text = "beta"))
            val first = migration(store).migrate(rows)
            assertEquals(2, first.migrated)
            assertEquals(2, store.entries.size)
            val transactionsAfterFirstRun = store.transactionCount

            val second = migration(store).migrate(rows)
            assertEquals(2, second.total)
            assertEquals(2, second.migrated)
            assertEquals(0, second.failed)
            assertTrue(second.success)
            assertEquals(2, store.entries.size)
            assertEquals(transactionsAfterFirstRun, store.transactionCount)
        }
    }

    @Test
    fun missingKeyFailsEverythingWithoutThrowing() {
        runBlocking {
            val store = FakeStore()
            val rows = (1..2).map { row(id = it, text = "row-$it") }
            val result = migration(store, key = null).migrate(rows)
            assertEquals(2, result.total)
            assertEquals(0, result.migrated)
            assertEquals(2, result.failed)
            assertFalse(result.success)
            assertTrue(store.entries.isEmpty())
        }
    }

    @Test
    fun zeroTimestampFallsBackToInjectedClock() {
        runBlocking {
            val store = FakeStore()
            migration(store).migrate(listOf(row(id = 1, text = "text", timestamp = 0L)))
            assertEquals(42L, store.entries.single().createdAt)
        }
    }

    @Test
    fun plaintextIsNotPresentInPersistedBlobs() {
        runBlocking {
            val store = FakeStore()
            val text = "unique-plaintext-marker-12345"
            migration(store).migrate(listOf(row(id = 1, text = text)))
            val entry = store.entries.single()
            assertFalse(String(entry.ciphertext, Charsets.ISO_8859_1).contains(text))
            assertFalse(String(entry.encryptedMetadata, Charsets.ISO_8859_1).contains(text))
            assertFalse(entry.dedupTag.contains(text))
        }
    }

    @Test
    fun resultCountsAlwaysAddUp() {
        runBlocking {
            val store = FakeStore().apply { dropInsertAtIndex = 0 }
            val rows = (1..7).map { row(id = it, text = "row-$it") }
            val result = migration(store).migrate(rows)
            assertEquals(result.total, result.migrated + result.failed)
        }
    }

    /** In-memory [SecureClipboardMigrationStore] with fault injection for partial-failure tests. */
    private class FakeStore : SecureClipboardMigrationStore {
        val entries = ArrayList<SecureClipboardEntry>()
        var transactionCount = 0

        /** 1-based transaction call that throws before executing (simulates a rolled back batch). */
        var failTransactionCall = -1

        /** Insert position that is silently not stored (simulates a lost write). */
        var dropInsertAtIndex = -1

        private var dropped = false

        override suspend fun totalCount(): Int = entries.size

        override suspend fun findByDedupTag(dedupTag: String): SecureClipboardEntry? =
            entries.firstOrNull { it.dedupTag == dedupTag && !it.isDeleted }

        override suspend fun insert(entry: SecureClipboardEntry): Long {
            if (!dropped && entries.size == dropInsertAtIndex) {
                dropped = true
                return 0L
            }
            entries.add(entry)
            return entries.size.toLong()
        }

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            transactionCount++
            if (transactionCount == failTransactionCall) {
                throw IllegalStateException("simulated transaction failure")
            }
            val snapshot = ArrayList(entries)
            return try {
                block()
            } catch (exception: Throwable) {
                entries.clear()
                entries.addAll(snapshot)
                throw exception
            }
        }
    }
}
