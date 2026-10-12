# Server Switcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Save several herdr companions in the Android app, switch the single live connection between them from a dashboard header chip, and keep notifications arriving from every saved server.

**Architecture:** A `Server(id, name, url)` list replaces the single `companion_url` in the preferences DataStore (migrated once, the old server getting id `"default"` = UnifiedPush's default instance). `MainActivity` owns one `Session` tagged with its server id; switching closes it and opens another. Each server is its own UnifiedPush instance (instance name = server id), so endpoints, incoming pushes and notifications are all per server. The Go companion persists the endpoint it is given so a restart doesn't silence it.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), DataStore Preferences, kotlinx.serialization, UnifiedPush connector 3.3.3, JUnit 4; Go 1.x companion.

**Spec:** `docs/superpowers/specs/2026-10-11-herdr-mobile-server-switcher-design.md`

## Global Constraints

- No companion protocol change: the phone still sends `register_push` with one endpoint per connection.
- Companion endpoint file: `<state-dir>/push-endpoint.json`, body `{"endpoint":"..."}`, written via temp file + rename, mode `0600`, dir `0700`; no state dir → memory only.
- Migrated server id is exactly `"default"` (UnifiedPush `INSTANCE_DEFAULT`); new server ids are `UUID.randomUUID().toString()`.
- Pref keys: `servers` (JSON list), `active_server`, `push_endpoint:<id>`; `push_enabled`, `terminal_font_size`, `recent_agents` stay global and unchanged.
- A blank server name falls back to the server's `hostPort(url)`.
- Notification titles get `"<server name> · "` prefixed only when more than one server is saved.
- Only one live socket at a time.
- Android unit tests: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest`; Go tests: `cd companion && go test -race ./...` (as CI runs them).

## Review Focus

- **App upgraded but not reopened, then a push arrives** → the receiver runs the migration itself first, so the notification still shows (Task 2 migration test; Task 3 receiver calls `migrate()`).
- **A notification tapped for a server removed since** → the app opens on the active server, nothing crashes, nothing switches (Task 2 `resolveServerOpen` test).
- **Removing the active server, and removing the last one** → switches to the first remaining server, or lands on setup with the socket closed (Task 2 `withRemoved` tests; Task 5 manual check).
- **The same companion added twice in a different spelling** (`ws://Host:8787` vs `host`) → rejected as already saved (Task 2 `savedAt` test).
- **Switching servers while a pane, sheet or answer is open** → nothing from the old server leaks: the dashboard resets, and the new socket only ever receives its own server's endpoint (Task 5: `Session.serverId`, `key(session)`; manual check against companion logs).

---

## File Structure

- `companion/internal/engine/endpoint.go` (create): load/save the push endpoint file.
- `companion/internal/engine/endpoint_test.go` (create).
- `companion/internal/engine/engine.go` (modify): load on `New`, save on `setEndpoint`.
- `companion/cmd/herdr-mobiled/main.go` (modify): `--state-dir` help text.
- `app/app/src/main/java/dev/herdr/mobile/data/Servers.kt` (create): `Server` and pure list logic.
- `app/app/src/test/java/dev/herdr/mobile/ServersTest.kt` (create).
- `app/app/src/main/java/dev/herdr/mobile/data/Settings.kt` (modify): server list, active id, per-server endpoints, migration.
- `app/app/src/main/java/dev/herdr/mobile/push/Notifications.kt` (modify): per-server tag, title prefix, intent extras.
- `app/app/src/main/java/dev/herdr/mobile/push/UnifiedPushReceiver.kt` (modify): per-instance endpoint and routing.
- `app/app/src/test/java/dev/herdr/mobile/NotificationsTest.kt` (create).
- `app/app/src/main/java/dev/herdr/mobile/ui/ServerSheet.kt` (create): chip, sheet, rename/remove dialogs.
- `app/app/src/main/java/dev/herdr/mobile/ui/ActionSheets.kt` (modify): `HerdrDialog` private → internal.
- `app/app/src/main/java/dev/herdr/mobile/ui/OnboardingScreen.kt` (modify): `ConnectScreen` gains `showPush`, `duplicateOf`, `onBack`.
- `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` (modify): `TopBar` shows the chip.
- `app/app/src/main/java/dev/herdr/mobile/MainActivity.kt` (modify): sessions per server, switch/add/rename/remove, push per instance, notification taps across servers.
- `CHANGELOG.md` (modify).

---

### Task 1: Companion persists the push endpoint

**Files:**
- Create: `companion/internal/engine/endpoint.go`
- Create: `companion/internal/engine/endpoint_test.go`
- Modify: `companion/internal/engine/engine.go` (struct fields ~line 44-46, `New` ~line 51-102, `setEndpoint` ~line 104-108)
- Modify: `companion/cmd/herdr-mobiled/main.go:65`

**Interfaces:**
- Consumes: `Config.StateDir` (existing).
- Produces: `loadEndpoint(path string) string`, `saveEndpoint(path, ep string)`, field `Engine.endpointPath string`. No exported API change.

- [ ] **Step 1: Write the failing tests**

`companion/internal/engine/endpoint_test.go`:

```go
package engine

import (
	"os"
	"path/filepath"
	"testing"
)

func TestEndpointSurvivesRestart(t *testing.T) {
	dir := t.TempDir()
	e := New(Config{SocketPath: filepath.Join(dir, "none.sock"), StateDir: dir})
	e.setEndpoint("https://push.example/abc")

	fi, err := os.Stat(filepath.Join(dir, "push-endpoint.json"))
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("mode = %v, want 0600", fi.Mode().Perm())
	}

	e2 := New(Config{SocketPath: filepath.Join(dir, "none.sock"), StateDir: dir})
	if e2.endpoint != "https://push.example/abc" {
		t.Fatalf("endpoint after restart = %q", e2.endpoint)
	}
}

func TestMalformedEndpointFileStartsEmpty(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "push-endpoint.json"), []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	e := New(Config{SocketPath: filepath.Join(dir, "none.sock"), StateDir: dir})
	if e.endpoint != "" {
		t.Fatalf("endpoint = %q, want empty", e.endpoint)
	}
}

func TestNoStateDirKeepsEndpointInMemory(t *testing.T) {
	e := New(Config{SocketPath: filepath.Join(t.TempDir(), "none.sock")})
	e.setEndpoint("https://push.example/abc")
	if e.endpoint != "https://push.example/abc" {
		t.Fatalf("endpoint = %q", e.endpoint)
	}
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd companion && go test -race ./internal/engine/ -run 'Endpoint'`
Expected: FAIL. `TestEndpointSurvivesRestart` fails on `os.Stat` (no such file); the others pass or fail trivially. The point is the restart test failing.

