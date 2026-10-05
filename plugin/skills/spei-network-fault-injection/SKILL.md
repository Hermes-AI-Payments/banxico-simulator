---
name: spei-network-fault-injection
description: Diseña y ejecuta pruebas de fault injection de RED (latencia, jitter, pérdida de paquetes, reordenamiento, duplicación, corte abrupto de conexión en un punto del protocolo, throttling asimétrico, fragmentación MTU) contra el contenedor real del simulador SPEI (banxico-simulator). Para latencia/jitter, duplicación, corte abrupto en un punto nombrado y throttling, el simulador ya expone esto como API (spec 005 capa A, vía MCP, sin SSH) -- este skill lo usa cuando aplica y solo recurre a tc netem/ss a nivel de infraestructura (capa B) para lo que la API no cubre (pérdida de paquetes real, reordenamiento, fragmentación MTU) o cuando el usuario pide explícitamente fidelidad de red real en vez de la simulación de capa A. Úsalo siempre que el usuario mencione "spec 005", "variaciones de red", "simular latencia/jitter/pérdida de paquetes contra el simulador", "cortar la conexión de minos", "probar el timeout de minos", o pida automatizar/diseñar pruebas de red repetibles para banxico-simulator -- incluso si no menciona explícitamente "netem" o "tc". NO uses este skill para variaciones de contenido de abono (tipo de pago, firma corrupta, folio duplicado), campañas de carga/volumen (spec 012), ni recepción/rechazo/liquidación de pagos (spec 014) -- esos son otro alcance, fuera de este skill.
allowed-tools: mcp__plugin_banxico-simulator_simulador__simulator_health, mcp__plugin_banxico-simulator_simulador__simulator_session, mcp__plugin_banxico-simulator_simulador__simulator_list_test_runs, mcp__plugin_banxico-simulator_simulador__simulator_test_run_events, mcp__plugin_banxico-simulator_simulador__simulator_capabilities, mcp__plugin_banxico-simulator_simulador__simulator_start_network_variation, mcp__plugin_banxico-simulator_simulador__simulator_network_variation_status, mcp__plugin_banxico-simulator_simulador__simulator_stop_network_variation
---

# SPEI network fault injection (spec 005)

## Por qué existe este skill

`specs/005-variaciones-de-red.md` describe siete variaciones de red. **Cuatro
de las siete ya están implementadas como API de aplicación** (capa A: retraso,
corte, duplicación, throttling -- `POST /red/variacion`, expuesto por este
mismo plugin como `simulator_start_network_variation`/`simulator_capabilities`/
etc.) y no requieren SSH ni tocar el contenedor directamente. **Las otras tres
(pérdida de paquetes real, reordenamiento, fragmentación MTU) siguen sin
construirse en código** -- para esas, y para quien pida explícitamente
fidelidad de red real en vez de la simulación de capa A, este skill logra el
mismo objetivo *sin escribir ni una línea de código Java*, aplicando fault
injection real a nivel de red (kernel Linux, `tc netem`) contra el contenedor
Docker que ya corre el simulador (capa B). Es más fiel que capa A -- es
pérdida de paquetes y latencia reales, no una aproximación en software de
aplicación -- pero necesita SSH al host y es más disruptivo (degrada *toda* la
salida del contenedor, no solo el escenario que pediste).

