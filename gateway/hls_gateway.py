#!/usr/bin/env python3
"""Restricted HLS-playlist-only proxy; media segments are never proxied."""
import hmac
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, quote, urljoin, urlparse
from urllib.request import Request, HTTPRedirectHandler, build_opener

TOKEN = os.environ.get("MYFILM_GATEWAY_TOKEN", "")
PUBLIC = os.environ.get("MYFILM_GATEWAY_PUBLIC_URL", "").rstrip("/")
MAX_BYTES = 2 * 1024 * 1024
HOST_RE = re.compile(r"^v[0-9]+\.rstu6\.com$", re.I)
EXT_URI = re.compile(r'URI="([^"]+)"')

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None

OPENER = build_opener(NoRedirect())

def allowed(url):
    try:
        p = urlparse(url)
        return (p.scheme == "https" and bool(HOST_RE.fullmatch(p.hostname or ""))
                and p.port in (None, 443) and not p.username and not p.password
                and p.path.lower().endswith(".m3u8") and not p.fragment)
    except ValueError:
        return False

def gateway_url(url):
    return PUBLIC + "/hls?url=" + quote(url, safe="") + "&token=" + quote(TOKEN, safe="")

def rewrite(playlist, source_url):
    lines = []
    for line in playlist.splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            absolute = urljoin(source_url, stripped)
            lines.append(gateway_url(absolute) if allowed(absolute) else absolute)
        elif stripped.startswith("#") and 'URI="' in line:
            def replace(match):
                absolute = urljoin(source_url, match.group(1))
                return 'URI="' + (gateway_url(absolute) if allowed(absolute) else absolute) + '"'
            lines.append(EXT_URI.sub(replace, line))
        else:
            lines.append(line)
    return "\n".join(lines) + "\n"

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlparse(self.path)
        if path.path == "/health":
            return self.reply(200, b"ok\n", "text/plain")
        if path.path != "/hls":
            return self.reply(404, b"not found\n", "text/plain")
        params = parse_qs(path.query)
        provided = params.get("token", [""])[0]
        src = params.get("url", [""])[0]
        if not TOKEN or not hmac.compare_digest(TOKEN, provided):
            return self.reply(403, b"forbidden\n", "text/plain")
        if not allowed(src):
            return self.reply(400, b"unsupported playlist URL\n", "text/plain")
        try:
            with OPENER.open(Request(src, headers={"Accept": "application/vnd.apple.mpegurl,*/*"}), timeout=12) as upstream:
                if upstream.status != 200:
                    return self.reply(502, b"upstream status\n", "text/plain")
                data = upstream.read(MAX_BYTES + 1)
            if len(data) > MAX_BYTES:
                return self.reply(502, b"playlist too large\n", "text/plain")
            body = data.decode("utf-8-sig")
            if not body.lstrip().startswith("#EXTM3U"):
                return self.reply(502, b"invalid playlist\n", "text/plain")
            return self.reply(200, rewrite(body, src).encode("utf-8"), "application/vnd.apple.mpegurl")
        except (HTTPError, URLError, UnicodeError, TimeoutError, ValueError, OSError):
            return self.reply(502, b"upstream unavailable\n", "text/plain")

    def reply(self, status, data, mime):
        self.send_response(status)
        self.send_header("Content-Type", mime + "; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        pass  # Avoid leaking query-string access tokens to logs.

if __name__ == "__main__":
    if len(TOKEN) < 24 or not PUBLIC.startswith("https://"):
        raise SystemExit("Configure MYFILM_GATEWAY_TOKEN (>=24 chars) and MYFILM_GATEWAY_PUBLIC_URL")
    ThreadingHTTPServer(("127.0.0.1", 8765), Handler).serve_forever()
