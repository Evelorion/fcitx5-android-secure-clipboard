/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.security

import android.app.AlertDialog
import android.content.Intent
import android.text.format.DateFormat
import androidx.annotation.Keep
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.secureclipboard.ClipboardProtectionState
import org.fcitx.fcitx5.android.data.secureclipboard.ClipboardSecurityStatus
import org.fcitx.fcitx5.android.data.secureclipboard.ClipboardSecurityStatusHolder
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardVault
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.toast

/**
 * "隐私与安全 → 安全剪贴板" settings page.
 *
 * Only non-sensitive state is displayed; plaintext clipboard content is never read here.
 */
class SecureClipboardSettingsFragment :
    ManagedPreferenceFragment(AppPrefs.getInstance().security) {

    private val security get() = AppPrefs.getInstance().security

    private lateinit var statusIme: Preference
    private lateinit var statusStorage: Preference
    private lateinit var statusKey: Preference
    private lateinit var statusCapture: Preference
    private lateinit var statusClear: Preference

    @Keep
    private val enableListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        if (enabled) showFirstEnableDialog()
        refreshStatus()
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = requireContext()
        statusIme = statusPreference(ctx, KEY_STATUS_IME, R.string.secure_clipboard_title_ime)
        statusStorage = statusPreference(ctx, KEY_STATUS_STORAGE, R.string.secure_clipboard_title_storage)
        statusKey = statusPreference(ctx, KEY_STATUS_KEY, R.string.secure_clipboard_title_key)
        statusCapture = statusPreference(ctx, KEY_STATUS_CAPTURE, R.string.secure_clipboard_title_capture)
        statusClear = statusPreference(ctx, KEY_STATUS_CLEAR, R.string.secure_clipboard_title_clear)
        screen.addPreference(statusIme)
        screen.addPreference(statusStorage)
        screen.addPreference(statusKey)
        screen.addPreference(statusCapture)
        screen.addPreference(statusClear)

        screen.addPreference(Preference(ctx).apply {
            key = KEY_HISTORY
            setTitle(R.string.privacy_history)
            setSummary(R.string.privacy_history_summary)
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), SecureHistoryActivity::class.java))
                true
            }
        })

        screen.addPreference(Preference(ctx).apply {
            key = KEY_MIGRATE
            setTitle(R.string.secure_clipboard_migrate)
            setSummary(R.string.secure_clipboard_migrate_summary)
            setOnPreferenceClickListener {
                lifecycleScope.launch {
                    val context = context ?: return@launch
                    if (!ClipboardManager.isSecureMode) {
                        context.toast(R.string.secure_clipboard_need_enable)
                        return@launch
                    }
                    val result = ClipboardManager.migrateLegacyIfNeeded(force = true)
                    when {
                        result == null -> context.toast(R.string.secure_clipboard_legacy_migrated_note)
                        result.success -> context.toast(
                            context.getString(R.string.secure_clipboard_migrate_success, result.migrated)
                        )
                        else -> context.toast(
                            context.getString(
                                R.string.secure_clipboard_migrate_failed,
                                result.failed.toString()
                            )
                        )
                    }
                }
                true
            }
        })

        screen.addPreference(Preference(ctx).apply {
            key = KEY_CLEAR_HISTORY
            setTitle(R.string.secure_clipboard_clear_history)
            setSummary(R.string.secure_clipboard_clear_history_summary)
            setOnPreferenceClickListener {
                lifecycleScope.launch {
                    val context = context ?: return@launch
                    if (!ClipboardManager.isSecureMode) {
                        context.toast(R.string.secure_clipboard_need_enable)
                        return@launch
                    }
                    ClipboardManager.deleteAll(skipPinned = true)
                    ClipboardManager.realDelete()
                    context.toast(R.string.secure_clipboard_clear_history_done)
                }
                true
            }
        })

        screen.addPreference(Preference(ctx).apply {
            key = KEY_NOTE
            isSelectable = false
            setTitle(R.string.secure_clipboard_note)
        })
    }

    private fun statusPreference(context: android.content.Context, key: String, title: Int) =
        Preference(context).apply {
            this.key = key
            isSelectable = false
            setTitle(title)
        }

    private fun showFirstEnableDialog() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.secure_clipboard)
            .setMessage(R.string.secure_clipboard_first_enable_note)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        security.secureClipboardEnabled.registerOnChangeListener(enableListener)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ClipboardSecurityStatusHolder.status.collect { refreshStatus(it) }
            }
        }
    }

    override fun onStop() {
        security.secureClipboardEnabled.unregisterOnChangeListener(enableListener)
        super.onStop()
    }

    private fun refreshStatus(status: ClipboardSecurityStatus = ClipboardSecurityStatus()) {
        if (!::statusIme.isInitialized) return
        val ctx = context ?: return
        val enabled = security.secureClipboardEnabled.getValue()
        val stateText = if (!enabled) {
            ctx.getString(R.string.secure_clipboard_status_disabled)
        } else {
            when (status.state) {
                ClipboardProtectionState.Protected -> ctx.getString(R.string.secure_clipboard_status_ok)
                ClipboardProtectionState.Degraded -> ctx.getString(R.string.secure_clipboard_status_failed)
                ClipboardProtectionState.Disabled -> ctx.getString(R.string.secure_clipboard_status_disabled)
                ClipboardProtectionState.Unknown -> ctx.getString(R.string.secure_clipboard_status_unknown)
            }
        }
        statusIme.summary = ctx.getString(
            if (InputMethodUtil.isSelected()) R.string.secure_clipboard_status_active
            else R.string.secure_clipboard_status_inactive
        )
        statusStorage.summary = stateText
        statusKey.summary = when {
            !enabled -> ctx.getString(R.string.secure_clipboard_status_disabled)
            SecureClipboardVault.isInitialized -> ctx.getString(R.string.secure_clipboard_status_ok)
            else -> ctx.getString(R.string.secure_clipboard_status_unknown)
        }
        statusCapture.summary = captureSummary(ctx, enabled, status)
        statusClear.summary = clearSummary(ctx, enabled, status)
    }

    private fun captureSummary(
        ctx: android.content.Context,
        enabled: Boolean,
        status: ClipboardSecurityStatus
    ): String = when {
        !enabled -> ctx.getString(R.string.secure_clipboard_status_disabled)
        status.lastCaptureSuccess == null -> ctx.getString(R.string.secure_clipboard_status_unknown)
        status.lastCaptureSuccess == false -> ctx.getString(
            R.string.secure_clipboard_status_failed
        ) + (status.lastError?.let { " (${it.name})" } ?: "")

        else -> ctx.getString(R.string.secure_clipboard_status_ok) + timeSuffix(ctx, status.lastCaptureTime)
    }

    private fun clearSummary(
        ctx: android.content.Context,
        enabled: Boolean,
        status: ClipboardSecurityStatus
    ): String = when {
        !enabled -> ctx.getString(R.string.secure_clipboard_status_disabled)
        status.lastClearSuccess == null -> ctx.getString(R.string.secure_clipboard_status_unknown)
        status.lastClearSuccess == false -> ctx.getString(R.string.secure_clipboard_status_failed)
        else -> ctx.getString(R.string.secure_clipboard_status_ok) + timeSuffix(ctx, status.lastClearTime)
    }

    private fun timeSuffix(ctx: android.content.Context, time: Long?): String =
        time?.let { " " + DateFormat.getTimeFormat(ctx).format(java.util.Date(it)) } ?: ""

    companion object {
        private const val KEY_STATUS_IME = "secure_clipboard_status_ime"
        private const val KEY_STATUS_STORAGE = "secure_clipboard_status_storage"
        private const val KEY_STATUS_KEY = "secure_clipboard_status_key"
        private const val KEY_STATUS_CAPTURE = "secure_clipboard_status_capture"
        private const val KEY_STATUS_CLEAR = "secure_clipboard_status_clear"
        private const val KEY_HISTORY = "secure_clipboard_privacy_history"
        private const val KEY_MIGRATE = "secure_clipboard_migrate_action"
        private const val KEY_CLEAR_HISTORY = "secure_clipboard_clear_history_action"
        private const val KEY_NOTE = "secure_clipboard_note"
    }
}