- [ ] **Step 3: Implement**

`companion/internal/engine/endpoint.go`:

```go
package engine

import (
	"encoding/json"
	"errors"
	"io/fs"
	"log"
	"os"
	"path/filepath"
)

// endpointFile keeps the phone's push endpoint across restarts, so a
// companion the phone isn't connected to still notifies after it restarts.
type endpointFile struct {
	Endpoint string `json:"endpoint"`
}

// loadEndpoint reads the saved endpoint; a missing, unreadable or malformed
// file means none (the phone re-registers on its next connect).
func loadEndpoint(path string) string {
	if path == "" {
		return ""
	}
	b, err := os.ReadFile(path)
	if err != nil {
		if !errors.Is(err, fs.ErrNotExist) {
			log.Printf("push endpoint: %v", err)
		}
		return ""
	}
	var f endpointFile
	if err := json.Unmarshal(b, &f); err != nil {
		log.Printf("push endpoint: %s: %v", path, err)
		return ""
	}
	return f.Endpoint
}

// saveEndpoint writes the endpoint atomically, 0600, like limits.json.
func saveEndpoint(path, ep string) {
	if path == "" {
		return
	}
	b, _ := json.Marshal(endpointFile{Endpoint: ep})
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		log.Printf("push endpoint: %v", err)
		return
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		log.Printf("push endpoint: %v", err)
		return
	}
	if err := os.Rename(tmp, path); err != nil {
		log.Printf("push endpoint: %v", err)
	}
}
```

In `engine.go`, add the field next to `endpoint`:

```go
	mu           sync.Mutex
	endpoint     string
	endpointPath string // where endpoint is kept across restarts; "" = memory only
```

In `New`, right after the existing `e.limits = limits.New(path, nil)` line:

```go
	if cfg.StateDir != "" {
		e.endpointPath = filepath.Join(cfg.StateDir, "push-endpoint.json")
	}
	e.endpoint = loadEndpoint(e.endpointPath)
```

Replace `setEndpoint`:

```go
func (e *Engine) setEndpoint(ep string) {
	e.mu.Lock()
	changed := ep != e.endpoint
	e.endpoint = ep
	e.mu.Unlock()
	if changed {
		saveEndpoint(e.endpointPath, ep)
	}
}
```

In `cmd/herdr-mobiled/main.go:65`, change the flag help to:
`"directory for state kept across restarts (the last rate-limit reading, the phone's push endpoint)"`.

- [ ] **Step 4: Run all companion tests**

Run: `cd companion && go test -race ./...`
Expected: PASS (including the three new tests).

- [ ] **Step 5: Commit**

```bash
git add companion/internal/engine/endpoint.go companion/internal/engine/endpoint_test.go companion/internal/engine/engine.go companion/cmd/herdr-mobiled/main.go
git commit -m "feat(companion): keep the push endpoint across restarts"
```

---

