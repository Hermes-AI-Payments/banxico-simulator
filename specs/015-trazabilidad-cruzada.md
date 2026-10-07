# 015 — Trazabilidad cruzada de una orden/pago (línea de tiempo)

## Contexto

Hoy cada sistema del flujo (Core falso → Judeca → Eaco → Estigia → minos → este simulador) tiene
su propia bitácora, pero no hay una sola vista que junte "qué le pasó a esta clave de rastreo, en
qué sistema, a qué hora, cuánto tardó cada salto" — hay que revisar cada sistema por separado y
cruzar manualmente por tiempo/clave. Esta spec cubre esa vista cruzada como herramienta de
diagnóstico, no un cambio al protocolo real.

**Decisión de alcance (2026-10-06, Miguel):** dos opciones evaluadas, no excluyentes:

1. **Aprovechar lo que ya existe** — correlacionar por `cveRastreo` (clave de rastreo), ya presente
   de forma consistente en los logs de Judeca (`claveRastreo=X` en cada línea) y ahora también en
   los eventos propios del simulador (ver Requisito 1). Cero dependencias nuevas, funciona hoy.
2. **Alinearse con la observabilidad que el equipo de Pedro ya está construyendo** (Graylog +
   OpenSearch, visto en los commits recientes de `hermes-instalador`) — si el equipo decide
   indexar `claveRastreo` como campo buscable ahí, la fuente de Judeca pasa de "SSH + grep" a "una
   búsqueda en Graylog", menos frágil y sin necesitar acceso SSH. **Pendiente de confirmar con
   Pedro** — se empieza por la opción 1 mientras tanto, diseñada para poder cambiar de fuente sin
   rehacer el resto.

## Requisitos

1. **El simulador debe registrar `cveRastreo` en sus propios eventos** (`/test-runs/{id}/events`)
   — hoy el detalle de `OrdenTopoV`/`AcuseRecibo` no la expone, hay que decodificar `rawHex` a mano
   para saberla. Sin esto, correlacionar contra Judeca requiere adivinar por cercanía de tiempo.
2. **Una herramienta que, dado un `cveRastreo` (y opcionalmente un `testId` del Core falso si se
   sabe), arme una línea de tiempo cronológica** con, por cada paso: sistema, hora, qué pasó,
   cuánto tardó desde el paso anterior. Fuentes, en orden de confiabilidad:
   - Core falso: `GET /test/spei-out/list/payments-test/{testId}` (estado agregado —
     `EN_COLA`/`ENVIADO`/`LIQUIDADO`/`DEVUELTO` — no tiene timestamp por transición todavía, ver
     Fuera de alcance).
   - Judeca: logs reales vía SSH, `journalctl -u judeca.service | grep claveRastreo=<clave>` —
     tiene timestamp por línea, ya lo trae.
   - Este simulador: `GET /test-runs/{id}/events`, ahora buscable por `cveRastreo` (Requisito 1).
3. **Presentación:** en el chat (tabla markdown) por default — sin fricción, para revisar una
   corrida al momento. Cuando se pida compartir el resultado con alguien más, publicarlo como
   artefacto HTML (dashboard simple, filtrable) en vez de generar un archivo `.md`/PDF suelto.

## Fuera de alcance

- Pedir a Pedro que agregue timestamps por transición de estado en el Core falso — es un cambio en
  otro repo (`hermes-core-lab-api`), no en este. Si se necesita, es una solicitud aparte.
- Distributed tracing formal (spans con trace-id propagado entre módulos) — correcto a largo plazo,
  pero requiere tocar código de Judeca/Radamanto/Pluton, fuera del alcance de este simulador y de
  esta spec. Ver conversación 2026-10-06 para la comparación completa de opciones.
- Reemplazar Graylog ni construir un backend de logs propio — si el equipo de Pedro ya está
  construyendo eso, esta herramienta debe consumirlo, no competir con él.
- Esta herramienta vive en el repo, **no** en `plugin/` (el plugin distribuido vía marketplace
  evita SSH a propósito, spec 011) — es tooling interno para quien tiene acceso al laboratorio
  (VPN + SSH), igual que `spei-network-fault-injection` capa B.

## Diseño propuesto

- **Requisito 1 (hecho, 2026-10-06):** `SpeiSession.handleOrdenTopoV`/`AcuseRecibo` ahora incluyen
  `clavesRastreo=...` y `resultadoPorClave=...` en el `detail` del evento logueado — sin cambiar el
  formato de bytes del protocolo (solo la bitácora propia, R1).
- **Requisito 2:** script/skill de repo (no distribuido por el plugin) que recibe `cveRastreo` (y
  opcionalmente `testId`), golpea las tres fuentes de arriba, y arma la línea de tiempo combinada
  ordenada por hora. Vive en `.claude/skills/` (raíz del repo), no en `plugin/skills/`.
- **Requisito 3:** la skill presenta la tabla en el chat por default; si el usuario pide
  compartirlo, usa el mecanismo de artefactos de Claude Code para publicar una vista HTML.

## Preguntas abiertas

- ¿Vale la pena que el Core falso agregue timestamps por transición de estado? Depende de la
  respuesta de Pedro — no se resuelve aquí.
- Si el equipo migra a Graylog, ¿la fuente de Judeca cambia a una consulta HTTP a su API de
  búsqueda, o se sigue usando SSH como respaldo si Graylog no está disponible? Decidir cuando se
  confirme que Graylog está realmente desplegado y accesible.

## Criterios de aceptación

- [x] Los eventos propios del simulador (`OrdenTopoV`, `AcuseRecibo`) incluyen la(s) clave(s) de
      rastreo en su detalle, buscables en `/test-runs/{id}/events` sin decodificar `rawHex` --
      confirmado contra minosA real 2026-10-06.
- [x] Dada una clave de rastreo real, la herramienta arma una línea de tiempo correcta cruzando
      Core falso + Judeca + este simulador, con los tiempos reales de cada sistema -- confirmado
      2026-10-06 contra una orden real (`20261006906461075786E5E000001`), orden cronológico
      correcto incluido el sub-segundo (Judeca procesa antes de que minos mande el `OrdenTopoV`,
      como se espera).
- [ ] La línea de tiempo se puede pedir como artefacto HTML compartible, no solo en el chat.

## Estado de implementación (2026-10-06)

Requisito 1 implementado, compilado, con la suite completa en verde (11/11 tests), desplegado a
`.200` y **confirmado contra minosA real**: una orden real enviada desde el Core falso
(`cveRastreo=20261006906461075786E5E000001`) quedó registrada tal cual en
`/test-runs/{id}/events` -- `OrdenTopoV` con `clavesRastreo=...` y `AcuseRecibo` con
`resultadoPorClave=...:ACEPTADA`, ambos buscables directo, sin decodificar `rawHex`.

**Requisito 2 implementado y verificado contra datos reales (2026-10-06):**
`.claude/skills/spei-trazabilidad-pago/scripts/timeline.py` junta las tres fuentes (Judeca por
SSH, este simulador, Core falso) y arma la tabla correctamente ordenada -- probado de punta a
punta con una orden real, orden cronológico correcto incluido el sub-segundo.

**Extensión (2026-10-07):** `Cargos` también quedó trazable por clave de rastreo (lista, no una
sola -- liquida un folioPack completo, puede traer varias órdenes) -- confirmado contra minosA
real (`folio=4 entradas=1 monto=88.00 balance=1000139.00 clavesRastreo=...`).

**Requisito 3 (artefacto HTML para compartir) en progreso** -- la skill ya documenta que se hace
solo si se pide explícitamente.
