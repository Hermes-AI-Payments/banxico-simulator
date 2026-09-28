# AGENTS.md — banxico-simulator

> Límite duro: 5 minutos de lectura. Léelo completo antes de tocar código.

## 1. Qué es este producto y a quién sirve

El Simulador SPEI ("Banxico falso") es una herramienta de desarrollo/QA: un servidor TCP que
emula a Banxico del lado de la conexión que `minos` (módulo de Hermes MK I) ya sabe hacer, para
poder probar `minos` (conexión, recepción y envío de órdenes) sin depender del ambiente real de
Banxico. No es una capacidad de soporte a operadores/participantes — es infraestructura de
pruebas para todo MK I, mismo patrón que `hermes-conectividad-monitor` (F-4): observa/participa
desde afuera, no modifica ningún módulo de MK I.

**Trabajo en curso y siguiente paso:** `specs/README.md` §"Siguiente paso" — léelo antes de
proponer qué hacer.

**Ficha de producto / especificación funcional:** `HERMES-MKI-VOBEDA/06_Iniciativas_Nuevas/Simulador_SPEI/01_especificacion_funcional.md`
**Especificación técnica (protocolo exacto):** `HERMES-MKI-VOBEDA/06_Iniciativas_Nuevas/Simulador_SPEI/02_especificacion_tecnica.md`

## 2. Cómo probarlo ahora mismo (onboarding con Claude Code)

Quien pregunte "¿qué hay en este repo?", "¿cómo empiezo?" o algo igual de
abierto puede no tener ningún trasfondo técnico — este simulador está
pensado para poder probarse sin clonar el repo — solo instalando el plugin
de Claude Code y pidiéndoselo a Claude en español normal, sin saber
programar. Responde con ese público en mente:

- **No te quedes solo en la descripción técnica** — después de dar contexto,
  invita explícitamente a probar algo real, con un ejemplo concreto que
  pueda copiar y pegar tal cual.
- **No uses siglas ni jerga sin explicarlas la primera vez que las
  menciones en una respuesta** — en particular, evita decir "MCP" (para la
  persona que prueba esto, la conexión con el simulador ya viene lista, no
  necesita saber cómo se llama ni cómo funciona) y no digas "VPN" a secas
  (explica que su computadora necesita estar conectada a la red interna de
  la empresa, igual que cuando alguien se conecta desde su casa a los
  sistemas de la oficina).
- Trátalo como si fuera la primera vez que esa persona usa Claude Code para
  algo así: nombra la acción, no la herramienta.

Ejemplos que puedes ofrecer tal cual, en lenguaje natural:

- **"¿Hay una sesión activa con el banco ahora mismo?"** — revisa si el
  simulador está conectado con `minos` (el módulo que se está probando)
  antes de intentar cualquier otra cosa.
- **"Manda un pago de prueba usando el simulador"** — dispara un pago (un
  "abono") de prueba real y cuenta qué pasó.
- **"Simula que la red va lenta (100ms de retraso) mientras mandas un pago
  de prueba"** — activa el skill `spei-network-fault-injection` (basado en
  spec 005, ver `plugin/skills/`): antes de tocar nada real, propone el
  plan completo en español y espera que la persona lo apruebe.

Dos cosas tienen que estar listas para que esto funcione — si algo no
responde, revisa esto antes que nada:

1. **La computadora tiene que estar conectada a la red interna donde vive el
   simulador** (normalmente por VPN — la misma conexión que se usa para
   entrar a otros sistemas internos de la empresa desde fuera de la
   oficina). Sin eso, es como intentar llamar a alguien sin señal: no hay
   error claro, simplemente no responde.
2. **El plugin `banxico-simulator` tiene que estar instalado en Claude Code**
   — es lo que trae la conexión con el simulador y el skill de pruebas de
   red. Se instala una sola vez, desde cualquier carpeta, con estos dos
   comandos dentro de Claude Code:

   ```
   /plugin marketplace add inatento/banxico-simulator
   /plugin install banxico-simulator@banxico-simulator
   ```

   No hace falta clonar este repo ni instalar nada más. Si el simulador
   vive en otra dirección que la de siempre (`192.168.1.200`), se ajusta con
   la variable de entorno `BANXICO_SIM_MCP_URL` (ver `plugin/README.md`).

