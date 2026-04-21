#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEFAULT_SERVER_DIR_LINUX="/root/minecraft-cabal/server"
DEFAULT_SERVER_DIR_LOCAL="${REPO_ROOT}/server"

if [[ -n "${MC_SERVER_DIR:-}" ]]; then
  SERVER_DIR="${MC_SERVER_DIR}"
elif [[ -d "${DEFAULT_SERVER_DIR_LOCAL}" ]]; then
  SERVER_DIR="${DEFAULT_SERVER_DIR_LOCAL}"
elif [[ -d "${DEFAULT_SERVER_DIR_LINUX}" ]]; then
  SERVER_DIR="${DEFAULT_SERVER_DIR_LINUX}"
else
  SERVER_DIR="${DEFAULT_SERVER_DIR_LOCAL}"
fi

if [[ -n "${JAVA_BIN:-}" ]]; then
  JAVA="${JAVA_BIN}"
elif [[ -x "/usr/lib/jvm/java-25-openjdk-amd64/bin/java" ]]; then
  JAVA="/usr/lib/jvm/java-25-openjdk-amd64/bin/java"
elif [[ -x "/opt/homebrew/opt/openjdk/bin/java" ]]; then
  JAVA="/opt/homebrew/opt/openjdk/bin/java"
else
  JAVA="java"
fi
JAR="fabric-server-launch.jar"
MIN_MEM="${MC_MIN_MEM:-10G}"
MAX_MEM="${MC_MAX_MEM:-10G}"
LOG4J_CONFIG="${SERVER_DIR}/log4j2.xml"
TEMPLATE_PROPERTIES_FILE="${SERVER_DIR}/server.properties.template"
RUNTIME_PROPERTIES_FILE="${SERVER_DIR}/server.properties"
RCON_PASSWORD_FILE="${SERVER_DIR}/.rcon-password"

set_property() {
  local key="$1"
  local value="$2"
  local file="$3"
  local escaped_key escaped_value
  escaped_key="$(printf '%s' "$key" | sed -e 's/[][\\/.*^$(){}?+|]/\\&/g')"
  escaped_value="$(printf '%s' "$value" | sed -e 's/[\\&|]/\\&/g')"
  if grep -q "^${escaped_key}=" "$file"; then
    if sed --version >/dev/null 2>&1; then
      sed -i "s|^${escaped_key}=.*|${key}=${escaped_value}|" "$file"
    else
      sed -i '' "s|^${escaped_key}=.*|${key}=${escaped_value}|" "$file"
    fi
  else
    printf "%s=%s\n" "$key" "$value" >> "$file"
  fi
}

apply_runtime_server_properties() {
  if [[ ! -f "${TEMPLATE_PROPERTIES_FILE}" ]]; then
    echo "[start] Missing ${TEMPLATE_PROPERTIES_FILE}" >&2
    exit 1
  fi

  cp "${TEMPLATE_PROPERTIES_FILE}" "${RUNTIME_PROPERTIES_FILE}"

  # Docker-only runtime: bind game + RCON on all interfaces.
  set_property "server-ip" "0.0.0.0" "${RUNTIME_PROPERTIES_FILE}"
  set_property "server-port" "${MC_SERVER_PORT:-25565}" "${RUNTIME_PROPERTIES_FILE}"
  set_property "enable-rcon" "true" "${RUNTIME_PROPERTIES_FILE}"
  set_property "rcon.port" "${MC_RCON_PORT:-25575}" "${RUNTIME_PROPERTIES_FILE}"

  if grep -q "^enable-rcon=true$" "${RUNTIME_PROPERTIES_FILE}"; then
    local rcon_password=""
    if [[ -n "${RCON_PASSWORD:-}" ]]; then
      rcon_password="${RCON_PASSWORD}"
    elif [[ -f "${RCON_PASSWORD_FILE}" ]]; then
      rcon_password="$(tr -d '\r\n' < "${RCON_PASSWORD_FILE}")"
    fi
    if [[ -z "${rcon_password}" ]]; then
      echo "[start] ERROR: enable-rcon=true but no RCON secret: set RCON_PASSWORD or create ${RCON_PASSWORD_FILE}." >&2
      exit 1
    fi

    set_property "rcon.password" "${rcon_password}" "${RUNTIME_PROPERTIES_FILE}"
  fi
}

cd "$SERVER_DIR"
apply_runtime_server_properties

if [[ ! -f "${SERVER_DIR}/${JAR}" ]]; then
  echo "[start] Missing ${SERVER_DIR}/${JAR}. Install Fabric server files into ${SERVER_DIR} (README / docker/README.md)." >&2
  exit 1
fi

JVM_ARGS=(
  --enable-native-access=ALL-UNNAMED
  -Dlog4j.configurationFile="${LOG4J_CONFIG}"
  -Xms${MIN_MEM}
  -Xmx${MAX_MEM}
  -XX:+UseG1GC
  -XX:+ParallelRefProcEnabled
  -XX:MaxGCPauseMillis=200
  -jar "$JAR"
  nogui
)
exec "$JAVA" "${JVM_ARGS[@]}"
