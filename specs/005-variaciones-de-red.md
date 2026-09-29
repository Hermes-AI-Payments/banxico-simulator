# 005 — Variaciones de red simulables desde la API de control

## Contexto

Además de los escenarios de contenido/protocolo (specs 001-004), se busca poder variar las
condiciones de red bajo las que corre una prueba, configurable desde la misma API de control.

## Requisitos

Variaciones a soportar, todas configurables por corrida vía parámetros nuevos en la API de
control (no requieren tocar la lógica de protocolo en `SpeiSession`/`AraSession` — viven en una
capa que envuelve el socket antes de que el protocolo lo use):

| Variación | Qué es | Cómo se implementaría |
|---|---|---|
| Latencia fija/jitter | Retraso constante o variable antes de mandar cada frame. | Decorador sobre el `OutputStream` del socket que duerme (`Thread.sleep`) antes de escribir — `{"latenciaMs": N, "jitterMs": M}`. |
| Pérdida de paquetes | Descartar un frame en vez de mandarlo, con probabilidad `p`. | Mismo decorador, descarta en vez de escribir. Depende de la spec 001 (particionado real) para tener sentido — sin partición real, "perder un paquete" es indistinguible de cortar la conexión. |
| Reordenamiento | Los frames llegan en orden distinto al que se mandaron. | Buffer de frames pendientes con retraso aleatorio por frame antes de liberarlos — requiere que el reensamblado (spec 001) tolere llegada fuera de orden. |
| Duplicación | El mismo frame llega más de una vez. | El decorador reescribe el mismo frame N veces con probabilidad `p` — útil para probar idempotencia del receptor. |
| Corte abrupto en momento específico | Cerrar el socket deliberadamente en un punto nombrado del flujo (no aleatorio). | Parámetro de corrida (`{"cortarEn": "post-ClvSim"}`, etc.) en vez de un decorador continuo — el punto de corte es parte del escenario, no de las "condiciones de red". |
| **Throttling asimétrico** | Ver explicación abajo. | Dos instancias del decorador de ancho de banda, una por dirección del socket, cada una con su propio límite. |
| **MTU reducido / fragmentación forzada** | Ver explicación abajo. | **No** es un decorador a nivel aplicación — vive en el socket TCP real o en el SO. Ver nota de alcance abajo. |

### Throttling asimétrico — qué es

"Throttling" es limitar el ancho de banda disponible (bytes/segundo) para simular una conexión
lenta. "Asimétrico" significa que el límite **no es el mismo en las dos direcciones** — por
ejemplo, minos podría tener mucho ancho de banda para *enviar* hacia Banxico pero poco para
*recibir* de vuelta (o viceversa), que es exactamente el patrón típico de conexiones reales
(muchas conexiones son más rápidas de bajada que de subida). Simularlo permite probar cómo se
comporta el protocolo cuando un lado puede "hablar" más rápido de lo que el otro puede
"escuchar" — un caso real y común, no solo una lentitud pareja en ambos sentidos.

Se implementaría con **dos** decoradores de ancho de banda independientes, uno envolviendo el
lado de escritura (out) del socket y otro el de lectura (in), cada uno con su propio límite
configurable — a diferencia del throttling simétrico, que sería un solo límite compartido.

### MTU reducido / fragmentación forzada — qué es

MTU (Maximum Transmission Unit) es el tamaño máximo de un paquete a nivel de red (típicamente
1500 bytes en Ethernet). Cuando un mensaje de aplicación es más grande que el MTU, el sistema
operativo/la pila TCP lo **fragmenta automáticamente** en varios paquetes IP para transportarlo —
esto es distinto y más bajo nivel que el particionado de la spec 001 (que es particionado a
**nivel de aplicación/protocolo SPEI**, decidido por el propio simulador). Reducir el MTU
artificialmente fuerza fragmentación real a nivel de red incluso para mensajes que el simulador
cree que está mandando "de una pieza".

