# Simulador SPEI ("Banxico falso")

Servidor TCP que emula a Banxico del lado de la conexión que `minos` (módulo de Hermes MK I) ya
sabe hacer, para poder probar `minos` en desarrollo/pruebas sin depender del ambiente real de
Banxico. Implementa el **protocolo real, cifrado (RSA + AES-CBC) y firmado** — no un atajo — según
[ADR-005](../HERMES-MKI-VOBEDA/ADRs/ADR_005_Simulador-SPEI-protocolo-real.md). Java 17, Maven, sin
frameworks — dos servidores de sockets simples.

Ver [AGENTS.md](AGENTS.md) para el mapa de arquitectura y las convenciones del repo.

## Compilar

Requisitos: JDK 17, Maven 3.8+.

```bash
mvn -DskipTests package
```

Esto genera `target/banxico-simulator.jar` (jar ejecutable con todas las dependencias incluidas).

## Configurar

```bash
cp config/simulator.properties.example config/simulator.properties
```

Edita `config/simulator.properties` si hace falta — los valores por defecto (puertos 6001/6002,
códigos de entidad de ejemplo) sirven para arrancar, pero **dos cosas hay que ajustar siempre**:

1. **`minos.entityCode`** debe coincidir exactamente con el código de entidad que tu instancia de
   minos tiene configurado internamente (`Spei.entityCode`) — si no coincide, minos no podrá
   resolver su propia entidad al procesar `EnSesion` y fallará más adelante al intentar mandar
   órdenes.
2. **El certificado público real de esa instancia de minos**, en
   `config/minos-public-cert.pem` (formato PEM, `-----BEGIN CERTIFICATE-----`). Es **obligatorio**:
   minos desencripta el reto RSA de ARA (`IdUsuarioAleat`) y la llave de sesión de `ClvSim` con su
   propia llave privada, así que el simulador necesita la llave pública correspondiente para poder
   cifrar hacia minos — exactamente como en la vida real, donde Banxico ya tiene registrado el
   certificado del participante de antemano; no se negocia en este socket. Cómo obtenerlo depende
   de dónde minos tiene guardada su llave — normalmente se puede exportar desde la tabla del
   certificado SPEI que usa `SpeiServiceEntity.defaultKey` en la base de datos de minos.

   Si arrancas sin este archivo, el simulador funciona igual para la Fase 1 (conexión básica),
   pero el login ARA y el desafío `ClvSim` **no completarán** (queda documentado con una
   advertencia en el log al arrancar).

## Correr

```bash
java -jar target/banxico-simulator.jar
```

```
==================================================================
 Simulador SPEI (Banxico falso) listo
 Puerto SPEI: 6001   Puerto ARA: 6002
 Comandos de consola: 'abono' | 'abono-invalido' | 'salir'
==================================================================
```

La primera vez que arranca genera su propia identidad (llaves RSA + certificado X.509
autofirmado) en `data/identity/` y la reutiliza en corridas siguientes. La base de datos de
pruebas (H2) vive en `data/banxicosim.mv.db`.

## Correr con Docker (recomendado)

Evita instalar JDK/Maven en el host — el `Dockerfile` es un build multi-etapa que compila el
proyecto adentro (a diferencia de los Dockerfile de judeca/minos/radamanto, que asumen `mvn clean
install` ya corrido en el host), así que `docker compose up` es el único paso.

```bash
cp config/simulator.properties.example config/simulator.properties
# coloca el certificado real de tu instancia de minos en config/minos-public-cert.pem (ver arriba)
docker compose up --build
```

`config/` y `data/` quedan montados como bind mounts (no volúmenes nombrados) a propósito:
`config/` es donde tú colocas `minos-public-cert.pem` a mano, y `data/` es donde puedes inspeccionar
`banxicosim.mv.db` con cualquier cliente H2 sin entrar al contenedor. Puertos expuestos: `6001`
(SPEI), `6002` (ARA), `8089` (API de control — ver abajo) y `8090` (servidor MCP). El `Dockerfile`
incluye un `HEALTHCHECK` contra `GET /health`.

