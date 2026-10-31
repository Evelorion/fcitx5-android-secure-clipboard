/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database that only stores authenticated AES-256-GCM ciphertext.
 *
 * The database file (`secure_clbdb`) intentionally has no plaintext columns. The plaintext
 * legacy clipboard database (`clbdb`) stays untouched and is only migrated / wiped by the
 * caller after a verified migration.
 */
@Database(
    entities = [SecureClipboardEntry::class],
    version = 1,
    exportSchema = true
)
abstract class SecureClipboardDatabase : RoomDatabase() {

    abstract fun secureClipboardDao(): SecureClipboardDao

    companion object {
        const val DATABASE_NAME = "secure_clbdb"

        fun create(context: Context): SecureClipboardDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SecureClipboardDatabase::class.java,
                DATABASE_NAME
            )
                // Refuse to silently downgrade and corrupt newer ciphertext: wipe instead of crash.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
