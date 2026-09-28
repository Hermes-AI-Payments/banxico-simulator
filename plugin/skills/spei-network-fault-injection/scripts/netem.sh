#!/usr/bin/env bash
# Orquesta tc netem contra el contenedor banxico-simulator vía un sidecar
# nicolaka/netshoot que comparte su network namespace. No modifica el
# simulador ni su imagen -- solo aplica/retira qdiscs en su eth0.
#
# El simulador vive en un host remoto (192.168.1.200) alcanzable por VPN.
# Si el CLI `docker` local no llega a ese host directamente, exporta:
#   export SPEI_SIM_RUNNER="ssh <usuario>@192.168.1.200"
# antes de usar este script. Por defecto RUNNER va vacío (docker local).
#
# Uso:
#   netem.sh detect                          # confirma que el contenedor existe y está corriendo
#   netem.sh apply  "<netem-args>" [mtu]      # aplica qdisc netem (y opcionalmente MTU) sobre eth0
#   netem.sh status                           # muestra el qdisc activo
#   netem.sh clean                            # revierte a pristine (idempotente, seguro llamar siempre)
#   netem.sh reset-conn <remote_ip> <remote_port>  # corte abrupto: fuerza reset de una conexión TCP activa
#   netem.sh lock-status                     # muestra si hay una prueba de red activa y de quién
#   netem.sh lock-acquire "<descripción>"     # toma el lock (falla si alguien más lo tiene)
#   netem.sh lock-release                    # libera el lock (idempotente)
#
# Sesión única compartida: solo puede haber UNA prueba de fault-injection de red
# corriendo a la vez contra el simulador (igual que en producción solo hay una
# sesión entre Hermes y Banxico). `apply` y `reset-conn` toman el lock
# automáticamente y `clean` siempre lo libera -- ver sección "Lock" abajo.
#
# Ejemplos de "<netem-args>" (ver references/spec-005-scenarios.md para el mapeo completo):
#   "delay 100ms 20ms"                         # latencia 100ms +/- 20ms jitter
#   "loss 5%"                                  # 5% pérdida de paquetes
#   "duplicate 1%"                             # 1% duplicación
#   "reorder 25% 50%"                          # 25% de paquetes reordenados, correlación 50%
#   "delay 100ms 20ms loss 5% duplicate 1% reorder 25% 50%"  # combinado

set -euo pipefail

RUNNER="${SPEI_SIM_RUNNER:-}"
CONTAINER="${SPEI_SIM_CONTAINER:-banxico-simulator}"
IFACE="${SPEI_SIM_IFACE:-eth0}"
NETSHOOT_IMG="nicolaka/netshoot"

run() {
  if [[ -z "$RUNNER" ]]; then
    "$@"
  else
    # ssh junta sus argumentos con espacios y el shell remoto los vuelve a
    # partir -- sin escaparlos, '{{index .Config.Labels "owner"}}' o
    # "scenario=netem: delay 100ms" llegan rotos al docker remoto.
    # shellcheck disable=SC2086
    $RUNNER "$(printf '%q ' "$@")"
  fi
}

sidecar_exec() {
  # Corre un comando tc/ip/ss dentro de un contenedor netshoot efímero
  # que comparte el namespace de red del simulador.
  run docker run --rm --cap-add=NET_ADMIN --network "container:${CONTAINER}" "$NETSHOOT_IMG" "$@"
}

# --- Lock de sesión única -----------------------------------------------
# Se implementa como un contenedor marcador (`docker run --name`) en el mismo
# host/daemon que ya usamos para todo lo demás -- docker garantiza nombres
# únicos de forma atómica, así que dos personas corriendo esto al mismo
# tiempo (una por SSH, otra local) no pueden ambas "ganar" el lock por una
# condición de carrera. No depende de un path de filesystem específico, así
# que funciona igual con SPEI_SIM_RUNNER vacío o con SSH.
LOCK_NAME="spei-fault-lock"

cmd_lock_status() {
  if run docker inspect "${LOCK_NAME}" >/dev/null 2>&1; then
    local owner scenario started
    owner=$(run docker inspect "${LOCK_NAME}" --format '{{index .Config.Labels "owner"}}')
    scenario=$(run docker inspect "${LOCK_NAME}" --format '{{index .Config.Labels "scenario"}}')
    started=$(run docker inspect "${LOCK_NAME}" --format '{{index .Config.Labels "started"}}')
    echo "OCUPADO -- owner=${owner} scenario=\"${scenario}\" started=${started}"
    return 1
  else
    echo "LIBRE"
    return 0
  fi
}

