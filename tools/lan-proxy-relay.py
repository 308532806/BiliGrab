"""
把本机回环上的代理端口暴露到局域网，供手机使用。

背景
----
PC 上的代理（Clash 一类）只监听 127.0.0.1:7897 —— 那是刻意的，
代理本身的设计就假设「只有本机能用」。手机因此够不到它。

要验证 BiliGrab 的 YouTube 下载路径，手机必须有一条能到 YouTube 的出口，
而局域网里那两台代理的出口 IP 都被 YouTube 风控了（解析阶段就过不去，
根本走不到下载那一步）。PC 这条出口是干净的 —— 实测 yt-dlp 经它
下载 format 160 / 251 都拿到了完整字节，没有 403。

所以这里做一个最小的 TCP 转发：0.0.0.0:7899 → 127.0.0.1:7897。

不做的事
--------
不修改用户的代理配置。改 allow-lan 之类的设置是动别人的东西，
而且多半要重启代理；转发器只占一个额外端口，用完关掉就行。
不做 TLS 解密，不做协议理解 —— 它连的是 CONNECT 隧道，字节照搬。

只转发，不缓存，不记录内容。
"""

import socket
import sys
import threading

LISTEN_HOST = "0.0.0.0"
LISTEN_PORT = 7899
UPSTREAM_HOST = "127.0.0.1"
UPSTREAM_PORT = 7897

BUFSIZE = 65536
# 隧道可能长时间空闲（用户在看进度条），不要用短超时把它掐掉
IDLE_TIMEOUT = 600


def pump(src, dst, tag):
    """单向搬运，直到任一端关闭。"""
    try:
        while True:
            data = src.recv(BUFSIZE)
            if not data:
                break
            dst.sendall(data)
    except (OSError, socket.timeout):
        pass
    finally:
        # 半关闭而不是直接 close：另一方向可能还有数据没搬完
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client, addr):
    upstream = None
    try:
        client.settimeout(IDLE_TIMEOUT)
        upstream = socket.create_connection((UPSTREAM_HOST, UPSTREAM_PORT), timeout=15)
        upstream.settimeout(IDLE_TIMEOUT)
        print("  + %s:%d" % addr, flush=True)

        t = threading.Thread(target=pump, args=(client, upstream, "->"), daemon=True)
        t.start()
        pump(upstream, client, "<-")
        t.join(timeout=5)
    except Exception as e:
        print("  ! %s:%d  %s: %s" % (addr[0], addr[1], type(e).__name__, e), flush=True)
    finally:
        for s in (client, upstream):
            if s is not None:
                try:
                    s.close()
                except OSError:
                    pass


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((LISTEN_HOST, LISTEN_PORT))
    srv.listen(64)
    print("转发中 %s:%d -> %s:%d" % (LISTEN_HOST, LISTEN_PORT, UPSTREAM_HOST, UPSTREAM_PORT),
          flush=True)
    while True:
        try:
            client, addr = srv.accept()
        except OSError as e:
            print("accept 失败：%s" % e, flush=True)
            continue
        threading.Thread(target=handle, args=(client, addr), daemon=True).start()


if __name__ == "__main__":
    main()
