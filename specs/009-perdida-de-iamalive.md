# 009 — Pérdida de `IAmAlive` (heartbeat)

## Contexto

**Corregido 2026-10-06 — el supuesto original de esta sección era falso, refutado contra minos
real y contra su código fuente.** Se creía (cita original: "minos fija un timeout de lectura de 6
segundos en su socket SPEI y cierra la conexión de inmediato ante cualquier `IOException` de
timeout, sin reintentos") que dejar de mandar `AreYouAlive` bastaba para que minos cerrara la
sesión a los ~6s. Verificado contra `code/mki/minos` (`SpeiInputListener.java:113-121`, método
`run()`): el bucle de lectura SÍ tiene un `SocketTimeoutException` de 6s, pero se **atrapa y se
ignora** (`logger.debug("Sin datos de Banxico en {}, esperando siguiente mensaje...")`) -- el bucle
sigue (`execute` no cambia), indefinidamente. Solo un `IOException` real (`read()` devuelve `-1`,
EOF/FIN real del socket) u otra excepción dispara `closeConnection()`/`reConnect()`. Es decir:
**minos no tiene ningún failsafe de heartbeat por timeout de lectura** -- solo nota la pérdida de
Banxico si la conexión TCP se cierra de verdad (FIN/RST), no por ausencia de mensajes. El emisor
del heartbeat es Banxico -- el simulador manda `AreYouAlive` cada 3 segundos (`startHeartbeat()`) y
minos responde `IAmAlive`, pero minos nunca usa su ausencia para decidir nada.

**Confirmado por el equipo de minos (Pedro, 2026-10-09):** verificado en su código, coincide con lo
de arriba. Agregan un detalle que no habíamos visto: si el timeout de 6s cae **a mitad de la
lectura de un mensaje** (no entre mensajes), los bytes ya leídos de ese mensaje se pierden -- no
solo se ignora el timeout, el mensaje parcial en sí desaparece. Ya quedó registrado en su backlog
para corregirse; no es algo que este simulador pueda probar o mitigar (es estado interno de
`SpeiInputListener`, no algo que dependa de qué o cuándo manda Banxico).

**Implicación que vale la pena escalar (no solo de testing):** si Banxico real alguna vez se queda
silencioso a nivel de red sin mandar un FIN/RST limpio (firewall que descarta paquetes en
silencio, por ejemplo), minos se quedaría creyendo la sesión viva indefinidamente, sin ningún
mecanismo de por sí que lo saque de ese estado. Esto es relevante para MK I en producción (R3), no
solo para este simulador -- vale la pena que el equipo de minos lo sepa y decida si es un riesgo
aceptado o algo que corregir (ej. un timeout de aplicación sobre ausencia de `IAmAlive`, no solo el
timeout de socket).

## Requisitos

1. Poder **suspender** el envío de `AreYouAlive` a mitad de una sesión de prueba (simulando que
   Banxico deja de mandar heartbeats) y confirmar que minos cierra la sesión, midiendo cuánto
   tarda en hacerlo (debería rondar los 6s documentados).
2. Poder simular que el simulador **recibe** un `IAmAlive` de minos con retraso o no lo recibe
   del todo, para observar si el simulador mismo tiene alguna lógica de expiración de sesión que
   dependa de eso (hoy no parece tenerla — solo registra el mensaje al recibirlo, no mide cuánto
   tarda en llegar).

## Fuera de alcance

- Cambiar el intervalo real de heartbeat de producción (3s) — esta spec es sobre **probar** el
  comportamiento existente, no sobre cambiar la cadencia real.

## Diseño propuesto

- Parámetro de escenario que detiene `startHeartbeat()` en un punto configurable de la corrida
  (ej. "después del N-ésimo heartbeat" o "después de mandar tal mensaje") en vez de dejarlo correr
  siempre.
- Medir y registrar en `/test-runs/{id}/events` cuánto tiempo pasa entre la suspensión y el cierre
  de conexión por parte de minos, para confirmar que coincide con el timeout documentado de 6s.

## Preguntas abiertas

- ¿Vale la pena también probar el caso contrario — que el simulador deje de *procesar* (no de
  mandar) heartbeats, para ver si eso afecta algo del lado del simulador mismo? Hoy no hay lógica
  de timeout propia documentada del lado del simulador para la recepción de `IAmAlive`.

## Estado de implementación (2026-10-06)

Implementado: `POST /heartbeat/detener` suspende de inmediato el `AreYouAlive` saliente de la
sesión activa (`SpeiSession.suspendHeartbeat()`). **Probado contra minosA real (2026-10-06,
runId 3221):** tras suspender el heartbeat, la sesión siguió `alive:true` en `GET /session` más
de 5 minutos después, sin ningún cierre -- confirmado también en los logs reales de minos
(`journalctl -u minosa.service`), que siguieron repitiendo cada ~6s el ciclo normal de lectura
(`Hilo spei-tcp-1, tiempo: 6.00Xs`) sin ninguna señal de cierre, consistente con el código fuente
citado arriba. El requisito 1 original de esta spec queda refutado: no hay nada que "confirmar
que minos cierra" porque minos no cierra.

## Criterios de aceptación

- [x] Se puede detener el heartbeat saliente en un punto conocido de una corrida de prueba.
- [x] Se confirma (2026-10-06, contra minosA real) que minos **no** cierra la sesión solo por
      ausencia de heartbeat -- la sesión permanece viva indefinidamente sin heartbeat saliente.
      Esto contradice el supuesto original de la spec (que sí cerraría a los ~6s); queda refutado
      y documentado arriba, no es simplemente "sin probar".
