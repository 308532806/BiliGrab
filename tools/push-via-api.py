"""
通过 GitHub Git Data API 推送一个本地已有的提交。

为什么要这么绕：这台 PC 现在连不上 github.com:443（DNS 能解析，TCP 不通），
所以 `git push` 走不了。但 api.github.com 是通的，而 Git Data API 提供的
正是 push 的底层动作 —— 建 blob、建 tree、建 commit、移动 ref。
等于手工做一遍 git push 做的事。

前提：父提交必须已经在远端。这里父提交是 c3a28b7，之前推成功过。
"""

import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

# Token 绝不写进仓库。
#
# 曾经把 PAT 直接写在源码里，结果 GitHub 的 secret scanning 在推送时
# 直接 422 拒掉（"Secret detected in content"）—— 它拦得对。
# 现在按顺序从三处找：环境变量、%TEMP%\biligrab-pat.txt、~/.biligrab-pat。
# 三者都没有就退出，不做任何猜测。
PAT = os.environ.get("BILIGRAB_PAT", "").strip()
if not PAT:
    for cand in (os.path.join(os.environ.get("TEMP", ""), "biligrab-pat.txt"),
                 os.path.join(os.path.expanduser("~"), ".biligrab-pat")):
        try:
            with open(cand, encoding="utf-8") as f:
                PAT = f.read().strip()
            if PAT:
                break
        except OSError:
            continue
if not PAT:
    print("找不到 GitHub Token。任选一种方式提供：")
    print("  $env:BILIGRAB_PAT = 'ghp_...'")
    print(r"  或写到 %TEMP%\biligrab-pat.txt")
    sys.exit(1)

REPO = "308532806/BiliGrab"
BRANCH = "main"
REPO_DIR = r"E:\Deepseek工作目录\BiliGrab"
TAG = "v1.5.1"
TAG_MSG = "v1.5.1：4K 权限提示、YouTube 直链沿用 yt-dlp 请求头"


def api(path, method="GET", payload=None):
    url = "https://api.github.com" + path
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    r = urllib.request.Request(url, method=method, data=data)
    r.add_header("Authorization", "token " + PAT)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("User-Agent", "BiliGrab-push")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            body = resp.read()
            return resp.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        print("  HTTP %d on %s %s" % (e.code, method, path))
        print("  " + body[:600])
        return e.code, None