### Task 2: Server list model, storage and migration

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/data/Servers.kt`
- Create: `app/app/src/test/java/dev/herdr/mobile/ServersTest.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/data/Settings.kt`

**Interfaces:**
- Consumes: `dev.herdr.mobile.net.json` (internal `Json { ignoreUnknownKeys = true; encodeDefaults = true }`), `dev.herdr.mobile.ui.hostPort(url: String): String`.
- Produces (all in package `dev.herdr.mobile.data`):
  - `@Serializable data class Server(val id: String, val name: String, val url: String)`
  - `const val LEGACY_SERVER_ID = "default"`
  - `fun encodeServers(servers: List<Server>): String`, `fun decodeServers(raw: String?): List<Server>`
  - `fun defaultServerName(url: String): String`
  - `data class Migration(val servers: List<Server>, val activeId: String?, val endpoint: String?)`, `fun migrateLegacy(url: String?, endpoint: String?): Migration`
  - `fun List<Server>.withRenamed(id: String, name: String): List<Server>`
  - `data class Removal(val servers: List<Server>, val activeId: String?)`, `fun List<Server>.withRemoved(id: String, activeId: String?): Removal`
  - `fun List<Server>.savedAt(url: String): Server?`
  - `sealed interface ServerOpen { Stay; Switch(server); Unknown }`, `fun resolveServerOpen(serverId: String?, activeId: String?, servers: List<Server>): ServerOpen`
  - `Settings`: `servers: Flow<List<Server>>`, `activeServerId: Flow<String?>`, `suspend setServers(servers: List<Server>, activeId: String?)`, `suspend setActiveServer(id: String)`, `suspend addServer(server: Server)`, `pushEndpoint(id: String): Flow<String?>`, `suspend setPushEndpoint(id: String, endpoint: String)`, `suspend removePushEndpoint(id: String)`, `suspend migrate()`. The old `companionUrl`/`setCompanionUrl`/`pushEndpoint`/`setPushEndpoint(endpoint)` stay until Task 5 removes them.

- [ ] **Step 1: Write the failing tests**

`app/app/src/test/java/dev/herdr/mobile/ServersTest.kt`:

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.data.*
import org.junit.Assert.*
import org.junit.Test

class ServersTest {
    private val a = Server("a", "laptop", "ws://100.1.1.1:8787")
    private val b = Server("b", "devbox", "ws://100.2.2.2:8787")

    @Test fun codecRoundTrips() {
        assertEquals(listOf(a, b), decodeServers(encodeServers(listOf(a, b))))
    }

    @Test fun missingOrCorruptListDecodesEmpty() {
        assertEquals(emptyList<Server>(), decodeServers(null))
        assertEquals(emptyList<Server>(), decodeServers(""))
        assertEquals(emptyList<Server>(), decodeServers("{not json"))
    }

    @Test fun migratesUrlAndEndpointToTheDefaultInstance() {
        val m = migrateLegacy("ws://100.1.1.1:8787", "https://up.example/x")
        assertEquals(listOf(Server("default", "100.1.1.1:8787", "ws://100.1.1.1:8787")), m.servers)
        assertEquals("default", m.activeId)
        assertEquals("https://up.example/x", m.endpoint)
    }

    @Test fun migratesUrlWithoutEndpoint() {
        val m = migrateLegacy("ws://100.1.1.1:8787", null)
        assertEquals(1, m.servers.size)
        assertNull(m.endpoint)
    }

    @Test fun freshInstallMigratesToNothing() {
        assertEquals(Migration(emptyList(), null, null), migrateLegacy(null, null))
    }

    @Test fun renameTrimsAndBlankFallsBackToHost() {
        assertEquals("work", listOf(a, b).withRenamed("a", "  work ")[0].name)
        assertEquals("100.1.1.1:8787", listOf(a, b).withRenamed("a", "   ")[0].name)
        assertEquals(b, listOf(a, b).withRenamed("a", "x")[1])
    }

    @Test fun removingAnInactiveServerKeepsTheActiveOne() {
        assertEquals(Removal(listOf(a), "a"), listOf(a, b).withRemoved("b", activeId = "a"))
    }

    @Test fun removingTheActiveServerActivatesTheFirstLeft() {
        assertEquals(Removal(listOf(b), "b"), listOf(a, b).withRemoved("a", activeId = "a"))
    }

    @Test fun removingTheLastServerLeavesNoneActive() {
        assertEquals(Removal(emptyList(), null), listOf(a).withRemoved("a", activeId = "a"))
    }

    @Test fun savedAtMatchesHostPortIgnoringSchemeAndCase() {
        assertEquals(a, listOf(a, b).savedAt("ws://100.1.1.1:8787"))
        val named = Server("c", "box", "ws://MyBox:8787")
        assertEquals(named, listOf(named).savedAt("ws://mybox:8787/"))
        assertNull(listOf(a).savedAt("ws://100.1.1.1:8788"))
    }

    @Test fun openOnTheActiveOrLegacyServerStays() {
        assertEquals(ServerOpen.Stay, resolveServerOpen("a", "a", listOf(a, b)))
        assertEquals(ServerOpen.Stay, resolveServerOpen(null, "a", listOf(a, b)))
    }

    @Test fun openOnAnotherServerSwitches() {
        assertEquals(ServerOpen.Switch(b), resolveServerOpen("b", "a", listOf(a, b)))
    }

    @Test fun openOnARemovedServerIsUnknown() {
        assertEquals(ServerOpen.Unknown, resolveServerOpen("gone", "a", listOf(a, b)))
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest --tests 'dev.herdr.mobile.ServersTest'`
Expected: FAIL to compile (`Unresolved reference: Server`).

- [ ] **Step 3: Implement `Servers.kt`**

```kotlin
package dev.herdr.mobile.data

import dev.herdr.mobile.net.json
import dev.herdr.mobile.ui.hostPort
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A saved companion. [id] is also its UnifiedPush instance name. */
@Serializable
data class Server(val id: String, val name: String, val url: String)

/**
 * The UnifiedPush connector's default instance. The one companion of an
 * install from before multiple servers gets it, so its push registration and
 * endpoint carry over without registering again.
 */
const val LEGACY_SERVER_ID = "default"

private val serverList = ListSerializer(Server.serializer())

fun encodeServers(servers: List<Server>): String = json.encodeToString(serverList, servers)

/** A missing or corrupt value reads as no servers, never a crash at launch. */
fun decodeServers(raw: String?): List<Server> =
    if (raw.isNullOrBlank()) emptyList()
    else runCatching { json.decodeFromString(serverList, raw) }.getOrDefault(emptyList())

/** The name a server has when the user gives none: its host:port. */
fun defaultServerName(url: String): String = hostPort(url)

/** What the single-companion prefs become: the servers, the active id, and the endpoint to move. */
data class Migration(val servers: List<Server>, val activeId: String?, val endpoint: String?)

fun migrateLegacy(url: String?, endpoint: String?): Migration =
    if (url.isNullOrBlank()) Migration(emptyList(), null, null)
    else Migration(listOf(Server(LEGACY_SERVER_ID, defaultServerName(url), url)), LEGACY_SERVER_ID, endpoint)

/** Renames [id]; a blank name falls back to the host. */
fun List<Server>.withRenamed(id: String, name: String): List<Server> =
    map { if (it.id == id) it.copy(name = name.trim().ifBlank { defaultServerName(it.url) }) else it }

/** The servers left after a removal, and which is active: unchanged, or the first left when it was removed. */
data class Removal(val servers: List<Server>, val activeId: String?)

fun List<Server>.withRemoved(id: String, activeId: String?): Removal {
    val left = filterNot { it.id == id }
    val active = if (left.any { it.id == activeId }) activeId else left.firstOrNull()?.id
    return Removal(left, active)
}

/** The server already saved at [url]'s host:port (case-insensitive), so one companion isn't saved twice. */
fun List<Server>.savedAt(url: String): Server? =
    firstOrNull { hostPort(it.url).equals(hostPort(url), ignoreCase = true) }

/** What tapping a notification from a server does. */
sealed interface ServerOpen {
    /** The active server, or a notification posted before servers had ids. */
    data object Stay : ServerOpen
    data class Switch(val server: Server) : ServerOpen
    /** Removed since: just open the app. */
    data object Unknown : ServerOpen
}

fun resolveServerOpen(serverId: String?, activeId: String?, servers: List<Server>): ServerOpen = when {
    serverId == null || serverId == activeId -> ServerOpen.Stay
    else -> servers.firstOrNull { it.id == serverId }?.let { ServerOpen.Switch(it) } ?: ServerOpen.Unknown
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest --tests 'dev.herdr.mobile.ServersTest'`
Expected: PASS (13 tests).

- [ ] **Step 5: Add the storage to `Settings.kt`**

Add keys under the existing ones:

