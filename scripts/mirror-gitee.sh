#!/usr/bin/env bash
# 把本仓库镜像到 Gitee：没有仓库就建、推 main 和所有 tag、可选把当前版本的安装包挂到 Gitee 发行版上。
#
# 用法：
#   GITEE_TOKEN=xxxx ./scripts/mirror-gitee.sh                    # 建仓库（没有时）+ 推 main 和 tag
#   GITEE_TOKEN=xxxx ./scripts/mirror-gitee.sh --release v0.3.35   # 另外建发行版并上传 release-artifacts 里的 APK
#
# 令牌：Gitee → 设置 → 私人令牌，勾选 projects 权限，只给这一台机器用，用完可以撤销。
# 可选环境变量：GITEE_REPO（默认 StudyOnTV）、GITEE_LOGIN（默认取令牌对应用户名）。
set -euo pipefail

TOKEN="${GITEE_TOKEN:-}"
REPO="${GITEE_REPO:-StudyOnTV}"
RELEASE_TAG=""
[[ "${1:-}" == "--release" ]] && RELEASE_TAG="${2:-}"

if [[ -z "$TOKEN" ]]; then
  echo "缺少 GITEE_TOKEN：Gitee → 设置 → 私人令牌，勾选 projects" >&2
  exit 1
fi

api() { curl -q -sS -H "Content-Type: application/json" "$@"; }

LOGIN="${GITEE_LOGIN:-$(api "https://gitee.com/api/v5/user?access_token=$TOKEN" | python -c 'import sys,json;print(json.load(sys.stdin).get("login",""))')}"
if [[ -z "$LOGIN" ]]; then
  echo "令牌无效，或者没有读取用户信息的权限" >&2
  exit 1
fi
echo "Gitee 账号：$LOGIN，仓库：$REPO"

# 1. 建仓库（已存在时接口会报已存在，直接跳过）
created=$(api -X POST "https://gitee.com/api/v5/user/repos" \
  -d "{\"access_token\":\"$TOKEN\",\"name\":\"$REPO\",\"has_issues\":true,\"has_wiki\":false,\"private\":false,\"auto_init\":false}" \
  | python -c 'import sys,json;d=json.load(sys.stdin);print("ok" if d.get("full_name") else d.get("message",""))')
echo "建仓库：$created"

# Gitee 新仓库可能默认是私有的（建仓库时传 private=false 不一定生效），这里再显式改一次公开
api -X PATCH "https://gitee.com/api/v5/repos/$LOGIN/$REPO" \
  -d "{\"access_token\":\"$TOKEN\",\"private\":false,\"name\":\"$REPO\"}" >/dev/null || true

REMOTE="https://oauth2:$TOKEN@gitee.com/$LOGIN/$REPO.git"
# 2. 推 main 与所有 tag（不打印带令牌的地址）
git push "$REMOTE" main --tags 2>&1 | sed "s#$TOKEN#***#g"
git remote remove gitee >/dev/null 2>&1 || true
git remote add gitee "https://gitee.com/$LOGIN/$REPO.git"

# 3. 可选：Gitee 发行版 + 安装包附件（国内直接下 GitHub Release 经常很慢）
if [[ -n "$RELEASE_TAG" ]]; then
  # 发行版说明优先用 release-artifacts/RELEASE_NOTES-<tag>.md（和 GitHub Release 同一份文案），没有就用 tag 备注
  desc_file="release-artifacts/RELEASE_NOTES-$RELEASE_TAG.md"
  if [[ -f "$desc_file" ]]; then desc="$(cat "$desc_file")"; else desc="$(git tag -l --format='%(contents)' "$RELEASE_TAG")"; fi
  # Gitee 建发行版必须给 target_commitish（指向这个 tag 的提交），只给 tag_name 会报 missing
  commit="$(git rev-list -n1 "$RELEASE_TAG")"
  payload=$(python - "$TOKEN" "$RELEASE_TAG" "$desc" "$commit" <<'PY'
import json,sys
token,tag,body,commit=sys.argv[1],sys.argv[2],sys.argv[3],sys.argv[4]
print(json.dumps({"access_token":token,"tag_name":tag,"name":tag,"body":body,"target_commitish":commit,"prerelease":False}))
PY
)
  rid=$(api -X POST "https://gitee.com/api/v5/repos/$LOGIN/$REPO/releases" -d "$payload" \
    | python -c 'import sys,json;d=json.load(sys.stdin);print(d.get("id",""))')
  if [[ -z "$rid" ]]; then
    echo "发行版创建失败（该 tag 可能已有发行版），跳过附件" >&2
  else
    for f in release-artifacts/*"$RELEASE_TAG".apk; do
      [[ -f "$f" ]] || continue
      echo "上传附件：$(basename "$f")"
      curl -q -sS -X POST "https://gitee.com/api/v5/repos/$LOGIN/$REPO/releases/$rid/attach_files" \
        -F "access_token=$TOKEN" -F "file=@$f" >/dev/null
    done
  fi
fi

echo "完成：https://gitee.com/$LOGIN/$REPO"
