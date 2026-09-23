"""
最小 HTTP/HTTPS 代理 —— 只为让手机验证 BiliGrab 的 YouTube 下载链路。

背景：这台 PC 走 TUN 模式的代理能直连 YouTube，但没有监听端口，
手机没法复用。这里在 PC 上开一个小代理转发出去，手机设成它即可。

只做 CONNECT 透传和普通 HTTP 转发；不实现缓存、认证、日志落盘。
用完就关，不是一个通用工具。
"""

import socket
import select
import sys
import threading

LISTEN_HOST = "0.0.0.0"
LISTEN_PORT = 7899
BUF = 65536

lock = threading.Lock()
served = [0]


def log(msg):
    with lock:
        print(msg, flush=True)


def pipe(a, b):
    """
    单向搬运，直到 a 端读到 EOF。

    这里刻意不用 select + 超时：上一版用了 60 秒的 select 超时，大文件
    下载中途一旦安静超过阈值就被判成结束，客户端看到的是
    「unexpected end of stream」。阻塞式 recv 没有这个问题。

    读到 EOF 后对 b 做 shutdown(SHUT_WR) 而不是直接 close —— 让对端
    知道「我这边发完了」，但还能继续把它的数据发回来（HTTP 半关闭）。
    """
    try:
        while True:
            data = a.recv(BUF)
            if not data:
                break
            b.sendall(data)
    except Exception:
        pass
    finally:
        try:
            b.shutdown(socket.SHUT_WR)
        except Exception:
            pass


def handle(client, addr):
    try:
        client.settimeout(30)
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = client.recv(BUF)
            if not chunk:
                return
            head += chunk
            if len(head) > 65536:
                return

        first = head.split(b"\r\n", 1)[0].decode("latin-1", "replace")
        parts = first.split()
        if len(parts) < 2:
            return
        method, target = parts[0], parts[1]

        if method.upper() == "CONNECT":
            hostport = target
            host, _, port = hostport.partition(":")
            port = int(port or 443)
            upstream = socket.create_connection((host, port), timeout=20)
            client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            client.settimeout(None)
            upstream.settimeout(None)
            t = threading.Thread(target=pipe, args=(upstream, client), daemon=True)
            t.start()
            pipe(client, upstream)
            upstream.close()
        else:
            # 普通 HTTP：target 是完整 URL 或路径
            if target.startswith("http://"):
                rest = target[len("http://"):]
                hostpart, _, path = rest.partition("/")
                host, _, port = hostpart.partition(":")
                port = int(port or 80)
                path = "/" + path
            else:
                # 只能靠 Host 头
                host, port, path = None, 80, target
                for line in head.split(b"\r\n")[1:]:
                    if line.lower().startswith(b"host:"):
                        host = line.split(b":", 1)[1].strip().decode()
                        break
                if host is None:
                    client.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")
                    return
                if ":" in host:
                    host, _, p = host.partition(":")
                    port = int(p)

            upstream = socket.create_connection((host, port), timeout=20)
            # 重建请求行，去掉绝对 URL
            rebuilt = method + " " + path + " " + " ".join(parts[2:])
            lines = head.split(b"\r\n")
            lines[0] = rebuilt.encode("latin-1")
            upstream.sendall(b"\r\n".join(lines))
            upstream.settimeout(None)
            client.settimeout(None)
            t = threading.Thread(target=pipe, args=(upstream, client), daemon=True)
            t.start()
            pipe(client, upstream)
            upstream.close()

        with lock:
            served[0] += 1
            n = served[0]
        log("  [%d] %s" % (n, first[:96]))
    except Exception as e:
        log("  !! %s  <- %s" % (type(e).__name__, first[:60] if 'first' in dir() else addr))
    finally:
        try:
            client.close()
        except Exception:
            pass


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((LISTEN_HOST, LISTEN_PORT))
    srv.listen(64)
    print("代理已启动  %s:%d" % (LISTEN_HOST, LISTEN_PORT), flush=True)
    while True:
        try:
            c, a = srv.accept()
        except KeyboardInterrupt:
            break
        threading.Thread(target=handle, args=(c, a), daemon=True).start()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("代理已停止", flush=True)
