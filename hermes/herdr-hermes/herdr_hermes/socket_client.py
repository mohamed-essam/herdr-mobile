"""Unix-socket HTTP client for the companion's chat bridge (POST /sync)."""
from __future__ import annotations

import http.client
import json
import os
import socket
import stat
from typing import Mapping, Optional

MAX_BODY = 8 * 1024 * 1024  # the companion rejects larger bodies


class SyncError(Exception):
    """The sync call failed (connect error, non-200, or a bad reply)."""


def socket_path(env: Mapping[str, str]) -> Optional[str]:
    """Same rule as chatbridge.SocketPath; None means chat is off."""
    p = env.get("HERDR_MOBILE_CHAT_SOCK")
    if p:
        return p
    d = env.get("XDG_RUNTIME_DIR")
    if d:
        return os.path.join(d, "herdr-mobile", "chat.sock")
    return None


def check_private_dir(path: str) -> Optional[str]:
    """Mirror chatbridge.Listen's checks on the socket directory.

    Returns a reason string when the directory is unusable, else None. A
    missing directory is fine: the companion creates it (0700).
    """
    try:
        st = os.stat(path)
    except FileNotFoundError:
        return None
    except OSError as e:
        return "%s: %s" % (path, e)
    if not stat.S_ISDIR(st.st_mode):
        return "%s is not a directory" % path
    if st.st_uid != os.getuid():
        return "%s is not owned by the current user" % path
    if stat.S_IMODE(st.st_mode) & 0o077:
        return "%s grants access to group/other; use a private (0700) directory" % path
    return None


class _UnixHTTPConnection(http.client.HTTPConnection):
    def __init__(self, path: str, timeout: float) -> None:
        super().__init__("chat", timeout=timeout)
        self._path = path

    def connect(self) -> None:
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.settimeout(self.timeout)
        try:
            s.connect(self._path)
        except BaseException:
            s.close()
            raise
        self.sock = s


def sync(path: str, body: dict, timeout: float = 10.0) -> dict:
    """POST body to /sync over the Unix socket at path; return the decoded reply."""
    try:
        data = json.dumps(body).encode("utf-8")
    except (TypeError, ValueError) as e:
        raise SyncError("encode: %s" % e) from e
    if len(data) > MAX_BODY:
        raise SyncError("body too large (%d bytes)" % len(data))
    conn = _UnixHTTPConnection(path, timeout)
    try:
        try:
            conn.request(
                "POST",
                "/sync",
                body=data,
                headers={"Content-Type": "application/json", "Host": "chat"},
            )
            resp = conn.getresponse()
            raw = resp.read()
        except (OSError, http.client.HTTPException) as e:
            raise SyncError("sync request failed: %s" % e) from e
        if resp.status != 200:
            raise SyncError("sync returned HTTP %d" % resp.status)
        try:
            reply = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as e:
            raise SyncError("bad sync reply: %s" % e) from e
        if not isinstance(reply, dict):
            raise SyncError("sync reply is not an object")
        return reply
    finally:
        conn.close()