```kotlin
private val SERVERS_KEY = stringPreferencesKey("servers")
private val ACTIVE_SERVER_KEY = stringPreferencesKey("active_server")
private fun endpointKey(serverId: String) = stringPreferencesKey("push_endpoint:$serverId")
```

Add to `class Settings` (keep the existing members for now):

```kotlin
    val servers: Flow<List<Server>> = context.dataStore.data.map { decodeServers(it[SERVERS_KEY]) }
    val activeServerId: Flow<String?> = context.dataStore.data.map { it[ACTIVE_SERVER_KEY] }

    suspend fun setServers(servers: List<Server>, activeId: String?) {
        context.dataStore.edit {
            it[SERVERS_KEY] = encodeServers(servers)
            if (activeId == null) it.remove(ACTIVE_SERVER_KEY) else it[ACTIVE_SERVER_KEY] = activeId
        }
    }

    suspend fun setActiveServer(id: String) { context.dataStore.edit { it[ACTIVE_SERVER_KEY] = id } }

    /** Appends [server] and makes it active. */
    suspend fun addServer(server: Server) {
        context.dataStore.edit {
            it[SERVERS_KEY] = encodeServers(decodeServers(it[SERVERS_KEY]) + server)
            it[ACTIVE_SERVER_KEY] = server.id
        }
    }

    fun pushEndpoint(serverId: String): Flow<String?> = context.dataStore.data.map { it[endpointKey(serverId)] }
    suspend fun setPushEndpoint(serverId: String, endpoint: String) {
        context.dataStore.edit { it[endpointKey(serverId)] = endpoint }
    }
    suspend fun removePushEndpoint(serverId: String) { context.dataStore.edit { it.remove(endpointKey(serverId)) } }

    /**
     * Moves the single-companion prefs to the server list, once: a no-op when
     * `servers` exists. Both the activity and the push receiver call it, since
     * a push can arrive before an upgraded app is opened.
     */
    suspend fun migrate() {
        context.dataStore.edit { prefs ->
            if (prefs[SERVERS_KEY] != null) return@edit
            val m = migrateLegacy(prefs[URL_KEY], prefs[PUSH_ENDPOINT_KEY])
            prefs[SERVERS_KEY] = encodeServers(m.servers)
            m.activeId?.let { prefs[ACTIVE_SERVER_KEY] = it }
            m.endpoint?.let { prefs[endpointKey(LEGACY_SERVER_ID)] = it }
            prefs.remove(URL_KEY)
            prefs.remove(PUSH_ENDPOINT_KEY)
        }
    }
```

- [ ] **Step 6: Run the full unit suite**

Run: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/data/Servers.kt app/app/src/main/java/dev/herdr/mobile/data/Settings.kt app/app/src/test/java/dev/herdr/mobile/ServersTest.kt
git commit -m "feat(app): saved server list with migration from the single companion URL"
```

---

### Task 3: Per-server push routing

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/push/Notifications.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/push/UnifiedPushReceiver.kt`
- Create: `app/app/src/test/java/dev/herdr/mobile/NotificationsTest.kt`

**Interfaces:**
- Consumes: Task 2's `Settings.migrate()`, `Settings.servers`, `Settings.setPushEndpoint(serverId, endpoint)`, `Settings.removePushEndpoint(serverId)`.
- Produces (package `dev.herdr.mobile.push`):
  - `const val EXTRA_SERVER_ID = "serverId"`, `const val EXTRA_PANE_ID = "paneId"`
  - `fun notificationTitle(title: String, serverName: String?): String`
  - `fun openRequestCode(serverId: String, paneId: String): Int`
  - `Notifications.post(ctx: Context, serverId: String, serverName: String?, p: PushPayload)`: `serverName` non-null means "prefix it".
  - `Notifications.cancel(ctx: Context, serverId: String, paneId: String)`
  - `Notifications.cancelServer(ctx: Context, serverId: String)`

Notifications are tagged with the server id (`notify(tag = serverId, id = paneId.hashCode())`) rather than hashing both into one id. It does the same job as the spec's `(serverId, paneId)` key without collisions, and lets `cancelServer` find a server's notifications by tag.

- [ ] **Step 1: Write the failing tests**

`app/app/src/test/java/dev/herdr/mobile/NotificationsTest.kt`:

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.push.notificationTitle
import dev.herdr.mobile.push.openRequestCode
import org.junit.Assert.*
import org.junit.Test

class NotificationsTest {
    @Test fun titleIsPrefixedOnlyWithAServerName() {
        assertEquals("herdr needs you", notificationTitle("herdr needs you", null))
        assertEquals("devbox · herdr needs you", notificationTitle("herdr needs you", "devbox"))
    }

