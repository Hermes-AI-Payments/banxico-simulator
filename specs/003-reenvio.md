# 003 — Reenvío real (solo el alcance de Banxico)

## Contexto

`Reenvio` (código 207) lo manda **siempre minos** — confirmado en el código real de minos:
`core/spei/dto/out/Reenvio.java` y `core/spei/message/out/ReenvioMessage.java` (paquete `out` =
lo que minos envía). `FinReenvio` (código 32) lo manda siempre Banxico en respuesta — confirmado
igual, `dto/in/FinReenvio.java`/`message/in/FinReenvioMessage.java` del lado de minos (paquete
`in` = lo que minos recibe). Es unidireccional: minos pide, Banxico responde.

Hoy `SpeiSession.handleReenvio` (`SpeiSession.java:364`) recibe el `Reenvio` y contesta
`FinReenvio` **sin reenvío real** — es un stub, documentado como limitación conocida de v1.

## Requisitos — alcance decidido (2026-09-21)

**Principio: el simulador solo implementa lo que le toca a Banxico en un escenario real — no es
responsable de lo que minos decida o necesite hacer.**

En el protocolo real, `Reenvio.processedBytes` le dice a Banxico "esto es lo último que
procesé de lo que me mandaste, reenvía lo que sigue". El único trabajo real de Banxico aquí es:
llevar un registro de lo que ya mandó en la sesión, y si le piden reenvío desde cierto punto,
reenviar exactamente eso — nada más.

1. El simulador debe llevar un historial de los frames/bytes que ha mandado en la sesión SPEI
   activa (suficiente para poder reconstituir "todo lo mandado después del byte N").
2. Al recibir un `Reenvio` con `processedBytes = N`, debe reenviar real y correctamente todo lo
   que mandó después de esa posición — no simplemente responder `FinReenvio` vacío como hoy.

## Fuera de alcance

- Por qué minos decide pedir un reenvío, cuándo lo hace, o cómo maneja lo que recibe — eso es
  responsabilidad y lógica interna de minos, no se simula ni se prueba desde este simulador.
- Forzar a minos real a pedir un reenvío deliberadamente — las pruebas de este escenario se
  ejercitan haciendo que el **arnés Python** ("minos falso") mande un `Reenvio` con un
  `processedBytes` arbitrario en el punto de la conversación que se quiera probar.

## Diseño propuesto

- Buffer de historial de envío por sesión (`SpeiSession`), acotado — no necesita ser ilimitado,
  pero sí cubrir razonablemente el tamaño de una sesión de prueba completa.
- `handleReenvio` calcula el offset faltante contra ese historial y reenvía los frames
  correspondientes, en vez de responder `FinReenvio` de inmediato.

## Preguntas abiertas

Resueltas 2026-10-06 al construir el arnés de pruebas Java (ver "Estado de implementación"):

- **¿Qué tan grande debe ser el historial retenido?** Se retiene la sesión completa mientras quepa
  en un tope de 16 MiB (`SentHistory.MAX_BYTES`) — de sobra para cualquier sesión de prueba
  razonable. Si se excede, se descartan los bytes más antiguos (ventana deslizante): un `Reenvio`
  que pida una posición ya descartada recibe, en su lugar, todo lo que sigue quedando (mejor
  esfuerzo, no error) — caso de borde que no debería ocurrir en una sesión de prueba corta, y que
  no tiene cobertura de prueba dedicada (ver "Estado de implementación").
- **¿Reenviar significa repetir los frames tal cual, o re-cifrar/re-firmar?** Repetir tal cual —
  mismo cifrado, misma firma de cada mensaje. `Reenvio` opera al nivel del flujo de bytes del
  socket, no al nivel de mensajes individuales (el historial que lleva `SentHistory` es un buffer
  de bytes crudos, no una lista de mensajes reconstruibles), así que no hay ningún motivo para
  re-cifrar o re-firmar algo que el simulador ya mandó una vez. No se verificó contra el código
  real de minos qué espera recibir en un reenvío (fuera del alcance de este simulador, ver
  "Fuera de alcance" arriba) — esta decisión se tomó por consistencia interna del diseño, no por
  evidencia directa de minos.

