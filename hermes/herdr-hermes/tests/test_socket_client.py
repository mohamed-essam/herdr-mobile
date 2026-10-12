import json
import os
import shutil
import socketserver
import tempfile
import threading
from http.server import BaseHTTPRequestHandler

import pytest

from herdr_hermes import socket_client
from herdr_hermes.socket_client import SyncError, check_private_dir, socket_path, sync


def test_socket_path_explicit_wins():
    env = {"HERDR_MOBILE_CHAT_SOCK": "/x/y.sock", "XDG_RUNTIME_DIR": "/run/u"}
    assert socket_path(env) == "/x/y.sock"


def test_socket_path_xdg():
    assert socket_path({"XDG_RUNTIME_DIR": "/run/u"}) == "/run/u/herdr-mobile/chat.sock"


def test_socket_path_none():
    assert socket_path({}) is None
    assert socket_path({"HERDR_MOBILE_CHAT_SOCK": "", "XDG_RUNTIME_DIR": ""}) is None


@pytest.fixture
def tmpdir_short():
    d = tempfile.mkdtemp(prefix="hh")
    os.chmod(d, 0o700)
    yield d
    shutil.rmtree(d, ignore_errors=True)


def test_check_private_dir_ok(tmpdir_short):
    assert check_private_dir(tmpdir_short) is None


def test_check_private_dir_open_perms(tmpdir_short):
    os.chmod(tmpdir_short, 0o755)
    reason = check_private_dir(tmpdir_short)
    assert reason and "group/other" in reason


def test_check_private_dir_not_a_dir(tmpdir_short):
    f = os.path.join(tmpdir_short, "f")
    open(f, "w").close()
    assert "not a directory" in check_private_dir(f)


class _Server(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True
    block_on_close = True


def _serve(sockpath, status, reply_bytes):
    seen = {}

    class H(BaseHTTPRequestHandler):
        def do_POST(self):
            n = int(self.headers.get("Content-Length", "0"))
            seen["body"] = self.rfile.read(n)
            seen["path"] = self.path
            seen["host"] = self.headers.get("Host")
            seen["ctype"] = self.headers.get("Content-Type")
            self.send_response(status)
            self.send_header("Content-Length", str(len(reply_bytes)))
            self.end_headers()
            self.wfile.write(reply_bytes)

        def log_message(self, *a):
            pass

        def address_string(self):
            return "unix"

    srv = _Server(sockpath, H)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv, t, seen


def _stop(srv, t):
    srv.shutdown()
    srv.server_close()
    t.join(5)


def test_sync_roundtrip(tmpdir_short):
    sp = os.path.join(tmpdir_short, "c.sock")
    srv, t, seen = _serve(sp, 200, b'{"ok": true, "n": 3}')
    try:
        body = {"session": "s1", "events": [{"a": 1}]}
        assert sync(sp, body) == {"ok": True, "n": 3}
    finally:
        _stop(srv, t)
    assert json.loads(seen["body"]) == body
    assert seen["path"] == "/sync"
    assert seen["host"] == "chat"
    assert seen["ctype"] == "application/json"


def test_sync_500_raises(tmpdir_short):
    sp = os.path.join(tmpdir_short, "c.sock")
    srv, t, _ = _serve(sp, 500, b"boom")
    try:
        with pytest.raises(SyncError):
            sync(sp, {})
    finally:
        _stop(srv, t)


def test_sync_bad_json_raises(tmpdir_short):
    sp = os.path.join(tmpdir_short, "c.sock")
    srv, t, _ = _serve(sp, 200, b"not json")
    try:
        with pytest.raises(SyncError):
            sync(sp, {})
    finally:
        _stop(srv, t)


def test_sync_refused_raises(tmpdir_short):
    with pytest.raises(SyncError):
        sync(os.path.join(tmpdir_short, "none.sock"), {})


def test_sync_module_exports():
    assert issubclass(socket_client.SyncError, Exception)
