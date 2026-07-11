package dev.herdr.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Sticky-modifier state for a bar modifier key. */
enum class ModState { OFF, ONE_SHOT, LOCKED }

/**
 * Single source of truth for the Ctrl/Alt sticky modifiers, shared by the key bar
 * (reads state to highlight, mutates on tap) and TerminalViewClientImpl (reads via
 * readControlKey/readAltKey; clears one-shots after a code point). Backed by Compose
 * snapshot state so the bar recomposes on change.
 */
class ModifierKeys {
    var ctrl by mutableStateOf(ModState.OFF)
    var alt by mutableStateOf(ModState.OFF)

    fun readCtrl(): Boolean = ctrl != ModState.OFF
    fun readAlt(): Boolean = alt != ModState.OFF

    /** Single tap: OFF -> ONE_SHOT; ONE_SHOT/LOCKED -> OFF. */
    private fun tapped(s: ModState): ModState = if (s == ModState.OFF) ModState.ONE_SHOT else ModState.OFF

    fun tapCtrl() { ctrl = tapped(ctrl) }
    fun tapAlt() { alt = tapped(alt) }
    fun lockCtrl() { ctrl = ModState.LOCKED }
    fun lockAlt() { alt = ModState.LOCKED }

    /** Clear modifiers armed for a single key; LOCKED persists. */
    fun consumeOneShot() {
        if (ctrl == ModState.ONE_SHOT) ctrl = ModState.OFF
        if (alt == ModState.ONE_SHOT) alt = ModState.OFF
    }
}

/** Non-printing keys the bar sends as literal byte sequences. */
enum class TermKey { ESC, TAB, UP, DOWN, LEFT, RIGHT, HOME, END, PGUP, PGDN }

private val ESC = byteArrayOf(0x1b)

/** Byte sequence for a [TermKey] (xterm normal-cursor-mode forms). */
fun bytesFor(key: TermKey): ByteArray = when (key) {
    TermKey.ESC -> ESC
    TermKey.TAB -> byteArrayOf(0x09)
    TermKey.UP -> ESC + "[A".toByteArray()
    TermKey.DOWN -> ESC + "[B".toByteArray()
    TermKey.RIGHT -> ESC + "[C".toByteArray()
    TermKey.LEFT -> ESC + "[D".toByteArray()
    TermKey.HOME -> ESC + "[H".toByteArray()
    TermKey.END -> ESC + "[F".toByteArray()
    TermKey.PGUP -> ESC + "[5~".toByteArray()
    TermKey.PGDN -> ESC + "[6~".toByteArray()
}