**Nota de alcance:** a diferencia de las demás variaciones de esta tabla, esto no se puede lograr
con un decorador de aplicación — requiere tocar `SO_SNDBUF`/opciones de socket real, o
configuración del sistema operativo (`tc`/`netem` en la VM Linux donde corre el simulador). Vale
la pena decidir explícitamente si entra en el alcance de "lo que la API de control puede
configurar", o si se documenta como configuración de infraestructura aparte, fuera de la API.

## Fuera de alcance

- Variaciones que dependan de hardware real de red (switches, routers) — todo lo de esta spec es
  simulable en software, sobre un único host.
- Variaciones sobre el socket ARA (6002) y la API de control (8089) — por ahora solo el tráfico
  SPEI (abonos y el resto de la sesión en 6001). Decisión 2026-09-28.
- Autenticación de la API de control o del MCP — ver "Decisiones", la VPN es la frontera de
  confianza.

## Decisiones de diseño (2026-09-28)

Tomadas con Miguel Zavala (dueño de spec) después de probar a mano, el 2026-09-25, 10 abonos con
retraso en el 3 y el 7 vía `tc netem` por SSH (skill `spei-network-fault-injection`). Esa prueba
funcionó, pero dejó claro el costo del enfoque por SSH: credenciales del host en manos de cada
persona que prueba, un script que falla en modo remoto por escapado de argumentos, y una falla
que degrada **toda** la salida del contenedor (incluida la API). Esta sección reemplaza ese
enfoque como destino final; el skill actual queda como puente hasta implementarla.

### 1. Dos capas: A (siempre disponible) + B (opcional)

| Capa | Dónde vive | Disponible | Cubre |
|---|---|---|---|
| **A — aplicación** | Decorador sobre el `OutputStream` del socket SPEI en `SpeiSession` (el diseño original de esta spec) | Siempre, sin instalar nada | Retraso/jitter por trama, corte en punto nombrado, duplicación de trama completa, throttling por dirección |
| **B — red real** | Script en el host (evolución de `netem.sh`), invocado por la API | Solo si quien despliega decide instalarlo | Pérdida de paquetes, reordenamiento, MTU reducido — lo que solo existe a nivel TCP/IP real |

La capa A permite dirigir la variación a mensajes concretos ("retrasa el próximo abono",
"el abono número K de una campaña", "corta después de `ClvSim`"), cosa que `tc` no puede hacer
porque no distingue un abono de un `AreYouAlive`. La capa B da realismo de red que la capa A no
puede imitar sin romper el encuadre del protocolo (perder una trama completa desalinea a minos —
ver la nota de spec 001 en la tabla de requisitos).

### 2. Despliegue: siempre se pregunta, nunca bloquea

- Un script de despliegue en la raíz del repo (`deploy.sh`) **siempre pregunta** si se instala el
  script de pruebas de red en el host. Acepta `--con-pruebas-red` / `--sin-pruebas-red` para
  responder sin preguntar (instalaciones automáticas o CI).
- **No es bloqueante:** si la respuesta es no, el simulador se despliega completo y el script
  termina avisando en una línea que las pruebas de red quedan limitadas a lo que puede hacer el
  propio contenedor (retraso, corte, duplicación y throttling; sin pérdida de paquetes,
  reordenamiento ni MTU).
- **Instalable después:** `scripts/host/pruebas-red.sh instalar | desinstalar | estado` se puede
  correr en cualquier momento posterior al despliegue, sin redesplegar el simulador.
- Instalarlo es **responsabilidad explícita de quien despliega** — el script lo dice antes de
  instalar y pide confirmación (salvo con `--con-pruebas-red`).

### 3. Comunicación API ↔ host (capa B)

- El script del host es **fijo**: vive versionado en el repo (se revisa como cualquier cambio),
  se instala en una ruta del sistema (`/usr/local/sbin/`) con dueño root y solo lectura para los
  demás. La API **nunca** escribe ni decide código que el host ejecute.
- La API solo deja **solicitudes con parámetros** (JSON) en una carpeta compartida montada en el
  contenedor (`data/pruebas-red/solicitudes/`). Un `systemd.path` en el host detecta la solicitud
  y ejecuta el script fijo, que **valida los parámetros contra su propia lista cerrada** (no
  confía en que la API ya validó) y escribe el resultado en `data/pruebas-red/resultados/`.
