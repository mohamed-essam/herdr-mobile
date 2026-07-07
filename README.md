# herdr-mobile

Monitor and unblock your [herdr](https://herdr.dev) agents from an Android phone.

- **companion/** — a small Go daemon that runs on your herdr host, exposes a
  WebSocket API over your Tailscale network, and pushes notifications via
  UnifiedPush when an agent is blocked or finishes.
- **app/** — the Android app (Kotlin + Compose): live agent dashboard + quick-reply.

v1 is monitor + quick-reply only. A full embedded terminal is planned for v2;
until then use your existing SSH app for real terminal work.

License: (match herdr's license — TODO confirm)