`docker compose up` levanta **dos servicios**: el simulador y, a su lado, el servidor MCP
(`mcp-server/`, spec 011) en modo HTTP. El MCP habla con la API por la red interna de compose y
publica `http://<host>:8090/mcp`, que es a donde apunta el plugin de Claude Code
([`plugin/`](plugin/README.md)) — así quien prueba solo instala el plugin, sin clonar este repo.

**Portabilidad — variables de entorno.** Cualquier clave de `simulator.properties` se puede
sobreescribir con una variable de entorno del mismo nombre en mayúsculas y puntos por guión bajo
(p. ej. `spei.port` &rarr; `SPEI_PORT`, `minos.entityCode` &rarr; `MINOS_ENTITY_CODE`) — ver
`SimConfig.applyEnvironmentOverrides` y los ejemplos comentados en `docker-compose.yml`. Sirve
para correr el simulador en otra máquina o en CI sin tener que montar un archivo solo para cambiar
un puerto; la única pieza que sigue necesitando un archivo montado es `minos-public-cert.pem`
(es contenido binario/PEM, no un valor de una sola línea).

**Los comandos de consola (`abono`, `abono-invalido`, `salir`) necesitan stdin interactivo**, que
no existe en un contenedor corrido en segundo plano (`docker compose up -d`). Ahí, dispara esos
mismos escenarios con la API de control HTTP (`POST /abonos/validos`, `POST /abonos/invalidos` —
ver la sección de abajo y `httpclient/02-abonos.http`); el simulador funciona igual sin la
consola, solo pierdes esa vía de disparo manual.

## Apuntar una instancia de minos en pruebas hacia el simulador

minos es quien abre la conexión hacia afuera en ambos sockets (nunca al revés) — ver
`SpeiSocketServiceImpl.initializeSocket()` y `AraSocketServiceImpl.initializeSocket()` en el
código de minos. Apuntarlo al simulador es **sólo cambio de configuración**, no de código: ajusta
en la base de datos de minos las entidades `SpeiServiceEntity` y `AraServiceEntity` (tabla de
servicios de minos) para que:

- `host` apunte a la máquina donde corre el simulador (`localhost` si es la misma máquina).
- `ports` sea el rango de puertos que incluya el puerto del simulador, en formato `"inicio:fin"`
  (ver `BanxicoServiceEntity.portRange()` en minos — por ejemplo `"6001:6001"` para el socket SPEI
  y `"6002:6002"` para ARA, si usas los puertos por defecto de este simulador).

## Tipos de pago disponibles para probar

> **Esto excede a propósito el alcance que
> [ADR-005](../HERMES-MKI-VOBEDA/ADRs/ADR_005_Simulador-SPEI-protocolo-real.md) fijó para v1.**
> Esa ADR decidió explícitamente cubrir sólo un subconjunto representativo de 4 tipos de pago
> (01, 02, 05, 12) "por desproporción de esfuerzo" frente a replicar los 35 tipos completos del
> catálogo SPEI. El dueño de este repo decidió, el 2026-09-03, ampliar de todas formas la capa de
> validación de contenido (`OrdenTopoV`/`OrderFieldValidator`) a los 35 tipos reales — una
> decisión consciente para este repo específico, no una corrección silenciosa de la ADR. **La ADR-005
> no se ha actualizado y esta ampliación no se ha confirmado con Pedro** (dueño de la decisión
> original documentada ahí). Cualquiera que audite este repo debe saber que hay una divergencia
> pendiente de reconciliar formalmente entre lo que dice ADR-005 y lo que el código realmente
> valida hoy.

El simulador valida (Fase 4, `OrdenTopoV`) los **35 tipos de pago reales del catálogo SPEI**
(códigos 0-36; se saltan 13 y 14). Ver `validation/PaymentType.java` para el detalle de campos por
tipo (orden exacto, cuáles son opcionales, citas línea por línea contra `pagos.properties`) y
`validation/OrderFieldValidator.java` para las reglas de formato por campo (citas línea por línea
contra `judeca.CamposOrdenesValidator`) y la nota sobre por qué el string de "detalle" no repite
los 5 campos comunes.

