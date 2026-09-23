#!/usr/bin/env python3
"""把已存在的 tag 移到指定提交上（先删 ref，再按新目标重建）。

背景：v1.7.0 第一次推送时只推上去了 docs 那个提交，tag 就建在它上面。
重推之后 main 已经包含了全部改动，但 tag 还指向那个只有文档的提交 ——
用户点开 Release 看到的是「这次发布什么都不含」。

不能直接 PATCH ref（GitHub 对 tag ref 允许 force 更新，但那样 tag 会指向
一个 commit 而不是 tag 对象，和既有风格不一致）。所以删掉重建。

用法: move-tag.py <tag> <目标提交sha> [说明]
"""
import json
import os
import sys
import urllib.error
import urllib.request

REPO = "308532806/BiliGrab"

PAT = os.environ.get("BILIGRAB_PAT", "").strip()
if not PAT:
    for cand in (os.path.join(os.environ.get("TEMP", ""), "biligrab-pat.txt"),
                 os.path.join(os.path.expanduser("~"), ".biligrab-pat")):
        if os.path.isfile(cand):
            PAT = open(cand, encoding="utf-8").read().strip()
            if PAT:
                break
if not PAT:
    sys.exit("找不到 Token")

if len(sys.argv) < 3:
    sys.exit("用法: move-tag.py <tag> <目标提交sha> [说明]")
TAG, TARGET = sys.argv[1], sys.argv[2]
MSG = sys.argv[3] if len(sys.argv) > 3 else TAG


def api(path, method="GET", payload=None):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    r = urllib.request.Request("https://api.github.com" + path, method=method, data=data)
    r.add_header("Authorization", "token " + PAT)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("User-Agent", "BiliGrab-tag")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            body = resp.read()
            return resp.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        print("  HTTP %d %s %s" % (e.code, method, path))
        print("  " + e.read().decode("utf-8", "replace")[:500])
        return e.code, None


st, target = api("/repos/%s/git/commits/%s" % (REPO, TARGET))
if st != 200 or not target:
    sys.exit("目标提交不在远端：%s" % TARGET)
print("目标提交 %s（%s）" % (TARGET[:12], target["message"].splitlines()[0][:60]))

st, existing = api("/repos/%s/git/ref/tags/%s" % (REPO, TAG))
if st == 200 and existing:
    old = existing["object"]["sha"]
    print("现有 tag 指向 %s（type=%s）" % (old[:12], existing["object"]["type"]))
    if existing["object"]["type"] == "commit" and old == TARGET:
        print("已经指向目标，不用动")
        sys.exit(0)
    st, _ = api("/repos/%s/git/refs/tags/%s" % (REPO, TAG), "DELETE")
    if st not in (204, 200):
        sys.exit("删除旧 tag 失败")
    print("已删除旧 tag")

st, tagobj = api("/repos/%s/git/tags" % REPO, "POST", {
    "tag": TAG, "message": MSG, "object": TARGET, "type": "commit",
})
if st not in (200, 201) or not tagobj:
    sys.exit("建 tag 对象失败")
print("新建 tag 对象 %s" % tagobj["sha"][:12])

st, ref = api("/repos/%s/git/refs" % REPO, "POST",
              {"ref": "refs/tags/" + TAG, "sha": tagobj["sha"]})
if st not in (200, 201) or not ref:
    sys.exit("建 tag ref 失败")

st, back = api("/repos/%s/git/ref/tags/%s" % (REPO, TAG))
st2, obj = api("/repos/%s/git/tags/%s" % (REPO, back["object"]["sha"]))
print("核对：refs/tags/%s -> %s -> commit %s"
      % (TAG, back["object"]["sha"][:12], obj["object"]["sha"][:12]))
if obj["object"]["sha"] != TARGET:
    sys.exit("核对失败：tag 没落在目标提交上")
print("完成")
