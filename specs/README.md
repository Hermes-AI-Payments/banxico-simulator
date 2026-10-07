# specs/ — desarrollo guiado por especificación (SDD) para el simulador

Este directorio define, antes de programarlas, las mejoras de automatización de pruebas del
simulador (control de empaquetado/reensamblado, variaciones de red, y lo que se derive de ahí).
No reemplaza la especificación funcional/técnica de `HERMES-MKI-VOBEDA` (esa sigue siendo la
fuente de verdad del protocolo real) — este directorio especifica **capacidades nuevas de la
herramienta de prueba misma**, que no existen en el protocolo SPEI real.

## Convención

- Un archivo por spec, numerado (`001-`, `002-`...) en el orden en que se propusieron, no en el
  orden en que se implementan.
- Cada spec sigue la misma forma: Contexto → Requisitos → Fuera de alcance → Diseño propuesto →
  Preguntas abiertas → Criterios de aceptación.
- **Preguntas abiertas es una sección real, no decorativa** — una spec puede mergearse con
  preguntas abiertas sin resolver, siempre que estén marcadas explícitamente y no bloqueen el
  criterio de aceptación mínimo. Se cierran en un commit posterior que las resuelve, citando el
  spec que actualiza.
- Cambios al protocolo/wire format del simulador (no a su tooling de pruebas) siguen siendo R2 —
  ver AGENTS.md §6, dueño de spec: Miguel Zavala.

## Índice

| # | Spec | Estado |
|---|---|---|
| [001](001-particionado-y-empaquetado.md) | Particionado y empaquetado configurable (B↔H) | En progreso |
| [002](002-devoluciones.md) | Devoluciones (B↔H), alcance acotado al simulador | En progreso (B→H) |
| [003](003-reenvio.md) | Reenvío real, solo el alcance de Banxico | Implementado (2026-10-06) |
| [004](004-firmas-corruptas.md) | Escenarios de firma corrupta (ambos sentidos) | En progreso (B→H) |
| [005](005-variaciones-de-red.md) | Variaciones de red simulables desde la API | Capa A completa y verificada contra minosA real (2026-10-06): corte, retraso, duplicación y throttling — este último encontró y corrigió un bug real (colgaba para siempre con mensajes grandes a tasas bajas), ya corregido y re-confirmado. Capa B (red real, host) no empezada |
| [006](006-folio-duplicado.md) | Folio/clave de rastreo duplicado | Implementado |
| [007](007-renovacion-certificado.md) | Renovación de certificado (`PideCrtNvo`) | Implementado (2026-10-06) |
| [008](008-msjcatalogos-contenido-real.md) | `MsjCatalogos` con contenido real | Implementado |
| [009](009-perdida-de-iamalive.md) | Pérdida de `IAmAlive` (heartbeat) | Implementado y verificado contra minosA real (2026-10-06) — supuesto original refutado, ver spec |
| [010](010-reconexion-a-media-transaccion.md) | Reconexión tras caída a media transacción | Borrador (depende de 001) |
| [011](011-mcp.md) | Integración MCP sobre la API de control; MCP junto a la API y plugin | Implementado y desplegado, confirmado en vivo (2026-10-06) |
| [012](012-pruebas-de-volumen.md) | Pruebas de volumen (tasa sostenida y búsqueda de techo) | En progreso |
| [013](013-registro-de-cierre-de-sesion.md) | Registro de cierre de sesión | Implementado y confirmado en host (2026-10-06) |
| [014](014-recepcion-y-liquidacion-de-pagos.md) | Recepción y liquidación de pagos: mapeo de motivos de rechazo, rechazo forzado, clave duplicada, `Cargos`, `LiquidacionFinal` | Primer ciclo completo (OrdenTopoV→AcuseRecibo→Cargos) confirmado contra minosa real; faltan escenarios de rechazo (2026-10-05) |
| [015](015-trazabilidad-cruzada.md) | Trazabilidad cruzada de una orden/pago (línea de tiempo Core falso↔Judeca↔simulador) | En progreso — clave de rastreo en eventos propios confirmada contra minosA real, falta la herramienta de línea de tiempo (2026-10-06) |