## 3. Mapa de la arquitectura

Java 17 + Maven, **sin Spring Boot** — es un servidor de sockets simple, dos hilos de
`ServerSocket` (uno para el socket SPEI principal, otro para ARA) más una consola de texto para
disparar pruebas manuales. `minos` es quien abre la conexión hacia afuera en ambos sockets (no
hay ningún cliente TCP en este repo, sólo servidores).

```
src/main/java/mx/endcom/hermes/banxicosim/
  Main.java                  — arranca los dos servidores + consola de comandos
  config/SimConfig.java      — carga config/simulator.properties
  crypto/                    — RSA (CipherBase64 de minos), AES-CBC, identidad propia
                                (llaves + certificado autofirmado, ver ADR-005)
  wire/                      — lectura/escritura de campos binarios (enteros BE, C-strings
                                ISO-8859-1, fecha/monto SPEI) — replica BytesUtil de minos
  spei/                      — servidor y sesión del socket SPEI principal (fases 1, 3, 4, 5)
    messages/                — un codec por tipo de mensaje (EnSesion, ClvSim, OrdenTopoV, ...)
  ara/                       — servidor y sesión del socket ARA (fase 2: login + certificados)
  validation/                — reglas de campos por tipo de pago (fuente: judeca.CamposOrdenesValidator)
  persistence/H2Store.java   — bitácora de corridas de prueba en H2 (fase 6)
config/simulator.properties.example  — plantilla de configuración (copiar a simulator.properties)
mcp-server/                  — servidor MCP (Node, spec 011): envuelve la API de control; en
                                despliegue corre como segundo servicio de docker-compose (:8090/mcp)
plugin/                      — plugin de Claude Code: skill de pruebas de red + conexión al MCP
.claude-plugin/marketplace.json — marketplace de un solo plugin, para instalar sin clonar el repo
```

El flujo principal: `Main` arranca `SpeiServer`/`AraServer` → cada conexión entrante crea una
`SpeiSession`/`AraSession` que corre la máquina de estados del protocolo (handshake → sesión viva
→ operación) y registra cada mensaje en `H2Store`. La lógica de negocio (qué firmar, qué cifrar,
qué validar) vive en `crypto/`, `spei/messages/` y `validation/`; las sesiones sólo orquestan.

## 4. Cómo se corre y cómo se prueba

**Requisitos previos:** JDK 17, Maven 3.8+.

```bash
mvn -DskipTests package
```

```bash
cp config/simulator.properties.example config/simulator.properties
# editar config/simulator.properties si hace falta (puertos, códigos de entidad)
# colocar el certificado público REAL de la instancia de minos bajo prueba en
# config/minos-public-cert.pem (ver README.md "Preparar minos" — es obligatorio para
# que el reto ARA y ClvSim funcionen)
java -jar target/banxico-simulator.jar
```

