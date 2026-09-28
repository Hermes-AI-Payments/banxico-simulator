# Spec 005 → comandos de fault injection

Spec original: `specs/005-variaciones-de-red.md`. Esa spec proponía un decorator
de aplicación dentro de `SpeiSession`/`AraSession` (nunca implementado). Este
skill logra la misma *intención* con fault injection a nivel de infraestructura
-- **sin tocar código del simulador** -- así que el mecanismo real no coincide
literalmente con la columna "Cómo se implementaría" de la spec para la mayoría
de las filas. Vale la pena decirlo explícitamente cuando propongas un plan, para
que no se confunda con una implementación de la spec en Java.

Todos los comandos de netem se aplican sobre la interfaz `eth0` del contenedor
`banxico-simulator` vía `scripts/netem.sh` (sidecar `nicolaka/netshoot`
compartiendo su network namespace). Un qdisc `root` en esa interfaz actúa
sobre el tráfico **de salida** del contenedor: todo lo que el simulador manda
(abonos, `AreYouAlive`, respuestas ARA y de la API de control), no lo que
recibe de minos. Para la dirección minos→simulador hace falta el ingress con
`ifb` de la fila 6.

| # | Variación (spec 005) | Comando | Notas |
|---|---|---|---|
| 1 | Latencia fija/jitter (`{"latenciaMs": N, "jitterMs": M}`) | `netem.sh apply "delay <N>ms <M>ms"` | Ejemplo spec: `delay 100ms 20ms`. |
| 2 | Pérdida de paquetes (probabilidad `p`) | `netem.sh apply "loss <p>%"` | Spec nota: sin partición real (spec 001), "perder un paquete" puede ser indistinguible de cortar la conexión -- para minos, un frame SPEI completo perdido probablemente se ve como timeout, no como retransmisión granular. Documenta esto en el plan que le muestres al usuario. |
| 3 | Reordenamiento | `netem.sh apply "reorder <p>% <correlacion>%"` | Ejemplo: `reorder 25% 50%`. Requiere que exista más de un paquete en vuelo para que el reordenamiento sea observable -- combínalo con `delay` en el paquete "no reordenado" (netem lo exige: `reorder` sin `delay` en algunos kernels no tiene efecto visible). Ejemplo combinado: `delay 10ms reorder 25% 50%`. |
| 4 | Duplicación (`p`) | `netem.sh apply "duplicate <p>%"` | Ejemplo spec: `duplicate 1%`. Útil para probar idempotencia del receptor ante folios duplicados a nivel de wire (distinto de folio duplicado a nivel de aplicación, que es spec 006 y está fuera de alcance de este skill). |
| 5 | Corte abrupto en punto nombrado (`{"cortarEn": "post-ClvSim"}`) | `netem.sh reset-conn <ip-minos> <puerto>` | **No usa netem.** Usa `ss -K` dentro del mismo sidecar para forzar el reset de la conexión TCP activa en el momento exacto. Procedimiento completo abajo. Único ejemplo de punto nombrado dado por la spec es `post-ClvSim`; para otros puntos, identifica el `messageName`/`opCode` de referencia en `GET /test-runs/{id}/events` y dispara el reset justo después de verlo aparecer. |
| 6 | Throttling asimétrico (bytes/seg por dirección) | Dos `tc` qdiscs vía `tbf`, uno en `eth0` (egress del contenedor) y otro en un `ifb0` redirigido desde el ingress | Ver sección dedicada abajo -- requiere más pasos que `netem.sh apply` por sí solo. |
| 7 | MTU reducido / fragmentación forzada | `netem.sh apply "<netem-args>" <mtu>` (segundo argumento) | La propia spec 005 (líneas 38-52) señala que esto vive en el SO, no en la app -- exactamente lo que hace este comando. Ejemplo: `netem.sh apply "delay 5ms" 512`. |

## Procedimiento: corte abrupto en punto nombrado (fila 5)

