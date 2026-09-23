#!/usr/bin/env python3
"""把已构建的 APK 作为 GitHub Release 附件发布。

背景
----
GitHub 不能通过 Git Data API 上传 Release 附件 —— 二进制走的是另一个域名
（uploads.github.com），必须单独发一次请求。这个脚本就做那一件事：
拿 tag 找到（或创建）Release，然后把 dist/ 里的 APK 挂上去。

为什么不写在 push-via-api.py 里
------------------------------
那个脚本只碰 Git 对象，跑得快、失败也干净。附件上传是几百毫秒到几十秒的
网络传输，混在一起会让「代码推上去了但附件没上去」变得难以判断 ——
这两件事的成功与否必须能分别看出来。

用法
----
    python tools/release-apk.py v1.7.0 dist/BiliGrab-1.7.0.apk [说明文件]

Token 的取法和 push-via-api.py 一致：环境变量 BILIGRAB_PAT、
%TEMP%\\biligrab-pat.txt、~/.biligrab-pat，按顺序找。
"""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO = "308532806/BiliGrab"

PAT = os.environ.get("BILIGRAB_PAT", "").strip()
if not PAT:
    for cand in (os.path.join(os.environ.get("TEMP", ""), "biligrab-pat.txt"),
                 os.path.join(os.path.expanduser("~"), ".biligrab-pat")):
        if os.path.isfile(cand):
            with open(cand, encoding="utf-8") as f:
                PAT = f.read().strip()
            if PAT:
                break
if not PAT:
    print("找不到 GitHub Token（BILIGRAB_PAT / %TEMP%\\biligrab-pat.txt / ~/.biligrab-pat）")
    sys.exit(1)

if len(sys.argv) < 3:
    print("用法: release-apk.py <tag> <apk路径> [说明文件]")
    sys.exit(1)

TAG = sys.argv[1]
APK = sys.argv[2]
NOTES_FILE = sys.argv[3] if len(sys.argv) > 3 else ""

if not os.path.isfile(APK):
    print("找不到 APK：%s" % APK)
    sys.exit(1)

NOTES = ""
if NOTES_FILE and os.path.isfile(NOTES_FILE):
    with open(NOTES_FILE, encoding="utf-8") as f:
        NOTES = f.read()
if not NOTES:
    NOTES = "BiliGrab %s\n\n安装：adb install -r %s" % (TAG, os.path.basename(APK))


def api(path, method="GET", payload=None, host="https://api.github.com",
        raw_body=None, content_type=None):
    """普通 JSON 调用；raw_body 用于上传二进制附件。"""
    url = host + path
    if raw_body is not None:
        data = raw_body
    elif payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    else:
        data = None
    r = urllib.request.Request(url, method=method, data=data)
    r.add_header("Authorization", "token " + PAT)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("User-Agent", "BiliGrab-release")
    if content_type:
        r.add_header("Content-Type", content_type)
    elif data is not None:
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=600) as resp:
            body = resp.read()
            return resp.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        print("  HTTP %d on %s %s" % (e.code, method, path))
        print("  " + body[:800])
        return e.code, None


print("=== 查这个 tag 的 Release ===")
status, rel = api("/repos/%s/releases/tags/%s" % (REPO, TAG))
if status == 200 and rel:
    print("  已存在 Release id=%s" % rel["id"])
else:
    # 404 有两种情况，必须分开处理。
    #
    # 一种是「这个 tag 还真没发过」。另一种是**发过，但 tag 被移动过**
    # （move-tag.py 会把 tag 删掉重建），于是旧 Release 跟 tag 脱钩，
    # 按 tag 查就 404 了。这时如果直接新建，远端会出现两个同 tag_name 的
    # Release：v1.7.1 那轮真的这样了，用户点进去看到的是哪一个全看运气，
    # 而旧的里面是修复前的 APK。
    #
    # 所以先按 tag_name 在列表里捞一遍：捞到就是脱钩的那个，改它，
    # 不要新建。
    print("  按 tag 查不到，先在列表里找有没有脱钩的同名 Release")
    stale = None
    st2, all_rels = api("/repos/%s/releases?per_page=100" % REPO)
    if st2 == 200 and all_rels:
        for r in all_rels:
            if r.get("tag_name") == TAG:
                stale = r
                break
    if stale:
        rel = stale
        print("  找到脱钩的 Release id=%s（tag 指向 %s），改它而不是新建"
              % (rel["id"], rel.get("target_commitish")))
        # 顺手把说明补回去
        st3, rel = api("/repos/%s/releases/%d" % (REPO, rel["id"]), "PATCH",
                       {"body": NOTES})
        if st3 not in (200, 201) or not rel:
            print("  更新说明失败")
            sys.exit(1)
        print("  说明已更新")
    else:
        print("  确实没发过，创建一个")
        status, rel = api("/repos/%s/releases" % REPO, "POST", {
            "tag_name": TAG,
            "name": "BiliGrab %s" % TAG,
            "body": NOTES,
            "draft": False,
            "prerelease": False,
        })
        if status not in (200, 201) or not rel:
            print("  创建 Release 失败")
            sys.exit(1)
        print("  已创建 Release id=%s" % rel["id"])

