# 013 — Registro de cierre de sesión

**Estado:** implementado (2026-09-28), aprobado por Miguel Zavala para ir antes de spec 005.
Probado localmente; falta confirmarlo contra minos real en el host.

## Contexto

Entre el 2026-09-25 y el 2026-09-28 se cerraron tres sesiones SPEI (6086, 10117, 12776) sin dejar
rastro en la bitácora: su último evento en `GET /test-runs/{id}/events` era el último mensaje
normal, y `GET /session` solo decía `"fase":"TERMINADA"`. El cierre sí se detectaba — el
`finally` de `SpeiSession.run()` distinguía "conexión cerrada por minos" de "terminada con error"
— pero **solo se escribía al log de consola**, nunca a H2.

Sin esto no se puede saber si una sesión la cerró minos, la red o el simulador. Es justo lo que
las pruebas de corte y de red (spec 005) y de heartbeat (spec 009) quieren medir.

## Requisitos

- Al terminar una sesión SPEI o ARA, registrar un evento `CierreSesion` (dirección `INTERNO`) en
  `test_event`, con hora, causa y detalle.
- `GET /session` agrega `"cierre": {"at", "causa", "detalle"}` cuando la sesión terminó, y
  `"vivaDesde"` (hora en que completó el handshake), para poder calcular cuánto duró viva.

### Causas

| Causa | Cuándo |
|---|---|
| `minos-cerro` | EOF en la lectura, o minos mandó `DeadSrvr`/`SmTtyClose`/`NoService` (detalle: `op N`) |
| `heartbeat-fallo` | Falló el envío de `AreYouAlive`: la sesión se cierra en ese momento (antes solo se detenía el heartbeat y la lectura podía quedar bloqueada si minos desapareció sin FIN) |
| `error-io` | Otra falla de I/O del socket (timeout, reset, broken pipe); detalle: clase y mensaje |
| `error-protocolo` | Cualquier otra excepción (trama malformada, firma o cifrado inválidos) |
| `corte-deliberado` | Reservada para la capa A de spec 005 |
| `sin-registro` | Asignada **al arrancar** a toda corrida que quedó sin `CierreSesion`, con la hora de su último evento |

**Por qué existe `sin-registro` y no `simulador-detenido`:** se intentó registrar el cierre desde
el shutdown hook de `Main`, pero el hook propio de H2 cierra la base en paralelo y en la práctica
siempre gana (`Database is already closed`). Desactivarlo (`DB_CLOSE_ON_EXIT=FALSE`) es
incompatible con `AUTO_SERVER=TRUE`, que se necesita para inspeccionar la base con un cliente H2
mientras el simulador corre. Reconciliar al arrancar es además más robusto: cubre también
caídas abruptas (`kill -9`, host reiniciado), que ningún hook alcanza. Efecto en el primer
arranque con esta versión: las corridas anteriores (sin cierre registrado) quedan marcadas
`sin-registro`.

## Fuera de alcance

- Reconexión automática o alertas — solo registrar.

## Diseño implementado

- `spei/SessionClosure` (record + enum de causas). `SpeiSession` fija la causa donde la conoce
  (`requestClose`) y la registra en su `finally`; la causa pedida gana sobre la excepción que el
  propio cierre provoca. `AraSession` registra la causa de la excepción que la termina.
- `H2Store.closeUnfinishedRuns()` al abrir la base.
- Nivel de riesgo **R1**: es bitácora, no toca formato de bytes (AGENTS.md §6). El único cambio
  de comportamiento es cerrar la sesión cuando el heartbeat ya no puede escribir.

## Criterios de aceptación

- [x] Una sesión cerrada por minos deja un `CierreSesion` con causa `minos-cerro` (SPEI y ARA,
      probado localmente).
- [x] `GET /session` muestra `cierre` en una sesión terminada (probado local, también vía MCP).
- [x] Una corrida que queda abierta al apagar el simulador queda `sin-registro` al arrancar, sin
      duplicarse en arranques siguientes.
- [ ] Una sesión que muere por timeout de heartbeat (spec 009) deja la causa correcta y su hora —
      requiere minos real, pendiente de confirmar en el host.
