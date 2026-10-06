---
name: spei-pagos-rechazo-liquidacion
description: Diseña y ejecuta pruebas de recepción y liquidación de pagos (spec 014) contra el simulador SPEI real (banxico-simulator) — forzar el rechazo de la próxima orden que mande minos (o de una con clave de rastreo específica) con un motivo elegido del catálogo real de devoluciones, consultar/cancelar ese rechazo forzado, consultar el saldo del día operativo, mandar un Cargos manual o liquidar en lote lo acumulado, y cerrar el día operativo (LiquidacionFinal). Úsalo siempre que el usuario mencione "spec 014", "rechazo forzado", "forzar que minos reciba un rechazo", "motivo de rechazo/devolución", "saldo del día operativo", "Cargos", "liquidar lote", "cerrar el día operativo", o pida probar cómo reacciona minos ante una orden rechazada o ante el cierre de día. NO uses este skill para variaciones de RED (retraso, corte, duplicación, throttling — spec 005, otro skill), para mandar abonos de prueba (specs 001-004, 006), ni para campañas de volumen (spec 012) — esos son otro alcance.
allowed-tools: mcp__plugin_banxico-simulator_simulador__simulator_health, mcp__plugin_banxico-simulator_simulador__simulator_session, mcp__plugin_banxico-simulator_simulador__simulator_force_payment_rejection, mcp__plugin_banxico-simulator_simulador__simulator_payment_rejection_status, mcp__plugin_banxico-simulator_simulador__simulator_cancel_forced_rejection, mcp__plugin_banxico-simulator_simulador__simulator_payment_balance, mcp__plugin_banxico-simulator_simulador__simulator_send_cargos, mcp__plugin_banxico-simulator_simulador__simulator_pending_cargos, mcp__plugin_banxico-simulator_simulador__simulator_flush_pending_cargos, mcp__plugin_banxico-simulator_simulador__simulator_close_operational_day
---

# SPEI recepción, rechazo forzado y liquidación de pagos (spec 014)

## Por qué existe este skill

`specs/014-recepcion-y-liquidacion-de-pagos.md` completa el lado de **recepción** del protocolo:
cuando minos manda una orden (`OrdenTopoV`), el simulador ya la valida contra el catálogo real de
Judeca y responde `AcuseRecibo` con el motivo correcto si la rechaza, pero esta spec agrega tres
capacidades que no existen en el protocolo real tal cual — son herramienta de prueba, no
simulación de Banxico: una **palanca para forzar un rechazo** de una orden que de otro modo sería
válida (para observar cómo reacciona minos ante un `AcuseRecibo` con `status` RECHAZADA, requisito
explícito de la especificación funcional de la iniciativa), consulta de **saldo** del día
operativo, y disparo manual de **liquidación** (`Cargos` por lote, `LiquidacionFinal` de cierre de
día).

El simulador real vive en el host `192.168.1.200`, alcanzable por VPN, conectado a una instancia
real de minos. Es infraestructura real, no un sandbox descartable — trátalo con el mismo cuidado
que tocar producción.

## Regla de autonomía: depende de la acción

- **Consultas (siempre directas, sin pedir confirmación):** `simulator_session`,
  `simulator_payment_rejection_status`, `simulator_payment_balance`, `simulator_pending_cargos`.
  Son de solo lectura, no cambian nada.
- **Armar/cancelar/disparar (propone el plan, espera confirmación explícita antes de ejecutar):**
  `simulator_force_payment_rejection`, `simulator_cancel_forced_rejection`, `simulator_send_cargos`,
  `simulator_flush_pending_cargos`, `simulator_close_operational_day`. Todas tienen efecto real
  sobre la sesión viva con minos — algunas (rechazo forzado) afectan la **próxima** orden que
  llegue, que podría no ser la que el usuario tenía en mente si alguien más está probando algo al
  mismo tiempo.

Una pregunta genérica ("¿puedo forzar un rechazo?", "¿cómo cierro el día?") es diseño, no
ejecución — responde con el plan, no lo dispares de una vez.

## Rechazo forzado

### 1. Antes de armar

Revisa `simulator_session` (hay sesión viva) y `simulator_payment_rejection_status` (no hay uno ya
armado por alguien más — si lo hay, dilo con quién/motivo y no lo pises sin confirmar que esa
persona ya terminó).

### 2. Elegir el motivo

