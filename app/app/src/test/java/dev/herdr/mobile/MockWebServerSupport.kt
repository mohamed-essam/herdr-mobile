package dev.herdr.mobile

import okhttp3.mockwebserver.MockWebServer
import java.io.IOException

/**
 * Shut down a [MockWebServer] without letting teardown noise fail a green test.
 *
 * Under heavy CI load (many Android test jobs sharing a runner) an active
 * WebSocket's reader task can miss MockWebServer's internal shutdown deadline,
 * making [MockWebServer.shutdown] throw `IOException("Gave up waiting for queue
 * to shut down")`. That is resource-cleanup timing, not a test failure, so the
 * assertions have already run by the time teardown reaches here — swallow it.
 */
fun MockWebServer.shutdownQuietly() {
    try {
        shutdown()
    } catch (_: IOException) {
        // benign: reader task didn't drain within MockWebServer's shutdown timeout
    }
}
