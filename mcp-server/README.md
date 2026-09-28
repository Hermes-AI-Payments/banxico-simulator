# banxico-simulator-mcp

Servidor MCP (spec [011](../specs/011-mcp.md)) que envuelve la API de control HTTP del simulador
(`ControlServer.java`). No la reemplaza — cada herramienta de aquí es una llamada HTTP directa a
un endpoint que sigue funcionando igual por `curl`/`httpclient/*.http`.

## Despliegue (modo normal): junto a la API, por HTTP

`docker compose up --build` en la raíz del repo levanta este servidor como segundo servicio
(`banxico-simulator-mcp`), publicado en `http://<host>:8090/mcp` (Streamable HTTP, sin estado).
Habla con la API por la red interna de compose (`SIMULATOR_URL=http://banxico-simulator:8089`).

Quien prueba no necesita nada de esta carpeta: instala el plugin de Claude Code
([`../plugin/`](../plugin/README.md)), que ya apunta a esa URL.

`GET /health` del propio MCP responde `{"status":"ok","simulatorUrl":...}` (lo usa el
`HEALTHCHECK` del contenedor). Solo `POST /mcp` está soportado — al ser sin estado, no hay stream
`GET` de notificaciones ni `DELETE` de sesión (responden 405).

## Desarrollo local: stdio

```bash
npm install
SIMULATOR_URL=http://192.168.1.200:8089 node index.js
```

Sin `MCP_TRANSPORT=http` corre por stdio, para registrarlo a mano en un cliente MCP como
subproceso mientras se desarrolla una herramienta nueva.

| Variable | Default | Qué es |
|---|---|---|
| `SIMULATOR_URL` | `http://localhost:8089` | URL de la API de control |
| `MCP_TRANSPORT` | `stdio` | `http` en despliegue (lo fija el `Dockerfile`) |
| `MCP_PORT` | `8090` | Puerto del modo HTTP |

## Herramientas expuestas

| Herramienta | Endpoint que envuelve |
|---|---|
| `simulator_health` | `GET /health` |
| `simulator_session` | `GET /session` |
| `simulator_send_abono_valido` | `POST /abonos/validos` |
| `simulator_send_abono_invalido` | `POST /abonos/invalidos` |
| `simulator_send_abono` | `POST /abonos` (tipo de pago/clave/firma configurables — specs 002/004/006) |
| `simulator_stop_heartbeat` | `POST /heartbeat/detener` (spec 009) |
| `simulator_list_test_runs` | `GET /test-runs` |
| `simulator_test_run_events` | `GET /test-runs/{id}/events` |
| `simulator_start_load_campaign` | `POST /abonos/carga` (spec 012) |
| `simulator_load_campaign_status` | `GET /abonos/carga/{id}` (spec 012) |
| `simulator_stop_load_campaign` | `POST /abonos/carga/{id}/detener` (spec 012) |

Como el MCP se despliega con la misma versión del repo que la API, las herramientas y los
endpoints que envuelven quedan siempre sincronizados.

## Sin autenticación propia

Mismo nivel de acceso que la API de control HTTP — decisión documentada en spec 011: la VPN hacia
la red del simulador es la frontera de confianza.

## Estado

Cubre exactamente lo que existe en la API de control al día de hoy. Conforme las specs 001, 003,
005, 007, 010 agreguen endpoints nuevos (particionado, variaciones de red, etc.), las
herramientas correspondientes se agregan aquí siguiendo el mismo patrón — no se diseñan por
adelantado sin la API que envuelven ya definida (ver spec 011).
