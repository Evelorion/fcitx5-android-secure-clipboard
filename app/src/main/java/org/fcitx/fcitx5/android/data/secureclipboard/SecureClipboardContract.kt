/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared contract of the "privacy-first dual-channel secure clipboard" feature.
 *
 * This file is the single source of truth for data models and interfaces shared by:
 *  - the crypto / storage layer (SecureClipboardRepositoryImpl)
 *  - the two capture channels (ACTION_PROCESS_TEXT / ACTION_SEND / IME selected text,
 *    and the system-clipboard listener)
 *  - the paste path and the clipboard panel UI
 *
 * Rules:
 *  - No plaintext clipboard content may be logged, persisted or written into SharedPreferences.
 *  - [SecureClipboardRecord.text] is a short-lived in-memory plaintext; callers must drop it
 *    as soon as it has been committed to the target editor.
 */

/** Which channel produced a capture. Stored as a stable string in the database. */
enum class CaptureSource(val storageValue: String) {
    /** ACTION_PROCESS_TEXT from the system text selection menu. */
    ProcessText("process_text"),

    /** Selected text read through InputConnection, triggered by the user tapping the shield button. */
    ImeSelection("ime_selection"),

    /** ACTION_SEND text/plain. */
    Share("share"),

    /** Plain "copy" captured from the Android system clipboard (channel B). */
    SystemClipboard("system_clipboard");

    companion object {
        fun fromStorageValue(value: String): CaptureSource =
            entries.firstOrNull { it.storageValue == value } ?: ProcessText
    }
}

/** A validated plaintext capture request, before encryption. */
data class CaptureRequest(
    val text: String,
    val source: CaptureSource,
    val sensitive: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    /** Package name of the originating app when it can be determined, else null. */
    val sourceApp: String? = null,
    /** ACTION_PROCESS_TEXT EXTRA_PROCESS_TEXT_READONLY hint. */
    val readOnly: Boolean = false,
)

sealed interface SaveResult {
    data class Success(val id: Int, val durationMs: Long) : SaveResult
    data class Failure(val error: SecureClipboardError, val cause: Throwable? = null) : SaveResult
}

/** Failure reasons. Values are stable and non-sensitive; they may be surfaced in the UI. */
enum class SecureClipboardError {
    Disabled,
    NotReady,
    KeyUnavailable,
    KeyInvalid,
    EncryptionFailed,
    StorageFailed,
    EmptyText,
    TooLargeText,
    UnsupportedFormat,
    SensitiveRejected,
    RateLimited,
    LegacyMigrationFailed,
}

/** Decrypted record. [text] must not be cached, logged or persisted anywhere. */
data class SecureClipboardRecord(
    val id: Int,
    val text: String,
    val source: CaptureSource,
    val timestamp: Long,
    val pinned: Boolean,
) {
    override fun toString(): String =
        "SecureClipboardRecord(id=$id, length=${text.length}, source=$source, pinned=$pinned)"
}

/** Non-sensitive state of the encrypted storage, for the settings / status UI. */
data class StorageStatus(
    val initialized: Boolean = false,
    val keyAvailable: Boolean = false,
    val keyVersion: Int = 1,
    val databaseAccessible: Boolean = false,
    val itemCount: Int = 0,
)

/** A row read from the legacy plaintext clipboard database, used only by the migration. */
data class LegacyClipboardRow(
    val id: Int,
    val text: String,
    val pinned: Boolean,
    val timestamp: Long,
    val type: String,
    val sensitive: Boolean,
)

data class MigrationResult(
    val total: Int,
    val migrated: Int,
    val failed: Int,
) {
    val success: Boolean get() = failed == 0 && migrated == total
}

/** Result of importing an encrypted export file. No plaintext is kept by the caller. */
data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
) {
    val success: Boolean get() = failed == 0
}

/**
 * Room entity holding only authenticated ciphertext.
 *
 * [ciphertext] is AES-256-GCM output (includes the 128-bit tag), [iv] is a fresh 96-bit IV,
 * [encryptedMetadata] is a separately encrypted small blob (length / source app / flags) so the
 * list can be rendered without decrypting history in bulk.
 */
@Entity(tableName = "secure_clipboard")
data class SecureClipboardEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val ciphertext: ByteArray,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val iv: ByteArray,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val encryptedMetadata: ByteArray,
    val keyVersion: Int,
    val createdAt: Long,
    val sourceType: String,
    @ColumnInfo(defaultValue = "0")
    val isPinned: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val isDeleted: Boolean = false,
    /** Keyed HMAC-SHA256 of the plaintext, hex encoded. Used for de-duplication only. */
    @ColumnInfo(defaultValue = "")
    val dedupTag: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecureClipboardEntry) return false
        return id == other.id &&
                ciphertext.contentEquals(other.ciphertext) &&
                iv.contentEquals(other.iv) &&
                encryptedMetadata.contentEquals(other.encryptedMetadata) &&
                keyVersion == other.keyVersion &&
                createdAt == other.createdAt &&
                sourceType == other.sourceType &&
                isPinned == other.isPinned &&
                isDeleted == other.isDeleted &&
                dedupTag == other.dedupTag
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + encryptedMetadata.contentHashCode()
        result = 31 * result + keyVersion
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + sourceType.hashCode()
        result = 31 * result + isPinned.hashCode()
        result = 31 * result + isDeleted.hashCode()
        result = 31 * result + dedupTag.hashCode()
        return result
    }
}

