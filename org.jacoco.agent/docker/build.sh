#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
JAR="$REPO_ROOT/org.jacoco.agent/target/classes/jacocoagent.jar"
IMAGE="harbor.fzzqft.com/qa/jacocoagent:latest"
PLATFORMS="linux/amd64,linux/arm64"
BUILD_INFO="$SCRIPT_DIR/build-info.txt"

SKIP_MAVEN=0
PUSH=1

usage() {
	cat <<'EOF'
用法: docker/build.sh [选项]

选项:
  --skip-maven   跳过 Maven 构建，直接使用已有 jacocoagent.jar
  --no-push      只构建镜像，不推送（调试用）
  -h, --help     显示帮助
EOF
}

while [ $# -gt 0 ]; do
	case "$1" in
		--skip-maven) SKIP_MAVEN=1 ;;
		--no-push) PUSH=0 ;;
		-h | --help) usage; exit 0 ;;
		*)
			echo "未知参数: $1" >&2
			usage
			exit 1
			;;
	esac
	shift
done

JAVA_BIN="java"
if [ -n "${JAVA_HOME:-}" ]; then
	JAVA_BIN="$JAVA_HOME/bin/java"
fi

if command -v shasum >/dev/null 2>&1; then
	SHA_CMD="shasum -a 256"
else
	SHA_CMD="sha256sum"
fi

echo "== 1/6 环境检查 =="
command -v docker >/dev/null 2>&1 || { echo "未找到 docker" >&2; exit 1; }
docker buildx version >/dev/null 2>&1 || { echo "未找到 docker buildx" >&2; exit 1; }
if [ ! -x "$JAVA_BIN" ]; then
	echo "未找到 Java: $JAVA_BIN（请设置 JAVA_HOME）" >&2
	exit 1
fi
JAVA_VERSION="$("$JAVA_BIN" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
JAVA_MAJOR="$(echo "$JAVA_VERSION" | awk -F. '{ if ($1 == "1") print $2; else print $1 }')"
if [ "${JAVA_MAJOR:-0}" -lt 17 ]; then
	echo "需要 JDK 17+，当前: ${JAVA_VERSION:-unknown}（请设置 JAVA_HOME）" >&2
	exit 1
fi
echo "JDK: $JAVA_VERSION"

if [ "$SKIP_MAVEN" -eq 0 ]; then
	echo "== 2/6 Maven 构建 =="
	command -v mvn >/dev/null 2>&1 || { echo "未找到 mvn" >&2; exit 1; }
	(cd "$REPO_ROOT" && mvn -pl org.jacoco.agent,org.jacoco.agent.rt -am package -DskipTests)
else
	echo "== 2/6 跳过 Maven（--skip-maven）=="
fi

echo "== 3/6 校验产物 =="
[ -f "$JAR" ] || { echo "产物不存在: $JAR" >&2; exit 1; }
unzip -t "$JAR" >/dev/null 2>&1 || { echo "jar 完整性校验失败: $JAR" >&2; exit 1; }
unzip -p "$JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -q '^Premain-Class:' || {
	echo "jar 缺少 Premain-Class: $JAR" >&2
	exit 1
}
JAR_SHA="$($SHA_CMD "$JAR" | awk '{print $1}')"
echo "jar:    $JAR"
echo "sha256: $JAR_SHA"
cp "$JAR" "$SCRIPT_DIR/jacocoagent.jar"

echo "== 4/6 构建镜像 =="
OLD_DIGEST=""
if [ -f "$BUILD_INFO" ]; then
	OLD_DIGEST="$(awk -F= '/^image_digest=/ {print $2}' "$BUILD_INFO" | tail -1)"
fi

if [ "$PUSH" -eq 1 ]; then
	docker buildx build --platform "$PLATFORMS" --tag "$IMAGE" --push "$SCRIPT_DIR"
else
	docker buildx build --platform linux/amd64 --tag "$IMAGE" --load "$SCRIPT_DIR"
fi

DIGEST="unknown"
if [ "$PUSH" -eq 1 ]; then
	echo "== 5/6 推送后校验 =="
	DIGEST="$(docker buildx imagetools inspect "$IMAGE" | awk '/Digest:/ {print $2; exit}')"
	echo "image:  $IMAGE"
	echo "digest: $DIGEST"
	if [ -n "$OLD_DIGEST" ] && [ "$OLD_DIGEST" = "$DIGEST" ]; then
		echo "警告: 镜像 digest 与上次相同（$DIGEST）" >&2
	fi
	docker pull --platform linux/amd64 "$IMAGE" >/dev/null
	IMAGE_SHA="$(docker run --rm --platform linux/amd64 "$IMAGE" sha256sum /jacocoagent.jar | awk '{print $1}')"
	echo "镜像内 sha256: $IMAGE_SHA"
	if [ "$IMAGE_SHA" != "$JAR_SHA" ]; then
		echo "错误: 镜像内 jar 与本地不一致" >&2
		exit 1
	fi
	echo "校验通过: 镜像内 jar 与本地一致"
else
	echo "== 5/6 跳过推送后校验（--no-push）=="
fi

echo "== 6/6 写入 build-info.txt =="
GIT_COMMIT="$(cd "$REPO_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo unknown)"
cat >"$BUILD_INFO" <<EOF
date=$(date '+%Y-%m-%dT%H:%M:%S%z')
git_commit=$GIT_COMMIT
jar_sha256=$JAR_SHA
image=$IMAGE
image_digest=$DIGEST
platforms=$PLATFORMS
EOF
cat "$BUILD_INFO"
echo "完成"
