# herdr-mobile: server switcher

Date: 2026-10-11.

## Goal

Save more than one herdr companion in the app and switch between them from the
dashboard, without clearing app data. Agents on every saved server can still
reach you: a blocked agent on a server you are not looking at still notifies,
and tapping that notification switches to its server and opens the pane.

- **One live connection.** Only the active server has a socket; switching
  closes it and opens the other.
- **Notifications from all servers.** Each saved server delivers push
  independently of which one is active.
- **Switcher in the dashboard header.** A chip with the active server's name
  opens a bottom sheet to switch, add, rename and remove servers.
- **Each server has a user-given name**, defaulting to its host.

Out of scope: several servers connected at once or merged into one dashboard,
per-server push toggles, syncing the server list across devices, a separate
settings screen.

## Today

- `Settings` stores one `companion_url` and one `push_endpoint`; onboarding
  writes the URL once after the first successful connection and nothing in the
  app changes it afterwards.
- `MainActivity` owns one `Session` (a `CompanionClient` + `DashboardViewModel`).
- UnifiedPush is registered under the connector's default instance; the
  receiver saves its endpoint, and `MainActivity` forwards it to the companion
  with `registerPush` whenever push is on.
- The companion keeps that endpoint **in memory only** (`Engine.setEndpoint`):
  it learns it from an open phone socket and forgets it on restart.
- A push carries `kind`, `paneId`, `workspaceId`, `title`, `body` and no server
  identity; notifications use `paneId.hashCode()` as their id, and `clear`
  cancels by that id.

## Data model and storage (`data/Settings.kt`)

```kotlin
@Serializable
data class Server(val id: String, val name: String, val url: String)
```

- `servers`: a JSON-encoded `List<Server>` in the existing preferences
  DataStore, in the order added.
- `active_server`: the active server's id.
- `push_endpoint:<id>`: that server's UnifiedPush endpoint.
- New ids are random UUIDs.
- `push_enabled`, `terminal_font_size` and `recent_agents` stay global.

**Migration**, run once when `servers` is absent: an existing `companion_url`
becomes `Server(id = "default", name = <host>, url)`, made active, and an
existing `push_endpoint` moves to `push_endpoint:default`; the old keys are then
removed. `"default"` is the UnifiedPush connector's default instance name, so an
upgraded install keeps its current registration and endpoint with no
re-registration. A fresh install with no URL migrates to an empty list.

Pure helpers, unit-tested: list encode/decode (a corrupt value decodes to an
empty list rather than crashing), migration from old prefs, `withRenamed`,
`withRemoved` (and choosing the next active server: the first remaining one, or
none).

## Session lifecycle (`MainActivity`)

- Startup: load servers; with none, route to `Connect` as today; otherwise
  start a session on the active server's URL.
- **Switch(id):** close the current client, build a new `Session`, set
  `active_server`, start it on the server's URL, and send that server's stored
  endpoint with `registerPush` once connected (the same collector as today,
  keyed on the active server's endpoint flow).
- **Add server:** the sheet's "+ Add server" routes to `ConnectScreen`, then
  `FirstConnectionScreen`, as onboarding does, with two differences: Back (and
  system back) returns to the dashboard on the previous server, and the
  onboarding push opt-in is hidden if push is already on. On a successful first
  connection the new server is appended, made active, and (with push on)
  registered for push. A failed attempt saves nothing and the previous server's
  session is restored.
- The address already saved under another server is rejected in the connect
  field ("already saved as <name>") rather than duplicated.

## Switcher UI

- **Header chip:** `TopBar` gains a chip showing the active server's name (and
  a ▾); it is shown on the dashboard and on the connecting/empty states, so a
  server that never answers can still be switched away from. With a single
  server it is still shown, since it is also the way to add a second one.
- **Server sheet** (`ui/ServerSheet.kt`, bottom sheet in the style of
  `WorkspaceSwitcher`): one row per server with name, `host:port` (via the
  existing `hostPort`) and a check on the active one. Tapping a row switches
  (no-op on the active one) and closes the sheet. Each row's overflow menu has
  **Rename** (text dialog, blank resets to the host) and **Remove** (confirm
  dialog). The sheet ends with **+ Add server**.
- **Remove:** unregister that server's UnifiedPush instance, drop its endpoint
  and its row; cancel its notifications. If it was active, switch to the next
  server, or route to `Connect` when none remain.

## Push routing

**App:**
- One UnifiedPush instance per server, the instance name being the server id:
  `UnifiedPush.register(context, instance = id)` once the distributor is
  confirmed (`tryUseCurrentOrDefaultDistributor`, as today). At startup, with
  push on, every saved server is (re-)registered so each keeps a current
  endpoint; a newly added server is registered after its first connection.
- `UnifiedPushReceiver.onNewEndpoint` saves to `push_endpoint:<instance>`.
  An endpoint arriving for a non-active server is forwarded the next time that
  server becomes active (a distributor rotating endpoints is rare; it costs at
  most one missed push window until you open that server).
- `onMessage` resolves the server from `instance`; a push for an instance that
  is no longer saved is dropped.
- Notification id becomes `notificationId(serverId, paneId)` =
  `"$serverId/$paneId".hashCode()`, so equal pane ids on two servers can't
  replace each other; `clear` cancels by the same key. The content title gets
  the server name as a prefix when more than one server is saved.
- The tap intent carries `serverId` and `paneId` (request code from the same
  key). `MainActivity` handles it as `OpenRequest(serverId, paneId)`: if
  `serverId` isn't active it switches first, then hands the pane id to the
  dashboard's existing `openRequest` handling. An unknown `serverId` just opens
  the app.

**Companion:**
- `Engine` persists the endpoint to `<state-dir>/push-endpoint.json`
  (`{"endpoint": "..."}`, written atomically via temp file + rename with mode
  0600, as `limits.json` is) whenever the phone registers one, and loads it in
  `New`. With no state dir it stays in memory only, as today.
- No protocol change: the phone still sends `register_push` with one endpoint;
  each companion simply receives a different one.

## Error handling

- Corrupt `servers` JSON: decode to an empty list and route to `Connect`;
  never crash at launch.
- Switching while a pane is open: the dashboard's existing "pane disappeared"
  logic returns to the list once the new server's panes arrive.
- Push registration failure for one instance doesn't affect the others; the
  receiver's `onRegistrationFailed` stays a no-op.
- Unreadable or malformed `push-endpoint.json` on the companion: log it and
  start with no endpoint (the phone re-registers on its next connect).

## Testing

- **App unit tests:** server list codec and migration (URL only; URL +
  endpoint; nothing; corrupt JSON), rename/remove/next-active, duplicate
  address detection, `notificationId` distinctness across servers, open
  request resolution (same server, other server, unknown server).
- **Companion test:** an engine given a state dir persists a registered
  endpoint and a new engine on the same dir loads it; file mode is 0600;
  malformed file → empty endpoint.
- **Manual (emulator):** two companions on different ports; add the second
  from the sheet, switch back and forth, rename, remove the active one, and
  trigger a blocked agent on the inactive server, check the notification
  arrives and that tapping it switches server and opens the pane. Upgrade an
  install holding the old single-URL prefs and check it lands on the dashboard
  with push still working.
