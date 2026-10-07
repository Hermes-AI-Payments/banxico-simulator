---
name: spei-trazabilidad-pago
description: Arma una línea de tiempo cruzada de una orden/pago específica (Core falso → Judeca → este simulador), dada su clave de rastreo. Úsalo cuando el usuario pida "trazabilidad", "línea de tiempo de un pago/orden", "qué pasó con la clave de rastreo X", "en qué momento pasó por cada sistema", o quiera medir tiempos/latencias entre pasos de un pago de prueba (spec 015). Requiere VPN + acceso SSH al laboratorio -- NO es parte del plugin distribuido (spec 011 evita SSH a propósito), vive solo en este repo para quien tiene acceso directo.
allowed-tools: Bash
---

# Trazabilidad cruzada de un pago (spec 015)

## Qué hace y qué no

Junta tres fuentes por clave de rastreo (`cveRastreo`) y las ordena en una sola línea de tiempo:

1. **Judeca** (vía SSH + `journalctl`) -- el tramo más detallado, procesamiento interno paso a paso.
2. **Este simulador** (`GET /test-runs/{id}/events`, ya filtrado por clave desde spec 015) -- cuándo
   llegó el `OrdenTopoV` y qué respondió el `AcuseRecibo`.
3. **Core falso** (`GET /test/spei-out/list/payments-test/{testId}`, si se sabe el `testId`) -- solo
   el estado actual (`EN_COLA`/`ENVIADO`/`LIQUIDADO`/`DEVUELTO`), **sin** timestamp por transición
   (limitación real del Core falso, no de esta herramienta -- ver spec 015 "Fuera de alcance").

No corrige reloj entre sistemas (todos corren en el mismo host de laboratorio, se asume UTC
consistente -- confirmado así en sesiones anteriores). No reintenta si una fuente no responde:
reporta qué fuente falló y sigue con las demás, nunca aborta todo por una sola fuente caída.

## Cómo usarlo

```bash
python3 "${CLAUDE_PROJECT_DIR}/.claude/skills/spei-trazabilidad-pago/scripts/timeline.py" \
  <clave-de-rastreo> \
  --ssh-identity ~/.ssh/id_ed25519_conecta \
  [--test-id <testId del Core falso, si se sabe>] \
  [--run-id <runId del simulador, si la sesión ya no está activa>]
```

Sin `--run-id`, usa la sesión SPEI activa (`GET /session`) -- si la corrida que se quiere ver ya
terminó y hay una sesión más nueva encima, hay que dar `--run-id` a mano (el `test-runs` más
reciente con esa clave no siempre es el de la sesión activa).

Flags para apuntar a otro laboratorio (todos tienen default para `.200`/`.52`):
`--simulator-url`, `--core-falso-url`, `--judeca-ssh`, `--judeca-unidad` (cambia a
`estigia.service`/`pluton.service`/`radamanto.service` para seguir otros tramos con el mismo
patrón de columnas si Judeca no alcanza).

## Cómo presentarlo

**Por default: pega la salida del script directo en el chat, tal cual** -- ya es una tabla
markdown lista. No generes ningún archivo `.md` ni lo guardes en el repo; es para revisar al
momento, no un entregable.

**Solo si el usuario pide explícitamente compartirlo con alguien más** (Pedro, un reporte de
hallazgo, etc.): usa el mecanismo de artefactos de Claude Code para publicar la misma tabla como
una página HTML simple (encabezado con la clave de rastreo, la tabla, y las advertencias de
fuentes sin datos si las hay). No publiques un artefacto por default -- es un paso extra que el
usuario pide, no el camino normal.

## Si Judeca no trae nada

Antes de asumir que algo falló, considera: ¿la orden es muy vieja (el log pudo rotar)? ¿la clave
de rastreo es exacta (sensible a mayúsculas/guiones)? ¿la sesión SSH tiene la llave correcta
(`~/.ssh/id_ed25519_conecta` en esta máquina, ver `AGENTS.md` del repo raíz de este laboratorio)?
Si el formato de línea de Judeca cambió (otra versión, otro `modulo=`), el script lo dice
explícitamente ("el formato no coincidió con el patrón esperado") en vez de fallar en silencio --
si eso pasa, hay que actualizar `LINEA_JUDECA` en `scripts/timeline.py` contra una línea real
nueva, no adivinar.

## Pendiente (spec 015, preguntas abiertas)

Si el equipo de Pedro confirma que Graylog/OpenSearch ya indexa `claveRastreo` como campo
buscable, la función `filas_judeca` de `scripts/timeline.py` debería cambiar su fuente de
"SSH + journalctl" a una consulta HTTP a la API de búsqueda de Graylog -- menos frágil, sin
necesitar SSH. No se ha confirmado todavía, por eso sigue en SSH por ahora.
