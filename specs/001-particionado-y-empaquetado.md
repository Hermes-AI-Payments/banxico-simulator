# 001 — Particionado y empaquetado configurable (B↔H)

## Contexto

Hoy el simulador asume que todo mensaje cabe en un solo paquete:

- **Saliente (B→H, `Abonos`):** `AbonosCodec.buildAbonoV`/`buildPayload` arman exactamente 1
  `abonoT` y 1 `abonoV` por mensaje (documentado en el javadoc de la clase: "v1 manda exactamente
  1 'abonoT'... y 1 'abonoV'..."). No existe manera de empaquetar varias transacciones en un solo
  `Abonos`, ni de partir un lote grande en varios paquetes.
- **Entrante (H→B, `Reenvio`/`OrdenTopoV`):** `SpeiSession.handleReenvio` (`SpeiSession.java:364`)
  responde `FinReenvio` **sin reensamblar nada de verdad** — es un stub. `README.md` ya documenta
  esto como limitación conocida de v1: *"El simulador siempre manda sus mensajes completos en una
  sola parte... Si una prueba futura necesita forzar partición real, hay que implementar
  reensamblado en WireFraming/SpeiSession."* Esta spec es esa prueba futura.

**El límite de 32 KB no es arbitrario:** el prefijo de tamaño total en `WireFraming.withLengthPrefix`
se escribe como `short` (`writeShortBE`), con un máximo representable de 32 767 bytes — casi
exactamente el límite de 32 KB mencionado. Cualquier paquete individual ya está acotado por el
propio formato de wire, con o sin esta spec.

## Requisitos

1. **Control de empaquetado saliente (B→H):** poder elegir, por corrida de prueba, si N
   transacciones se mandan una por paquete o empaquetadas (varias por paquete, respetando el
   límite de 32 KB — si no caben todas, particionar en más de un paquete).
2. **Detección de empaquetado entrante (H→B):** el simulador debe poder distinguir, al recibir,
   si las órdenes llegan una por paquete o repartidas en varios paquetes que requieren
   reensamblado real — y reensamblarlas correctamente antes de procesarlas.

## Fuera de alcance (de esta spec específicamente)

- Variaciones de red (latencia, pérdida, reordenamiento) — spec aparte, depende de esta pero no la
  bloquea.
- Los demás flujos del brainstorm (devoluciones, CoDi, reenvío con pérdida real) — usan el
  particionado que aquí se define, pero se especifican por separado.
- Integración MCP.

## Diseño propuesto (a discutir)

- **Saliente:** una `BatchPolicy` (nombre tentativo) configurable vía la API de control al
  disparar una corrida: `{"modo": "uno-por-paquete" | "empaquetado", "cantidad": N}`. En modo
  empaquetado, la lógica agrupa `abonoV`/`abonoT` hasta acercarse al límite de 32 KB (dejando
  margen para el envoltorio de firma+cifrado, que agrega overhead variable — la agrupación debe
  medirse sobre el tamaño *ya envuelto*, no sobre el payload crudo) y parte en más de un frame si
  no alcanza.
- **Entrante:** implementar el reensamblado real que `handleReenvio` hoy no hace — acumular partes
  hasta que el `totalSize` declarado en el primer frame coincida con lo acumulado, en vez de
  asumir siempre un solo frame. Requiere decidir cómo el simulador *fuerza* que minos parta sus
  envíos (para poder probar esta ruta) — ver primera pregunta abierta.

## Preguntas abiertas

- **Resuelta (2026-09-21):** ¿cómo se le indica a minos que debe partir su envío para poder
  probar el reensamblado H→B? — **No se le indica.** Por decisión explícita, el simulador no es
  responsable de forzar ni de simular el comportamiento de minos (ver spec 003, mismo principio
  aplicado a `Reenvio`). Probar el reensamblado real se hace contra el **arnés Python** que actúa
  como "minos falso" (el mismo que ya se usa para verificación end-to-end antes de probar contra
  minos real, ver README "Estado de avance por fase") — ese arnés sí está bajo nuestro control y
  puede partir su envío deliberadamente en N frames para ejercitar el reensamblado. La prueba
  contra minos real (si alguna vez parte algo de forma nativa) queda como validación adicional,
  no como el mecanismo de prueba principal.
- ¿El límite de 32 KB es el mismo en ambas direcciones, o minos podría tener su propio límite
  distinto del lado que envía?
- ¿Cómo se reporta en `/test-runs/{id}/events` un mensaje que llegó/se mandó en varias partes —
  un evento por parte, o un evento consolidado con las partes como detalle?

## Referencia de un día de certificación real (analizado 2026-09-21)

De `minosA.log.2025-09-12.1` (log real de un día de certificación SPEI, ~46 segundos con tráfico
de `Abonos`, 21 mensajes recibidos por minos):

- **Tamaño real en el wire (cifrado):** entre 1360 y 1984 bytes por mensaje `Abonos`. **Muy por
  debajo** del techo de 32 KB — en este día de certificación nunca se acercó al límite.
- **Tamaño de lote real:** nunca más de **2** `AbonoV` por mensaje `Abonos` (9 mensajes con 2,
  12 con 1). No se observó ningún lote grande — el "empaquetado" real observado es modesto, no
  agresivo.
- **Cadencia:** un mensaje `Abonos` cada ~2.3 segundos, muy regular — consistente con un ciclo de
  Banxico que agrupa lo acumulado en esa ventana y lo manda, no con ráfagas irregulares.
- **Firma:** el 100% de los mensajes en la muestra validó con `RSASSA-PSS` (confirma en producción
  real lo mismo que encontramos y corregimos en el simulador).

**Implicación para el diseño:** el escenario "realista" de empaquetado a probar primero debería
ser lotes pequeños (1-2 `AbonoV`) cada ~2 segundos, no forzar inmediatamente el caso extremo de
acercarse a 32 KB — ese caso extremo sigue siendo válido como prueba de estrés, pero no es el
flujo típico observado. No fue posible ver el contenido real (`tipoPg`, montos, cuentas) desde el
log porque viaja cifrado dentro del wire — el log solo expone metadatos de framing (tamaño,
timestamp, claves de rastreo), no el contenido de la orden.

## Estado de implementación (2026-09-22)

**Mecanismo de partición real, implementado y verificado byte a byte contra el código fuente real
de minos, ambos lados** (`SpeiOutputSignedPartitionedMessage.setMessageParts()` para envío,
`SpeiInputPartitionedMessage.addPart()`/`isComplete()` para recepción): el prefijo de `totalSize`
se antepone UNA SOLA VEZ al inicio de todo el stream firmado, ese stream se corta en trozos de
`maxMessageLength - 4` bytes, y cada trozo se cifra por separado (sin encadenar AES-CBC entre
trozos). Confirma y resuelve la pregunta abierta anterior sobre cómo forzar que minos parta de
verdad: `maxMessageLength` es el mismo valor que el simulador declara en su propio `EnSesion`
(`EnSesionCodec.buildBody`) — bajarlo con `wire.maxMessageLength` en la config hace que **minos
real** empiece a partir sus propios envíos, sin necesitar ningún arnés para esta parte.

- `WireFraming.buildEncryptedSignedPartitionedFrames(...)` — lado de envío, genera 1+ frames.
- `WireFraming.PartitionedAccumulator` — lado de recepción, reensambla real (antes: stub que
  tronaba con `IllegalStateException` ante cualquier mensaje partido).
- `SpeiSession.handleOrdenTopoV` ahora lee frames adicionales del socket hasta completar el
  mensaje antes de parsear.
- `SpeiSession.sendAbono` ahora manda 1+ frames según el tamaño real, usando
  `config.maxMessageLength()`.
- **Con el valor por defecto (65535), el comportamiento es idéntico byte a byte al de antes** —
  el camino de envío de abonos que ya confirmamos funcionando contra minos real no se tocó.

**Compilado, no probado todavía contra minos real** — falta bajar `wire.maxMessageLength` a un
valor pequeño (ej. 200) y confirmar que minos realmente parte sus envíos y que el simulador los
reensambla bien, y viceversa para el envío.

**Pendiente, no implementado en esta pasada:** empaquetar varias transacciones (`AbonoV`) en un
solo mensaje `Abonos` — hoy sigue mandando exactamente 1 por mensaje (`buildPayload` sigue
hardcodeado a `abonosV=1`). Es un cambio de menor riesgo (el wire ya soporta N, `judeca`/minos
real llega a 2 por mensaje según el log de certificación analizado) pero no se hizo todavía para
no alargar más este cambio.

**Actualización 2026-09-29 (spec 012):** un abono aislado sigue confirmado funcionando contra
minos real, pero se encontró que minos responde `AcuseParteCas` (código 198 — "mensaje
particionado incompleto") después de `MsjCatalogos` y `FinReenvio` en cada handshake, y que
mandar `Abonos` en ráfaga rápida (300/min) eventualmente tumba la sesión. No parece ser un bug de
la aritmética de `WireFraming` de este repo (autochequeo confirmado consistente) — hipótesis de
trabajo: un `temporalPart` compartido entre tipos de mensaje del lado de minos. Detalle completo
y evidencia en `specs/012-pruebas-de-volumen.md` §"Hallazgo 2026-09-29".

## Criterios de aceptación

- [ ] Disparar una corrida con 5 transacciones B→H en modo "uno por paquete" produce 5 frames
      `Abonos` distintos, verificable en `/test-runs/{id}/events`.
- [ ] Disparar la misma corrida en modo "empaquetado" produce el menor número de frames que quepan
      dentro de 32 KB cada uno, con las 5 transacciones presentes y en orden.
- [ ] Un envío H→B partido en múltiples frames se reensambla correctamente antes de validarse
      (mismo resultado que si hubiera llegado en un solo frame con el mismo contenido).
- [ ] Un envío H→B con `totalSize` declarado que nunca se completa (parte faltante) no cuelga la
      sesión indefinidamente — se define un timeout o comportamiento explícito.
