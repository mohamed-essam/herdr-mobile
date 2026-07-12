package dev.herdr.mobile

import okhttp3.mockwebserver.MockWebServer

/**
 * Shut down a [MockWebServer] without letting teardown noise fail a green test.
 *
 * Under CI load an active WebSocket's reader task can miss MockWebServer's
 * internal shutdown deadline, making [MockWebServer.shutdown] throw when it
 * gives up waiting for its queue to drain. The thrown type is version-specific
 * (okhttp 4 throws `IOException`; okhttp 5 throws `AssertionError`), so this
 * best-effort teardown swallows any throwable — the test's assertions have
 * already run by the time teardown reaches here, and cleanup timing must never
 * red-flag a green run.
 */
fun MockWebServer.shutdownQuietly() {
    try {
        shutdown()
    } catch (_: Throwable) {
        // benign: reader task didn't drain within MockWebServer's shutdown timeout
    }
}