- **Solo tráfico SPEI:** la variación se aplica con un `prio` qdisc + filtro `u32` por puerto de
  origen `spei.port` (6001): el ARA y **la API de control no se degradan** — la herramienta que
  quita la variación siempre responde. Los `AreYouAlive` sí viajan por 6001 y se ven afectados;
  por eso existen los límites de §4.
- **Limpieza independiente de la API:** cada variación aplicada lleva su vencimiento, y el
  script del host la retira solo al vencer (temporizador de systemd) aunque la API o el
  contenedor hayan muerto. Al arrancar, el script limpia cualquier residuo.

### 4. Límites por defecto: derivados del protocolo, no números fijos

Los límites se calculan a partir de parámetros que el simulador ya conoce, para que una
variación no termine cortando la sesión sin que nadie lo pidiera (para eso existe el corte
explícito). Todos son sobreescribibles en `config/simulator.properties`.

| Límite | Default | De dónde sale |
|---|---|---|
| Retraso + jitter máximo | `minos.readTimeoutMs − intervalo de AreYouAlive − margen` = 6000 − 3000 − 500 = **2500 ms** | Al aplicar un retraso D, el hueco entre dos `AreYouAlive` que ve minos crece de 3 s a 3 s + D; si supera su timeout de 6 s (spec 009), cierra la sesión |
| Pérdida máxima | **25 %** | TCP retransmite con backoff (≈200, 400, 800, 1600 ms): cuatro pérdidas seguidas del mismo segmento ya suman ~3 s y rozan el timeout. Con p = 25 %, eso ocurre en ~0.4 % de los segmentos |
| Duplicación / reordenamiento máximo | **50 %** | Por encima, la prueba deja de medir tolerancia y solo mide saturación |
| MTU mínimo | **576 bytes** | Mínimo que todo host IPv4 debe poder reensamblar (RFC 791) |
| Duración | Default **60 s**, máximo **15 min** | Toda variación vence sola; no existe "aplicar sin fin" |
| Cola de netem (`limit`) | Producto tasa × retraso máximo, con piso en el default de netem (1000 paquetes) | A la tasa real de SPEI el costo de CPU/memoria de netem es despreciable; el límite que importa es no descartar por cola llena sin quererlo |

Nuevo parámetro de configuración: `minos.readTimeoutMs` (default `6000`, documentado en spec 009).
El intervalo de `AreYouAlive` (hoy fijo en 3 s en `SpeiSession.startHeartbeat`) pasa a leerse de
la misma configuración para que el cálculo use el valor real.

### 5. Inventario de capacidades

- Nuevo endpoint `GET /capacidades` (y herramienta MCP `simulator_capabilities`) que responde qué
  pruebas están disponibles **en este despliegue** y con qué límites: capa A siempre; capa B solo
  si el script del host está instalado y responde.
- La API detecta la capa B con una solicitud de tipo `ping` en la carpeta compartida (respuesta
  esperada en ≤ 5 s), cacheada 60 s. Sin respuesta → capa B "no instalada o sin responder".
- El agente que carga el skill consulta primero `simulator_capabilities` y solo propone pruebas
  que ese despliegue puede ejecutar. El skill no asume nada sobre el host.

### 6. Turno único y sin autenticación

- Una sola variación de red activa a la vez, controlada **en memoria por la API** (reemplaza el
  contenedor marcador `spei-fault-lock`). Quien la pide se identifica con un nombre en texto
  libre (`quien`), que aparece en `/capacidades` mientras la variación está activa.
- **Sin autenticación:** la VPN hacia la red del simulador es la frontera de confianza, igual que
  para el resto de la API y del MCP (spec 011). Con la lista cerrada del script del host, ni
  siquiera un uso malintencionado de la API puede hacer que el host ejecute algo fuera de ella.

### 7. Nivel de riesgo: R1

