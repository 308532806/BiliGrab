# -*- coding: utf-8 -*-
"""给 v1.7.0 的 Release 加上「已被 v1.7.1 取代」的警示。

单独写成脚本是因为 PowerShell 5.1 把中文请求体发成问号：
它默认按本地代码页编码 body，而 GitHub 收的是 UTF-8。
"""
import json
import os
import sys
import urllib.request

PAT = os.environ.get("BILIGRAB_PAT", "")
if not PAT:
    print("缺少 BILIGRAB_PAT")
    sys.exit(1)

REPO = "308532806/BiliGrab"

BODY = """\
> [!WARNING]
> **这个版本有缺陷，请改用 [v1.7.1](https://github.com/308532806/BiliGrab/releases/tag/v1.7.1)。**
>
> v1.7.0 封装出来的 MP4 **音轨可能是空的** —— 文件能播、有画面、时长也对，
> 但完全没有声音。仅音频下载则可能只写到 85% 就中断（时长从 34:15 缩到 29:43），
> 产物没有样本表、根本打不开，却被标成「已完成」。
>
> 原因与修复见 [v1.7.1 的说明](https://github.com/308532806/BiliGrab/releases/tag/v1.7.1)。
> **用这个版本下载过的视频建议重下。**

---

"""


def api(path, method="GET", payload=None):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload else None
    req = urllib.request.Request("https://api.github.com" + path, method=method, data=data)
    req.add_header("Authorization", "token " + PAT)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "BiliGrab-fix-note")
    if data:
        req.add_header("Content-Type", "application/json; charset=utf-8")
    with urllib.request.urlopen(req, timeout=120) as resp:
        raw = resp.read()
        return resp.status, (json.loads(raw) if raw else None)


st, rel = api("/repos/%s/releases/tags/v1.7.0" % REPO)
if st != 200 or not rel:
    print("取 v1.7.0 Release 失败：HTTP %s" % st)
    sys.exit(1)

old = rel.get("body") or ""

# 先清掉可能已经存在的警示块，再统一加一遍。
# 需要清理是因为这个脚本被跑过两次：第一次用 PowerShell 发的请求体
# 被编成了问号（它按本地代码页编码，GitHub 收 UTF-8），于是页面顶部
# 留了一段「????」的残骸。只在开头判断「已经加过没」是不够的，
# 那样残骸会一直挂在那里。
markers = ["> [!WARNING]", "---"]
while True:
    stripped = old.lstrip()
    if not stripped.startswith("> [!WARNING]"):
        break
    # 警示块到第一个分隔线为止
    cut = stripped.find("\n---\n")
    if cut < 0:
        # 没有分隔线：说明整个 body 就是警示，直接换成新的
        old = ""
        break
    old = stripped[cut + len("\n---\n"):].lstrip("\n")
    # 继续循环，因为可能叠了两层警示

if "这个版本有缺陷" in old:
    print("清理后仍残留警示，异常")
    sys.exit(1)

st, out = api("/repos/%s/releases/%d" % (REPO, rel["id"]), "PATCH",
              {"body": BODY + old})
if st not in (200, 201) or not out:
    print("更新失败：HTTP %s" % st)
    sys.exit(1)

print("已更新 v1.7.0 的说明（%d 字节）" % len(out.get("body") or ""))
# 回读确认中文没被写成问号 —— 上一次用 PowerShell 发就是这个下场
st, again = api("/repos/%s/releases/tags/v1.7.0" % REPO)
body = again.get("body") or ""
if "这个版本有缺陷" not in body[:200]:
    print("开头不是中文警示，异常：%s" % body[:80])
    sys.exit(1)
if "??????" in body:
    print("仍有问号残骸")
    sys.exit(1)
print("中文正常，且没有残骸（共 %d 字节）" % len(body))
