package dev.herdr.mobile.ui

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** No-frills client: pinch-to-zoom font sizing, hardware-key passthrough, no logging. */
class TerminalViewClientImpl(
    private val view: TerminalView,
    initialPx: Int,
    private val bounds: FontBounds,
    private val mods: ModifierKeys,
    private val onFontSizeChanged: (Int) -> Unit,
) : TerminalViewClient {
    private var textSizePx = initialPx

    /** Apply a size without notifying the persist callback (used to seed a stored value). */
    fun applyFontSize(px: Int) {
        textSizePx = px
        view.setTextSize(px)
    }

    override fun onScale(scale: Float): Float {
        val next = steppedFontSize(textSizePx, scale, bounds) ?: return scale
        if (next != textSizePx) {
            textSizePx = next
            view.setTextSize(next)
            onFontSizeChanged(next)
        }
        return 1.0f // threshold crossed: reset the gesture accumulator
    }
    override fun onSingleTapUp(e: MotionEvent) {
        view.requestFocus()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = mods.readCtrl()
    override fun readAltKey(): Boolean = mods.readAlt()
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        mods.consumeOneShot()
        return false
    }
    override fun onEmulatorSet() {}
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
