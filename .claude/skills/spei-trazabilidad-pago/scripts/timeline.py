#!/usr/bin/env python3
"""Spec 015 -- línea de tiempo cruzada de una orden/pago por clave de rastreo.

Junta cuatro fuentes (Core falso, Judeca, Minos/Estigia vía Graylog, este simulador), ordena por
hora y la imprime como tabla markdown en stdout -- pensado para que quien invoca este script (un
agente de Claude Code) pegue la salida directo en el chat, no para generar un archivo. Ver
".claude/skills/spei-trazabilidad-pago/SKILL.md" para cuándo usarlo y cómo presentarlo.

No falla si una fuente no responde o no tiene nada -- cada fuente es independiente, y la tabla
final solo advierte qué fuente no se pudo consultar, no aborta el resto.
"""
import argparse
import csv
import io
import json
import os
import re
import shlex
import ssl
import subprocess
import sys
import urllib.request
from datetime import datetime


def log(msg):
    print(f"# {msg}", file=sys.stderr)


def fetch_json(url):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(url, timeout=10, context=ctx) as resp:
            return json.loads(resp.read().decode("utf-8")), None
    except Exception as e:  # noqa: BLE001 -- cualquier falla de red/parseo es "fuente no disponible"
        return None, str(e)


def filas_simulador(simulator_url, cve_rastreo, run_id):
    if run_id is None:
        sesion, err = fetch_json(f"{simulator_url}/session")
        if err or not sesion.get("session"):
            return [], f"simulador: sin sesión activa ({err or 'session es null'}), pasa --run-id a mano si la corrida ya terminó"
        run_id = sesion["session"]["runId"]
    eventos, err = fetch_json(f"{simulator_url}/test-runs/{run_id}/events")
    if err:
        return [], f"simulador: no se pudo leer /test-runs/{run_id}/events ({err})"
    filas = []
    for e in eventos.get("events", eventos if isinstance(eventos, list) else []):
        detalle = e.get("detail") or ""
        if cve_rastreo in detalle:
            filas.append({
                "hora": e["occurredAt"],
                "fuente": "simulador",
                "evento": f"{e['direction']} {e['messageName']}",
                "detalle": detalle,
            })
    if not filas:
        return [], f"simulador: runId {run_id} no tiene ningún evento con esa clave en el detalle"
    return filas, None


def filas_core_falso(core_falso_url, test_id):
    if not test_id:
        return [], None  # opcional -- sin testId simplemente no hay fila de Core falso
    datos, err = fetch_json(f"{core_falso_url}/test/spei-out/list/payments-test/{test_id}")
    if err:
        return [], f"Core falso: no se pudo leer /test/spei-out/list/payments-test/{test_id} ({err})"
    pagos = datos if isinstance(datos, list) else datos.get("data", datos.get("pagos", []))
    if not pagos:
        return [], f"Core falso: testId {test_id} no devolvió pagos"
    pago = pagos[0]
    # El Core falso no trae timestamp por transición de estado (spec 015 "Fuera de alcance") --
    # se muestra como snapshot, sin pretender un momento exacto que no existe.
    return [{
        "hora": None,
        "fuente": "Core falso",
        "evento": f"estado actual: {pago.get('status', '?')}",
        "detalle": f"testId={test_id} (sin timestamp por transición, ver spec 015)",
    }], None


#   Oct 07 03:20:49 hermes bash[...]: 2026-10-07 03:20:49 589 | [ INFO] - [     thread-1] - [clase.Java] | modulo=JUDECA | claveRastreo=X | evento=Y | mensaje
# Los corchetes de nivel/hilo/clase se matchean con "cualquier cosa menos ]" -- el nombre del hilo
# trae guiones y relleno de espacios variable (p.ej. "EnvioOrdenes-1"), no son solo \w.
LINEA_JUDECA = re.compile(
    r"^\w+ \d+ \d+:\d+:\d+ \S+ \S+: (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) (\d+) \| "
    r"\[([^\]]*)\] - \[([^\]]*)\] - \[([^\]]*)\] \| modulo=(\w+) \| claveRastreo=[^|]* \| evento=([^|]*) \| (.*)$"
)