## Criterios de aceptación

- [x] El arnés (Java, `FakeMinosClient` — ver "Estado de implementación"; la spec originalmente
      decía "arnés Python", ver `specs/README.md` hallazgo 2026-09-21), tras recibir una parte de
      la conversación, manda `Reenvio` con `processedBytes` apuntando a un punto intermedio
      conocido, y el simulador reenvía exactamente los bytes correspondientes (verificado byte a
      byte contra lo que ya se había mandado, registrado en `/test-runs/{id}/events`) — ver
      `ReenvioAcceptanceTest.reenvioDesdePuntoIntermedioReenviaBytesExactos`.
- [x] Un `Reenvio` con `processedBytes` igual al total ya mandado (nada que reenviar) se responde
      con `FinReenvio` sin contenido adicional, sin error — ver
      `ReenvioAcceptanceTest.reenvioSinNadaPendienteRespondeFinReenvioSinError`.

## Estado de implementación (2026-10-06)

**Código nuevo en el simulador** (antes stub, ahora reenvío real — ver "Requisitos" arriba):

- `SentHistory` (`src/main/java/.../spei/SentHistory.java`) — historial de bytes mandados por
  sesión SPEI, acotado a 16 MiB con ventana deslizante (ver "Preguntas abiertas").
- `RecordingOutputStream` (`src/main/java/.../spei/RecordingOutputStream.java`) — intercepta cada
  byte que sale por el socket SPEI y lo anota en `SentHistory`, colocado entre `DataOutputStream`
  y `ThrottledOutputStream` (spec 005) en `SpeiSession.run()`.
- `SpeiSession.handleReenvio` reescrito: calcula el offset faltante contra `SentHistory` y, si hay
  algo pendiente, lo reenvía tal cual (mismos bytes, dentro de `writeLock`) antes de responder
  `FinReenvio` — en vez de responder `FinReenvio` vacío de inmediato como antes. El reenvío mismo
  también pasa por `RecordingOutputStream`, así que queda registrado en el historial (correcto: un
  reenvío también son bytes que el simulador mandó en la sesión).
- `ReenvioCodec.buildReenvioBody`/`parseFinReenvio` y `ClvSimCodec.parse`/`buildRespClvSimBody` y
  `AraWireFraming.parsePaddedSignedBody` y `RsaCipher.decryptOaepSha512`/`decryptOaepSha512FromBase64`
  — codecs/utilidades "de salida" que faltaban porque antes nadie necesitaba jugar el papel de
  minos contra este simulador (ver más abajo).

**Arnés de pruebas Java** (reconstruido bajo `src/test/java`, JUnit 5 — ver `specs/README.md`
hallazgo 2026-09-21, "el arnés Python nunca existió en el repo"):

- `mx.endcom.hermes.banxicosim.testsupport.SimuladorHarness` — arranca una instancia completa del
  simulador (SPEI + ARA + API de control + H2) en el mismo proceso, en puertos efímeros, con una
  identidad "minos falsa" generada en el momento (no depende de `config/minos-public-cert.pem`
  real).
- `mx.endcom.hermes.banxicosim.testsupport.FakeMinosClient` — el "minos falso": genera su propia
  identidad RSA, hace el login ARA completo (incluye `PideCrtNvo`), el reto `ClvSim`,
  `EnSesion`/`MsjCatalogos`, y manda `Reenvio` con un `processedBytes` arbitrario.
- `mx.endcom.hermes.banxicosim.spei.ReenvioAcceptanceTest` — los dos tests de esta spec.

**Verificado:** `mvn test` corre los 9 tests del repo (incluidos los 2 nuevos de esta spec) en
verde, dentro de un contenedor `maven:3.9-eclipse-temurin-17` (este entorno de agente no tiene
JDK/Maven instalados nativamente — ver nota en `specs/README.md`). `mvn -DskipTests package` sigue
generando el jar ejecutable sin cambios de comportamiento para el resto del simulador.