    @Test fun samePaneOnTwoServersGetsDistinctRequestCodes() {
        assertNotEquals(openRequestCode("a", "w1:p1"), openRequestCode("b", "w1:p1"))
        assertEquals(openRequestCode("a", "w1:p1"), openRequestCode("a", "w1:p1"))
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest --tests 'dev.herdr.mobile.NotificationsTest'`
Expected: FAIL to compile (`Unresolved reference: notificationTitle`).

- [ ] **Step 3: Implement `Notifications.kt`**

Replace the file body after the imports with:

```kotlin
const val EXTRA_SERVER_ID = "serverId"
const val EXTRA_PANE_ID = "paneId"

/** [title], prefixed with the server's name when there's one to tell apart. */
fun notificationTitle(title: String, serverName: String?): String =
    if (serverName == null) title else "$serverName · $title"

/** Distinct per server and pane, so two servers' taps don't overwrite each other's intent. */
fun openRequestCode(serverId: String, paneId: String): Int = "$serverId/$paneId".hashCode()

object Notifications {
    private const val CH_BLOCKED = "blocked"
    private const val CH_FINISHED = "finished"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_BLOCKED, "Agent needs you", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_FINISHED, "Agent finished", NotificationManager.IMPORTANCE_DEFAULT))
    }

    // Tagged with the server, so equal pane ids on two servers stay apart.
    fun cancel(ctx: Context, serverId: String, paneId: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(serverId, paneId.hashCode())
    }

    /** Withdraws everything a (removed) server posted. */
    fun cancelServer(ctx: Context, serverId: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.tag == serverId }.forEach { nm.cancel(it.tag, it.id) }
    }

    /** [serverName] is prefixed to the title when non-null (more than one server saved). */
    fun post(ctx: Context, serverId: String, serverName: String?, p: PushPayload) {
        if (p.kind == "clear") return
        ensureChannels(ctx)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SERVER_ID, serverId)
            putExtra(EXTRA_PANE_ID, p.paneId)
        }
        val pi = PendingIntent.getActivity(ctx, openRequestCode(serverId, p.paneId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val channel = if (p.kind == "blocked") CH_BLOCKED else CH_FINISHED
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notificationTitle(p.title, serverName))
            .setContentText(p.body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(if (p.kind == "blocked") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(serverId, p.paneId.hashCode(), n)
    }
}
```

- [ ] **Step 4: Implement `UnifiedPushReceiver.kt`**

Replace the class (and its doc comment) with:

```kotlin
/**
 * One UnifiedPush instance per saved server (instance = server id): saves each
 * server's endpoint and turns its pushes into notifications tagged with it.
 * MainActivity forwards the active server's endpoint to its companion.
 */
class UnifiedPushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        CoroutineScope(Dispatchers.IO).launch { Settings(context).setPushEndpoint(instance, endpoint.url) }
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        val p = parsePush(message.content) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val settings = Settings(context)
            settings.migrate()   // an upgraded app may not have been opened yet
            val servers = settings.servers.first()
            val server = servers.firstOrNull { it.id == instance } ?: return@launch   // removed since
            if (p.kind == "clear") Notifications.cancel(context, server.id, p.paneId)
            else Notifications.post(context, server.id, server.name.takeIf { servers.size > 1 }, p)
        }
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {}

    override fun onUnregistered(context: Context, instance: String) {
        CoroutineScope(Dispatchers.IO).launch { Settings(context).removePushEndpoint(instance) }
    }
}
```

Add the import `kotlinx.coroutines.flow.first`.

- [ ] **Step 5: Run the full unit suite**

Run: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/push/ app/app/src/test/java/dev/herdr/mobile/NotificationsTest.kt
git commit -m "feat(app): route pushes and notifications per server"
```

---

### Task 4: Server chip, server sheet and connect-screen options

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/ServerSheet.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/ActionSheets.kt:476` (`private fun HerdrDialog` → `internal fun HerdrDialog`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/OnboardingScreen.kt:47-112` (`ConnectScreen`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` (`DashboardScreen` params ~67-72, `TopBar` ~375, `EmptyState` ~678, `ConnectingState` ~720 and their call sites ~179-180, ~197)

**Interfaces:**
- Consumes: Task 2's `Server`, `defaultServerName`; existing `hostPort`, `PrimaryButton(text, onClick, modifier)`, `SheetHandle()`, `HerdrDialog(title, onDismiss, confirmLabel, confirmEnabled, onConfirm, content)`, `Herdr.colors`, `HerdrType`, `HerdrRadius`.
- Produces:
  - `@Composable fun ServerChip(name: String, onClick: () -> Unit)`
  - `@Composable fun ServerSheet(servers: List<Server>, activeId: String?, onSwitch: (Server) -> Unit, onRename: (Server, String) -> Unit, onRemove: (Server) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit)`
  - `ConnectScreen(initialInput, initialPush, showPush: Boolean = true, duplicateOf: (CompanionAddress) -> String? = { null }, onBack: (() -> Unit)? = null, onConnect)`: `onConnect` stays the last parameter so trailing-lambda call sites still compile.
  - `DashboardScreen(vm, openRequest, onOpenRequestHandled, companionUrl = null, serverName: String? = null, onServers: () -> Unit = {})`

These are Compose UI with no logic beyond what Task 2 tests, so this task's check is that it compiles and the existing suite passes; Task 5 checks it on the emulator.

- [ ] **Step 1: Make `HerdrDialog` internal**

In `ActionSheets.kt` change `private fun HerdrDialog(` to `internal fun HerdrDialog(`.

- [ ] **Step 2: Create `ServerSheet.kt`**

```kotlin
package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.data.Server
import dev.herdr.mobile.data.defaultServerName
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrRadius
import dev.herdr.mobile.ui.theme.HerdrType

/** The dashboard header's active-server chip; opens [ServerSheet]. */
@Composable
fun ServerChip(name: String, onClick: () -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(c.base).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = HerdrType.meta, color = c.subtext1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp))
        Spacer(Modifier.width(6.dp))
        Text("▾", style = HerdrType.meta, color = c.overlay2)
    }
}

