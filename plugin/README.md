# Plugin de Claude Code: `banxico-simulator`

Todo lo que hace falta para probar `minos` contra el simulador SPEI desde Claude Code, **sin
clonar este repo**:

- **Conexión con el simulador** (`.mcp.json`): apunta al servidor MCP que corre junto a la API de
  control en el host del simulador (spec 011).
- **Skill `spei-network-fault-injection`** (`skills/`): diseña y ejecuta pruebas de variaciones de
  red (spec 005), siempre proponiendo el plan y esperando confirmación antes de tocar nada real.

## Instalar

Requisito: la computadora conectada a la red interna donde vive el simulador (VPN).

Dentro de Claude Code, desde cualquier carpeta:

```
/plugin marketplace add Hermes-AI-Payments/banxico-simulator
/plugin install banxico-simulator@banxico-simulator
```

Para recibir versiones nuevas: `/plugin marketplace update banxico-simulator`.

## Configurar (solo si el simulador no está en la dirección de siempre)

La conexión usa `http://192.168.1.200:8090/mcp` por defecto. Para otro host, define la variable de
entorno antes de abrir Claude Code:

```bash
export BANXICO_SIM_MCP_URL=http://<host>:8090/mcp
```

## Qué se puede pedir

En español normal, por ejemplo: "¿Hay una sesión activa con el banco ahora mismo?", "Manda un pago
de prueba usando el simulador", "Simula que la red va lenta mientras mandas un pago de prueba",
"Fuerza el rechazo del próximo pago con motivo cuenta inexistente", "¿Cuál es el saldo del día
operativo?", "Cierra el día operativo".

## Permisos

Las herramientas de solo lectura (estado, sesión, historial) quedan preaprobadas mientras el skill
está activo. Mandar abonos o aplicar variaciones de red siempre pasa por la confirmación normal de
Claude Code. Nombres de herramienta para un allowlist propio:
`mcp__plugin_banxico-simulator_simulador__<herramienta>`.

## Pruebas de red: dos capas

Cuatro de las siete variaciones de spec 005 (latencia/jitter, duplicación, corte abrupto en un
punto nombrado, throttling) ya están implementadas como API de aplicación (capa A) y funcionan
solo con el plugin, sin SSH. Las otras tres (pérdida de paquetes real, reordenamiento, fragmentación
MTU) siguen sin construirse en código: para esas, el skill recurre a `scripts/netem.sh` por SSH
contra el host del simulador — ese paso sí requiere acceso SSH. El resto (estado, abonos, historial,
rechazo forzado, saldo, cierre de día) funciona solo con el plugin.
