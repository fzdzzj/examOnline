#!/usr/bin/env python3
"""告警接收器（add-alert-delivery 本地闭环验收专用，最小实现，零业务绑定）。

作用：接收 Alertmanager webhook POST，把 body 逐字打到容器 stdout（docker logs 可见），
用于本地验证「Alertmanager → webhook 通道」真实投递闭环。不转发到任何真实平台，
不写库、不持久化、不依赖任何业务服务——仅作验收探针。
"""
from http.server import BaseHTTPRequestHandler, HTTPServer
import sys


class AlertHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(length).decode("utf-8", "replace")
        print(
            "ALERT-RECEIVER POST path=%s content-type=%s"
            % (self.path, self.headers.get("Content-Type", "-")),
            flush=True,
        )
        print("ALERT-RECEIVER BODY: %s" % body, flush=True)
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"ok")

    def do_GET(self):
        # 就绪探针：docker healthcheck 用
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, fmt, *args):
        # 抑制默认访问日志噪音，仅保留上述显式输出
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 5000
    print("ALERT-RECEIVER listening on 0.0.0.0:%d" % port, flush=True)
    HTTPServer(("0.0.0.0", port), AlertHandler).serve_forever()