El decorador de la capa A cambia **cuándo** (o si) se escriben las tramas, no su formato de
bytes — no toca `spei/messages/*` ni `WireFraming`. Queda como **R1** (AGENTS.md §6).

### 8. El skill y `netem.sh` después de implementar

- `netem.sh` **se conserva**: es la base del script del host de la capa B (sin el sidecar por
  SSH, que ya no hace falta cuando el script corre en el propio host).
- El skill `spei-network-fault-injection` se reescribe para usar **solo herramientas MCP**:
  consulta capacidades, propone el plan, espera confirmación, aplica, observa y limpia. Sin SSH ni
  credenciales.

## Diseño de implementación de Capa A (2026-09-28, listo para codear)

Planeado en detalle el 2026-09-28 (sesión de Claude, con Miguel). Resuelve cómo construir §1 de
"Decisiones de diseño" arriba sin tocar el formato de bytes de ningún mensaje.

### Por qué no hay un decorador único de socket

No existe un único choke-point de escritura en `SpeiSession` — 9 sitios llaman
`synchronized (writeLock) { Frame.of(OP, body).writeTo(out); }` directamente (`sendGreeting`,
`sendSmLoginReq`, `performClvSim`, `sendEnSesion`, `sendMsjCatalogos`, el heartbeat, `handleReenvio`,
`handleOrdenTopoV`, `sendAbono`). `Frame.writeTo` nunca cambia — ningún formato de bytes se toca.

- **Retraso/jitter y duplicación** necesitan saber qué mensaje se está mandando (para poder decir
  "el próximo `Abonos`" o "la ocurrencia 3 de `Abonos`") — un decorador de bytes puro no puede
  distinguir eso sin leer el op-code (acoplaría un componente pensado para ser R1 a leer el wire
  format). Se resuelve con **un aviso explícito de `SpeiSession` antes de cada envío** (una llamada
  a `RedVariacionControl.beforeWrite("Abonos")` justo antes del `synchronized(writeLock)` de cada
  uno de los 9 sitios) — una línea por sitio, cero cambio a los codecs.
- **Corte deliberado** se integra directamente con lo que spec 013 ya dejó listo:
  `requestClose(SessionClosure.Cause.CORTE_DELIBERADO, "post-X")`, llamado después de soltar
  `writeLock` (mismo patrón que el heartbeat ya usa).
- **Throttling** sí es puro a nivel de bytes (no le importa qué mensaje es) — un
  `ThrottledOutputStream`/`ThrottledInputStream` envolviendo los streams reales del socket en
  `run()`, con pacing tipo token-bucket, límite independiente por dirección.
- **Retraso duerme *antes* de tomar `writeLock`** (no dentro) — un retraso real de red no bloquea
  paquetes ajenos; si el `Thread.sleep` quedara dentro del lock, el hilo del heartbeat se
  congelaría junto con el envío que se está retrasando, un artefacto de simulación que no
  corresponde a nada real. **Duplicación sí queda dentro del lock** (las dos copias deben salir
  sin nada intercalado, para que sea fiel a "el mismo frame llegó dos veces"). **Throttling se
  queda donde ya está** (dentro de `writeLock`, porque el propio `write()` del stream throttleado
  es lo que tarda) — ahí sí es realista que un envío concurrente espere su turno.

### Clases nuevas (paquete `spei`)

