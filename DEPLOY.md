# DEPLOY.md — Desplegar en el host de pruebas (`192.168.1.200`)

Runbook operativo para actualizar el simulador (y su MCP) en el host real donde vive conectado a
minos. Escrito el 2026-09-28 tras el primer despliegue de las specs 011/013 — sigue estos pasos
literalmente, no los reinventes por commit; si algo de aquí queda obsoleto, actualiza este archivo
en el mismo commit que lo cambie.

## Dónde vive y cómo conectarse

- Host: `192.168.1.200`, alcanzable por VPN.
- Usuario: `hermes`. El acceso es por llave SSH, no por contraseña. Si esta máquina no tiene ya
  una llave autorizada en el host, instálala una vez (pide la contraseña de `hermes` esa única
  vez; después queda passwordless):
  ```bash
  ssh-copy-id -i ~/.ssh/<tu_llave>.pub hermes@192.168.1.200
  ```
  En esta máquina la llave ya autorizada es `~/.ssh/id_ed25519_conecta`.
- Verificar acceso: `ssh -i ~/.ssh/id_ed25519_conecta hermes@192.168.1.200 'docker ps --filter name=banxico-simulator'`.
- Carpeta del repo en el host: `/home/hermes/banxico-simulator`.

## Advertencia de permisos (ya pasó una vez, puede repetirse)

`config/` y `data/` son bind mounts que el contenedor escribe como su usuario interno `simulador`
(creado en el `Dockerfile` vía `useradd`, uid 1001 en este host) — **no como `hermes`**. Si
`git pull` falla con `Permission denied` al intentar reemplazar un archivo dentro de `config/`
(por ejemplo `simulator.properties.example`), es porque `hermes` no es dueño del *directorio* y no
puede hacer unlink/rename ahí, aunque sí pueda leer los archivos que ya hay adentro.

Arreglo (mínimo y seguro):
```bash
sudo chown hermes:hermes config   # SOLO el directorio, sin -R
```
Esto le da a `hermes` permiso de escritura sobre el directorio sin tocar el dueño de los archivos
que ya hay adentro — el contenedor los sigue leyendo/escribiendo igual, porque conservan permiso
de lectura para "otros".

**Nunca hagas esto sobre `data/`, ni de forma recursiva sobre `config/`.** El contenedor escribe
activamente `data/banxicosim.mv.db` como uid 1001 mientras corre; cambiarle el dueño rompe la base
de datos del simulador en vivo.

## Flujo de despliegue

1. **Revisar que el árbol esté limpio** antes de tocar nada:
   ```bash
   ssh hermes@192.168.1.200 'cd /home/hermes/banxico-simulator && git status --porcelain=v1 --untracked-files=all'
   ```
   Si sale algo, **no asumas que es basura ni lo borres a ciegas**. Compara cada archivo contra
   `origin/main` directo (no contra `HEAD`, que puede estar desactualizado):
   ```bash
   git show origin/main:<ruta> | diff - <ruta>
   ```
   Si el contenido es idéntico (o el del host es un subconjunto más viejo de lo mismo), es seguro
   seguir. Si hay algo genuinamente distinto, detente y pregunta al dueño de spec — podría ser
   trabajo real que nadie comiteó.

   Encontrado el 2026-09-28: el host tenía ~45 archivos "sin commitear" que en realidad eran
   copias byte-idénticas de lo que ya traía `origin/main` (de un despliegue anterior hecho
   copiando archivos a mano en vez de `git pull`) — no era trabajo perdido, solo drift.

2. **Guardar cualquier cosa antes de resetear/hacer pull, como red de seguridad** — nunca
   `git reset --hard` / `git checkout -- .` a ciegas sobre esto:
   ```bash
   git stash push -u -m "backup-antes-de-sync-<fecha>"
   ```

3. **Actualizar y desplegar:**
   ```bash
   git pull origin main
   docker compose up --build -d
   ```
   Esto reconstruye **ambos** servicios (`banxico-simulator` y `banxico-simulator-mcp`) y reinicia
   el contenedor del simulador — **corta la sesión SPEI activa con minos**. Avisa/coordina antes
   si alguien depende de esa sesión.

4. **Verificar:**
   ```bash
   docker ps --format "{{.Names}}: {{.Status}}"   # ambos "healthy"
   curl -s http://192.168.1.200:8089/health        # {"status":"ok"}
   curl -s http://192.168.1.200:8090/health        # MCP: {"status":"ok","simulatorUrl":...}
   curl -s http://192.168.1.200:8089/session       # null hasta que minos reconecte
   ```

5. **Minos no reconecta solo.** Si `/session` sigue en `null` varios minutos después del deploy y
   `docker logs banxico-simulator --since 5m` no muestra ningún `[SPEI] Conexión entrante`, no es
   un bug del simulador — minos no tiene lógica de reintento automático tras un corte. Hay que
   pedir que alguien reinicie/reconecte minos manualmente; no hay nada más que probar del lado del
   simulador hasta que eso pase.

## Qué nunca se debe borrar

- `data/` — identidad propia del simulador (llaves RSA + certificado autofirmado) y la bitácora H2
  de corridas de prueba. Borrarla genera una identidad nueva y minos deja de reconocerla.
- `config/simulator.properties` y `config/minos-public-cert.pem` — no están en git (gitignored a
  propósito, ver `AGENTS.md` §7), son específicos de este despliegue.

## Ver también

- `specs/README.md` §"Siguiente paso" — estado vivo de qué falta implementar/verificar en el host.
- `AGENTS.md` §2 — cómo invitar a alguien a probar esto sin que sepa de infraestructura.
