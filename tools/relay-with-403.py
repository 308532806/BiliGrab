"""
带故障注入的局域网转发器 —— 专门用来验证「下载遇 403 换档重试」那条路。

为什么要做这个
--------------
BiliGrab 里有一段防御性代码：某条流被 CDN 拒了（403）之后，换下一档
播放器客户端重新解析、重试。这段代码写了很久，但**一次都没被执行过** ——
周围网络环境里没有任何一条出口能让 YouTube 走到下载那一步。

现在有了干净出口，下载反而一次就成功，重试路径依然碰不到。
所以这里主动制造故障：把**第一次**发往 *.googlevideo.com 的 CONNECT
掐掉，回一个 403。

于是 App 会看到：

    尝试 1  → 403  → 判定与客户端有关 → 换档重解析 → 尝试 2
    尝试 2  → 放行 → 成功

一次完整的「失败 → 换档 → 恢复」就被跑出来了。这比读代码可信得多。

只掐 googlevideo
----------------
解析阶段的请求打的是 youtube.com / googlevideo 之外的地方，必须放行 ——
否则连解析都过不去，测试就退化成「什么都跑不起来」，说明不了任何事。

用法
----
    python relay-with-403.py [端口] [要掐的次数]
默认 7898 端口，掐 1 次。
"""

import socket
import sys
import threading

LISTEN_HOST = "0.0.0.0"
LISTEN_PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 7898
FAIL_TIMES = int(sys.argv[2]) if len(sys.argv) > 2 else 1
UPSTREAM_HOST = "127.0.0.1"
UPSTREAM_PORT = 7897

BUFSIZE = 65536
IDLE_TIMEOUT = 600

# 只掐「媒体流」那一类主机。
#
# 第一次试的时候只匹配了 googlevideo.com，结果掐中的是
# manifest.googlevideo.com —— 那是 yt-dlp 在**解析阶段**取 HLS 清单用的，
# 下载阶段连的是 rr3---sn-oguesndl.googlevideo.com。
# 掐错地方的后果是：解析照常成功（yt-dlp 对清单取不到会优雅降级），
# 但下载一次就过，重试路径根本没被碰到 —— 测试等于没做。
#
# 真正的媒体流主机名形如 rr<数字>---sn-<节点>.googlevideo.com，
# 而 manifest 那条以 "manifest." 开头，用它来区分。
TARGET_MARK = "googlevideo.com"
TARGET_EXCLUDE = "manifest."

_lock = threading.Lock()
_failed = [0]
_seen = [0]


def should_fail(head: bytes) -> bool:
    """这条请求该不该被掐掉。"""
    text = head.decode("latin-1", "replace").lower()
    # 只看请求行，避免 URL 里带 googlevideo 字样的其它请求被误伤
    first_line = text.split("\r\n", 1)[0]
    if TARGET_MARK not in first_line or TARGET_EXCLUDE in first_line:
        return False
    with _lock:
        _seen[0] += 1
        if _failed[0] < FAIL_TIMES:
            _failed[0] += 1
            return True
    return False


def read_head(sock: socket.socket) -> bytes:
    """读到请求头结束（空行）。只用于判断，不消费后续字节。"""
    buf = b""
    while b"\r\n\r\n" not in buf and len(buf) < 65536:
        chunk = sock.recv(BUFSIZE)
        if not chunk:
            break
        buf += chunk
    return buf


def pump(src, dst):
    try:
        while True:
            data = src.recv(BUFSIZE)
            if not data:
                break
            dst.sendall(data)
    except (OSError, socket.timeout):
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client, addr):
    upstream = None
    try:
        client.settimeout(IDLE_TIMEOUT)
        head = read_head(client)
        if not head:
            return

        first_line = head.split(b"\r\n", 1)[0].decode("latin-1", "replace")
        n = _seen[0]

        if should_fail(head):
            # 回一个货真价实的 403。App 侧会把它归结成「CDN 返回 403」，
            # 正是要触发的那个分支。
            print("  X 掐掉 #%d：%s" % (n + 1, first_line), flush=True)
            client.sendall(b"HTTP/1.1 403 Forbidden\r\n"
                           b"Content-Length: 0\r\n"
                           b"Connection: close\r\n\r\n")
            return

        if TARGET_MARK in first_line.lower():
            print("  > 放行媒体流：%s" % first_line, flush=True)

        upstream = socket.create_connection((UPSTREAM_HOST, UPSTREAM_PORT), timeout=15)
        upstream.settimeout(IDLE_TIMEOUT)
        upstream.sendall(head)

        t = threading.Thread(target=pump, args=(client, upstream), daemon=True)
        t.start()
        pump(upstream, client)
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
    print("故障注入中：%s:%d -> %s:%d   先掐 %d 次 %s（不含 %s）"
          % (LISTEN_HOST, LISTEN_PORT, UPSTREAM_HOST, UPSTREAM_PORT, FAIL_TIMES,
             TARGET_MARK, TARGET_EXCLUDE), flush=True)
    while True:
        try:
            client, addr = srv.accept()
        except OSError as e:
            print("accept 失败：%s" % e, flush=True)
            continue
        threading.Thread(target=handle, args=(client, addr), daemon=True).start()


if __name__ == "__main__":
    main()