| Código | Nombre |
|---|---|
| 00 | Devolución de Órdenes de Transferencia Aceptadas por SPEI no acreditadas en Cuentas de Clientes |
| 01 | Tercero a Tercero |
| 02 | Tercero a Ventanilla |
| 03 | Tercero a Tercero Vostro |
| 04 | Tercero a Participante |
| 05 | Participante a Tercero |
| 06 | Participante a Tercero Vostro |
| 07 | Participante a Participante |
| 08 | Tercero a Tercero FSW |
| 09 | Tercero a Tercero Vostro FSW |
| 10 | Participante a Tercero FSW |
| 11 | Participante a Tercero Vostro FSW |
| 12 | Nómina |
| 15 | Pago Factura |
| 16 | Devolución Extemporánea de Órdenes de Transferencia Aceptadas por SPEI no acreditadas en Cuentas de Clientes |
| 17 | Devolución de Órdenes de Transferencia Aceptadas por SPEI acreditadas en Cuentas de Clientes |
| 18 | Devolución Extemporánea de Órdenes de Transferencia Aceptadas por SPEI acreditadas en Cuentas de Clientes |
| 19 | Cobros presenciales de una ocasión |
| 20 | Cobros no presenciales de una ocasión |
| 21 | Cobros no presenciales recurrentes |
| 22 | Cobros no presenciales recurrentes y no recurrentes a nombre de un tercero |
| 23 | Devolución especial de Órdenes de Transferencia Aceptadas por SPEI acreditadas en Cuentas de Clientes |
| 24 | Devolución extemporánea especial de Órdenes de Transferencia Aceptadas por SPEI acreditadas en Cuentas de Clientes |
| 25 | Tercero a Tercero FSW CLS |
| 26 | Tercero a tercero vostro FSW CLS |
| 27 | Participante a tercero FSW CLS |
| 28 | Participante a tercero vostro FSW CLS |
| 29 | Participante a Participante FSW CLS |
| 30 | Tercero indirecto a tercero |
| 31 | Tercero indirecto a participante |
| 32 | Presencial de una ocasión indirecto |
| 33 | No presencial de una ocasión indirecto |
| 34 | No presencial recurrente indirecto |
| 35 | Remesa saliente |
| 36 | Remesa entrante |

Cualquier código fuera de este catálogo (i.e. distinto de 0-36 o igual a 13/14) en un `OrdenTopoV`
entrante es tratado por `OrderFieldValidator` como fuera de catálogo SPEI real — no como "fuera de
alcance de v1" (ya no aplica esa distinción). Ver `validation/OrderFieldValidator.java` para las
limitaciones conocidas y documentadas de esta ampliación (catálogos externos configurables vacíos
por defecto, campos "*Original" de devoluciones sin una orden original real contra la cual
comparar, e inconsistencias encontradas entre `pagos.properties` y `CamposOrdenesValidator.java`
— nombres de campo sin "case" correspondiente en el switch de judeca).

## API de control y flujo de pruebas

Además de los dos sockets del protocolo SPEI/ARA (que sólo minos consume) y la consola de
stdin, el simulador expone una **API de control HTTP** — un tercer puerto, sin relación con el
protocolo real, pensado para disparar y observar corridas de prueba con clientes HTTP simples en
vez de la consola. Está implementada con `com.sun.net.httpserver.HttpServer` del JDK (cero
dependencias nuevas, ver AGENTS.md &sect;5). Puerto configurable en `config/simulator.properties`
(`control.port`, default `8089`).

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/health` | Liveness del proceso. `{"status":"ok"}` |
| `GET` | `/session` | Estado de la sesión SPEI más reciente: fase de handshake alcanzada, si sigue viva, día operativo declarado en `EnSesion`. `{"session":null}` si nunca se ha conectado minos — nunca un 500. |
| `POST` | `/abonos/validos` | Dispara un abono de prueba válido — mismo camino que el comando de consola `abono` (`SpeiSession.sendTestAbono(true)`). 409 con detalle si no hay sesión SPEI viva. |
| `POST` | `/abonos/invalidos` | Igual, con contenido deliberadamente inválido (RFC roto) — equivalente a `abono-invalido`. |
| `GET` | `/test-runs` | Lista las corridas de prueba registradas en H2 (tabla `test_run`), más reciente primero. |
| `GET` | `/test-runs/{id}/events` | Lista los mensajes enviados/recibidos de una corrida (tabla `test_event`), en orden cronológico. |

Ejemplo de `GET /session` con una sesión viva:

```json
{"session":{"runId":1001,"channel":"SPEI","remoteAddress":"/127.0.0.1:54321",
  "alive":true,"fase":"VIVA","diaOperativo":"2026-09-03"}}