| Clase | Rol |
|---|---|
| `RedVariacion.java` | Modelo de una variación (tipo + estado + parámetros), mismo patrón que `LoadCampaign.java`: constructor privado + factories estáticas por tipo, dos enums ortogonales (`Tipo`: RETRASO/CORTE/DUPLICACION/THROTTLING; `Estado`: ACTIVA/DETENIDA/TERMINADA/VENCIDA), `finish()` idempotente, `status()` → `LinkedHashMap`. |
| `RedVariacionRegistry.java` | El lock de "una sola variación a la vez" — reemplaza el contenedor Docker `spei-fault-lock` que usaba el skill puente. `AtomicReference` + auto-vencimiento por `ScheduledExecutorService`. Construido una vez en `Main`, pasado a `SpeiServer`→`SpeiSession` y a `ControlServer`. Expone `capacidades()` para `GET /capacidades`. |
| `RedVariacionControl.java` | Una instancia por `SpeiSession`. Contador de ocurrencias por punto (`Map<String,Integer>`) + `beforeWrite(String punto) -> Decision`. `Decision` es inmutable (duplicar, cortarDespues, detalleCorte) — sin estado compartido mutable que otro hilo pueda leer a destiempo. |
| `ThrottledOutputStream.java`, `ThrottledInputStream.java` | Envuelven los streams reales del socket en `run()`. Overridean **`write(byte[],off,len)`/`read(byte[],off,len)`** (no las variantes de un solo byte — son las que `DataOutputStream`/`DataInputStream` realmente invocan). `TokenBucket` compartido para el pacing. |

### Puntos nombrados (vocabulario)

Nombre desnudo (para `retraso`/`duplicacion`, semántica "antes de mandar"): `Greeting`,
`SmLoginReq`, `EnSesion`, `ClvSim`, `MsjCatalogos`, `AreYouAlive`, `FinReenvio`, `AcuseRecibo`,
`Abonos`. Nombre `post-X` (para `cortarEn`, semántica "después de mandar", como en el ejemplo de
arriba `post-ClvSim`) — mismo set, prefijado. `Abonos`, `AreYouAlive`, `FinReenvio` y
`AcuseRecibo` se repiten dentro de una sesión; ahí `ocurrencia` acepta un entero o `"siguiente"`.
"Abono número K de una campaña" se resuelve *fuera* de esta clase: quien prueba calcula el
`ocurrencia` absoluto a partir de `LoadCampaign.status().enviados` + K — cero acoplamiento nuevo
entre `RedVariacionControl` y `LoadCampaign`.

### Config nueva (`SimConfig.java` + `config/simulator.properties.example`)

Mismo patrón de siempre (default en `load()`, override de entorno automático, accessor con
javadoc citando el spec):

| Key | Default | Uso |
|---|---|---|
| `minos.readTimeoutMs` | `6000` | Documenta el timeout real de minos; base del cálculo del límite de retraso. |
| `spei.heartbeatIntervalMs` | `3000` | Reemplaza el `3, 3, TimeUnit.SECONDS` hardcodeado en `startHeartbeat()`. |
| `red.variacion.retrasoMaximoMs` | `2500` | `readTimeoutMs − heartbeatIntervalMs − margen(500)` — literal, no calculado en runtime. |
| `red.variacion.duplicacionProbabilidadMaxima` | `0.5` | Tope de §4. |
| `red.variacion.duracionSegundosDefault` | `60` | — |
| `red.variacion.duracionSegundosMaxima` | `900` | 15 min, ninguna variación queda indefinida. |

### HTTP (`ControlServer.java`, mismo patrón que `/abonos/carga*`)

- `GET /capacidades` — `capaA.disponible: true` siempre, con tipos/límites/puntos válidos y la
  variación activa si hay una; `capaB.disponible: false` (punto de extensión para cuando exista).
- `POST /red/variacion` — arranca una variación (`tipo`, `quien` obligatorio, params por tipo).
  `409` si ya hay una activa (mensaje dice quién y desde cuándo). `400` con el límite exacto y de
  dónde sale si se pide algo fuera de rango. Sin precondición de "sesión viva" — se puede armar un
  corte para `post-Greeting` antes de que exista sesión.
- `GET /red/variacion` — estado de la activa, o `{"activa": false}`.
- `POST /red/variacion/detener` — para manualmente.

### MCP (`mcp-server/index.js`)

4 tools nuevas dentro de `buildServer()`, mismo patrón que las 11 existentes:
`simulator_capabilities`, `simulator_start_network_variation`, `simulator_network_variation_status`,
`simulator_stop_network_variation`. Params vía zod, mapeados 1:1 a los campos de `POST /red/variacion`.

### Archivos que cambian

