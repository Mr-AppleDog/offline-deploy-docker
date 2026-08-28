#!/usr/bin/env bash
set -Eeuo pipefail

# 本机 Docker Registry 镜像保留脚本：默认只预览，--apply 才执行。
# 动态管理所有 *-backend、*-frontend、*-ui 仓库；可用 --purge-repo 手动清空指定仓库。

REGISTRY_URL="http://127.0.0.1:5000"
REGISTRY_CONTAINER="registry"
KEEP_LIMIT=2
PURGE_REPOSITORIES=()
LOCK_FILE="/var/lock/cleanup-registry-images.lock"
APPLY=0

usage() {
  echo "用法：sudo $0 [--apply] [--keep 数量] [--purge-repo 仓库名]"
  echo "默认仅预览；--apply 会短暂停止 Registry、删除旧标签并执行垃圾回收。"
}

while (($#)); do
  case "$1" in
    --apply)
      APPLY=1
      shift
      ;;
    --keep)
      [[ $# -ge 2 ]] || { echo "--keep 缺少数量" >&2; exit 2; }
      KEEP_LIMIT="$2"
      shift 2
      ;;
    --purge-repo)
      [[ $# -ge 2 ]] || { echo "--purge-repo 缺少仓库名" >&2; exit 2; }
      PURGE_REPOSITORIES+=("$2")
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数：$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[[ "$KEEP_LIMIT" =~ ^[0-9]+$ ]] || { echo "--keep 必须是整数" >&2; exit 2; }
((KEEP_LIMIT >= 1 && KEEP_LIMIT <= 20)) || { echo "--keep 必须在 1 到 20 之间" >&2; exit 2; }
((EUID == 0)) || { echo "请使用 sudo 运行此脚本" >&2; exit 2; }

for required in bash docker k3s curl find stat sort awk sed grep realpath flock mktemp basename cut date df seq tail; do
  command -v "$required" >/dev/null || { echo "缺少命令：$required" >&2; exit 2; }
done

exec 9>"$LOCK_FILE"
flock -n 9 || { echo "另一个 Registry 清理任务正在运行" >&2; exit 2; }

TMP_DIR="$(mktemp -d /tmp/registry-cleanup.XXXXXX)"
PLAN_FILE="$TMP_DIR/plan"
BLOCK_FILE="$TMP_DIR/blocked"
ACTIVE_FILE="$TMP_DIR/active"
REPOSITORY_FILE="$TMP_DIR/repositories"
REGISTRY_STOPPED=0

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if ((REGISTRY_STOPPED)); then
    docker start "$REGISTRY_CONTAINER" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$TMP_DIR"
  exit "$status"
}
trap cleanup EXIT INT TERM

valid_repository() {
  [[ "$1" =~ ^[a-z0-9]+([._-][a-z0-9]+)*(/[a-z0-9]+([._-][a-z0-9]+)*)*$ ]]
}

valid_tag() {
  [[ ${#1} -le 128 && "$1" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ ]]
}

for purge_repository in "${PURGE_REPOSITORIES[@]}"; do
  valid_repository "$purge_repository" || { echo "--purge-repo 仓库名不安全：$purge_repository" >&2; exit 2; }
done

is_purge_repository() {
  local candidate="$1" value
  for value in "${PURGE_REPOSITORIES[@]}"; do
    [[ "$candidate" == "$value" ]] && return 0
  done
  return 1
}

storage_source="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/registry"}}{{.Source}}{{end}}{{end}}' "$REGISTRY_CONTAINER")"
[[ -n "$storage_source" ]] || { echo "Registry 没有挂载 /var/lib/registry" >&2; exit 1; }
REPOSITORY_ROOT="$(realpath -e -- "$storage_source/docker/registry/v2/repositories")"
[[ -d "$REPOSITORY_ROOT" && "$REPOSITORY_ROOT" != "/" ]] || { echo "Registry 仓库目录无效" >&2; exit 1; }

safe_path() {
  local candidate resolved
  candidate="$1"
  resolved="$(realpath -m -- "$candidate")"
  case "$resolved" in
    "$REPOSITORY_ROOT"/*) printf '%s\n' "$resolved" ;;
    *) echo "拒绝越界路径：$resolved" >&2; return 1 ;;
  esac
}

collect_active_images() {
  local destination="$1" raw="$TMP_DIR/active-raw"
  : >"$raw"

  k3s kubectl get deployment,statefulset,daemonset,job -A -o jsonpath='{range .items[*]}{range .spec.template.spec.containers[*]}{.image}{"\n"}{end}{range .spec.template.spec.initContainers[*]}{.image}{"\n"}{end}{end}' >>"$raw"
  k3s kubectl get cronjob -A -o jsonpath='{range .items[*]}{range .spec.jobTemplate.spec.template.spec.containers[*]}{.image}{"\n"}{end}{range .spec.jobTemplate.spec.template.spec.initContainers[*]}{.image}{"\n"}{end}{end}' >>"$raw"
  k3s kubectl get pods -A -o jsonpath='{range .items[*]}{range .spec.containers[*]}{.image}{"\n"}{end}{range .spec.initContainers[*]}{.image}{"\n"}{end}{end}' >>"$raw"

  : >"$destination"
  while IFS= read -r image; do
    case "$image" in
      localhost:5000/*) reference="${image#localhost:5000/}" ;;
      127.0.0.1:5000/*) reference="${image#127.0.0.1:5000/}" ;;
      *) continue ;;
    esac
    [[ "$reference" != *@* && "$reference" == *:* ]] || continue
    repository="${reference%:*}"
    tag="${reference##*:}"
    valid_repository "$repository" && valid_tag "$tag" || continue
    printf '%s|%s\n' "$repository" "$tag" >>"$destination"
  done <"$raw"
  sort -u -o "$destination" "$destination"
}

is_active() {
  grep -Fxq -- "$1|$2" "$ACTIVE_FILE"
}

collect_active_images "$ACTIVE_FILE"
: >"$PLAN_FILE"
: >"$BLOCK_FILE"
: >"$REPOSITORY_FILE"

while IFS= read -r -d '' tags_directory; do
  relative="${tags_directory#"$REPOSITORY_ROOT"/}"
  repository="${relative%/_manifests/tags}"
  valid_repository "$repository" || { echo "跳过异常仓库名：$repository" >&2; continue; }
  case "$repository" in
    *-backend|*-frontend|*-ui) printf '%s|%s\n' "$repository" "$tags_directory" >>"$REPOSITORY_FILE" ;;
  esac
done < <(find "$REPOSITORY_ROOT" -type d -path '*/_manifests/tags' -print0)
sort -t '|' -k1,1 -o "$REPOSITORY_FILE" "$REPOSITORY_FILE"

while IFS='|' read -r repository tags_directory; do
  [[ -n "$repository" ]] || continue
  tags_file="$TMP_DIR/tags.$RANDOM.$RANDOM"
  : >"$tags_file"
  while IFS= read -r -d '' tag_directory; do
    tag="$(basename -- "$tag_directory")"
    valid_tag "$tag" || { echo "跳过异常标签名：$repository:$tag" >&2; continue; }
    link="$tag_directory/current/link"
    if [[ -f "$link" ]]; then
      modified="$(stat -c '%Y' -- "$link")"
    else
      modified=0
    fi
    printf '%020d|%s\n' "$modified" "$tag" >>"$tags_file"
  done < <(find "$tags_directory" -mindepth 1 -maxdepth 1 -type d -print0)
  sort -t '|' -k1,1nr -k2,2r -o "$tags_file" "$tags_file"

  if is_purge_repository "$repository"; then
    while IFS='|' read -r modified tag; do
      [[ -n "$tag" ]] || continue
      if is_active "$repository" "$tag"; then
        echo "$repository 仍被 K3s 使用：$tag" >>"$BLOCK_FILE"
      fi
      printf 'PURGE|%s|%s|%s\n' "$repository" "$tag" "$modified" >>"$PLAN_FILE"
    done <"$tags_file"
    rm -f -- "$tags_file"
    continue
  fi

  unset KEEP_TAGS
  declare -A KEEP_TAGS=()
  active_count=0
  while IFS='|' read -r modified tag; do
    [[ -n "$tag" ]] || continue
    if is_active "$repository" "$tag"; then
      KEEP_TAGS["$tag"]=1
      ((active_count += 1))
    fi
  done <"$tags_file"

  if ((active_count > KEEP_LIMIT)); then
    echo "$repository 正被 K3s 使用 $active_count 个标签，超过保留上限 $KEEP_LIMIT" >>"$BLOCK_FILE"
    while IFS='|' read -r modified tag; do
      [[ -n "$tag" ]] && printf 'KEEP|%s|%s|%s\n' "$repository" "$tag" "$modified" >>"$PLAN_FILE"
    done <"$tags_file"
    rm -f -- "$tags_file"
    continue
  fi

  keep_count=$active_count
  while IFS='|' read -r modified tag; do
    [[ -n "$tag" ]] || continue
    if [[ -z "${KEEP_TAGS[$tag]+x}" && $keep_count -lt $KEEP_LIMIT ]]; then
      KEEP_TAGS["$tag"]=1
      ((keep_count += 1))
    fi
  done <"$tags_file"

  while IFS='|' read -r modified tag; do
    [[ -n "$tag" ]] || continue
    if [[ -n "${KEEP_TAGS[$tag]+x}" ]]; then action=KEEP; else action=DELETE; fi
    printf '%s|%s|%s|%s\n' "$action" "$repository" "$tag" "$modified" >>"$PLAN_FILE"
  done <"$tags_file"
  rm -f -- "$tags_file"
done <"$REPOSITORY_FILE"

echo "Registry：$REGISTRY_URL；普通仓库最多保留：$KEEP_LIMIT"
echo "动态匹配：*-backend、*-frontend、*-ui"
if ((${#PURGE_REPOSITORIES[@]})); then
  echo "本次全量清理：${PURGE_REPOSITORIES[*]}"
else
  echo "本次全量清理：无"
fi

current_repository=""
delete_count=0
while IFS='|' read -r action repository tag modified; do
  [[ -n "$repository" ]] || continue
  if [[ "$repository" != "$current_repository" ]]; then
    echo
    echo "[$repository]"
    [[ "$action" == PURGE ]] && echo "  策略：全量清理"
    current_repository="$repository"
  fi
  timestamp="时间未知"
  if ((10#$modified > 0)); then timestamp="$(date -d "@$((10#$modified))" '+%Y-%m-%d %H:%M:%S %z')"; fi
  active_marker=""
  is_active "$repository" "$tag" && active_marker="，K3s 使用中"
  case "$action" in
    KEEP) echo "  保留：$tag（$timestamp$active_marker）" ;;
    DELETE|PURGE)
      echo "  删除：$tag（$timestamp$active_marker）"
      ((delete_count += 1))
      ;;
  esac
done <"$PLAN_FILE"

echo
echo "计划删除标签：$delete_count 个"
if [[ -s "$BLOCK_FILE" ]]; then
  echo "安全检查阻止执行：" >&2
  sed 's/^/  /' "$BLOCK_FILE" >&2
  ((APPLY == 0)) || exit 1
fi

if ((APPLY == 0)); then
  echo "当前是预览模式；确认后使用 --apply 执行"
  exit 0
fi

((delete_count > 0)) || { echo "没有需要删除的标签"; exit 0; }
[[ "$(docker inspect --format '{{.State.Running}}' "$REGISTRY_CONTAINER")" == true ]] || { echo "Registry 容器未运行" >&2; exit 1; }
registry_image="$(docker inspect --format '{{.Config.Image}}' "$REGISTRY_CONTAINER")"
[[ -n "$registry_image" && "$registry_image" != -* ]] || { echo "Registry 镜像名无效" >&2; exit 1; }
free_before="$(df -B1 / | awk 'NR==2 {print $4}')"

echo "进入 Registry 维护窗口……"
docker stop --time 30 "$REGISTRY_CONTAINER" >/dev/null
REGISTRY_STOPPED=1

# 停止 Registry 后再次确认，防止生成计划后某个旧标签刚被部署。
SECOND_ACTIVE_FILE="$TMP_DIR/active-second"
collect_active_images "$SECOND_ACTIVE_FILE"
while IFS='|' read -r action repository tag modified; do
  case "$action" in DELETE|PURGE) ;; *) continue ;; esac
  if grep -Fxq -- "$repository|$tag" "$SECOND_ACTIVE_FILE"; then
    echo "待删除标签刚被 K3s 使用，已中止：$repository:$tag" >&2
    exit 1
  fi
done <"$PLAN_FILE"

cut -d '|' -f1-2 "$PLAN_FILE" | awk -F '|' '$1=="PURGE" {print $2}' | sort -u >"$TMP_DIR/purge-repositories"
while IFS= read -r repository; do
  [[ -n "$repository" ]] || continue
  valid_repository "$repository" || { echo "仓库名校验失败：$repository" >&2; exit 1; }
  target="$(safe_path "$REPOSITORY_ROOT/$repository")"
  if [[ -e "$target" ]]; then
    echo "删除整个仓库：$repository"
    rm -rf -- "$target"
  fi
done <"$TMP_DIR/purge-repositories"

while IFS='|' read -r action repository tag modified; do
  [[ "$action" == DELETE ]] || continue
  valid_repository "$repository" && valid_tag "$tag" || { echo "路径参数校验失败" >&2; exit 1; }
  target="$(safe_path "$REPOSITORY_ROOT/$repository/_manifests/tags/$tag")"
  if [[ -e "$target" ]]; then
    echo "删除标签：$repository:$tag"
    rm -rf -- "$target"
  fi
done <"$PLAN_FILE"

gc_log="/var/log/registry-gc-$(date '+%Y%m%d-%H%M%S').log"
echo "执行 Registry 垃圾回收，完整日志：$gc_log"
docker run --rm --volumes-from "$REGISTRY_CONTAINER" "$registry_image" \
  garbage-collect --delete-untagged /etc/docker/registry/config.yml >"$gc_log" 2>&1
grep -E 'blobs marked|eligible for deletion' "$gc_log" | tail -n 3 || true

docker start "$REGISTRY_CONTAINER" >/dev/null
REGISTRY_STOPPED=0
for _ in $(seq 1 30); do
  curl -fsS --max-time 2 "$REGISTRY_URL/v2/" >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS --max-time 2 "$REGISTRY_URL/v2/" >/dev/null || { echo "Registry 健康检查失败" >&2; exit 1; }

free_after="$(df -B1 / | awk 'NR==2 {print $4}')"
released=$((free_after - free_before))
((released < 0)) && released=0
echo "清理完成，根分区可用空间增加约 $((released / 1024 / 1024)) MiB"