name = os.path.basename(APK)
size = os.path.getsize(APK)

print()
print("=== 检查同名附件 ===")
status, assets = api("/repos/%s/releases/%s/assets" % (REPO, rel["id"]))
if status == 200 and assets:
    for a in assets:
        if a["name"] == name:
            # 同名附件不能覆盖，必须先删。否则上传会 422，
            # 而旧的那个二进制会继续挂在页面上，看起来像发布成功了。
            print("  已有同名 %s（%d 字节），先删掉" % (name, a["size"]))
            st, _ = api("/repos/%s/releases/assets/%s" % (REPO, a["id"]), "DELETE")
            if st not in (204, 200):
                print("  删除失败，中止")
                sys.exit(1)

print()
print("=== 上传 %s（%d 字节）===" % (name, size))
with open(APK, "rb") as f:
    blob = f.read()

# 走 uploads.github.com，不是 api.github.com。用 URL 参数带 name，
# 上传超时给到 10 分钟：国内到 GitHub 的上行可能很慢。
status, asset = api(
    "/repos/%s/releases/%s/assets?name=%s" % (REPO, rel["id"], urllib.parse.quote(name)),
    "POST", host="https://uploads.github.com", raw_body=blob,
    content_type="application/vnd.android.package-archive")
if status not in (200, 201) or not asset:
    print("  上传失败")
    sys.exit(1)

print("  附件 id=%s" % asset["id"])
print("  下载地址 %s" % asset["browser_download_url"])

# 回读一次，确认附件真的在 Release 上、大小对得上。
# 只信上传响应是不够的 —— 之前有过「返回 201 但页面上没有」的情况，
# 而 Release 页面才是用户实际看到的东西。
print()
print("=== 回读核对 ===")
status, assets2 = api("/repos/%s/releases/%s/assets" % (REPO, rel["id"]))
found = None
if status == 200 and assets2:
    for a in assets2:
        if a["name"] == name:
            found = a
if not found:
    print("  回读没找到附件")
    sys.exit(1)
if found["size"] != size:
    print("  大小对不上：远端 %d，本地 %d" % (found["size"], size))
    sys.exit(1)
print("  远端附件 %s，%d 字节，状态 %s" % (found["name"], found["size"], found["state"]))

# 最后确认 Release 没被搞成草稿、也没跟 tag 脱钩。
#
# 这一步是吃过亏才加的：move-tag.py 会先删 tag 再重建，而 GitHub 在 tag
# 被删的瞬间会把 Release 变成 draft、tag_name 换成一串
# `untagged-<hash>`。此时如果上传附件，附件是传上去了，但下载地址变成
# `releases/download/untagged-xxx/...`，而且 Release 在页面上**看不见**
# —— 因为它还是草稿。工具照样报「发布完成」，只有去查 API 才发现。
# 所以这里主动修一次并核对。
status, nowrel = api("/repos/%s/releases/%d" % (REPO, rel["id"]))
if status != 200 or not nowrel:
    print("  回读 Release 失败")
    sys.exit(1)
if nowrel.get("draft") or nowrel.get("tag_name") != TAG:
    print("  Release 是草稿或跟 tag 脱钩（tag_name=%r），修复"
          % nowrel.get("tag_name"))
    status, fixed = api("/repos/%s/releases/%d" % (REPO, rel["id"]), "PATCH", {
        "tag_name": TAG, "name": "BiliGrab %s" % TAG,
        "body": NOTES, "draft": False, "prerelease": False,
    })
    if status not in (200, 201) or not fixed:
        print("  修复失败")
        sys.exit(1)
    nowrel = fixed
if nowrel.get("draft"):
    print("  仍然是草稿，中止")
    sys.exit(1)
if nowrel.get("tag_name") != TAG:
    print("  tag_name 仍是 %r，中止" % nowrel.get("tag_name"))
    sys.exit(1)
print("  Release 状态正常：tag=%s，draft=False，附件 %d 个"
      % (nowrel["tag_name"], len(nowrel.get("assets") or [])))
print()
print("发布完成：%s" % found["browser_download_url"])
