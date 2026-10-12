---
name: spei-trazabilidad-pago
description: Arma una línea de tiempo cruzada de una orden/pago específica (Core falso → Judeca → Minos/Estigia vía Graylog → este simulador), dada su clave de rastreo. Úsalo cuando el usuario pida "trazabilidad", "línea de tiempo de un pago/orden", "qué pasó con la clave de rastreo X", "en qué momento pasó por cada sistema", o quiera medir tiempos/latencias entre pasos de un pago de prueba (spec 015). Requiere VPN + acceso SSH al laboratorio -- NO es parte del plugin distribuido (spec 011 evita SSH a propósito), vive solo en este repo para quien tiene acceso directo.
allowed-tools: Bash
---

# Trazabilidad cruzada de un pago (spec 015)

## Qué hace y qué no

Junta cuatro fuentes por clave de rastreo (`cveRastreo`) y las ordena en una sola línea de tiempo:

1. **Judeca** (vía SSH + `docker logs`, contenedor `hermes-judeca` -- el laboratorio migró de
   systemd a Docker el 2026-10-12, `journalctl -u judeca.service` ya no funciona) -- el tramo más
   detallado, procesamiento interno paso a paso.
2. **Minos/Estigia** (vía Graylog, confirmado por el equipo de Pedro 2026-10-09 -- ver "Fuente
   Graylog" abajo) -- cubre Minos A/B/C y Estigia, todo salvo los access logs de Tomcat.
3. **Este simulador** (`GET /test-runs/{id}/events`, ya filtrado por clave desde spec 015) -- cuándo
   llegó el `OrdenTopoV` y qué respondió el `AcuseRecibo`.
4. **Core falso** (`GET /test/spei-out/list/payments-test/{testId}`, si se sabe el `testId`) -- solo
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
`--simulator-url`, `--core-falso-url`, `--judeca-ssh`, `--judeca-contenedor` (default
`hermes-judeca`, cambia a `hermes-estigia`/`hermes-pluton`/`hermes-radamanto` para seguir otros
tramos con el mismo patrón de columnas si Judeca no alcanza), `--graylog-ssh`, `--sin-graylog`
(omite esa fuente si `GRAYLOG_API_TOKEN` todavía no está configurado).

## Fuente Graylog (Minos/Estigia)

Requiere la variable de entorno `GRAYLOG_API_TOKEN` (el token de solo-lectura que el equipo de
Pedro emitió para el usuario `miguel`, vence el 2027-01-07) -- **nunca la pegues en el chat ni la
pases como argumento del script**; expórtala en tu propia terminal antes de correrlo (mejor en
`~/.zshrc` o equivalente, para que persista entre sesiones):

```bash
export GRAYLOG_API_TOKEN=<el token que te compartió Pedro>
```

**Mecanismo de autenticación real, confirmado por Pedro el 2026-10-12** (no es Bearer, como se
asumió al principio): HTTP Basic -- el token va como usuario y la palabra literal `token` como
contraseña (`curl -u "<token>:token"`), más el header `X-Requested-By` (Graylog lo exige como
anti-CSRF). El script arma esto como config de curl (`-K -`, por stdin) en vez de pasarlo como
argumento `-u`/`-H`, para que el token nunca aparezca en `ps` de ningún host mientras el proceso
corre -- ni local ni remoto.

Pedro recomienda abrir un túnel local (`ssh -L 9000:127.0.0.1:9000 hermes@192.168.1.52`) y pegar
el curl directo; este script en cambio ejecuta el curl *dentro* de la VM por SSH (sin túnel) --
logra lo mismo (Graylog solo escucha en `127.0.0.1:9000` de esa VM) sin dejar un túnel abierto.

Retención confirmada: 10 días. El campo `clave_rastreo` está indexado pero viene vacío para Minos
y Estigia, así que ahí se busca por `message:"<clave>"` (igual que con Judeca). El `timestamp` que
regresa Graylog es el valor UTC con precisión de milisegundos que la propia aplicación registró
(no `gl2_receive_timestamp`), así que es comparable directamente con las horas de las otras
fuentes.

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

Antes de asumir que algo falló, considera: ¿la orden es muy vieja (`docker logs --since 24h` es la
ventana por default -- sube `--judeca-desde` si hace falta más)? ¿la clave de rastreo es exacta
(sensible a mayúsculas/guiones)? ¿la sesión SSH tiene la llave correcta
(`~/.ssh/id_ed25519_conecta` en esta máquina, ver `AGENTS.md` del repo raíz de este laboratorio)?
¿el contenedor se llama distinto en este despliegue (`docker ps` para confirmar, en vez de asumir
`hermes-judeca`)? Si el formato de línea de Judeca cambió (otra versión, otro `modulo=`), el
script lo dice explícitamente ("el formato no coincidió con el patrón esperado") en vez de fallar
en silencio -- si eso pasa, hay que actualizar `LINEA_JUDECA` en `scripts/timeline.py` contra una
línea real nueva, no adivinar.

## Resuelto (spec 015, era "pendiente")

El equipo de Pedro confirmó (2026-10-09) acceso de solo-lectura a Graylog, cubriendo todos los
módulos salvo Tomcat -- implementado como la cuarta fuente (`filas_minos_graylog`), ver arriba. La
fuente de Judeca **sigue siendo SSH + `docker logs`** a propósito (no se reemplazó): Graylog cubre
Minos/Estigia, que antes no teníamos forma de ver; Judeca ya teníamos un camino confiable y
cambiarlo no aporta nada nuevo. Si en el futuro se decide consolidar también Judeca sobre Graylog,
es un cambio aparte, no asumido aquí.
