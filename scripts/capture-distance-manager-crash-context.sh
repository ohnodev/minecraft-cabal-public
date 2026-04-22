#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${ENV_FILE:-/etc/default/minecraft-cabal}"
if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
fi

SERVER_DIR="${SERVER_DIR:-/root/minecraft-cabal/server}"
CRASH_DIR="${CRASH_DIR:-${SERVER_DIR}/crash-reports}"
LOG_DIR="${LOG_DIR:-${SERVER_DIR}/logs}"
CONTEXT_ROOT="${CONTEXT_ROOT:-${LOG_DIR}/crash-context}"
STATE_FILE="${STATE_FILE:-${CONTEXT_ROOT}/.last-distance-manager-report}"
COMPOSE_SERVICE="${COMPOSE_SERVICE:-minecraft}"
RETAIN="${RETAIN:-20}"
JAVA_BIN="${JAVA_BIN:-/usr/lib/jvm/java-25-openjdk-amd64/bin/java}"

if [[ ! "${RETAIN}" =~ ^[0-9]+$ ]]; then
  echo "[crash-context] warning: invalid RETAIN='${RETAIN}', using default 20" >&2
  RETAIN=20
elif [[ "${RETAIN}" -lt 1 ]]; then
  echo "[crash-context] warning: RETAIN='${RETAIN}' is less than 1, clamping to 1" >&2
  RETAIN=1
fi

mkdir -p "${CONTEXT_ROOT}"

latest_report=""
while IFS= read -r -d '' report_path; do
  latest_report="${report_path}"
  break
done < <(
  find "${CRASH_DIR}" -maxdepth 1 -type f -name 'crash-*-server.txt' -printf '%T@ %p\0' 2>/dev/null \
    | sort -z -nr \
    | cut -z -d ' ' -f2-
)
if [[ -z "${latest_report}" || ! -f "${latest_report}" ]]; then
  exit 0
fi

if ! grep -Fq 'ReferenceOpenHashSet$SetIterator.next' "${latest_report}"; then
  exit 0
fi

if ! grep -Fq 'DistanceManager.runAllUpdates' "${latest_report}"; then
  exit 0
fi

report_key="$(basename "${latest_report}"):$(
  stat -c %Y "${latest_report}" 2>/dev/null || echo 0
)"
if [[ -f "${STATE_FILE}" ]] && [[ "$(cat "${STATE_FILE}")" == "${report_key}" ]]; then
  exit 0
fi

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
bundle_dir="${CONTEXT_ROOT}/${stamp}-distance-manager-npe"
mkdir -p "${bundle_dir}"

capture_ok=1

if ! cp -f "${latest_report}" "${bundle_dir}/"; then
  capture_ok=0
fi
if ! cp -f "${LOG_DIR}/latest.log" "${bundle_dir}/latest.log"; then
  echo "[crash-context] warning: skipped latest.log capture (${LOG_DIR}/latest.log unreadable or missing)" >&2
fi

if ! {
  echo "captured_at_utc=${stamp}"
  echo "service=${COMPOSE_SERVICE}"
  echo "signature=ReferenceOpenHashSet\$SetIterator.next + DistanceManager.runAllUpdates"
  echo "report_path=${latest_report}"
  echo "report_key=${report_key}"
  echo ""
  echo "--- uname ---"
  uname -a || true
  echo ""
  echo "--- java version ---"
  "${JAVA_BIN}" -version 2>&1 || true
  echo ""
  echo "--- docker compose ps ---"
  (cd /root/minecraft-cabal && docker compose ps "${COMPOSE_SERVICE}") || true
} > "${bundle_dir}/context.txt"; then
  capture_ok=0
fi

(cd /root/minecraft-cabal && docker compose logs --tail=400 "${COMPOSE_SERVICE}") > "${bundle_dir}/journal-tail.log" 2>/dev/null || true
ls -la "${SERVER_DIR}/mods" > "${bundle_dir}/mods-list.txt" 2>/dev/null || true

{
  for jar in "${SERVER_DIR}"/mods/*.jar; do
    [[ -f "${jar}" ]] || continue
    sha256sum "${jar}"
  done
} > "${bundle_dir}/mods-sha256.txt" 2>/dev/null || true

if [[ "${capture_ok}" -eq 1 ]]; then
  state_tmp="${STATE_FILE}.tmp.$$"
  cleanup_state_tmp() {
    rm -f "${state_tmp}" >/dev/null 2>&1 || true
  }
  trap cleanup_state_tmp EXIT

  if ! printf '%s\n' "${report_key}" > "${state_tmp}"; then
    echo "[crash-context] failed to write dedupe temp state file ${state_tmp}" >&2
    exit 1
  fi
  sync >/dev/null 2>&1 || true
  if ! mv -f "${state_tmp}" "${STATE_FILE}"; then
    echo "[crash-context] failed to atomically update dedupe state ${STATE_FILE}" >&2
    exit 1
  fi
  trap - EXIT

  while IFS= read -r -d '' old_bundle; do
    [[ -n "${old_bundle}" ]] || continue
    rm -rf "${old_bundle}" >/dev/null 2>&1 || true
  done < <(
    find "${CONTEXT_ROOT}" -mindepth 1 -maxdepth 1 -type d -name '*-distance-manager-npe' -printf '%T@ %p\0' 2>/dev/null \
      | sort -z -nr \
      | cut -z -d ' ' -f2- \
      | tail -z -n +$((RETAIN + 1))
  )
  echo "[crash-context] captured DistanceManager crash context to ${bundle_dir}" >&2
else
  echo "[crash-context] failed to capture DistanceManager crash context for ${bundle_dir}" >&2
  exit 1
fi