/**
 * Saved servers: tap one to switch to it, its ⋯ to rename or remove it, and
 * "+ Add server" to connect another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(
    servers: List<Server>,
    activeId: String?,
    onSwitch: (Server) -> Unit,
    onRename: (Server, String) -> Unit,
    onRemove: (Server) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Herdr.colors
    var renaming by remember { mutableStateOf<Server?>(null) }
    var removing by remember { mutableStateOf<Server?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.mantle,
        shape = HerdrRadius.sheet,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Servers", style = HerdrType.headline, color = c.text, modifier = Modifier.padding(bottom = 8.dp))
            servers.forEach { s ->
                ServerRow(s, active = s.id == activeId, onClick = { onSwitch(s) },
                    onRename = { renaming = s }, onRemove = { removing = s })
            }
            PrimaryButton("+ Add server", onAdd, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
    renaming?.let { s ->
        ServerRenameDialog(s, onConfirm = { onRename(s, it); renaming = null }, onDismiss = { renaming = null })
    }
    removing?.let { s ->
        RemoveServerDialog(s, onConfirm = { onRemove(s); removing = null }, onDismiss = { removing = null })
    }
}

@Composable
private fun ServerRow(server: Server, active: Boolean, onClick: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    val c = Herdr.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(HerdrRadius.tile).background(c.base)
            .border(1.dp, if (active) c.mauve.copy(alpha = 0.5f) else c.base, HerdrRadius.tile)
            .clickable(onClick = onClick).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(server.name, style = HerdrType.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hostPort(server.url), style = HerdrType.meta, color = c.overlay2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (active) Text("✓", style = HerdrType.title, color = c.mauve)
        Box {
            Text("⋯", style = HerdrType.title, color = c.subtext1,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { menu = true }.padding(12.dp))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Remove", color = c.red) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun ServerRenameDialog(server: Server, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(server.id) { mutableStateOf(server.name) }
    HerdrDialog(
        title = "Rename server",
        onDismiss = onDismiss,
        confirmLabel = "Save",
        confirmEnabled = text.trim() != server.name,
        onConfirm = { onConfirm(text) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, shape = HerdrRadius.field,
                placeholder = { Text(defaultServerName(server.url)) },
            )
            Text("Leave blank to use the address.", style = HerdrType.small, color = Herdr.colors.overlay2)
        }
    }
}

@Composable
private fun RemoveServerDialog(server: Server, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    HerdrDialog(
        title = "Remove ${server.name}?",
        onDismiss = onDismiss,
        confirmLabel = "Remove",
        confirmEnabled = true,
        onConfirm = onConfirm,
    ) {
        Text(
            "Its notifications stop and its address is forgotten. Agents on it keep running.",
            style = HerdrType.body, color = Herdr.colors.subtext1,
        )
    }
}
```

- [ ] **Step 3: Extend `ConnectScreen`**

Change the signature to:

```kotlin
@Composable
fun ConnectScreen(
    initialInput: String,
    initialPush: Boolean,
    showPush: Boolean = true,
    duplicateOf: (CompanionAddress) -> String? = { null },
    onBack: (() -> Unit)? = null,
    onConnect: (input: String, address: CompanionAddress, push: Boolean) -> Unit,
) {
```

Below `val address = parseCompanionAddress(input)` add `val duplicate = address?.let(duplicateOf)`. Change `connect()` to `fun connect() { if (duplicate == null) address?.let { onConnect(input.trim(), it, push) } }`. Add `onBack?.let { BackHandler(onBack = it) }` (import `androidx.activity.compose.BackHandler`).

At the top of the header column (before `Wordmark`), when `onBack != null`, add a back affordance:

```kotlin
onBack?.let {
    Text("← back", style = HerdrType.meta, color = c.subtext1,
        modifier = Modifier.padding(bottom = 16.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = it))
}
```

In step 2's helper text, add the duplicate case first:

```kotlin
when {
    duplicate != null -> Text("Already saved as $duplicate.", style = HerdrType.small, color = c.red)
    input.isNotBlank() && address == null ->
        Text("That doesn't look like a host or ws:// address.", style = HerdrType.small, color = c.red)
    else -> Text("Keep it on your tailnet. The companion has no auth.", style = HerdrType.small, color = c.overlay2)
}
```

Wrap the `Step(3, …) { … }` block in `if (showPush) { … }`. Change the button's `enabled` to `address != null && duplicate == null`.

- [ ] **Step 4: Put the chip in the dashboard header**

In `DashboardScreen.kt`:
- Add params after `companionUrl`: `serverName: String? = null,` and `onServers: () -> Unit = {},` and document them in the KDoc ("[serverName] is the active server's chip in the header; tapping it calls [onServers]").
- `TopBar(connected: Boolean, count: Int, serverName: String?, onServers: () -> Unit)`: after `Wordmark()` add
  ```kotlin
  serverName?.let { Spacer(Modifier.width(12.dp)); ServerChip(it, onServers) }
  ```
- Pass `serverName, onServers` through every `TopBar` call: the list's `item(key = "top")`, `EmptyState(companionUrl, serverName, onServers, onNew)` and `ConnectingState(companionUrl, serverName, onServers)` (add the two params to both private composables and their call sites at ~179-180).

- [ ] **Step 5: Build and run the unit suite**

Run: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, tests PASS. (`MainActivity` still calls `ConnectScreen(draftInput, draftPush) { … }` and `DashboardScreen(…, companionUrl)`, which compile thanks to the defaults.)

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/
git commit -m "feat(app): server chip, server sheet, and add-server options on the connect screen"
```

---

### Task 5: Wire switching, adding, renaming and removing in `MainActivity`

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/MainActivity.kt` (whole file)
- Modify: `app/app/src/main/java/dev/herdr/mobile/data/Settings.kt` (remove the now-unused single-server members)

**Interfaces:**
- Consumes: everything Tasks 2–4 produce (exact names in their Interfaces blocks); `UnifiedPush.register(context, instance)`, `UnifiedPush.unregister(context, instance)` and `UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { success -> }` (connector 3.3.3; the second positional parameter of `register`/`unregister` is the instance).
- Produces: nothing for later tasks.

Key rules this task must keep:
- `Session` carries its `serverId`. Only that server's endpoint is ever forwarded on its socket, so after a switch the new companion can't receive the old server's endpoint.
- `DashboardScreen` sits inside `key(session)`, so a switch resets the open pane, sheets and in-flight answers.
- A notification target waits while the route is `Loading`, `Connect` or `FirstConnect`, and is handled once on the dashboard.

- [ ] **Step 1: Rewrite `MainActivity.kt`**

```kotlin
package dev.herdr.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.data.Server
import dev.herdr.mobile.data.ServerOpen
import dev.herdr.mobile.data.Settings
import dev.herdr.mobile.data.defaultServerName
import dev.herdr.mobile.data.pushEnabled
import dev.herdr.mobile.data.resolveServerOpen
import dev.herdr.mobile.data.savedAt
import dev.herdr.mobile.data.withRemoved
import dev.herdr.mobile.data.withRenamed
import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.push.EXTRA_PANE_ID
import dev.herdr.mobile.push.EXTRA_SERVER_ID
import dev.herdr.mobile.push.Notifications
import dev.herdr.mobile.ui.CompanionAddress
import dev.herdr.mobile.ui.ConnectScreen
import dev.herdr.mobile.ui.DashboardScreen
import dev.herdr.mobile.ui.DashboardViewModel
import dev.herdr.mobile.ui.FirstConnectionScreen
import dev.herdr.mobile.ui.ServerSheet
import dev.herdr.mobile.ui.theme.HerdrTheme
import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush

/** Where the app is: setup (or adding a server), the first connection attempt, or the dashboard. */
private sealed interface Route {
    data object Loading : Route
    /** [adding]: from the dashboard's server sheet, so Back returns there. */
    data class Connect(val adding: Boolean = false) : Route
    data class FirstConnect(val serverId: String, val address: CompanionAddress, val push: Boolean, val adding: Boolean) : Route
    data object Dashboard : Route
}

