package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;

/**
 * A {@link TerminalSession} with no local subprocess: input the view produces is
 * forwarded to {@link Io#sendInput} (→ websocket term_input) and bytes arriving
 * from the companion are fed via {@link #feed} into the emulator. Reuses the
 * whole Termux TerminalView unchanged.
 */
public class RemoteTerminalSession extends TerminalSession {

    public interface Io {
        void sendInput(byte[] data);
        void sendResize(int cols, int rows);
    }

    private final Io mIo;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    public RemoteTerminalSession(TerminalSessionClient client, Io io) {
        // shell/cwd/args/env are unused (no subprocess); transcriptRows default.
        super("/system/bin/sh", "/", new String[0], new String[0], 2000, client);
        this.mIo = io;
    }

    /** Create the emulator WITHOUT spawning a subprocess or IO threads. */
    @Override
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        mIo.sendResize(columns, rows);
    }

    /** The view calls this on layout/rotation; forward size changes upstream. */
    @Override
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            // our initializeEmulator override creates the emulator (no subprocess) and sends the initial resize
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            // resize the emulator directly — do NOT call super, whose else-branch calls
            // JNI.setPtyWindowSize (crashes: this remote build bundles no libtermux.so)
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            mIo.sendResize(columns, rows);
        }
    }

    @Override
    public void finishIfRunning() {
        // remote session: no local subprocess or file descriptor to tear down (avoid JNI.close)
    }

    /** All view-originated input (keys, codepoints, paste) routes here. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (data == null || count <= 0) return;
        mIo.sendInput(Arrays.copyOfRange(data, offset, offset + count));
    }

    /** Feed bytes received from the companion into the emulator (main thread). */
    public void feed(final byte[] data, final int len) {
        mMain.post(() -> {
            if (mEmulator != null) {
                mEmulator.append(data, len);
                notifyScreenUpdate();
            }
        });
    }
}
