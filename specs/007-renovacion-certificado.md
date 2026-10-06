# 007 — Renovación de certificado (`PideCrtNvo`)

## Contexto

Ya implementado y verificado desde la Fase 2 original (README, "Estado de avance por fase"):
`AraSession.handlePideCrtNvo` (`AraSession.java:163-179`) atiende `PideCrtNvo` (op `0x50`) y
responde `RegCrtNvoFmt` (op `0xC3`, `AraSession.java:181-200`) con el certificado propio si el
número solicitado coincide, o `CrtNoExiste` (op `0xC2`, `AraSession.java:203-207`) si no. Lo que
falta no es código — es un **escenario de prueba repetible** que lo ejercite deliberadamente
contra minos real o el arnés Python, en vez de haber sido validado solo una vez al construirlo.

## Requisitos

1. Poder disparar, como escenario de prueba, una solicitud `PideCrtNvo` pidiendo el certificado
   propio del simulador — confirmar que la respuesta `RegCrtNvoFmt` sigue siendo correcta con la
   identidad actual (recordemos que la identidad se regenera en cada VM nueva — ver el incidente
   de huellas de certificado no coincidentes documentado en esta misma conversación).
2. Poder disparar una solicitud `PideCrtNvo` pidiendo un número de certificado **que no existe**,
   y confirmar que la respuesta es `CrtNoExiste`, no un error no manejado.

## Fuera de alcance

- La lógica de minos sobre cuándo decide pedir un certificado nuevo (vencimiento, rotación, etc.)
  — eso es interno a minos, no se simula.

## Diseño propuesto

- Ninguno nuevo del lado del simulador — la implementación ya existe. Esta spec es sobre
  **cobertura de prueba**, no sobre construir algo. El trabajo real es: un escenario en el arnés
  Python que dispare ambos casos (número propio / número inexistente) y verifique la respuesta,
  para que quede como prueba de regresión repetible en vez de haberse verificado una sola vez.

## Preguntas abiertas

- ¿Vale la pena también probar que minos, al recibir `RegCrtNvoFmt`, efectivamente registra el
  certificado nuevo? Eso requeriría inspeccionar el estado de minos después, no solo la respuesta
  del simulador — puede que exceda lo que el simulador puede verificar por sí mismo.

## Criterios de aceptación

- [x] Escenario de prueba repetible (arnés Java — ver "Estado de implementación"; la spec
      originalmente decía "arnés Python", ver `specs/README.md` hallazgo 2026-09-21) para
      `PideCrtNvo` con número propio → confirma `RegCrtNvoFmt` con el certificado correcto de la
      identidad actual — ver `PideCrtNvoAcceptanceTest.pideCrtNvoConNumeroPropioConfirmaRegCrtNvoFmt`.
- [x] Escenario de prueba repetible para `PideCrtNvo` con número inexistente → confirma
      `CrtNoExiste` — ver `PideCrtNvoAcceptanceTest.pideCrtNvoConNumeroInexistenteConfirmaCrtNoExiste`.

## Estado de implementación (2026-10-06)

Sin cambios de código en el simulador (confirmado en "Diseño propuesto": `AraSession.handlePideCrtNvo`
ya funcionaba). Lo que se construyó fue el arnés de pruebas Java que faltaba (ver `specs/README.md`
hallazgo 2026-09-21):

- `mx.endcom.hermes.banxicosim.testsupport.SimuladorHarness` — arranca el simulador completo
  (incluido el servidor ARA) en proceso, puertos efímeros.
- `mx.endcom.hermes.banxicosim.testsupport.FakeMinosClient` — hace el login ARA completo (`ConnUsr`
  → `IdUsuarioAleat`/`IdFmaAleat` → `Logged`) y manda `PideCrtNvo` con un número de certificado
  arbitrario; parsea la respuesta (`RegCrtNvoFmt` o `CrtNoExiste`) usando el nuevo
  `AraWireFraming.parsePaddedSignedBody` (contraparte de lectura de `buildSignedBody`, que no
  existía porque nadie más que minos real parseaba este formato).
- `mx.endcom.hermes.banxicosim.ara.PideCrtNvoAcceptanceTest` — los dos tests de esta spec. El
  primero además verifica la firma RSA de `RegCrtNvoFmt` contra la llave pública de la identidad
  actual del simulador (verificación extra, no pedida explícitamente por el criterio de
  aceptación, pero gratis de hacer con el código ya escrito).

**Pregunta abierta de la spec** ("¿vale la pena probar que minos registra el certificado nuevo?"):
sigue sin resolverse — confirmado que excede lo que este simulador puede verificar por sí mismo
(no hay forma de inspeccionar el estado interno de una instancia real de minos desde aquí).

**Verificado:** `mvn test` corre los 9 tests del repo (incluidos los 2 nuevos de esta spec) en
verde, dentro de un contenedor `maven:3.9-eclipse-temurin-17` (este entorno de agente no tiene
JDK/Maven instalados nativamente — ver nota en `specs/README.md`).
