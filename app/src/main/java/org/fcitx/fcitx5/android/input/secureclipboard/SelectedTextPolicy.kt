/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.secureclipboard

import android.view.inputmethod.EditorInfo

/**
 * Decides whether the currently focused editor may offer the "save selected text" shield action.
 *
 * The decision is a pure function of [EditorInfo] fields so it can be unit tested on a plain JVM:
 * the [android.text.InputType] / [EditorInfo] constants are mirrored as local constants (their
 * values are part of the public Android API and stable), so no Android framework class is loaded
 * or called from the primitive [evaluate] overload.
 *
 * Policy:
 *  - Password-like fields (text/number password variations, visible password) never offer the
 *    action. A user can still paste a stored record explicitly; this only blocks capturing new
 *    secrets from such fields.
 *  - Fields marked with IME_FLAG_NO_PERSONALIZED_LEARNING are still allowed to capture because
 *    the user explicitly triggers the action, but the request is flagged sensitive so that the
 *    repository policy can reject it.
 *  - Clients without a text editor (TYPE_NULL) never offer the action.
 */
object SelectedTextPolicy {

    enum class DenialReason {
        None,

        /** The focused editor is a password-like field. */
        PasswordField,

        /** The focused editor does not accept text at all. */
        NullInputType,

        /** The client package is on the local block list (defensive hook / tests). */
        BlockedPackage,
    }

    /**
     * @param allowed whether the shield action may be offered at all.
     * @param sensitive whether the captured text must be marked sensitive in the request.
     */
    data class Decision(
        val allowed: Boolean,
        val sensitive: Boolean,
        val denial: DenialReason,
    )

    private const val TYPE_NULL = 0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    private const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010
    private const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x10000000

    /**
     * Evaluate the shield action policy for an [android.view.inputmethod.EditorInfo] snapshot.
     *
     * @param packageName package name of the client, null when unknown.
     * @param blockedPackages optional local block list, defaults to empty.
     */
    fun evaluate(
        inputType: Int,
        imeOptions: Int,
        packageName: String?,
        blockedPackages: Set<String> = emptySet(),
    ): Decision {
        if (packageName != null && packageName in blockedPackages) {
            return Decision(allowed = false, sensitive = false, denial = DenialReason.BlockedPackage)
        }
        val inputClass = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        if (inputClass == TYPE_NULL) {
            return Decision(allowed = false, sensitive = false, denial = DenialReason.NullInputType)
        }
        val passwordField = when (inputClass) {
            TYPE_CLASS_TEXT -> variation == TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

            TYPE_CLASS_NUMBER -> variation == TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        if (passwordField) {
            return Decision(allowed = false, sensitive = false, denial = DenialReason.PasswordField)
        }
        val sensitive = (imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        return Decision(allowed = true, sensitive = sensitive, denial = DenialReason.None)
    }

    /** Convenience overload for a possibly missing [EditorInfo]. */
    fun evaluate(info: EditorInfo?): Decision {
        if (info == null) {
            return Decision(allowed = false, sensitive = false, denial = DenialReason.NullInputType)
        }
        return evaluate(info.inputType, info.imeOptions, info.packageName?.toString())
    }
}