cmd_lock_acquire() {
  local scenario="${1:?uso: lock-acquire \"<descripción del escenario>\"}"
  local owner started
  owner="$(whoami)@$(hostname)"
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if ! run docker run -d --name "${LOCK_NAME}" \
        --label "owner=${owner}" --label "scenario=${scenario}" --label "started=${started}" \
        alpine sleep infinity >/dev/null 2>&1; then
    echo "ERROR: ya hay una prueba de red activa -- no se puede tomar el lock." >&2
    cmd_lock_status >&2 || true
    echo "No se debe forzar la liberación de este lock a menos que confirmes con esa persona que ya terminó o que quedó huérfano por un fallo." >&2
    exit 1
  fi
  echo "Lock tomado: owner=${owner} scenario=\"${scenario}\" started=${started}"
}

cmd_lock_release() {
  run docker rm -f "${LOCK_NAME}" >/dev/null 2>&1 || true
  echo "Lock liberado (o ya estaba libre)."
}

cmd_detect() {
  echo "Buscando contenedor '${CONTAINER}'..."
  if ! run docker ps --filter "name=^${CONTAINER}\$" --filter "status=running" --format '{{.Names}} ({{.Status}})' | grep -q "${CONTAINER}"; then
    echo "ERROR: no se encontró un contenedor '${CONTAINER}' corriendo." >&2
    echo "Si el host remoto no es alcanzable directamente, exporta SPEI_SIM_RUNNER=\"ssh usuario@192.168.1.200\" y reintenta." >&2
    exit 1
  fi
  run docker ps --filter "name=^${CONTAINER}\$" --format 'OK: {{.Names}} {{.Status}} {{.Ports}}'
  echo "Interfaz objetivo: ${IFACE} (namespace del contenedor)"
  echo -n "Lock de prueba de red: "
  cmd_lock_status || true
}

cmd_apply() {
  local netem_args="${1:?uso: apply \"<netem-args>\" [mtu]}"
  local mtu="${2:-}"
  cmd_lock_acquire "netem: ${netem_args}${mtu:+ mtu=${mtu}}"
  echo "Aplicando: tc qdisc add dev ${IFACE} root netem ${netem_args}"
  sidecar_exec tc qdisc add dev "${IFACE}" root netem ${netem_args}
  if [[ -n "$mtu" ]]; then
    echo "Aplicando MTU: ${mtu}"
    sidecar_exec ip link set "${IFACE}" mtu "${mtu}"
  fi
  cmd_status
}

cmd_status() {
  sidecar_exec tc qdisc show dev "${IFACE}"
  sidecar_exec ip link show "${IFACE}"
}

cmd_clean() {
  echo "Revirtiendo qdisc en ${IFACE} (si existe)..."
  sidecar_exec tc qdisc del dev "${IFACE}" root 2>/dev/null || echo "  (no había qdisc custom -- ya estaba limpio)"
  echo "Restaurando MTU a 1500 (default)..."
  sidecar_exec ip link set "${IFACE}" mtu 1500 || true
  cmd_status
  cmd_lock_release
}

cmd_reset_conn() {
  local remote_ip="${1:?uso: reset-conn <remote_ip> <remote_port>}"
  local remote_port="${2:?uso: reset-conn <remote_ip> <remote_port>}"
  cmd_lock_acquire "corte abrupto -> ${remote_ip}:${remote_port}"
  echo "Forzando reset de conexión TCP hacia ${remote_ip}:${remote_port} (corte abrupto)..."
  sidecar_exec ss -K dst "${remote_ip}" dport "${remote_port}"
  cmd_lock_release
}

case "${1:-}" in
  detect) cmd_detect ;;
  apply) cmd_apply "${2:-}" "${3:-}" ;;
  status) cmd_status ;;
  clean) cmd_clean ;;
  reset-conn) cmd_reset_conn "${2:-}" "${3:-}" ;;
  lock-status) cmd_lock_status ;;
  lock-acquire) cmd_lock_acquire "${2:-}" ;;
  lock-release) cmd_lock_release ;;
  *)
    echo "Uso: $0 {detect|apply|status|clean|reset-conn|lock-status|lock-acquire|lock-release}" >&2
    exit 1
    ;;
esac