Este es el único escenario *stateful* -- depende de la fase del protocolo, no
solo de parámetros estáticos de red. No requiere Toxiproxy ni reconfigurar a
minos: como el simulador acepta la conexión, se puede forzar el reset desde
el mismo host con `ss -K` (disponible en `nicolaka/netshoot` via iproute2).

1. Antes de iniciar, obtén el `remoteAddress` de la sesión viva con
   `simulator_session` (MCP) -- ese es el `<ip-minos>` y su puerto efímero.
2. Dispara la acción que produce el punto nombrado (p.ej. para `post-ClvSim`,
   deja que ocurra el envío normal de `ClvSim` -- no se necesita intervención,
   solo observación).
3. Vigila `simulator_test_run_events` (o `GET /test-runs/{id}/events`) en
   busca de que el evento objetivo aparezca (p.ej. `messageName: "ClvSim"`,
   `direction: OUT`, `result: enviado`).
4. Inmediatamente después de confirmarlo, ejecuta
   `netem.sh reset-conn <ip-minos> <puerto-minos>`.
5. Verifica el efecto con `simulator_session` (debería mostrar `alive: false`)
   y con los eventos posteriores (busca ausencia de `RespClvSim` o un cierre
   abrupto sin `FinSesion`).

No hay forma de "deshacer" un reset ya disparado -- la limpieza aquí es
simplemente confirmar que la sesión murió como se esperaba; no queda ningún
qdisc ni estado pendiente que revertir (a diferencia de netem).

## Procedimiento: throttling asimétrico (fila 6)

`scripts/netem.sh` no automatiza este caso por su complejidad (requiere el
módulo `ifb` para redirigir tráfico de ingreso, que no siempre está cargado
en el kernel del host Docker) -- pero sí debe pasar por el mismo lock que
todo lo demás, porque también ocupa la única sesión de prueba disponible:

```bash
# 1. Tomar el lock a mano (apply/reset-conn lo hacen solo; esto no)
scripts/netem.sh lock-acquire "throttling asimétrico"
```

Luego ejecuta manualmente dentro del sidecar:

```bash
# Egress: limitar salida del contenedor a 50kbit
tc qdisc add dev eth0 root tbf rate 50kbit burst 32kbit latency 400ms

# Ingress: requiere redirigir a un dispositivo ifb primero
ip link add ifb0 type ifb
ip link set ifb0 up
tc qdisc add dev eth0 handle ffff: ingress
tc filter add dev eth0 parent ffff: matchall action mirred egress redirect dev ifb0
tc qdisc add dev ifb0 root tbf rate 20kbit burst 16kbit latency 400ms
```

Limpieza manual correspondiente:

```bash
tc qdisc del dev eth0 root
tc qdisc del dev eth0 ingress
tc qdisc del dev ifb0 root 2>/dev/null || true
ip link del ifb0 2>/dev/null || true
```

```bash
# 2. Liberar el lock -- no lo olvides, esto no pasa por netem.sh clean
scripts/netem.sh lock-release
```

Si el host Docker no soporta `ifb` (módulo no cargable, común en algunos
kernels de VM), repórtalo al usuario como limitación y ofrece degradar el
escenario a throttling solo de salida (`tbf` simple en `eth0`, afecta lo
que el simulador manda hacia minos) en vez de bloquear todo el plan por esto.

## Preguntas abiertas de la spec (sin resolver -- decide con el usuario si aplican)

1. ¿Los parámetros de red son estáticos durante toda la corrida, o deben poder
   cambiar a mitad de corrida (p.ej. "sube la latencia después del mensaje 3")?
   Este skill asume **estático**: un plan = un conjunto de parámetros fijos
   aplicados antes de disparar el escenario. Si el usuario pide algo dinámico,
   dile explícitamente que es un modo no cubierto todavía y pregúntale si
   quiere que se diseñe como una secuencia de planes encadenados en vez de uno
   solo.
2. ¿La fragmentación MTU pertenece al "API de control" o es config de infra
   aparte? Este skill la trata como infra (comando directo, no vía HTTP), lo
   cual coincide con lo que la propia spec 005 sugería como la única opción
   viable.