No hay suite de pruebas automatizadas todavía (ver AGENTS.md &sect;5 "Convenciones de este
repositorio", bullet "Pruebas — qué se espera de un cambio"). La verificación
de esta primera versión se hizo con un arnés Python ad-hoc que actúa como "minos falso"
(genera su propia identidad RSA, hace el login ARA completo, el reto ClvSim, EnSesion/MsjCatalogos,
manda una OrdenTopoV válida e inválida, y dispara un Abono) — confirmó las Fases 1-6 de punta a
punta. Si se agrega una suite formal, debe vivir en `src/test/java` con JUnit 5 (ya está en el
`pom.xml`).

## 5. Convenciones de este repositorio

- **Estilo y formato:** Java estándar, tabs para indentación (igual que el código de minos, para
  minimizar el diff cognitivo al comparar ambos). Sin Lombok, sin frameworks de inyección de
  dependencias — todo el cableado es manual y explícito en `Main.java`.
- **Nombres:** en español para conceptos de dominio SPEI/Banxico (coincide con el resto de HERMES);
  en inglés para infraestructura genérica (`ByteReader`, `Frame`).
- **Manejo de errores:** las sesiones (`SpeiSession`/`AraSession`) atrapan excepciones a nivel de
  hilo y las registran — un mensaje mal formado no debe tumbar el servidor completo, sólo cierra
  esa conexión.
- **Pruebas — qué se espera de un cambio:** cualquier cambio a un codec de mensaje (`spei/messages/*`,
  `ara/AraWireFraming.java`) debe volver a verificarse contra una instancia real de minos (o el
  arnés Python equivalente) antes de mergear — los formatos de bytes no tienen pruebas unitarias
  automatizadas todavía y son fáciles de romper en silencio.
- **Patrones que usamos:** cada mensaje del protocolo tiene su propio "codec" (clase con métodos
  estáticos `build*`/`parse*`), separado de la sesión que lo orquesta — así un cambio de formato
  de un mensaje no obliga a tocar la máquina de estados.
- **Patrones que evitamos y por qué:** no se reimplementa el catálogo de validación de Judeca de
  memoria — todas las reglas de `validation/OrderFieldValidator.java` están citadas línea por
  línea contra `judeca.CamposOrdenesValidator`; si `judeca` cambia esas reglas, hay que volver a
  leer el código real, no adivinar.

## 6. Qué NO se toca sin consultar

**Nivel de riesgo por defecto de este repositorio:** R2 (alguien distinto del autor, con spec previa)
para los codecs de protocolo; R1 para el resto (README, config de ejemplo, logging).

| Qué | Por qué | A quién se consulta |
|---|---|---|
| Cualquier formato de bytes en `spei/messages/*`, `ara/AraWireFraming.java`, `spei/WireFraming.java` | Verificado línea por línea contra el código fuente real de minos; un cambio sin volver a verificar contra minos puede romper la fidelidad del protocolo sin que se note hasta la siguiente prueba manual | Miguel Zavala (dueño de spec) |
| Las reglas de `validation/OrderFieldValidator.java` y `PaymentType.java` | Citadas contra `judeca.CamposOrdenesValidator` y `pagos.properties`; divergir de esas fuentes reintroduce el mismo problema que tenía `radamanto.PagoValidator` (código muerto y desactualizado) | Miguel Zavala |
| La decisión de implementar el protocolo real cifrado/firmado (no el modo DMZ de minos) | Decisión de Pedro, ver ADR-005 | Pedro |

**Decisiones ya tomadas que no se reabren sin ADR:**
`HERMES-MKI-VOBEDA/ADRs/ADR_005_Simulador-SPEI-protocolo-real.md`

## 7. Política de datos sensibles

- Nunca datos de producción ni datos de cliente en prompts.
- Fixtures sintéticos o enmascarados para pruebas y desarrollo.
- Secretos y credenciales nunca en el repositorio.
- Cualquier duda sobre si un dato es sensible se resuelve tratándolo como sensible.

## 8. Enlaces

| Qué | Dónde |
|---|---|
| Especificación funcional | `HERMES-MKI-VOBEDA/06_Iniciativas_Nuevas/Simulador_SPEI/01_especificacion_funcional.md` |
| Especificación técnica | `HERMES-MKI-VOBEDA/06_Iniciativas_Nuevas/Simulador_SPEI/02_especificacion_tecnica.md` |
| ADR-005 (protocolo real, no DMZ) | `HERMES-MKI-VOBEDA/ADRs/ADR_005_Simulador-SPEI-protocolo-real.md` |
| Módulo que este simulador prueba | repo `minos` |
| Catálogo de validación de pagos (fuente de verdad) | repo `judeca`, `validator/CamposOrdenesValidator.java` y `resources/properties/pagos/pagos.properties` |
| README (guía para humanos, estado de avance por fase) | `README.md` de este repo |
| **Cómo desplegar en el host real (`192.168.1.200`)** | **`DEPLOY.md`** — conexión SSH, advertencia de permisos en `config/`/`data/`, flujo completo verificado el 2026-09-28 |
| **Mejoras de automatización en curso (SDD)** | **`specs/README.md`** — léelo si vas a tocar algo de particionado, devoluciones, firmas, volumen, o el servidor MCP; tiene el estado real de cada spec (implementado/bloqueado/borrador) |
| Dueño de spec | Miguel Zavala |