def filas_judeca(judeca_ssh, cve_rastreo, unidad, ssh_identity):
    cmd = ["ssh"]
    if ssh_identity:
        cmd += ["-i", ssh_identity]
    cmd += [
        judeca_ssh,
        f"journalctl -u {unidad} --no-pager | grep -F 'claveRastreo={cve_rastreo}'",
    ]
    try:
        resultado = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    except Exception as e:  # noqa: BLE001
        return [], f"{unidad}: no se pudo conectar por SSH a {judeca_ssh} ({e})"
    if not resultado.stdout.strip():
        return [], f"{unidad}: sin líneas con esa clave de rastreo (¿corrió hace más de lo que retiene el log?)"
    filas = []
    sin_match = 0
    for linea in resultado.stdout.splitlines():
        m = LINEA_JUDECA.match(linea)
        if not m:
            sin_match += 1
            continue
        hora, milis, nivel, _hilo, clase, modulo, evento, mensaje = m.groups()
        evento = evento.strip()
        filas.append({
            "hora": hora.replace(" ", "T") + "." + milis.zfill(3) + "Z",
            "fuente": modulo.capitalize(),
            "evento": evento if evento else clase.strip().rsplit(".", 1)[-1],
            "detalle": mensaje.strip(),
        })
    if not filas and sin_match:
        return [], f"{unidad}: {sin_match} línea(s) encontradas pero el formato no coincidió con el patrón esperado -- revisa LINEA_JUDECA en este script contra una línea real"
    return filas, None


