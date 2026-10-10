#!/usr/bin/env python3
"""
_local reverse proxy for Gradle on networks that RST Java's TLS handshake.

Java 的 TLS 指纹在公司网络里被丢弃（curl/openssl 正常，Java 全被 RST）。
该代理用 Python(OpenSSL) 向上游取资源，Gradle 只需要明文 HTTP 访问本机即可。

Usage:
  python3 tools/maven-proxy.py            # listen on 127.0.0.1:18080
Env:
  MAVEN_PROXY_PORT   (default 18080)
  MAVEN_PROXY_CACHE  (default .maven-proxy-cache)
"""
import hashlib
import http.server
import os
import urllib.error
import urllib.request
from pathlib import Path

PORT = int(os.environ.get("MAVEN_PROXY_PORT", "18080"))
CACHE = Path(
    os.environ.get(
        "MAVEN_PROXY_CACHE",
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".maven-proxy-cache"),
    )
).resolve()
CACHE.mkdir(parents=True, exist_ok=True)

DEFAULT_HEADERS = {"User-Agent": "local-maven-proxy/1.0", "Accept": "*/*"}


def upstream_get(url: str, method: str):
    req = urllib.request.Request(url, headers=DEFAULT_HEADERS, method=method)
    return urllib.request.urlopen(req, timeout=1800)


def cache_path(url: str) -> Path:
    return CACHE / (hashlib.sha256(url.encode()).hexdigest() + ".bin")


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _resolve_url(self):
        # path 形如 /<upstream-host>/<rest>
        raw = self.path.lstrip("/")
        if "/" not in raw:
            raise ValueError("path must be /<host>/<resource>")
        target_host, _, rest = raw.partition("/")
        return f"https://{target_host}/{rest}"

    def _proxy(self, method: str):
        try:
            url = self._resolve_url()
            cp = cache_path(url)
            if cp.is_file():
                data = cp.read_bytes()
                code, headers = 200, {"Content-Type": "application/octet-stream"}
            else:
                resp = upstream_get(url, method)
                code = resp.status
                headers = {k: v for k, v in resp.headers.items() if k.lower() not in
                           ("transfer-encoding", "connection")}
                data = resp.read()
                if method == "GET" and code == 200 and data:
                    tmp = cp.with_suffix(".tmp")
                    tmp.write_bytes(data)
                    os.replace(tmp, cp)
        except urllib.error.HTTPError as e:
            data = e.read() or b""
            code = e.code if e.code < 500 else 404
            headers = {"Content-Type": "text/plain"}
        except Exception:
            data = b"upstream error\n"
            code = 502
            headers = {"Content-Type": "text/plain"}

        self.send_response(code)
        for k, v in headers.items():
            if k.lower() in ("content-type", "content-length", "location", "etag", "last-modified"):
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if method != "HEAD":
            self.wfile.write(data)

    def do_GET(self):
        self._proxy("GET")

    def do_HEAD(self):
        self._proxy("HEAD")

    def log_message(self, fmt, *args):
        if os.environ.get("MAVEN_PROXY_VERBOSE"):
            super().log_message(fmt, *args)


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True


if __name__ == "__main__":
    print(f"maven-proxy listening on http://127.0.0.1:{PORT} cache={CACHE}")
    Server(("127.0.0.1", PORT), Handler).serve_forever()
