#!/usr/bin/env python3
"""最小 Range HTTP 服务（仅用于真机/模拟器验证并行分片）。

标准库的 SimpleHTTPRequestHandler 不支持 Range（一律回 200），而并行分片的前提就是
服务器支持 206 —— 所以这里自己实现一个，顺便把请求记录打到 stdout，方便确认
「5 条连接是否真的并发」。

用法：python scripts/range_server.py <目录> [端口]
"""
import os
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8123
RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)")

_print_lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 用一行打印，带线程名，便于看并发
        with _print_lock:
            print("[%s] %s" % (threading.current_thread().name, fmt % args), flush=True)

    def _resolve(self):
        path = self.path.split("?")[0]
        rel = os.path.normpath(path.lstrip("/"))
        full = os.path.abspath(os.path.join(ROOT, rel))
        if not full.startswith(ROOT) or not os.path.isfile(full):
            return None
        return full

    def do_HEAD(self):
        self.do_GET(head_only=True)

    def do_GET(self, head_only=False):
        full = self._resolve()
        if full is None:
            self.send_error(404)
            return
        size = os.path.getsize(full)
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        partial = False
        if rng:
            m = RANGE_RE.match(rng.strip())
            if m:
                if m.group(1):
                    start = int(m.group(1))
                if m.group(2):
                    end = min(int(m.group(2)), size - 1)
                partial = True
        if start >= size:
            self.send_error(416)
            return
        length = end - start + 1
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if partial:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        if head_only:
            return
        with open(full, "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(256 * 1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print("serving %s on http://0.0.0.0:%d" % (ROOT, PORT), flush=True)
    server.serve_forever()