/** Limits shared by every capture channel. Enforced again by the repository. */
object SecureClipboardLimits {
    /** Maximum accepted plaintext length in UTF-16 code units. */
    const val MAX_TEXT_LENGTH = 100_000

    /** Maximum accepted plaintext size in UTF-8 bytes (defence in depth for very wide code points). */
    const val MAX_TEXT_UTF8_BYTES = 400_000

    /** Default number of encrypted entries kept. */
    const val DEFAULT_HISTORY_LIMIT = 1000

    const val MAX_HISTORY_LIMIT = 5000

    /** Simple per-source rate limit for externally reachable capture entry points. */
    const val RATE_LIMIT_WINDOW_MS = 2_000L
    const val MAX_CAPTURES_PER_WINDOW = 3
}

/** Repository implemented by the encrypted storage layer. */
interface SecureClipboardRepository {
    /** Raw encrypted rows for the clipboard panel (paging without decrypting). */
    fun allRows(): PagingSource<Int, SecureClipboardEntry>

    suspend fun itemCount(): Int

    suspend fun haveUnpinned(): Boolean

    /** Validate, encrypt and persist a capture. Never stores plaintext. */
    suspend fun save(request: CaptureRequest): SaveResult

    /** Raw encrypted row without decrypting (used for display metadata only). */
    suspend fun getRow(id: Int): SecureClipboardEntry?

    /** Decrypt exactly one record; returns null when the key is gone or authentication fails. */
    suspend fun read(id: Int): SecureClipboardRecord?

    suspend fun pin(id: Int, pinned: Boolean)

    /** Re-encrypt a record after the user edited it in the clipboard edit activity. */
    suspend fun replaceText(id: Int, request: CaptureRequest): SaveResult

    suspend fun delete(id: Int)

    suspend fun deleteAll(skipPinned: Boolean): IntArray

    suspend fun undoDelete(vararg ids: Int)

    suspend fun realDelete()

    suspend fun enforceLimit(limit: Int)

    /** Encrypt every row of the legacy plaintext database, then let the caller wipe it. */
    suspend fun migrateLegacy(rows: List<LegacyClipboardRow>): MigrationResult

    suspend fun clearAll()

    /**
     * Most recent active rows without decrypting, for the privacy history list.
     * Newest first, bounded by [limit] (history limit is 1000 by default).
     */
    suspend fun recentRows(limit: Int = 500): List<SecureClipboardEntry>

    /**
     * Decrypt every active record, one at a time, for a **user-initiated** export or search.
     * Unreadable records (key lost / corrupted) are skipped rather than aborting the whole scan.
     * Callers must show progress, wipe the returned list and never log the plaintext.
     */
    suspend fun readAllRecords(
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): List<SecureClipboardRecord>

    /**
     * User-initiated search in the encrypted history.
     *
     * Records are decrypted one by one (bounded by [limit]) and matched case-insensitively.
     * The returned list is plaintext and must only be used for the immediate UI response.
     */
    suspend fun search(query: String, limit: Int = 200): List<SecureClipboardRecord>

    /**
     * Re-encrypt and insert records read from an authenticated export file.
     * Duplicates are skipped using the keyed de-duplication tag.
     */
    suspend fun importRecords(records: List<SecureClipboardRecord>): ImportResult

    suspend fun status(): StorageStatus
}

/** Overall protection state of the secure clipboard (non-sensitive, safe to display). */
enum class ClipboardProtectionState {
    Unknown,
    Disabled,
    Protected,
    Degraded,
}

/** Non-sensitive protection status. Must never contain plaintext clipboard content. */
data class ClipboardSecurityStatus(
    val state: ClipboardProtectionState = ClipboardProtectionState.Unknown,
    val lastCaptureTime: Long? = null,
    val lastCaptureSource: CaptureSource? = null,
    val lastCaptureSuccess: Boolean? = null,
    val lastClearTime: Long? = null,
    val lastClearSuccess: Boolean? = null,
    val lastError: SecureClipboardError? = null,
    val lastErrorTime: Long? = null,
    val captureCount: Long = 0,
    val clearCount: Long = 0,
    val staleSkipCount: Long = 0,
    val clearFailedCount: Long = 0,
)

/** In-process status holder shared by the capture listener, settings UI and clipboard panel. */
object ClipboardSecurityStatusHolder {
    private val _status = MutableStateFlow(ClipboardSecurityStatus())
    val status: StateFlow<ClipboardSecurityStatus> = _status.asStateFlow()

    fun update(block: (ClipboardSecurityStatus) -> ClipboardSecurityStatus) {
        _status.value = block(_status.value)
    }

    fun reset() {
        _status.value = ClipboardSecurityStatus()
    }
}
