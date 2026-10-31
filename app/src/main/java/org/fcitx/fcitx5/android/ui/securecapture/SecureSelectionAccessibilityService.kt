/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.securecapture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * Optional accessibility helper for apps whose text selection toolbar does not implement
 * ACTION_PROCESS_TEXT (for example some third-party app custom selection menus).
 *
 * When the user selects text in any app and the secure clipboard is enabled, a small floating
 * "save securely" button is shown. Tapping it opens the normal secure-capture confirmation
 * dialog; this service never writes the system clipboard and never logs the selected text.
 *
 * Disabled unless the user explicitly enables it in system accessibility settings.
 */
class SecureSelectionAccessibilityService : AccessibilityService() {

    private val security get() = AppPrefs.getInstance().security

    private var windowManager: WindowManager? = null
    private var overlay: TextView? = null
    private var pendingText: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return
        val node = event.source ?: run {
            hideOverlay()
            return
        }
        if (!security.secureClipboardEnabled.getValue() || node.isPassword) {
            hideOverlay()
            return
        }
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start < 0 || end < 0 || start == end) {
            hideOverlay()
            return
        }
        val full = node.text?.toString() ?: run {
            hideOverlay()
            return
        }
        val from = minOf(start, end)
        val to = maxOf(start, end)
        if (to > full.length) {
            hideOverlay()
            return
        }
        val selected = full.substring(from, to)
        if (selected.isBlank()) {
            hideOverlay()
            return
        }
        pendingText = selected
        showOverlay()
    }

    override fun onInterrupt() {
        hideOverlay()
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    private fun showOverlay() {
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        val view = overlay ?: TextView(this).apply {
            setText(R.string.secure_selection_overlay)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            val padH = (12 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_shield_24, 0, 0, 0)
            compoundDrawablePadding = (6 * density).toInt()
            background = GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(Color.parseColor("#2E7D32"))
            }
            elevation = 6 * density
            setOnClickListener { saveSelectedText() }
        }.also { overlay = it }
        if (view.parent != null) {
            view.visibility = View.VISIBLE
            return
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * density).toInt()
            y = (120 * density).toInt()
        }
        runCatching { wm.addView(view, lp) }.onFailure { overlay = null }
    }

    private fun hideOverlay() {
        val view = overlay ?: return
        overlay = null
        pendingText = null
        runCatching { windowManager?.removeView(view) }
    }

    private fun saveSelectedText() {
        val text = pendingText ?: return
        pendingText = null
        hideOverlay()
        val intent = Intent(this, SecureCaptureActivity::class.java).apply {
            action = SecureCaptureValidator.ACTION_PROCESS_TEXT
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        runCatching { startActivity(intent) }
    }
}
