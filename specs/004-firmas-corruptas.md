# 004 — Escenarios de firma corrupta (ambos sentidos)

## Contexto

Hoy solo existe un camino de "firma inválida" del lado B→H: `AbonosCodec.buildAbonoV` con
`signProperly=false` manda 32 bytes en cero como firma de relleno (`new byte[32]`) — prueba que
la ruta estructural no depende de la firma, pero no prueba una firma **corrupta con formato
válido** (mismo tamaño/codificación que una firma real, contenido inválido) — que es un caso
distinto y más realista de qué tan bien detecta minos una firma que no es simplemente "vacía".

## Requisitos — alcance decidido (2026-09-21)

Contemplar firma corrupta (formato válido, contenido inválido) en **ambos sentidos**:

1. **B→H:** el simulador manda un `Abonos`/`OrdenTopoV` (donde aplique) con una firma del tamaño
   y codificación correctos, pero criptográficamente inválida (ej. firmar con una llave distinta
   a la que minos tiene registrada, o alterar un byte de una firma real) — para confirmar que
   minos la rechaza correctamente en vez de aceptarla o tronar.
2. **H→B:** vía el arnés Python ("minos falso", ver spec 003), mandar una orden con firma
   corrupta pero bien formada, y confirmar que el simulador la rechaza con el motivo correcto
   (no con una excepción no manejada).

## Fuera de alcance

- Firma vacía/de relleno (`new byte[32]`) — ya existe, no es el objeto de esta spec.
- Ataques de firma criptográfica reales (forjar una firma válida sin la llave privada) — esto es
  simplemente "firma que no valida", no un ejercicio de criptoanálisis.

## Diseño propuesto

- Agregar una tercera variante además de "firma real"/"firma vacía": "firma corrupta" — mismo
  tamaño de bytes que una firma real (256 bytes para RSA-2048), pero generada alterando un byte de
  una firma real o firmando con una llave que no es la registrada.
- Del lado H→B, el arnés Python necesita la misma capacidad simétrica.

## Preguntas abiertas

- ¿"Corrupta" para esta prueba significa "un byte alterado de una firma real" (case más común en
  la práctica — corrupción en tránsito) o "firmada con una llave incorrecta pero coherente"
  (más relevante para probar el matching de certificados)? Podrían ser dos casos distintos, no
  necesariamente uno solo.

## Estado de implementación

**2026-09-21:** Parte B→H lista en código: `AbonosCodec.SignatureMode` ahora tiene
`VALIDA`/`VACIA`/`CORRUPTA` (antes solo booleano válido/relleno) — `CORRUPTA` firma de verdad y
luego voltea un byte, disparable vía `POST /abonos` con `"firma":"corrupta"`. Compilado, no
probado todavía contra minos real. Parte H→B bloqueada por la falta del arnés (ver
`specs/README.md`).

**2026-10-09 — ambas partes probadas. Arnés Java ya existente (spec 003/014) extendido con**
`WireFraming.signedBlockCorrupto`/`buildEncryptedSignedPartitionedCorrupto` (mismo criterio que
`AbonosCodec.corrupt`: firma real, un byte volteado de la firma CRUDA antes de Base64 -- volcarlo
después de codificar arriesgaba aterrizar en un carácter fuera del alfabeto Base64, que es una
falla de decodificación distinta a una falla de verificación) y
`FakeMinosClient.sendOrdenTopoVConFirmaCorrupta`.

**Parte H→B — hallazgo real, no solo falta de prueba:** antes de escribir el test, se confirmó que
`SpeiSession.handleOrdenTopoV` **calculaba `unwrapped.signatureVerified()` pero nunca lo exigía** —
una `OrdenTopoV` con firma de envoltura inválida se procesaba exactamente igual que una válida,
pudiendo quedar ACEPTADA. No era un caso sin probar, era un hueco real de validación. **Corregido**
en el mismo commit: si la firma no verifica, se rechazan TODAS las órdenes del `folioPack` con
motivo 19 (`CARACTER_INVALIDO` — el catálogo real de devoluciones no tiene un motivo específico
para "firma inválida", ver nota de clase de `MotivoRechazo`), sin correr rechazo forzado, clave
duplicada ni `OrderFieldValidator` sobre contenido que no se puede confiar que sea auténtico (se
extrajo `finalizarOrdenTopoV` para compartir el armado de `AcuseRecibo`/disparo de `Cargos` entre
este camino y el normal). Verificado con dos pruebas nuevas (`FirmaCorruptaAcceptanceTest`: una
orden sola, y un folioPack con dos órdenes -- confirma que se rechazan AMBAS, no solo la primera),
suite completa en verde (16/16).

**Parte B→H — probada en vivo contra minosA real (2026-10-09, runId 6315, `trackingKey`
`SPEC004FIRMACORRUPTA1791583613`), con un hallazgo que contradice el criterio de aceptación
original:** el log real de minos (`journalctl -u minosa.service` en `.52`) muestra
`AbonoInceptionMessage`: `firma validada con algoritmo SHA512withRSA/PSS` seguido de
`Valid Signaure: false` -- **minos sí detecta correctamente que la firma es inválida.** Pero
inmediatamente después, sin ninguna bifurcación visible en el log, sigue procesando el mensaje
exactamente igual que uno válido: lo guarda (`MsgSaved`), le asigna folio, manda `AcuseParteCas`
(el ack de transporte normal de un mensaje particionado) y `AbonosMessageHandler` lo despacha con
`evento=ABONO_RECIBIDO_BANXICO`. **No hay rechazo, no hay camino distinto para firma inválida** --
minos registra la falla pero no actúa sobre ella. La sesión SPEI real no se vio afectada (`GET
/session` mostró el mismo `runId`/`vivaDesde` antes y después, confirmando que esto no es
disruptivo para la sesión, solo un hueco de validación de contenido).

## Hallazgo a escalar (no es un bug de este simulador)

**minos acepta un `Abonos` con firma de contenido criptográficamente inválida.** Confirmado contra
minosA real, log citado arriba. Esto es relevante más allá de este simulador (MK I en producción,
R3) -- si Banxico real alguna vez mandara (o un tercero lograra inyectar) un abono con firma
corrupta, minos lo procesaría como si fuera legítimo en vez de rechazarlo. Vale la pena que el
equipo de minos lo sepa y decida si es un riesgo aceptado (quizás la validación real ocurre en otra
capa no visible en este log) o algo que corregir -- mismo patrón de escalación que spec 009.

## Criterios de aceptación

- [x] Un `Abonos` con firma corrupta (no vacía) se manda y se puede confirmar en el log de minos
      qué hizo con ella. **Resultado real, 2026-10-09: minos detecta la firma inválida
      (`Valid Signaure: false`) pero NO la rechaza -- la procesa igual que una válida.** El
      criterio original asumía que minos la rechazaría; queda refutado y documentado arriba, no es
      "sin probar". No tronó con una excepción no manejada en ningún lado (ni en minos ni en este
      simulador).
- [x] Una orden entrante con firma corrupta (vía arnés Java) es rechazada por el simulador con el
      motivo correcto (19, `CARACTER_INVALIDO`), registrado en `/test-runs/{id}/events` --
      **confirmado 2026-10-09, y solo después de corregir un bug real** (la verificación se
      calculaba pero no se exigía antes de este trabajo). Ver `FirmaCorruptaAcceptanceTest`.