**2026-09-21 — hallazgo que afecta 003/007 y la parte H→B de 004:** el "arnés Python" que las
tres specs asumían poder extender **no existe en el repo** (`AGENTS.md` lo describe como
"ad-hoc", nunca se comiteó). Decisión: reconstruirlo en Java bajo `src/test/java` (JUnit 5, ya
está en `pom.xml`) en vez de Python, reutilizando las clases de cripto/wire ya existentes —
pendiente de construir, por eso quedan "Bloqueado" y no "Borrador".

**2026-10-06 — arnés Java reconstruido, 003 y 007 implementadas:** `mx.endcom.hermes.banxicosim.testsupport.SimuladorHarness`
+ `FakeMinosClient` (bajo `src/test/java`) arrancan una instancia completa del simulador en proceso
y juegan el papel de "minos falso" (identidad RSA propia, login ARA completo incluido `PideCrtNvo`,
reto `ClvSim`, `EnSesion`/`MsjCatalogos`, y `Reenvio` con `processedBytes` arbitrario) — ver
`specs/003-reenvio.md` y `specs/007-renovacion-certificado.md` &sect;"Estado de implementación"
para el detalle completo. Spec 003 sí requirió código nuevo en el simulador (`SentHistory` +
`RecordingOutputStream` + `SpeiSession.handleReenvio` reescrito); spec 007 no (solo el escenario de
prueba). La parte H→B de 004 (firmas corruptas) sigue pendiente — el arnés ya puede extenderse
para cubrirla, pero no se hizo en este trabajo (fuera del alcance pedido). **Nota sobre el entorno
de este agente:** no tenía JDK/Maven instalados nativamente — `mvn test`/`mvn -DskipTests package`
se verificaron dentro de un contenedor `maven:3.9-eclipse-temurin-17` (Docker), montando el repo;
quien retome este trabajo en un entorno con Maven nativo puede seguir usando los comandos de
AGENTS.md &sect;4 tal cual.

## Siguiente paso (actualizar al avanzar)

**Al 2026-09-28** — hecho: MCP junto a la API + plugin (spec 011) y registro de cierre de sesión
(spec 013), probados localmente con `docker compose`. Spec 005, capa A, implementada y compilada.
En orden:

1. **Desplegado el 2026-09-28** (spec 011 + 013) — ver `DEPLOY.md` para el flujo exacto (conexión
   SSH, un problema de permisos en `config/` ya encontrado y resuelto ahí, verificación). Cortó la
   sesión con minos, como se esperaba.
2. **Confirmado 2026-09-28:** minos reconectó (runId 5691, `vivaDesde` 23:06 UTC, heartbeat
   intercambiándose normal). Falta nada más que cerrar formalmente los criterios pendientes de 011
   y 013 (mandar un abono y confirmar `cierre` en `GET /session` al terminar una sesión) cuando
   alguien lo dispare.
3. **Implementada spec 005, capa A, el 2026-09-28; verificada contra minosA real el 2026-10-06** —
   las 5 clases, config, rutas HTTP y tools MCP ya están en el repo; compila y se probó tanto por
   HTTP sin minos como, ahora, los cuatro escenarios reales (corte en `post-ClvSim`, retraso al
   límite de 2500ms, duplicación de un `Abonos`, throttling a 5 B/s) contra minosA — ver
   `specs/005-variaciones-de-red.md` §"Estado de implementación" para el detalle y dos hallazgos
   nuevos (minos no deduplica frames repetidos; el heartbeat comparte `writeLock` con cualquier
   envío throttleado, así que un envío lento lo pausa por completo). Lista para main.
4. **Capa B** (`deploy.sh`, `scripts/host/pruebas-red.sh`, reescritura del skill a solo-MCP) —
   no empezada; instala un script con dueño root en el host real, decisión aparte de cuándo hacerla.

## Decisiones de alcance (2026-09-21)

- **Multi-sesión simultánea: excluido explícitamente.** No se especificará ni implementará —
  minos, por diseño (`speiSocket`/`araSocket` son campos singulares en
  `SpeiSocketServiceImpl`/`AraSocketServiceImpl`), no sostiene múltiples sesiones concurrentes
  hacia Banxico. No hay nada real que simular ahí.
- **CoDi: diferido**, no en el alcance actual — se marca como posible implementación futura si el
  equipo lo decide más adelante. No tiene spec todavía.

## Pendientes de convertir en spec

- Ninguno por ahora — todo lo identificado hasta el 2026-09-21 ya tiene spec (borrador).