```

El contrato completo de cada endpoint (forma exacta de la respuesta, casos de error) está en los
comentarios de los propios requests ejecutables — no se duplica aquí, ver
[`httpclient/`](httpclient/).

### Flujo de prueba end-to-end

1. Arranca el simulador (`java -jar target/banxico-simulator.jar`).
2. Apunta una instancia de minos de pruebas hacia él (sección "Apuntar una instancia de minos en
   pruebas hacia el simulador" arriba) — es minos quien abre la conexión, nunca al revés.
3. Espera a que el handshake real complete — verifica con `GET /session` hasta ver
   `"alive":true` y `"fase":"VIVA"`.
4. Usa los archivos de [`httpclient/`](httpclient/) para disparar abonos de prueba y consultar
   corridas: `httpclient/01-salud-y-sesion.http` (salud y estado de sesión),
   `httpclient/02-abonos.http` (disparar abonos válidos/inválidos),
   `httpclient/03-corridas-de-prueba.http` (listar corridas y sus eventos).
5. Revisa el detalle completo de cada corrida con `GET /test-runs/{id}/events`, o directamente
   contra `data/banxicosim.mv.db` con cualquier cliente SQL compatible con H2.

## Estado de avance por fase

Las 6 fases del plan (`02_especificacion_tecnica.md` &sect;9). **Verificado de punta a punta**
con un arnés de prueba en Python que actúa como "minos falso" (genera su propia identidad RSA,
completa el login ARA real, el reto `ClvSim`, `EnSesion`/`MsjCatalogos`, manda una `OrdenTopoV`
válida y otra deliberadamente inválida, y dispara un `Abonos`) — no sólo compilado, sino corrido
contra el jar real.

| Fase | Estado | Notas |
|---|---|---|
| 1 — Servidor TCP + framing + Conexion/Greeting | **Completa** | Verificada con un cliente TCP crudo y con el arnés completo. |
| 2 — Servidor ARA + certificado autofirmado + ClvSim/RespClvSim | **Completa** | Login ARA (ConnUsr → IdUsuarioAleat → IdFmaAleat → Logged) y `PideCrtNvo`/`RegCrtNvoFmt`/`CrtNoExiste` verificados. Requiere el certificado público de minos configurado (ver arriba). |
| 3 — EnSesion + MsjCatalogos (sesión "viva") | **Completa** | Incluye `Reenvio`/`FinReenvio` e `InicioSesionCifrada`, que el código real de minos exige tras `MsjCatalogos` aunque la spec técnica no los liste explícitamente (ver `ReenvioCodec.java` y `SpeiSession.handleInicioSesionCifrada`). |
| 4 — Recepción y validación de OrdenTopoV + AcuseRecibo | **Completa** para los 35 tipos de pago reales del catálogo SPEI (códigos 0-36, sin 13/14) — ampliado más allá de los 4 tipos que fijó ADR-005 para v1 (ver "Tipos de pago disponibles para probar" abajo, incluye la nota de divergencia con la ADR). Verificada aceptando órdenes válidas de varios tipos (incluidos una devolución y un CoDi), rechazando una con un campo obligatorio faltante, y confirmando la regla especial de RFC/CURP de los tipos de participación indirecta/remesas (30-36). |
| 5 — Envío de Abonos (válidos/inválidos) | **Completa**, disparo manual por consola (`abono` / `abono-invalido`) — no hay todavía un catálogo amplio de escenarios inválidos, sólo el de RFC roto usado para la verificación. |
| 6 — Persistencia H2 de corridas de prueba | **Completa**. Cada mensaje enviado/recibido en cada sesión queda en `test_event` (con `test_run` como cabecera de corrida), consultable con cualquier cliente SQL sobre `data/banxicosim.mv.db`. |

### Lo que falta / simplificaciones conocidas de v1

- **No hay reensamblado real de mensajes particionados multi-parte.** El simulador siempre manda
  sus mensajes completos en una sola parte (aprovechando que declara un `maxMessageLength` grande
  en `EnSesion`, así minos nunca necesita partir lo que le manda). Si una prueba futura necesita
  forzar partición real, hay que implementar reensamblado en `WireFraming`/`SpeiSession`.
- **Catálogos de institución / tipo de cuenta:** `judeca.CamposOrdenesValidator` los recibe de un
  sistema externo en tiempo de ejecución (no están hardcodeados ni en judeca ni aquí) —
  `OrderFieldValidator` los deja configurables pero vacíos por defecto (acepta cualquier entero
  válido). Si una prueba necesita rechazar por catálogo, hay que poblar
  `OrderFieldValidator.setValidInstitucion/setValidTipoCuenta`.
- **`Abonos` de prueba:** sólo hay un escenario válido y uno inválido (RFC roto) cableados en
  `SpeiSession.sendTestAbono`. Ampliar el catálogo de escenarios es sencillo (mismo patrón).
- **Sin suite de pruebas automatizada** (JUnit) todavía — ver AGENTS.md &sect;4.

### Decisiones donde la especificación no era 100% explícita

- **Orden de campos del "detalle" de una orden** (`OrdenTopoV`): se asume que el string de
  detalle (separado por 0x00 en el wire) contiene sólo los campos *específicos* del tipo de pago
  (catálogo `pagos.properties`), no los 5 campos "comunes" (`tipoPg`, `cveRastreo`, `cveEmisor`,
  `cveReceptor`, `monto`) — esos ya viajan en otras partes del wire de `OrdenTopoV`. Ver comentario
  en `validation/PaymentType.java`.
- **Contenido firmado en la fase ARA de `RegCrtNvoFmt`/`CrtNoExiste`:** el código de minos que
  arma estos mensajes salientes (`AraSignedMessage.signMessage`) y el que los parsea como
  entrantes (`AraSignedMessage` con `header,body`) no son simétricos — el que parsea espera un
  byte de relleno entre el contenido y la firma que el que construye no pone. El simulador replica
  la fórmula de *análisis* (con el relleno), porque es minos quien recibe y parsea estos mensajes.
  Ver `ara/AraWireFraming.java`.
- **Qué firma exactamente minos en `RespClvSim`:** no está en ningún documento — se encontró
  leyendo `SpeiInputManagerServiceImpl.processClvSimMessage`: firma los 32 bytes crudos de la
  llave simétrica de sesión completa (antes de partirla en llave+IV), con su propia llave privada.
  Ver `spei/messages/ClvSimCodec.java`.
- **Certificado de "servidor" (`serverCertificateNumber` en `EnSesion`):** debe ser el número de
  certificado del simulador (no el de minos) — es la llave que minos usará para cifrar hacia el
  simulador en `InicioSesionCifrada` y para verificar las firmas de mensajes que el simulador le
  manda. minos también necesita su propia entidad declarada en `EnSesion` con su propio número de
  certificado, o no puede resolver `Spei.myEntity` y truena más adelante — por eso `EnSesion`
  siempre declara exactamente dos entidades (la propia y la de minos).

### Bugs de protocolo encontrados y corregidos (2026-09-14, pendiente de revisión de Miguel Zavala)

Encontrados conectando una instancia real de minos (no el arnés Python) contra el simulador
desplegado en `192.168.1.52`, comparando directamente contra el código fuente de minos. Los tres
tocan codecs de protocolo (`spei/messages/*`, `spei/SpeiSession.java`) — **riesgo R2, no mergeados
a la ligera**: quedan documentados aquí para revisión de Miguel Zavala antes de darlos por
definitivos, ver AGENTS.md &sect;6.

1. **Padding RSA incorrecto en el reto `ClvSim`.** `RsaCipher`/`ClvSimCodec` cifraban la llave de
   sesión con `RSA/ECB/PKCS1Padding` (el padding que usa el resto del protocolo), pero
   `ClvSim.build()` del minos real descifra específicamente ese mensaje con
   `RSA/None/OAEPWithSHA-512AndMGF1Padding` vía el provider BC. Con PKCS1 el descifrado de minos
   fallaba o producía una llave de sesión corrupta. Se agregó `RsaCipher.encryptOaepSha512(...)` y
   `ClvSimCodec.build()` ahora la usa exclusivamente para este mensaje; el resto del protocolo
   sigue en PKCS1.
2. **Orden de mensajes `ClvSim`/`EnSesion` invertido respecto al real.** La especificación técnica
   (`02_especificacion_tecnica.md` &sect;3) documenta `ClvSim → EnSesion`, pero
   `EnSesionMessageHandler.handle()` de minos llama `Spei.resetSession()` al procesar `EnSesion`,
   lo que borra la llave AES que `ClvSim` acababa de establecer — el descifrado posterior de
   `MsjCatalogos` truena (`IllegalArgumentException: Missing argument`, el mensaje de JCE para un
   `SecretKeySpec`/`IvParameterSpec` nulo). Se invirtió el orden en `SpeiSession.run()` a
   `EnSesion → ClvSim`. Esta es una divergencia deliberada frente a la spec, no un error de lectura
   de la spec — el comportamiento real de minos manda sobre el documento.
3. **Carrera de concurrencia introducida por el punto anterior.** Al mandar `EnSesion` primero,
   minos dispara de forma asíncrona (hilo aparte, fetch de certificado a ARA) su
   `InicioSesionCifrada`, que puede llegar al simulador antes que el `RespClvSim` que
   `performClvSim()` esperaba de forma estrictamente secuencial (`IllegalStateException: Se
   esperaba RespClvSim (221), llegó 220`). Se hizo `performClvSim()` tolerante a que
   `InicioSesionCifrada` llegue primero, procesándolo en un ciclo de lectura antes de continuar
   esperando `RespClvSim`.

Con los tres fixes, una sesión real minos-simulador llega de punta a punta a `"alive":true,
"fase":"VIVA"` (confirmado también del lado de minos con
`{"speiConecction":true,"speiOperation":true}`). Archivos tocados:
`crypto/RsaCipher.java`, `spei/messages/ClvSimCodec.java`, `spei/SpeiSession.java` — javadoc de
cada uno actualizado con el detalle técnico de su fix.

## Mejoras futuras (opcionales — no bloquean el prototipo)

A diferencia de la sección anterior, esto no es alcance recortado de v1: son ideas de mejora que
nadie ha pedido todavía, sin dueño ni fecha. Se listan aquí para no perderlas, no como pendiente.

- **Publicar la imagen en un registro** (p. ej. GitHub Container Registry) para que quien clone el
  repo haga `docker compose pull` en vez de reconstruir el jar localmente con cada `docker compose
  up --build`. Hoy cada quien construye su propia imagen, lo cual es suficiente mientras el
  simulador lo use una persona a la vez — se vuelve más valioso en cuanto lo use más de un dev o
  quede en un pipeline de CI.
- **Imagen multi-arquitectura** (`amd64`/`arm64`) — solo hace falta si el equipo termina corriendo
  esto en máquinas con distinta arquitectura (p. ej. Apple Silicon en local, x86 en CI); es un flag
  extra en el build (`docker buildx build --platform linux/amd64,linux/arm64`), no un rediseño.

## Estructura del repositorio

Ver [AGENTS.md](AGENTS.md) &sect;2.