def filas_minos_graylog(graylog_ssh, cve_rastreo, dias_retencion, ssh_identity):
    """Consulta Graylog por `clave_rastreo` -- cubre Minos A/B/C y Estigia (todo salvo los access
    logs de Tomcat), confirmado por el equipo de Pedro el 2026-10-09 (ver specs/015 "Preguntas
    abiertas", ahora cerradas). El campo `clave_rastreo` viene indexado pero vacío para Minos y
    Estigia -- ahí se busca por `message:"<clave>"`, igual que con Judeca.

    Se ejecuta por SSH *dentro* de la .52 (no hace falta túnel local: la API de Graylog solo es
    alcanzable desde dentro de esa VM). El token nunca viaja por línea de comandos -- se lee
    localmente de la variable de entorno GRAYLOG_API_TOKEN y se manda al host remoto por stdin del
    túnel SSH (ya cifrado), nunca como argumento de un proceso que `ps` pudiera mostrar en la .52.
    """
    token = os.environ.get("GRAYLOG_API_TOKEN")
    if not token:
        return [], ("Minos/Estigia (Graylog): falta la variable de entorno GRAYLOG_API_TOKEN -- "
                     "exporta el token que te dio el equipo de Pedro antes de correr esto "
                     "(no lo pases por argumento ni lo pegues en el chat)")

    query = f'clave_rastreo:"{cve_rastreo}" OR message:"{cve_rastreo}"'
    body = json.dumps({
        "query_string": {"type": "elasticsearch", "query_string": query},
        "timerange": {"type": "relative", "range": dias_retencion * 86400},
        "streams": [],
        "fields_in_order": ["timestamp", "modulo", "evento", "clave_rastreo", "message"],
    })
    remote_cmd = (
        "TOKEN=$(cat); curl -s -X POST http://127.0.0.1:9000/api/views/search/messages "
        '-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" '
        '-H "Accept: text/csv" -d ' + shlex.quote(body)
    )
    cmd = ["ssh"]
    if ssh_identity:
        cmd += ["-i", ssh_identity]
    cmd += [graylog_ssh, remote_cmd]
    try:
        resultado = subprocess.run(cmd, input=token, capture_output=True, text=True, timeout=30)
    except Exception as e:  # noqa: BLE001
        return [], f"Minos/Estigia (Graylog): no se pudo conectar por SSH a {graylog_ssh} ({e})"
    if resultado.returncode != 0 or not resultado.stdout.strip():
        detalle = resultado.stderr.strip() or "sin salida"
        return [], f"Minos/Estigia (Graylog): la consulta no devolvió datos ({detalle})"

    filas = []
    lector = csv.DictReader(io.StringIO(resultado.stdout))
    for fila in lector:
        filas.append({
            "hora": (fila.get("timestamp") or "").strip(),
            "fuente": (fila.get("modulo") or "Minos").strip().capitalize(),
            "evento": (fila.get("evento") or "").strip(),
            "detalle": (fila.get("message") or "").strip(),
        })
    if not filas:
        return [], (f"Minos/Estigia (Graylog): sin filas para la clave {cve_rastreo} "
                     f"(¿pasaron más de {dias_retencion} días de retención?)")
    return filas, None


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("cve_rastreo", help="Clave de rastreo a buscar en las tres fuentes.")
    p.add_argument("--test-id", help="testId del Core falso, si se sabe (opcional).")
    p.add_argument("--run-id", type=int, help="runId del simulador a usar en vez del de la sesión activa.")
    p.add_argument("--simulator-url", default="http://192.168.1.200:8089")
    p.add_argument("--core-falso-url", default="https://192.168.1.52:3000")
    p.add_argument("--judeca-ssh", default="hermes@192.168.1.52")
    p.add_argument("--judeca-unidad", default="judeca.service",
                   help="Unidad systemd a consultar -- cambia a estigia.service/pluton.service/etc. para seguir otros tramos.")
    p.add_argument("--ssh-identity", default=None,
                   help="Llave SSH a usar (-i). Si el agente ya tiene la llave cargada en su propio ssh-agent no hace falta -- en este repo normalmente sí hace falta pasar la ruta explícita (ver AGENTS.md).")
    p.add_argument("--graylog-ssh", default="hermes@192.168.1.52",
                   help="Host SSH desde el que se ejecuta el curl a Graylog (la API solo es alcanzable desde dentro de esta VM).")
    p.add_argument("--graylog-dias-retencion", type=int, default=10,
                   help="Días de retención configurados en Graylog (confirmado 10 por el equipo de Pedro, 2026-10-09).")
    p.add_argument("--sin-graylog", action="store_true",
                   help="Omite la consulta a Graylog (Minos/Estigia) -- útil si GRAYLOG_API_TOKEN todavía no está configurado.")
    args = p.parse_args()

    advertencias = []
    filas = []

    fs, adv = filas_simulador(args.simulator_url, args.cve_rastreo, args.run_id)
    filas += fs
    if adv:
        advertencias.append(adv)

    fc, adv = filas_core_falso(args.core_falso_url, args.test_id)
    filas += fc
    if adv:
        advertencias.append(adv)

    fj, adv = filas_judeca(args.judeca_ssh, args.cve_rastreo, args.judeca_unidad, args.ssh_identity)
    filas += fj
    if adv:
        advertencias.append(adv)

    if not args.sin_graylog:
        fg, adv = filas_minos_graylog(args.graylog_ssh, args.cve_rastreo,
                                       args.graylog_dias_retencion, args.ssh_identity)
        filas += fg
        if adv:
            advertencias.append(adv)

    if not filas:
        log(f"Sin resultados en ninguna fuente para clave de rastreo {args.cve_rastreo}.")
        for a in advertencias:
            log(a)
        sys.exit(1)

    con_hora = [f for f in filas if f["hora"]]
    sin_hora = [f for f in filas if not f["hora"]]
    con_hora.sort(key=lambda f: f["hora"])

    print(f"## Línea de tiempo -- clave de rastreo `{args.cve_rastreo}`\n")
    print("| Hora (UTC) | Fuente | Evento | Detalle |")
    print("|---|---|---|---|")
    anterior = None
    for f in con_hora:
        delta = ""
        if anterior:
            try:
                t1 = datetime.fromisoformat(anterior.replace("Z", "+00:00"))
                t2 = datetime.fromisoformat(f["hora"].replace("Z", "+00:00"))
                delta = f" (+{(t2 - t1).total_seconds():.3f}s)"
            except ValueError:
                pass
        anterior = f["hora"]
        print(f"| {f['hora']}{delta} | {f['fuente']} | {f['evento']} | {f['detalle'][:120]} |")
    for f in sin_hora:
        print(f"| (sin hora) | {f['fuente']} | {f['evento']} | {f['detalle'][:120]} |")

    if advertencias:
        print("\n**Fuentes sin datos:**")
        for a in advertencias:
            print(f"- {a}")


if __name__ == "__main__":
    main()