/** One companion connection, the server it belongs to, and the view model fed by it. */
private class Session(val serverId: String?, val client: CompanionClient, val vm: DashboardViewModel)

/** A tapped notification's target; [serverId] is null on ones posted before multiple servers. */
private data class OpenTarget(val serverId: String?, val paneId: String)

private fun Intent.openTarget(): OpenTarget? =
    getStringExtra(EXTRA_PANE_ID)?.let { OpenTarget(getStringExtra(EXTRA_SERVER_ID), it) }

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var permissionAsked = false

    /** Ask for the notification permission once, then register each server's UnifiedPush instance. */
    private fun enablePush(serverIds: List<String>) {
        if (serverIds.isEmpty()) return
        if (!permissionAsked) {
            permissionAsked = true
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // UnifiedPush connector 3.3.3 dropped the 2.x `registerAppWithDialog` helper.
        // Pick (or reuse) a distributor first, then register once one is confirmed.
        // The instance is the server id, so each companion gets its own endpoint.
        UnifiedPush.tryUseCurrentOrDefaultDistributor(this) { success ->
            if (success) serverIds.forEach { UnifiedPush.register(this, it) }
        }
    }

    /** A notification to act on; cleared once handled. */
    private var openTarget by mutableStateOf<OpenTarget?>(null)

    // singleTop: a notification tapped while the app is open lands here instead
    // of recreating the activity (and its companion session).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.openTarget()?.let { openTarget = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = Settings(applicationContext)
        fun newSession(serverId: String?): Session {
            val client = CompanionClient()
            return Session(
                serverId,
                client,
                DashboardViewModel(
                    client,
                    PaneRepository(),
                    fontSizeStore = settings.terminalFontSize,
                    persistFontSize = { px -> lifecycleScope.launch { settings.setTerminalFontSize(px) } },
                    recentAgentsStore = settings.recentAgents,
                    persistRecentAgent = { name -> lifecycleScope.launch { settings.addRecentAgent(name) } },
                ),
            )
        }
        // Only a fresh launch honours the extra: a recreated activity still carries
        // the launch intent, and must not reopen a pane the user has since left.
        if (savedInstanceState == null) openTarget = intent.openTarget()

        setContent {
            var session by remember { mutableStateOf(newSession(null)) }
            var route by remember { mutableStateOf<Route>(Route.Loading) }
            var pushOn by remember { mutableStateOf(false) }
            val servers by settings.servers.collectAsState(initial = emptyList())
            val activeId by settings.activeServerId.collectAsState(initial = null)
            val active = servers.firstOrNull { it.id == activeId }
            var showServers by remember { mutableStateOf(false) }
            // A pane id handed to the dashboard (from a notification), on the active server.
            var dashboardOpen by remember { mutableStateOf<String?>(null) }
            // What the connect screen shows again after a failed first attempt.
            var draftInput by remember { mutableStateOf("") }
            var draftPush by remember { mutableStateOf(false) }

            /** Drops the current socket and starts a fresh session on [server] (or none). */
            fun connectTo(server: Server?) {
                session.client.close()
                session = newSession(server?.id)
                // An unparseable host (OkHttp is stricter than our field) just never connects.
                server?.let { runCatching { session.vm.start(it.url) } }
            }

            fun switchTo(server: Server) {
                if (server.id == session.serverId) return
                lifecycleScope.launch { settings.setActiveServer(server.id) }
                connectTo(server)
                route = Route.Dashboard
            }

            fun removeServer(server: Server) {
                val left = servers.withRemoved(server.id, activeId)
                if (pushOn) UnifiedPush.unregister(this@MainActivity, server.id)
                Notifications.cancelServer(this@MainActivity, server.id)
                lifecycleScope.launch {
                    settings.setServers(left.servers, left.activeId)
                    settings.removePushEndpoint(server.id)
                }
                if (server.id != session.serverId) return
                showServers = false
                val next = left.servers.firstOrNull { it.id == left.activeId }
                connectTo(next)
                if (next == null) { draftInput = ""; draftPush = false; route = Route.Connect() }
            }

            LaunchedEffect(Unit) {
                settings.migrate()
                val all = settings.servers.first()
                val stored = settings.activeServerId.first()
                val start = all.firstOrNull { it.id == stored } ?: all.firstOrNull()
                if (start == null) {
                    route = Route.Connect()
                    return@LaunchedEffect
                }
                if (start.id != stored) settings.setActiveServer(start.id)
                connectTo(start)
                route = Route.Dashboard
                if (pushEnabled(settings.pushEnabledChoice.first(), hasCompanionUrl = true)) {
                    enablePush(all.map { it.id })
                    pushOn = true
                }
            }

            // Forward the session's own server's endpoint to its companion, so it
            // knows where to deliver notifications. Keyed on the session: a switch
            // never sends one server's endpoint to another.
            LaunchedEffect(session, pushOn) {
                val id = session.serverId ?: return@LaunchedEffect
                if (!pushOn) return@LaunchedEffect
                settings.pushEndpoint(id).filterNotNull().collect { endpoint -> session.vm.registerPush(endpoint) }
            }

            // A tapped notification: switch to its server if needed, then open the pane.
            // It waits through startup and setup, and is handled once on the dashboard.
            LaunchedEffect(openTarget, route) {
                val t = openTarget ?: return@LaunchedEffect
                if (route != Route.Dashboard) return@LaunchedEffect
                val all = settings.servers.first()
                when (val o = resolveServerOpen(t.serverId, session.serverId, all)) {
                    ServerOpen.Stay -> dashboardOpen = t.paneId
                    is ServerOpen.Switch -> { switchTo(o.server); dashboardOpen = t.paneId }
                    ServerOpen.Unknown -> {}
                }
                openTarget = null
            }

            HerdrTheme {
                when (val r = route) {
                    Route.Loading -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                    is Route.Connect -> ConnectScreen(
                        draftInput,
                        draftPush,
                        showPush = !(r.adding && pushOn),
                        duplicateOf = { a -> servers.savedAt(a.url)?.name },
                        onBack = if (r.adding) ({ route = Route.Dashboard }) else null,
                    ) { input, address, push ->
                        draftInput = input
                        draftPush = push
                        val id = UUID.randomUUID().toString()
                        session.client.close()
                        session = newSession(id)
                        // An unparseable host just never connects; the first-connection screen then offers Back.
                        runCatching { session.vm.start(address.url) }
                        route = Route.FirstConnect(id, address, push || (r.adding && pushOn), r.adding)
                    }
                    is Route.FirstConnect -> {
                        // Drop the failed attempt; a fresh session keeps a stale socket and
                        // its collectors from feeding the next one. Adding a server puts the
                        // previous server's session back behind the connect screen.
                        fun back() {
                            connectTo(if (r.adding) active else null)
                            route = Route.Connect(adding = r.adding)
                        }
                        val connected by session.vm.connected.collectAsState()
                        BackHandler(enabled = !connected) { back() }
                        FirstConnectionScreen(
                            session.vm,
                            r.address.hostPort,
                            onConnected = {
                                // Only now is the address worth keeping: it joins the list as the active server.
                                val server = Server(r.serverId, defaultServerName(r.address.url), r.address.url)
                                lifecycleScope.launch {
                                    settings.addServer(server)
                                    settings.setPushEnabled(r.push)
                                }
                                if (r.push) {
                                    enablePush(if (pushOn) listOf(server.id) else servers.map { it.id } + server.id)
                                    pushOn = true
                                }
                            },
                            onContinue = { route = Route.Dashboard },
                            onBack = ::back,
                        )
                    }
                    Route.Dashboard -> {
                        key(session) {
                            DashboardScreen(
                                session.vm, dashboardOpen, { dashboardOpen = null }, active?.url,
                                serverName = active?.name,
                                onServers = { showServers = true },
                            )
                        }
                        if (showServers) ServerSheet(
                            servers,
                            activeId,
                            onSwitch = { showServers = false; switchTo(it) },
                            onRename = { s, name ->
                                lifecycleScope.launch { settings.setServers(servers.withRenamed(s.id, name), activeId) }
                            },
                            onRemove = ::removeServer,
                            onAdd = {
                                showServers = false
                                draftInput = ""
                                draftPush = false
                                route = Route.Connect(adding = true)
                            },
                            onDismiss = { showServers = false },
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Remove the single-server members from `Settings.kt`**

Delete `companionUrl`, `setCompanionUrl`, the old `pushEndpoint` property and `setPushEndpoint(endpoint: String)`. Keep the `URL_KEY` and `PUSH_ENDPOINT_KEY` constants; `migrate()` still reads them. Update `pushEnabled`'s KDoc to say "if they already had a companion (now: any saved server)".

Run: `grep -rn "companionUrl\b\|setCompanionUrl" app/app/src` and expect only `DashboardScreen`'s own `companionUrl` parameter.

- [ ] **Step 3: Build and run the unit suite**

Run: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 4: Manual check on the emulator**

Run two companions on the host against the same herdr, each with its own state dir:

```bash
cd companion
go run ./cmd/herdr-mobiled --listen 0.0.0.0:8787 --state-dir /tmp/hm-a &
go run ./cmd/herdr-mobiled --listen 0.0.0.0:8788 --state-dir /tmp/hm-b &
```

Install the debug APK (`cd app && ./gradlew installDebug`) and check:
1. **Upgrade path:** install the APK built from `origin/master` first, connect to `10.0.2.2:8787` with push on, then install this branch's APK over it. It opens straight to the dashboard, the chip reads `10.0.2.2:8787`, and a blocked agent still notifies.
2. Chip → sheet → **+ Add server** → `10.0.2.2:8787` shows "Already saved as …" with Connect disabled; `10.0.2.2:8788` connects and becomes active (chip updates), and the push opt-in step is hidden.
3. **+ Add server** → a dead address → Back, then Back again: you're back on the dashboard of the previous server, and it reconnects.
4. Switch between the two from the sheet with a pane open: the pane closes and the new server's panes load.
5. Rename one (and rename to blank, which falls back to the host).
6. With server B active, make an agent block on server A's herdr session. (Both share one herdr here, so to tell them apart stop companion B first, or point B at another herdr socket with `--socket`.) A's notification is titled "<A name> · …", and tapping it switches to A and opens the pane.
7. Restart companion A while B is active, then trigger a blocked agent through A: it still notifies (`/tmp/hm-a/push-endpoint.json` exists, mode 0600).
8. Remove the inactive server, then the active one, then the last one: it switches to the remaining server, then lands on setup.

Without a UnifiedPush distributor on the emulator, run items 1, 6 and 7 on a device. Write down which items were checked where.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/MainActivity.kt app/app/src/main/java/dev/herdr/mobile/data/Settings.kt
git commit -m "feat(app): switch, add, rename and remove servers from the dashboard"
```

---

### Task 6: Changelog

**Files:**
- Modify: `CHANGELOG.md` (top of `## Unreleased`)

- [ ] **Step 1: Add the entry** as the first bullet under `## Unreleased`:

```markdown
- Server switcher: save more than one companion and switch between them from the dashboard header's server chip
  (a sheet lists them, with Rename, Remove and "+ Add server"). One server is connected at a time; notifications
  keep arriving from every saved server, prefixed with its name when more than one is saved, and tapping one
  switches to its server. Each server is its own UnifiedPush instance; an existing install's companion moves over
  as the `default` instance, keeping its registration. The companion now keeps the phone's push endpoint in
  `<state dir>/push-endpoint.json` (0600), so a restarted companion still notifies. No protocol change; update the
  companion for notifications after a restart.
```

- [ ] **Step 2: Final verification**

Run: `cd companion && go test -race ./... && cd ../app && ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`
Expected: all PASS, BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: changelog for the server switcher"
```