**Antes de diseñar cualquier plan, decide capa A o capa B** (ver tabla en
`references/spec-005-scenarios.md`): si el escenario es latencia/jitter,
duplicación, corte abrupto en punto nombrado, o throttling, y el usuario no
pidió explícitamente netem/fidelidad real, prefiere capa A -- es más simple,
no requiere SSH, y no degrada tráfico que no sea parte del escenario. Dilo en
el plan ("uso capa A vía API, no netem, porque es más simple y no toca nada
más") para que quien lee el plan sepa qué mecanismo se aplicó. Usa capa B
(el resto de este skill) para pérdida de paquetes, reordenamiento, MTU, o
cuando el usuario pida explícitamente fidelidad de red real.

El simulador real vive en el host `192.168.1.200`, alcanzable por VPN, en el
contenedor `banxico-simulator` (nombre fijo, ver `docker-compose.yml`). Esto
es infraestructura real conectada a un cliente real (minos) -- no un sandbox
descartable. Trátalo con el mismo cuidado que tocar producción, sea cual sea
la capa que uses.

## Regla de autonomía (no negociable)

Este skill tiene dos fases separadas y **nunca las saltes ni las combines**:

1. **Diseñar y proponer.** Genera el plan completo -- qué comando(s) de
   `netem.sh` vas a correr, con qué parámetros exactos, contra qué sesión,
   qué escenario MCP vas a disparar durante la falla inyectada (si aplica), y
   el plan de limpieza correspondiente. Muéstraselo al usuario en texto claro
   ANTES de ejecutar nada contra el contenedor real.
2. **Ejecutar, solo tras confirmación explícita del usuario.** Una vez que
   confirme, corre el plan paso a paso, observa el resultado, y **siempre**
   ejecuta la limpieza al final -- incluso si el escenario falla a la mitad o
   el usuario cancela después de aplicar el qdisc.

Nunca apliques un qdisc netem, un reset de conexión, ni ningún cambio de MTU
sin haber mostrado el plan primero y recibido un "sí"/"adelante"/equivalente
explícito. Una pregunta genérica del usuario ("¿cómo simularía yo latencia?")
es una solicitud de diseño, no de ejecución -- responde con el plan, no lo
ejecutes de una vez.

## Flujo operativo

### 1. Detectar el contenedor

```bash
bash "${CLAUDE_PLUGIN_ROOT}/skills/spei-network-fault-injection/scripts/netem.sh" detect
```

Si falla porque el host no es alcanzable directamente desde donde corre
Claude Code, pide al usuario confirmar la ruta de acceso y exporta
`SPEI_SIM_RUNNER="ssh <usuario>@192.168.1.200"` antes de reintentar -- el
script antepone ese valor a cada comando `docker`. No asumas credenciales SSH
por tu cuenta; pregúntalas si `detect` falla.

### 1.5. Revisar el lock de sesión única

El simulador solo admite **una prueba de fault-injection de red a la vez** --
igual que en producción solo existe una sesión entre Hermes y Banxico, así que
es intencional que esto sea bloqueante, no una limitación a rodear. `detect`
ya muestra el estado del lock (`bash "${CLAUDE_PLUGIN_ROOT}/skills/spei-network-fault-injection/scripts/netem.sh" lock-status`). Si está
`OCUPADO`, el reporte incluye quién lo tomó (`owner`), para qué escenario, y
cuándo -- comunícaselo al usuario y **no continúes** diseñando un plan que no
se puede ejecutar todavía. No fuerces la liberación del lock (`docker rm -f
spei-fault-lock` a mano) solo porque falló la adquisición -- eso le quitaría
la prueba a quien la esté corriendo. Solo libéralo si el usuario confirma
explícitamente que esa persona ya terminó o que el lock quedó huérfano por un
fallo previo sin limpieza.

### 2. Confirmar estado de la sesión SPEI

Usa las tools MCP `simulator_health` y `simulator_session` (servidor
`simulador` de este mismo plugin, que apunta al MCP desplegado junto a la API
-- `BANXICO_SIM_MCP_URL`, default `http://192.168.1.200:8090/mcp`) antes de diseñar cualquier
escenario que dependa de una sesión viva (todos excepto los que solo tocan
MTU/latencia sin disparar tráfico de aplicación). Si no hay sesión viva y el
escenario la necesita, dilo en el plan como precondición pendiente -- no
esperes en silencio a que aparezca.

### 3. Elegir el escenario y mapearlo a comandos

Lee `references/spec-005-scenarios.md` -- ahí está la tabla completa de las
siete variaciones de spec 005, con el comando exacto de `netem.sh` para cada
una (o el procedimiento alterno para las dos que no son netem simple: corte
abrupto en punto nombrado, y throttling asimétrico). Cita los valores de
ejemplo de la spec (`delay 100ms 20ms`, `loss` con probabilidad `p`,
`cortarEn: "post-ClvSim"`, etc.) cuando el usuario no dé parámetros propios.

Si el usuario pide combinar variaciones (p.ej. latencia + pérdida +
reordenamiento a la vez), es válido -- netem acepta múltiples parámetros en
un solo `tc qdisc add ... netem <args combinados>`. Inclúyelo así en el plan.

### 4. Presentar el plan

Estructura el plan que le muestras al usuario así, siempre:

```
## Plan: <nombre del escenario>
**Qué simula:** <una línea, en términos de spec 005>
**Precondición:** <sesión viva sí/no, estado actual>
**Comando(s) a ejecutar:**
  1. <comando netem.sh exacto>
  2. <acción MCP a disparar durante la falla, si aplica -- ej. simulator_send_abono_valido>
  3. <cómo se observará el resultado -- session/test-run-events>
**Limpieza garantizada:** <comando(s) de rollback, siempre incluidos aunque el usuario no los pida>
**Riesgo:** <qué tan disruptivo es -- ej. "puede tumbar la sesión activa de minos", "solo latencia, no debería cortar nada">
```

Espera confirmación explícita antes de seguir.

### 5. Ejecutar y observar

- Aplica el/los comando(s) con `${CLAUDE_PLUGIN_ROOT}/skills/spei-network-fault-injection/scripts/netem.sh apply ...` (o el
  procedimiento manual de la sección correspondiente en el reference para
  throttling asimétrico o corte abrupto). `apply` y `reset-conn` toman el
  lock de sesión única automáticamente -- si alguien más lo tiene, el
  comando falla con el detalle de quién y para qué, en vez de aplicar nada.
  Para el procedimiento manual de throttling asimétrico (que no pasa por
  `apply`), toma el lock a mano primero: `${CLAUDE_PLUGIN_ROOT}/skills/spei-network-fault-injection/scripts/netem.sh lock-acquire
  "throttling asimétrico"`.
- Dispara el tráfico de prueba necesario vía las tools MCP existentes
  (`simulator_send_abono_valido`, etc.) si el escenario lo requiere.
- Observa el efecto con `simulator_session` y/o
  `simulator_test_run_events` -- reporta lo que realmente pasó, no solo que
  el comando no falló.

### 6. Limpieza garantizada

Corre `bash "${CLAUDE_PLUGIN_ROOT}/skills/spei-network-fault-injection/scripts/netem.sh" clean` **siempre**, sin excepción, al terminar
la observación -- exitoso, fallido, o si el usuario interrumpe a mitad de
camino. Es idempotente (seguro llamarlo aunque ya esté limpio). No dejes al
simulador con un qdisc aplicado entre una prueba y otra: la siguiente persona
que use el simulador (o tú mismo en la siguiente prueba) heredaría la
degradación sin saberlo. `clean` también libera el lock de sesión única --
si lo tomaste a mano (throttling asimétrico), corre `${CLAUDE_PLUGIN_ROOT}/skills/spei-network-fault-injection/scripts/netem.sh
lock-release` como último paso si por alguna razón no pasas por `clean`.

Si el escenario fue un corte abrupto (`reset-conn`), no hay qdisc que limpiar
-- confirma solo que la sesión murió como se esperaba (ver
`references/spec-005-scenarios.md`); el lock se libera solo al final de ese
comando.

## Notas honestas sobre el enfoque

- Para los cuatro escenarios de capa A (retraso, duplicación, corte abrupto,
  throttling), el código del simulador **sí** cambió -- es la implementación
  real de spec 005 vía API. Para los otros tres (pérdida de paquetes,
  reordenamiento, MTU), este skill logra la misma intención por otro mecanismo
  (netem/kernel, capa B), sin tocar el código Java. Dilo si el usuario compara
  el resultado contra los criterios de aceptación originales de la spec, para
  que sepa cuál mecanismo aplicó en cada caso.
- Un qdisc `root` en `eth0` del contenedor afecta **solo el tráfico que sale
  del simulador** (hacia minos), no el que entra -- para afectar la dirección
  minos→simulador hace falta el procedimiento de ingress con `ifb` (throttling
  asimétrico). Para abonos, que van del simulador a minos, basta con la salida.
  Además afecta **toda** la salida del contenedor: los `AreYouAlive`, el ARA y
  las respuestas de la API de control también se degradan mientras esté activo.
- La pérdida de paquetes con `loss` de netem es pérdida real a nivel de
  paquete, pero TCP la retransmite sola: para minos se percibe como **retraso**,
  no como un mensaje que nunca llega. Solo termina en corte si las
  retransmisiones acumuladas superan su timeout de lectura de 6s (spec 009). Si
  el usuario pide que un abono "no llegue", eso es un corte abrupto (fila 5), no
  `loss` -- acláralo en el plan en vez de prometer pérdida de mensajes (spec 005 ya anotaba esta ambigüedad, ligada a que spec 001 de
  particionado real tampoco resuelve retransmisión granular).