| Archivo | Cambio |
|---|---|
| `spei/RedVariacion.java` | Nuevo |
| `spei/RedVariacionRegistry.java` | Nuevo |
| `spei/RedVariacionControl.java` | Nuevo |
| `spei/ThrottledOutputStream.java`, `ThrottledInputStream.java` | Nuevos |
| `spei/SpeiSession.java` | Construye `RedVariacionControl` + envuelve `in`/`out` en `run()`; una llamada `beforeWrite`/`Decision` en cada uno de los 9 sitios de envío; `startHeartbeat()` lee `config.heartbeatIntervalMs()` |
| `spei/SpeiServer.java` | Recibe `RedVariacionRegistry`, lo pasa a cada `SpeiSession` |
| `config/SimConfig.java` | 6 keys/accessors nuevos |
| `config/simulator.properties.example` | Bloque comentado espejo |
| `control/ControlServer.java` | Recibe `RedVariacionRegistry`; rutas `/capacidades`, `/red/variacion`, `/red/variacion/detener` |
| `Main.java` | Construye el `RedVariacionRegistry` compartido, lo conecta a `SpeiServer`/`ControlServer` |
| `mcp-server/index.js` | 4 tools nuevas |
| `plugin/skills/spei-network-fault-injection/SKILL.md`, `references/spec-005-scenarios.md` | Reescritos para usar solo MCP |

### Orden de commits y verificación contra minos real

Cada paso se verifica contra minos real (no solo el arnés Python) antes de seguir al siguiente —
`AGENTS.md` lo pide para cualquier cambio cerca del protocolo, y este toca timing real de sesión:

1. **Config + intervalo de heartbeat leído de config** (sin ningún comportamiento nuevo). Verificar:
   conectar minos real, confirmar que el heartbeat sigue cada 3s y la sesión sigue viva más allá
   de la ventana de timeout de siempre.
2. **Clases nuevas + wiring en `SpeiSession`/`SpeiServer`/`Main`, sin superficie HTTP todavía.**
   Verificar: con minos real conectado y ninguna variación jamás armada, confirmar cero diferencia
   de comportamiento (todo `Decision` es no-op, los streams throttleados son transparentes).
3. **Rutas de `ControlServer`.** Este es el paso que de verdad prueba la funcionalidad contra
   minos real: (a) `cortarEn: post-ClvSim` — confirmar que minos ve un corte real justo ahí y que
   `GET /session` muestra `causa: corte-deliberado` (no `error-io` por error de clasificación);
   (b) un retraso al límite (2500ms) en un punto que se repite — confirmar que NO dispara el
   timeout de 6s de minos por sí solo; (c) duplicación en un `Abonos` — confirmar que el parser de
   minos no truena con la trama repetida; (d) throttling a una tasa baja — confirmar que la sesión
   se degrada pero no se queda colgada permanentemente (un `Thread.sleep` atorado en el envío del
   heartbeat se vería igual que un bug de cuelgue real).
4. **Tools MCP.** Verificar que cada una, llamada contra el mismo simulador + minos real, devuelve
   exactamente lo mismo que su ruta HTTP.
5. **Reescritura del skill.** Ensayar el flujo completo (proponer→confirmar→ejecutar→observar→
   limpiar) una vez por cada uno de los 4 tipos contra minos real, incluyendo el camino de
   conflicto 409 y el de vencimiento automático (no llamar `detener`, confirmar que se autolimpia).

No se considera nada de esto listo para mergear a `main` sin los pasos (a)-(d) del punto 3 hechos
contra minos real — es exactamente lo que el arnés Python no puede probar (el timeout real de 6s
de minos y su reacción real a un corte a mitad de handshake).

## Preguntas abiertas

Ninguna bloqueante. Las dos originales quedan resueltas:

- ~~¿MTU/fragmentación entra al alcance de la API?~~ Sí, por la capa B (§1, §3).
- ~~¿Parámetros estáticos o cambiantes a media corrida?~~ Cada variación es una solicitud con
  duración propia; un escenario "sube la latencia después del mensaje 3" se arma encadenando
  solicitudes, o con la capa A dirigida a mensajes concretos (§1).

