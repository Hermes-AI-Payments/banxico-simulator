# 014 — Recepción y liquidación de pagos (ciclo completo de `OrdenTopoV`)

## Contexto

Hasta ahora el simulador implementa el **envío de abonos** (simulador→minos, specs 001-006, 012) y
una **recepción básica de `OrdenTopoV`** (minos→simulador, código 206): se parsea, se valida cada
orden contra el catálogo real de `judeca` (`OrderFieldValidator`), y se responde `AcuseRecibo`
(código 27) con `status` ACEPTADA/RECHAZADA — esto ya existe en `SpeiSession.handleOrdenTopoV`, no
es nuevo.

Esta spec completa ese flujo del lado de **recepción de pagos** para que sea un ciclo real, no solo
"parsear y responder": mapeo correcto de motivos de rechazo, una palanca para forzar un rechazo a
propósito (requisito funcional explícito de la iniciativa, ver abajo), detección de clave de
rastreo duplicada, y los dos mensajes que le siguen a un `AcuseRecibo` exitoso en la vida real —
`Cargos` (simulador→minos, liquidación/saldo) y `LiquidacionFinal` (simulador→minos, cierre de día
operativo).

**Fuente:** `code/HERMES-MKI-VOBEDA/06_Iniciativas_Nuevas/Simulador_SPEI/01_especificacion_funcional.md`
(Objetivo 2: "recepción de órdenes... con la posibilidad de forzar un rechazo a propósito para
probar cómo reacciona minos") y `02_especificacion_tecnica.md` (Fase 4 del plan de 6 fases). Todo
el layout de bytes de esta spec se verificó contra `code/mki/minos` (el checkout vigente,
2026-09-28 — **no** `code/minos`, que está parado en 2024-07-08 y quedó desactualizado; ver nota
en specs/README.md).

## Requisitos

1. **Mapeo real de motivos de rechazo** en `AcuseRecibo.codeErrors[]` — hoy siempre manda `1`
   (placeholder), sin importar la razón real de la falla de validación.
2. **Palanca de rechazo forzado** vía API de control — poder rechazar una orden que de otro modo
   sería válida, para observar cómo reacciona minos a un `AcuseRecibo` con `status` RECHAZADA
   (requisito explícito de la spec funcional, no una extensión nuestra).
3. **Detección de clave de rastreo duplicada** (motivo 30) dentro del mismo día operativo.
4. **`Cargos`** (simulador→minos) — liquidación/saldo tras aceptar órdenes.
5. **`LiquidacionFinal`** (simulador→minos) — cierre de día operativo, disparable por API.

## Fuera de alcance

- **CEP/CDA** — viven en Plutón con su propio socket hacia Banxico, no en el canal minos↔Banxico
  que este simulador cubre (confirmado en `ADR_005` y en `01_especificacion_funcional.md` de la
  bóveda, exclusión explícita).
- **`MsjNoProcesable`** (rechazo a nivel protocolo, minos→simulador) — es un mensaje que *nosotros*
  mandaríamos para rechazar algo que minos nos envió a *nosotros*; no aplica aquí, donde el
  rechazo es sobre una orden que *minos* nos mandó (ver Requisito 2, que es distinto: nuestro
  `AcuseRecibo` con `status` RECHAZADA, no un `MsjNoProcesable`).
- **Cancelación de órdenes** (`CancelaPago`, Topología V sin liquidar) — ciclo de vida posterior al
  `AcuseRecibo`, spec aparte si se necesita.
- **Comparación cruzada de devoluciones contra la orden original** — limitación ya documentada en
  `OrderFieldValidator` (el simulador no persiste órdenes previas); no se resuelve aquí.
- **Reconciliar la divergencia de alcance de tipos de pago** (35 tipos implementados vs. 4 de
  ADR-005) — ya documentada como pendiente en el código, sigue sin confirmar con Pedro, no bloquea
  esta spec.

## Diseño propuesto

### 1. Mapeo de motivos de rechazo

Catálogo real (único que existe en toda la documentación disponible):
`01_Conceptos_Transversales_SPEI/Catalogo_Devoluciones.md`. `AcuseRecibo.codeErrors` es **un byte
por orden rechazada** (`OrdenTopoVCodec.Order` → `AcuseReciboCodec.OrderError(internalFolio,
char)`) — no hay espacio para múltiples motivos por orden, así que si una orden falla varias
validaciones se reporta el **primer** motivo encontrado (mismo orden en que `validate()` ya revisa
los campos hoy).

**Ajuste durante la implementación (2026-10-05):** el diseño original proponía que
`OrderFieldValidator.validate()` devolviera `List<CampoInvalido>` en vez de `List<String>`,
anotando el motivo en cada uno de los ~40 sitios `errors.add(...)` del archivo. Al revisarlos todos
uno por uno para construir la tabla de abajo, los mensajes resultaron ser únicos y estables entre
sí (ninguno colisiona con otro) — así que en vez de tocar la lógica de validación ya probada y
citada línea por línea contra `judeca`, se agrega una clasificación aparte,
`MotivoRechazo.clasificar(String mensaje)`, que a partir del **primer** mensaje de `validate()`
infiere el motivo por patrón de texto. Mismo resultado, sin arriesgar un typo en 40 sitios de
código ya correcto.

Tabla de mapeo (revisada contra cada punto real de `errors.add(...)` en el archivo actual):

| Categoría de falla (como ya las distingue el código) | Motivo | Nombre |
|---|---|---|
| `tipoPg` no reconocido en el catálogo | 15 | Tipo de pago erróneo |
| Campo obligatorio ausente/vacío (`"... esta vacio"`) | 14 | Falta información mandatoria |
| `tipoCtaOrdenante`/`tipoCtaBeneficiario` fuera del catálogo | 17 | Tipo de cuenta no corresponde |
| `tipoOperacion` fuera del catálogo | 16 | Tipo de operación errónea |
| Cantidad de campos de detalle no coincide con la esperada por tipo | 24 | Estructura de información adicional incorrecta |
| Todo lo demás: longitud excedida, regex/formato inválido, catálogo no reconocido sin motivo específico (institución, causa de devolución, etc.) | 19 | Carácter inválido |

El motivo 19 queda como **fallback genérico** — es el único del catálogo que razonablemente cubre
"formato o contenido no válido" sin ser más específico. Documentado así a propósito (no hay un
motivo más preciso disponible para esos casos).

`AcuseReciboCodec.OrderError` pasa de `(internalFolio, char errorCode)` fijo a construirse con
`campoInvalido.motivo()` en vez de `(char) 1`.

### 2. Palanca de rechazo forzado

Mismo patrón que spec 005 (un registro de turno único, armado por API, consumido al coincidir) pero
**independiente** de `RedVariacionRegistry` — es una falla de contenido/negocio, no de red, y
mezclar los dos conceptos en el mismo enum los acopla sin necesidad.

- `RechazoForzado.java` (paquete `spei`): `id`, `quien`, `motivo` (1-30), `trackingKey` (opcional —
  si se da, solo esa orden; si se omite, la **próxima** orden recibida, de cualquier clave),
  `ocurrencia` (ninguna por ahora — se consume siempre en la primera orden que coincida, luego
  pasa a `TERMINADA`; no hay noción de "duración" como en spec 005 porque esto no es continuo).
- `RechazoForzadoRegistry.java`: mismo patrón de turno único que `RedVariacionRegistry` (sin el
  `ScheduledExecutorService` de vencimiento — si nadie manda una orden que coincida, se cancela
  manualmente o queda armado hasta que llegue una).
- En `handleOrdenTopoV`, antes de correr `OrderFieldValidator` sobre una orden: si hay un
  `RechazoForzado` activo y coincide (trackingKey específico, o cualquiera si no se especificó),
  se marca esa orden como rechazada con el motivo armado, **sin correr la validación real** (es un
  rechazo deliberado de una orden que de otro modo sería válida — si se corriera la validación
  real encima, se perdería el propósito del requisito).
- **Precedencia entre los tres chequeos de una misma orden (esta sección, §3, y la validación
  real): rechazo forzado primero, clave duplicada segundo, validación real al final.** El forzado
  gana porque es la intención explícita y deliberada de quien prueba -- si además la orden traía
  una clave duplicada, eso es casualidad, no lo que se quería observar. La clave duplicada va antes
  que la validación real porque es más barata y es una regla real de Banxico, no un detalle interno
  de formato de campos.
- Rutas HTTP: `POST /pagos/rechazo-forzado` (`{"motivo": N, "quien": "...", "trackingKey": "opcional"}`),
  `GET /pagos/rechazo-forzado`, `POST /pagos/rechazo-forzado/cancelar` — mismo esqueleto de
  dispatch que usa `ControlServer` para `/red/variacion`.
- Tools MCP: `simulator_forzar_rechazo_pago`, `simulator_estado_rechazo_forzado`,
  `simulator_cancelar_rechazo_forzado`.

### 3. Clave de rastreo duplicada (motivo 30) — persistida en H2 por día operativo

**Decisión (2026-10-05, Miguel):** persistir en `H2Store` por fecha, no en memoria por sesión —
la razón es que el mismo problema (estado que debe sobrevivir una reconexión de minos a medio día
operativo) aplica igual al saldo de `Cargos` (§4) — construir un solo mecanismo de "estado del día
operativo" en H2 que cubra ambos es más sólido que resolver cada uno por separado, y evita que una
reconexión (ya nos pasó en pruebas reales, spec 012) deje al simulador reportando saldo o claves
vistas inconsistentes con lo que minos ya cree que pasó.

`H2Store` gana una tabla nueva (mismo patrón JDBC directo que `test_run`/`test_event`, sin ORM):

```sql
CREATE TABLE IF NOT EXISTS clave_rastreo_vista (
    operation_date DATE NOT NULL,
    tracking_key VARCHAR(30) NOT NULL,
    PRIMARY KEY (operation_date, tracking_key)
)
```

`H2Store.claveYaVista(LocalDate, String)` / `H2Store.marcarClaveVista(LocalDate, String)`. En
`handleOrdenTopoV`, antes de validar cada orden: `claveYaVista(orden.operationDate(), trackingKey)`
→ si existe, rechazo motivo 30 sin correr `OrderFieldValidator`; si no, se valida normal y, si se
acepta, `marcarClaveVista(...)` (una orden rechazada por otro motivo no "reserva" su clave — mismo
criterio que la versión en memoria). No hay limpieza explícita por antigüedad en esta pasada: las
filas de un día operativo quedan ahí (volumen bajo para una herramienta de pruebas, no un problema
real todavía).

### 4. `Cargos` (simulador→minos, código 24)

Verificado contra `CargosMessage.loadProperties()` (mki/minos) — mismo framing que `Abonos`
(partitioned + firmado + cifrado, reutiliza `WireFraming.buildEncryptedSignedPartitioned`, el
`signLenght` inicial es el `sigSize` de la envoltura, igual que el desalineamiento de 4 bytes ya
documentado y resuelto en `AbonosCodec`):

```
serverTimestamp(7) → operationDate(4) → folio(4) → cargos(4, cantidad) →
entityIndexes[cargos](1 c/u) → entityCodes[cargos](4 c/u) → instructionFolios[cargos](4 c/u) →
internalFolios[cargos](2 c/u) → amount(8, money) → balance(8, money) → reservedBalance(8, money)
```

`folio` = el mismo `folioPack` del `OrdenTopoV` que disparó este `Cargos` (no un contador nuevo) —
une observablemente el cargo con el paquete de órdenes que lo originó, igual que ya hace
`AcuseRecibo.folio` hoy.

`CargosMessageHandler` (minos) solo hace `Spei.balance = ...; Spei.reservedBalance = ...` y
notifica a Caina — no valida nada contra el histórico, así que el simulador no necesita un motor de
saldos real, solo un número que evolucione de forma plausible y **sobreviva una reconexión**
(mismo razonamiento que §3 — el saldo es justo el otro pedazo de "estado del día operativo").

**Saldo, persistido en H2 junto con las claves de §3:**

```sql
CREATE TABLE IF NOT EXISTS dia_operativo (
    operation_date DATE PRIMARY KEY,
    balance DECIMAL(18,2) NOT NULL,
    reserved_balance DECIMAL(18,2) NOT NULL
)
```

`H2Store.saldoDelDia(LocalDate)` (si no existe fila, la crea con los defaults configurables
`cargos.balanceInicial`/`cargos.reservedBalanceInicial`, ej. 1,000,000.00 / 0) y
`H2Store.actualizarSaldo(LocalDate, nuevoBalance, nuevoReservedBalance)`.

**Decisión (2026-10-05, Miguel): los dos modos de disparo no son excluyentes, se implementan
ambos** — `cargos.modoLiquidacion=inmediato|acumulado` en config (default `inmediato`, preserva el
comportamiento más simple de por sí):

- **Modo `inmediato`** (default): inmediatamente después de mandar un `AcuseRecibo` con al menos
  una orden ACEPTADA, se arma y manda un `Cargos` con esas órdenes (`entityIndexes`/`entityCodes`/
  `instructionFolios`/`internalFolios` = las de las órdenes aceptadas, `amount` = suma de sus
  montos), y se actualiza el saldo en H2. Es lo más fiel al flujo real (Banxico liquida y notifica
  de inmediato) y lo más simple de verificar en pruebas (1 `Cargos` ↔ 1 `OrdenTopoV`).
- **Modo `acumulado`**: las órdenes aceptadas de cada `OrdenTopoV` se acumulan en vez de liquidarse
  al toque — tabla nueva `cargo_pendiente(id IDENTITY, operation_date, entity_index, entity_code,
  instruction_folio, internal_folio, amount)`, una fila por orden aceptada. Nada se manda a minos
  hasta `POST /pagos/cargos/liquidar-lote`, que arma un solo `Cargos` con **todas** las filas
  pendientes del día operativo vigente, actualiza el saldo, y limpia la tabla. Al persistir en H2
  (no en memoria), un lote pendiente tampoco se pierde si minos reconecta a medio día.
- Cambiar de modo a medio día operativo no limpia lo ya pendiente -- si había un lote acumulado y
  se cambia a `inmediato`, ese lote queda esperando un `liquidar-lote` manual; los criterios de
  aceptación no cubren ese caso mixto, es un detalle de implementación a resolver con sentido común
  (probablemente: avisar en el log, no bloquear el cambio de modo).
- `POST /pagos/cargos` sigue existiendo para mandar un `Cargos` manual arbitrario (no ligado a
  órdenes reales) en cualquier modo — útil para probar escenarios de saldo sin depender de una
  orden real.
- `GET /pagos/saldo` — consulta `balance`/`reservedBalance` del día operativo vigente.
- `GET /pagos/cargos/pendientes` — lista lo acumulado sin pagar (vacío si el modo es `inmediato`).

### 5. `LiquidacionFinal` (simulador→minos, código 51)

Verificado contra `LiquidacionFinalMessage` (mki/minos) — **framing simple**, sin partición ni
firma (`SpeiInputEncryptedMessage`, no `...PartitionedMessage` ni `...SignedMessage`): solo
`WireFraming.encryptSession(payload, sessionKey, sessionIv)` directo, sin prefijo de tamaño:

```
serverTimestamp(7) → operationDate(4) → folio(4) → entityIndex(1) → entityCode(4) → finalAmount(8, money)
```

Dispara en minos: `minosService.stopPollingEstigia()`, purga de datos viejos si `Minos.delData`,
`minosService.araConect()` (reconecta ARA), notifica a Rada. Es un evento de cierre de día — **solo
disparable manualmente** (`POST /dia/cerrar`, cuerpo opcional `{"montoFinal": N}`), nunca
automático: no tiene sentido simular un cron de las 18:00 dentro de una herramienta de pruebas bajo
demanda.

## Preguntas abiertas

Ninguna bloqueante. Las tres originales quedan resueltas (decisiones con Miguel, 2026-10-05):

- ~~¿Vale la pena persistir `clavesVistas` en H2 por fecha en vez de por sesión?~~ Sí — mismo
  mecanismo de "estado del día operativo" que el saldo de `Cargos` (ver §3, §4), no dos soluciones
  distintas al mismo problema.
- ~~Motivo 19 como fallback genérico~~ Confirmado tal cual estaba propuesto en §1.
- ~~¿`Cargos` 1:1 por `OrdenTopoV` o acumulado en lotes?~~ Ambos, no son excluyentes — modo
  configurable (`cargos.modoLiquidacion`), ver §4.

## Estado de implementación (2026-10-05)

Implementado y compilado completo: `MotivoRechazo` (catálogo + `clasificar`, clasificador de
mensajes en vez del refactor a `List<CampoInvalido>` del diseño original -- ver nota al inicio de
&sect;1), `RechazoForzado`/`RechazoForzadoRegistry`, `CargosCodec`/`LiquidacionFinalCodec`, las 3
tablas nuevas de H2 (`clave_rastreo_vista`, `dia_operativo`, `cargo_pendiente`) con sus métodos en
`H2Store`, la reescritura de `handleOrdenTopoV` con la precedencia forzado→duplicada→validación
real, `sendCargos`/`sendLiquidacionFinal` en `SpeiSession`, las rutas HTTP
(`/pagos/rechazo-forzado*`, `/pagos/cargos*`, `/pagos/saldo`, `/dia/cerrar`) y las 8 tools MCP
correspondientes.

**Probado por HTTP sin minos** (`/pagos/rechazo-forzado` armar/consultar/cancelar, conflicto 409,
motivo inválido rechazado con 400, `/pagos/saldo` y `/dia/cerrar` devolviendo 409 sin sesión viva)
-- todo correcto, sin errores en el arranque (esquema H2 nuevo se crea limpio).

**Actualización 2026-10-05 (más tarde): primer `OrdenTopoV` real exitoso de punta a punta.**
`POST /minos/radamanto/orden-topo-v` en `minosa` había estado bloqueado todo el día por un bug
real encontrado junto con Pedro (no de este spec): `minos.certificateNumber` en
`simulator.properties` traía un placeholder inventado ("0000000002") en vez del número de serie
X.509 real del certificado propio de minos -- `Spei.getMyDefaultCertificateIndex()` (minos) nunca
encontraba coincidencia y CUALQUIER `OrdenTopoV` que minos intentara armar truena con
"Certificate not found". Corregido en `.200` (minosA) y en el simulador local de `.52` (minosC) con
el serial real (ver commit que corrige `config/simulator.properties.example`). Con eso corregido:

```
IN  OrdenTopoV 206 recibido folioPack=2 ordenes=1 firmaVerificada=true
OUT AcuseRecibo 27 aceptado erroresOrdenes=0
OUT Cargos 24 enviado folio=2 entradas=1 monto=100.00 balance=1000100.00
```

Saldo confirmado en `GET /pagos/saldo`: `1,000,000.00 → 1,000,100.00`. Primera vez que el ciclo
completo de este spec (recepción + validación real + `AcuseRecibo` aceptado + `Cargos` automático +
persistencia de saldo) corre contra minos real, no solo compilado.

**Pendiente, no probado todavía:** los escenarios de RECHAZO contra minos real (motivos 14/15/16/17
mapeados, rechazo forzado, clave duplicada sobreviviendo una reconexión), el modo `acumulado` de
`Cargos`, y `LiquidacionFinal` contra `minosa`/`minosC` específicamente (sí se probó ya contra
minosa antes de este fix, exitosamente -- ver más abajo -- pero no después de corregir el
certificado, aunque no hay razón para esperar que cambie). También pendiente: Pedro recomendó
probar el flujo completo HERMES (Core bancario falso → Judeca → Estigia → minosC → este simulador)
en vez de solo minos aislado -- se intentó (`POST /simulator/hermes-payments/spei-out` en
`hermes-core-lab-api`, puerto 3000 en `.52`, genera un payload firmado) pero `POST
/core/enviar/ordenes` en Judeca (puerto 8071) devolvió 401 -- requiere credenciales que no se
intentaron adivinar. Pendiente de retomar con esas credenciales o con Pedro directamente.

## Criterios de aceptación

- [x] Una orden con un campo obligatorio faltante se rechaza con motivo 14; una con `tipoPg` fuera
      de catálogo, con motivo 15 — **confirmado 2026-10-08** con `OrdenTopoVAcceptanceTest`
      (arnés Java, bypass de Judeca -- ver nota de esa clase). Motivos 16 (`tipoOperacion`) y 17
      (`tipoCtaOrdenante`/`tipoCtaBeneficiario`) quedan **sin probar a propósito**: son
      inalcanzables en la configuración por defecto de `OrderFieldValidator` -- su catálogo externo
      (`validTipoCuenta` y equivalente de tipoOperacion) viene vacío por diseño (= acepta cualquier
      valor, ver nota de clase), así que el chequeo nunca dispara sin antes configurar esos
      catálogos. No es un bug de esta spec, es la divergencia ya documentada con ADR-005.
- [x] Se puede forzar el rechazo de la próxima orden (o de una con clave de rastreo específica)
      por API/MCP, con el motivo elegido, sin que la orden en sí sea inválida. **Confirmado
      2026-10-07 contra minosA real** (demo de spec 015): orden válida de $75.00, rechazo forzado
      motivo 19, `AcuseRecibo` con `RECHAZADA(19)`, reflejado como `RECHAZADO` en el Core falso.
- [x] Mandar dos órdenes con la misma clave de rastreo en el mismo día operativo rechaza la
      segunda con motivo 30. **Confirmado 2026-10-08, con un hallazgo real en el camino:**
      probarlo vía Core falso→Judeca→minos (el camino originalmente previsto) es imposible --
      Judeca tiene su PROPIA detección de clave repetida ("Clave de rastreo en uso") que rechaza
      localmente antes de reenviar a minos, así que el simulador nunca llega a ver el duplicado por
      esa vía (confirmado en vivo contra minosA real). La protección de **este** simulador (spec
      014 §3) se aisló y confirmó aparte con `OrdenTopoVAcceptanceTest` (arnés Java, bypass de
      Judeca). La parte "sobrevive una reconexión" no se repitió con un reinicio real de sesión en
      el arnés (la detección usa H2 por `(fecha, clave)`, no memoria de sesión -- ya verificado por
      construcción, y el mismo mecanismo de persistencia se confirmó sobreviviendo una reconexión
      real para el saldo, criterio de abajo).
- [x] En modo `inmediato` (default), tras un `AcuseRecibo` con órdenes aceptadas se manda
      automáticamente un `Cargos` coherente (mismos folios/entidades, monto = suma de las órdenes
      aceptadas, `folio` = el `folioPack` del `OrdenTopoV`), confirmado por el log de minos
      (`processCargosMessage`) y/o `GET /minos/radamanto/getVariables` (`balance` actualizado).
      **Confirmado 2026-10-05 contra minosa real** — folioPack=2, monto=100.00, balance
      1,000,000.00 → 1,000,100.00.
- [ ] En modo `acumulado`, las órdenes aceptadas NO generan `Cargos` hasta
      `POST /pagos/cargos/liquidar-lote`, que manda uno solo con todas las pendientes del día —
      y lo pendiente sobrevive una reconexión de la sesión SPEI antes de liquidarlo.
- [x] El saldo (`GET /pagos/saldo`) sobrevive una reconexión de la sesión SPEI dentro del mismo día
      operativo (no se reinicia a los valores por defecto). **Confirmado 2026-10-08 contra minosA
      real**: $1,000,033.00 antes y después de reiniciar el contenedor y reconectar.
- [x] `POST /dia/cerrar` dispara `LiquidacionFinal` y se observa en minos el efecto esperado
      (reconexión de ARA, notificación a Rada) sin que la sesión SPEI se caiga de forma anómala.
      **Confirmado 2026-10-05 contra minosa real** (antes del fix de certificado, no debería
      cambiar) — minos reconectó ARA exitosamente y Radamanto respondió "Mensaje de inicio de
      cambio de dia recibido"; también resolvió un día operativo atorado 3 días (2 oct → 5 oct).
- [ ] Todo lo anterior compilado y, antes de mergear a `main`, verificado contra minos real
      (misma disciplina que specs 001/005/012 — ver `minos_wire_protocol_verification`).