def git(*args):
    out = subprocess.run(["git"] + list(args), cwd=REPO_DIR,
                         capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0:
        raise RuntimeError("git %s 失败: %s" % (" ".join(args), out.stderr))
    return out.stdout.strip()


print("=== 本地提交信息 ===")
head = git("rev-parse", "HEAD")
local_parent = git("rev-parse", "HEAD~1")
message = git("show", "--no-patch", "--format=%B", "HEAD")
author_name = git("show", "--no-patch", "--format=%an", "HEAD")
author_email = git("show", "--no-patch", "--format=%ae", "HEAD")
author_date = git("show", "--no-patch", "--format=%aI", "HEAD")
changed = [l.split("\t", 1)[1] for l in git("diff", "--name-status", "HEAD~1", "HEAD").splitlines() if l.strip()]

print("  HEAD   %s" % head)
print("  本地父 %s" % local_parent)
print("  作者   %s <%s>" % (author_name, author_email))
print("  日期   %s" % author_date)
print("  文件   %d 个" % len(changed))

# 父提交要取**远端**的 main HEAD，不能直接用本地的 HEAD~1。
#
# 走 API 推过一次之后，远端那个 commit 是服务端重新构造的，SHA 和本地不一样
# （内容相同、对象不同）。此时再拿本地 HEAD~1 当父，GitHub 会找不到这个对象。
# 两个提交树的**内容**是一致的，所以「本地这次改了什么」照样可以套到远端树上。
print()
print("=== 取远端 main HEAD 作为父提交 ===")
status, rb = api("/repos/%s/git/ref/heads/%s" % (REPO, BRANCH))
if status != 200 or not rb:
    sys.exit(1)
parent = rb["object"]["sha"]
print("  远端 main %s" % parent)
if parent != local_parent:
    print("  （与本地父 %s 不同 —— 属正常，见脚本内注释）" % local_parent[:12])

status, pc = api("/repos/%s/git/commits/%s" % (REPO, parent))
if status != 200 or not pc:
    print("  父提交不在远端，无法走这条路")
    sys.exit(1)
base_tree = pc["tree"]["sha"]
print("  父提交树 %s" % base_tree)

print()
print("=== 上传 blob ===")
entries = []
for rel in changed:
    full = os.path.join(REPO_DIR, rel.replace("/", os.sep))
    with open(full, "rb") as f:
        raw = f.read()
    try:
        text = raw.decode("utf-8")
        payload = {"content": text, "encoding": "utf-8"}
        kind = "utf-8"
    except UnicodeDecodeError:
        payload = {"content": base64.b64encode(raw).decode("ascii"), "encoding": "base64"}
        kind = "base64"
    status, blob = api("/repos/%s/git/blobs" % REPO, "POST", payload)
    if status not in (200, 201) or not blob:
        print("  %s 上传失败" % rel)
        sys.exit(1)
    entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": blob["sha"]})
    print("  %-58s %s (%s, %d 字节)" % (rel, blob["sha"][:10], kind, len(raw)))

print()
print("=== 建 tree ===")
status, tree = api("/repos/%s/git/trees" % REPO, "POST",
                   {"base_tree": base_tree, "tree": entries})
if status not in (200, 201) or not tree:
    sys.exit(1)
print("  tree %s" % tree["sha"])

print()
print("=== 建 commit ===")
status, commit = api("/repos/%s/git/commits" % REPO, "POST", {
    "message": message,
    "tree": tree["sha"],
    "parents": [parent],
    "author": {"name": author_name, "email": author_email, "date": author_date},
    "committer": {"name": author_name, "email": author_email, "date": author_date},
})
if status not in (200, 201) or not commit:
    sys.exit(1)
print("  commit %s" % commit["sha"])

print()
print("=== 移动 refs/heads/%s ===")
status, ref = api("/repos/%s/git/refs/heads/%s" % (REPO, BRANCH), "PATCH",
                  {"sha": commit["sha"], "force": False})
if status != 200 or not ref:
    sys.exit(1)
print("  %s -> %s" % (ref["ref"], ref["object"]["sha"]))

print()
print("=== 建 tag ===")
status, tagobj = api("/repos/%s/git/tags" % REPO, "POST", {
    "tag": TAG,
    "message": TAG_MSG,
    "object": commit["sha"],
    "type": "commit",
    "tagger": {"name": author_name, "email": author_email, "date": author_date},
})
if status not in (200, 201) or not tagobj:
    sys.exit(1)
status, tagref = api("/repos/%s/git/refs" % REPO, "POST",
                     {"ref": "refs/tags/" + TAG, "sha": tagobj["sha"]})
if status == 422:
    # 这个 tag 已经存在。多数情况是上一次跑已经建过 —— 不算失败，
    # 但要把话说出来：如果它指向的commit 不是这次的，说明同名 tag 指向别处。
    st2, existing = api("/repos/%s/git/ref/tags/%s" % (REPO, TAG))
    if st2 == 200 and existing:
        cur = existing["object"]["sha"]
        if cur == tagobj["sha"]:
            print("  refs/tags/%s 已存在且指向同一个 tag 对象，跳过" % TAG)
        else:
            print("  refs/tags/%s 已存在，指向 %s（本次新对象是 %s）"
                  % (TAG, cur[:12], tagobj["sha"][:12]))
else:
    if status not in (200, 201) or not tagref:
        sys.exit(1)
    print("  refs/tags/%s -> %s" % (TAG, tagobj["sha"]))

print()
print("推送完成：%s" % commit["sha"])
