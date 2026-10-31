/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * DAO of the encrypted clipboard database. Every query works on ciphertext only; only
 * [SecureClipboardRepositoryImpl] decrypts a single row on demand.
 */
@Dao
interface SecureClipboardDao {

    @Insert
    suspend fun insert(entry: SecureClipboardEntry): Long

    @Query("SELECT * FROM secure_clipboard WHERE id = :id AND isDeleted = 0 LIMIT 1")
    suspend fun getActive(id: Int): SecureClipboardEntry?

    @Query("SELECT * FROM secure_clipboard WHERE dedupTag = :dedupTag AND isDeleted = 0 ORDER BY id DESC LIMIT 1")
    suspend fun findByDedupTag(dedupTag: String): SecureClipboardEntry?

    @Query("SELECT COUNT(*) FROM secure_clipboard WHERE isDeleted = 0")
    suspend fun itemCount(): Int

    /** Row count including soft-deleted rows, used by the migration verification. */
    @Query("SELECT COUNT(*) FROM secure_clipboard")
    suspend fun totalCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM secure_clipboard WHERE isPinned = 0 AND isDeleted = 0)")
    suspend fun haveUnpinned(): Boolean

    /** Raw encrypted rows for the clipboard panel; never decrypts. */
    @Query("SELECT * FROM secure_clipboard WHERE isDeleted = 0 ORDER BY isPinned DESC, createdAt DESC, id DESC")
    fun allRows(): PagingSource<Int, SecureClipboardEntry>

    @Query("SELECT * FROM secure_clipboard WHERE isDeleted = 0 AND isPinned = 0 ORDER BY createdAt ASC, id ASC")
    suspend fun getAllUnpinned(): List<SecureClipboardEntry>

    @Query("SELECT id FROM secure_clipboard WHERE isDeleted = 0 AND isPinned = 0")
    suspend fun findUnpinnedIds(): IntArray

    @Query("SELECT id FROM secure_clipboard WHERE isDeleted = 0")
    suspend fun findActiveIds(): IntArray

    /** Newest-first active rows without decrypting (privacy history list / search scans). */
    @Query("SELECT * FROM secure_clipboard WHERE isDeleted = 0 ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recentActiveRows(limit: Int): List<SecureClipboardEntry>

    @Query("UPDATE secure_clipboard SET isPinned = :pinned WHERE id = :id")
    suspend fun updatePinStatus(id: Int, pinned: Boolean)

    @Query("UPDATE secure_clipboard SET createdAt = :createdAt WHERE id = :id")
    suspend fun updateCreatedAt(id: Int, createdAt: Long)

    @Query(
        "UPDATE secure_clipboard SET ciphertext = :ciphertext, iv = :iv, " +
                "encryptedMetadata = :encryptedMetadata, keyVersion = :keyVersion, " +
                "sourceType = :sourceType, dedupTag = :dedupTag " +
                "WHERE id = :id AND isDeleted = 0"
    )
    suspend fun updateEncryptedContent(
        id: Int,
        ciphertext: ByteArray,
        iv: ByteArray,
        encryptedMetadata: ByteArray,
        keyVersion: Int,
        sourceType: String,
        dedupTag: String,
    ): Int

    @Query("UPDATE secure_clipboard SET isDeleted = 1 WHERE id IN (:ids)")
    suspend fun markAsDeleted(vararg ids: Int)

    @Query("UPDATE secure_clipboard SET isDeleted = 0 WHERE id IN (:ids)")
    suspend fun undoDelete(vararg ids: Int)

    @Query("DELETE FROM secure_clipboard WHERE isDeleted = 1")
    suspend fun realDelete()

    @Query("DELETE FROM secure_clipboard")
    suspend fun clearAll()
}