## Estado de implementación (2026-09-28)

Implementada la Capa A completa (pasos 1-4 de "Orden de commits" arriba): config nueva
(`minos.readTimeoutMs`, `spei.heartbeatIntervalMs`, los 4 límites de &sect;4), las 5 clases nuevas
(`RedVariacion`, `RedVariacionRegistry`, `RedVariacionControl`, `ThrottledOutputStream`,
`ThrottledInputStream`), wiring en `SpeiSession`/`SpeiServer`/`Main` (los 9 puntos nombrados llaman
`beforeWrite`, los streams del socket quedan envueltos), las rutas HTTP (`GET /capacidades`,
`GET`/`POST /red/variacion`, `POST /red/variacion/detener`) y las 4 tools MCP
(`simulator_capabilities`, `simulator_start_network_variation`, `simulator_network_variation_status`,
`simulator_stop_network_variation`).

**Compilado (`mvn -DskipTests package`, BUILD SUCCESS) y probado por HTTP contra el simulador
corriendo sin minos** (`/capacidades`, armar/consultar/detener una variación, conflicto 409,
vencimiento automático a los 5s, validación de los 4 límites, rechazo de `corte` sin prefijo
`post-`) -- todo se comportó como se esperaba.

**NO probado todavía contra minos real** -- el paso 4 de "Orden de commits" arriba (a-d) sigue
pendiente: corte real en `post-ClvSim` con `causa: corte-deliberado` en `GET /session`, retraso al
límite (2500ms) sin disparar el timeout de minos, duplicación de un `Abonos` sin tronar el parser,
throttling degradando sin colgar la sesión. **No mergear a `main` sin esos cuatro pasos hechos
contra minos real** (la razón de por qué está en el propio spec, arriba).

La semántica exacta de `ocurrencia` (entero = dispara una vez y termina; `"siguiente"` = dispara
desde la próxima ocurrencia en adelante mientras la variación siga activa) es una interpretación
tomada durante la implementación para conciliar el vocabulario del spec con los criterios de
aceptación -- ver javadoc de `RedVariacion` -- pendiente de confirmar con Miguel Zavala y con
pruebas reales.

**Pendiente, no empezado:** Capa B completa (script de host evolucionado de `netem.sh`,
`deploy.sh` con las preguntas de instalación, `scripts/host/pruebas-red.sh`, comunicación API↔host
por `systemd.path` y carpeta compartida) y la reescritura del skill
`spei-network-fault-injection` a solo-MCP (&sect;8) -- deliberadamente diferido: instala un script
con dueño root en el host real (`192.168.1.200`) y requiere acceso a ese host para probarse, a
diferencia de la Capa A que es código Java en el propio proceso.

## Criterios de aceptación

- [ ] `GET /capacidades` y `simulator_capabilities` informan correctamente capa A siempre, y capa
      B solo cuando el script del host está instalado y responde.
- [ ] Capa A: retraso a un abono concreto (o a los próximos N), corte después de un mensaje
      nombrado, y duplicación de trama, disparables por la API sin tocar código entre corridas.
- [ ] Capa B: pérdida, reordenamiento y MTU aplicados **solo** al tráfico del puerto SPEI; la API
      de control responde normalmente mientras hay una variación activa.
- [ ] Toda variación vence sola; con la API detenida a la mitad, el host la retira igual al
      vencer.
- [ ] Una solicitud fuera de los límites por defecto se rechaza con un mensaje que dice el límite
      y de dónde sale.
- [ ] `deploy.sh` pregunta siempre, respeta `--con-pruebas-red` / `--sin-pruebas-red`, y sin el
      script el simulador queda desplegado completo con el aviso de pruebas limitadas.
- [ ] `scripts/host/pruebas-red.sh instalar` funciona sobre un simulador ya desplegado, sin
      redesplegarlo.
- [ ] Las variaciones son combinables entre sí en una misma solicitud de capa B (ej. retraso +
      pérdida + reordenamiento), dentro de los límites.
- [ ] El skill funciona solo con herramientas MCP, sin SSH.
