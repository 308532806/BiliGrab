# -*- coding: utf-8 -*-
"""把 v1.7.0 的 Release 说明重建成「警示 + 原始发布说明」。

背景：这个脚本的前身为了清掉一段被 PowerShell 编码成问号的残骸，
沿分隔线把 body 从中间截断，结果**连同 v1.7.0 原本的发布说明一起删掉了**
（342 字节，只剩警示）。原始的说明在仓库里是有的（docs/release-notes-1.7.0.md，
Release 就是从这个文件建的），所以这里直接从本地重建成正确内容，
而不是靠记忆拼一份近似的。

用法: fix-170-body.py <tag> <说明文件>
"""
import json
import os
import sys
import urllib.request

PAT = os.environ.get("BILIGRAB_PAT", "")
if not PAT:
    print("缺少 BILIGRAB_PAT")
    sys.exit(1)
if len(sys.argv) < 3:
    print("用法: fix-170-body.py <tag> <说明文件>")
    sys.exit(1)

TAG = sys.argv[1]
NOTES_FILE = sys.argv[2]
REPO = "308532806/BiliGrab"

WARNING = """\
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


if not os.path.isfile(NOTES_FILE):
    print("找不到说明文件：%s" % NOTES_FILE)
    sys.exit(1)
with open(NOTES_FILE, encoding="utf-8") as f:
    notes = f.read()
if not notes.strip():
    print("说明文件是空的")
    sys.exit(1)

st, rel = api("/repos/%s/releases/tags/%s" % (REPO, TAG))
if st != 200 or not rel:
    print("取 Release 失败：HTTP %s" % st)
    sys.exit(1)

body = WARNING + notes
st, out = api("/repos/%s/releases/%d" % (REPO, rel["id"]), "PATCH", {"body": body})
if st not in (200, 201) or not out:
    print("更新失败：HTTP %s" % st)
    sys.exit(1)

st, again = api("/repos/%s/releases/tags/%s" % (REPO, TAG))
got = again.get("body") or ""
print("已写回 %d 字节" % len(got))
# 逐项核对：警示在、原说明的关键小节也在、没有问号残骸
for must in ("这个版本有缺陷", "## v1.7.0", "进度条不动", "下载管理页"):
    if must not in got:
        print("缺少内容：%s" % must)
        sys.exit(1)
if "??????" in got:
    print("仍有问号残骸")
    sys.exit(1)
print("校验通过：警示 + 原始发布说明齐全，无编码残骸。")