Lee `references/catalogo-motivos-rechazo.md` — el catálogo completo de motivos (1-30) que
`AcuseRecibo.codeErrors` puede reportar. Si el usuario no da un motivo, pregunta cuál quiere probar
en vez de asumir uno por default; si solo quiere "un rechazo cualquiera", 19 (Carácter inválido) es
el más neutral.

### 3. Presentar el plan

```
## Plan: rechazo forzado
**Motivo:** <código> — <nombre del catálogo>
**Alcance:** <"la próxima orden que mande minos, de cualquier clave" o "solo la orden con clave de rastreo <X>">
**Quién:** <nombre de quien lo pide>
**Cómo se observa:** AcuseRecibo con status RECHAZADA y el motivo elegido, en /test-runs/{id}/events o en los logs de minos
**Riesgo:** si alguien más manda una orden de prueba mientras esto está armado y no dio clave de rastreo, esa orden (no necesariamente la que el usuario espera) será la rechazada
```

Espera confirmación explícita antes de llamar `simulator_force_payment_rejection`.

### 4. Tras armar

No hace falta reenviar nada manualmente — se consume solo en cuanto llegue (o ya haya) una orden
que coincida. Si el usuario quiere verlo de inmediato y tiene forma de disparar una orden de prueba
desde minos (fuera del alcance de este skill — eso es del lado de minos/Judeca/Core falso), dilo.
Usa `simulator_payment_rejection_status` para confirmar que se consumió (pasa a `TERMINADA`) y
revisa el `AcuseRecibo` resultante.

### 5. Cancelar sin esperar

Si el usuario ya no quiere el rechazo armado (se equivocó de motivo, cambió de idea), confirma y
llama `simulator_cancel_forced_rejection` — 409 si no hay ninguno activo.

## Saldo y Cargos

- **Consultar saldo:** `simulator_payment_balance` directo, sin plan ni confirmación.
- **Cargos automático:** en modo `inmediato` (el default — no hay endpoint para consultar el modo
  configurado en este despliegue; si `simulator_pending_cargos` siempre regresa vacío después de
  órdenes aceptadas, es buena señal de que está en `inmediato`, pero no es concluyente), cada
  `AcuseRecibo` aceptado ya dispara su propio `Cargos` solo — no hace falta ni hay que disparar nada
  a mano para el flujo normal.
- **Cargos manual arbitrario** (`simulator_send_cargos`, no ligado a una orden real): úsalo solo si
  el usuario pide explícitamente probar un escenario de saldo sin depender de un `OrdenTopoV` real.
  Propón el plan (folio, entradas, montos) antes de mandarlo — cambia el saldo real que minos tiene
  registrado.
- **Modo acumulado:** si el despliegue usa `cargos.modoLiquidacion=acumulado`, las órdenes
  aceptadas se acumulan sin liquidar hasta que alguien dispare el lote. `simulator_pending_cargos`
  (consulta directa) muestra qué hay pendiente; `simulator_flush_pending_cargos` (propone el plan,
  confirma) lo liquida todo de una vez — 409 si no hay nada pendiente.

## Cierre de día operativo (`LiquidacionFinal`)

**El más disruptivo de todos los disparados por este skill.** Dispara en minos: desconexión y
reconexión de ARA, posible purga de datos viejos, notificación a Radamanto — un evento real de
cierre de día, no cosmético. Siempre plan + confirmación explícita, incluso si el usuario lo pide
de forma casual ("cierra el día"):

```
## Plan: cierre de día operativo
**Monto final:** <el que dé el usuario, o 0 si no especifica>
**Qué dispara en minos:** reconexión de ARA, posible purga de datos antiguos, notificación a Radamanto
**Riesgo:** evento real de cierre de día -- no reversible, no lo propongas como algo de "solo prueba" sin más
```

Tras ejecutar `simulator_close_operational_day`, confirma con `simulator_session` que la sesión
SPEI sigue sana (no debería caerse de forma anómala) y reporta lo que minos haya notificado.

## Notas honestas sobre el enfoque

- El rechazo forzado es una palanca de **prueba**, no algo que exista en el protocolo SPEI real —
  Banxico real nunca "decide forzar" un rechazo arbitrario; esto existe para ver cómo reacciona
  minos ante un caso que de otra forma sería imposible de provocar a voluntad. Dilo si el usuario
  lo compara contra el comportamiento real de Banxico.
- La detección de clave de rastreo duplicada (motivo 30) y el saldo del día operativo persisten en
  H2 por fecha, no en memoria de la sesión — sobreviven una reconexión de minos a medio día. Si
  algo parece "no resetear" tras una reconexión, es a propósito, no un bug.
