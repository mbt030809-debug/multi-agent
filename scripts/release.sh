#!/usr/bin/env bash
# 사용: bash scripts/release.sh [태그]  (예: v0.8.48)
# 개발 브랜치를 main에 머지하고 푸시(+태그). 토큰은 환경변수 GITHUB_TOKEN 사용(파일 저장 안 함).
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="mbt030809-debug/multi-agent"
BRANCH="${DEV_BRANCH:-claude/happy-dirac-7i5ch2}"
TAG="${1:-}"

if [ -z "${GITHUB_TOKEN:-}" ]; then
  echo "GITHUB_TOKEN 환경변수가 없습니다. 예: export GITHUB_TOKEN=<토큰>" >&2
  exit 1
fi
URL="https://x-access-token:${GITHUB_TOKEN}@github.com/${REPO}.git"

echo "▶ 가져오기: $BRANCH"
git -c credential.helper= fetch "$URL" "$BRANCH:refs/remotes/origin/$BRANCH"
git checkout main
git -c credential.helper= pull --ff-only "$URL" main
git merge --no-edit "origin/$BRANCH"

if [ -n "$TAG" ]; then
  git tag -f "$TAG"
  echo "▶ 푸시: main + $TAG"
  git -c credential.helper= push "$URL" main "$TAG"
else
  echo "▶ 푸시: main"
  git -c credential.helper= push "$URL" main
fi
echo "✅ 완료 — Actions 탭에서 빌드를 확인하세요."
