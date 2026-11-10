# Base de datos del experimento (PostgreSQL)

Cada sesión del experimento se guarda en PostgreSQL, en la máquina virtual del laboratorio
(`10.1.70.39`), además de los TSV locales de `experiment/results/`, que se mantienen como respaldo.
Las PCs del experimento se conectan directo por JDBC (puerto 5432) y deben estar en la misma red.

## 1. Preparar la máquina virtual (una sola vez)

**a) Aceptar conexiones de la red.** En `postgresql.conf` (ubicarlo con `sudo -u postgres psql -c "show config_file"`):

```
listen_addresses = '*'          # o: 'localhost,10.1.70.39'
```

**b) Permitir solo al rol del laboratorio, desde la red del laboratorio.** En `pg_hba.conf`
(`show hba_file`), agregar una línea; cambiar la subred por la real del laboratorio:

```
# TYPE  DATABASE             USER       ADDRESS          METHOD
host    NOMBRE_DE_LA_BASE    mario_lab  10.1.70.0/24     scram-sha-256
```

**c) Reiniciar y abrir el puerto:**

```bash
sudo systemctl restart postgresql
sudo ufw allow from 10.1.70.0/24 to any port 5432 proto tcp   # si la VM usa ufw
```

## 2. Crear las tablas

Desde la VM, con el usuario administrador:

```bash
psql -h localhost -U postgres -d NOMBRE_DE_LA_BASE -f schema.sql
```

El script es idempotente (se puede volver a correr sin perder datos). Crea las 4 tablas,
las vistas de análisis y el rol `mario_lab`.

## 3. Darle contraseña al rol del laboratorio

```bash
psql -h localhost -U postgres -d NOMBRE_DE_LA_BASE \
     -c "alter role mario_lab login password 'UNA_CONTRASEÑA_LARGA';"
```

`mario_lab` solo puede **insertar**: no puede leer, modificar ni borrar datos. Si alguien
copia la contraseña de una PC del laboratorio, no puede ver ni dañar lo que ya está guardado.

## 4. Configurar cada PC del laboratorio

```bash
cd src/experiment
cp database.properties.example database.properties
# editar: db_name y db_password (db_host ya es 10.1.70.39, db_user ya es mario_lab)
cd .. && make compile     # incluye el driver JDBC de PostgreSQL en bin/
```

`database.properties` está en `.gitignore`.

## 4b. Túnel SSH y configuración centralizada (alternativa al puerto directo)

Si la red no deja pasar el puerto de Postgres (p. ej. desde la VPN), el experimento puede
conectarse por un túnel SSH. `make gui` lo hace todo solo:

1. Abre el túnel `ssh -N -L 15432:localhost:5432 investigacioncc@10.1.70.39`.
2. Descarga `database.properties` desde la VM (`~/mario-ai/database.properties`) y lo
   guarda en `src/experiment/` con `db_host=localhost` y `db_port=15432`.
3. Arranca el dashboard. Al cerrarlo (Ctrl+C), cierra el túnel.

Si la VM no responde, el dashboard arranca igual y los datos quedan en `db_pending/`.

**Una sola vez**, desde una PC que ya tenga un `database.properties` correcto:

```bash
cd src
make db-push-config     # sube experiment/database.properties a la VM (permisos 600)
```

Otros comandos:

```bash
make db-tunnel          # solo abre el túnel (Ctrl+C para cerrarlo)
make db-pull-config     # solo descarga la configuración de la VM
```

Los valores (usuario SSH, IP, puertos, ruta en la VM) están en `experiment/config.properties`
(claves `db_tunnel_*`). Para no escribir la contraseña de SSH cada vez, instalar una llave
en cada PC: `ssh-keygen -t ed25519` y luego `ssh-copy-id investigacioncc@10.1.70.39`.

## 5. Probar la conexión

```bash
cd src
make test-db   # envía un participante TEST_... y confirma que llegó
make sync-db   # reenvía lo que haya quedado en experiment/db_pending/
```

Si falla, el mensaje `[DB]` dice el motivo:

| Mensaje | Causa probable |
|---|---|
| `connect timed out` / `Connection refused` | VM apagada, `listen_addresses`, firewall o la PC no está en la red del laboratorio |
| `SQLState 28000: no pg_hba.conf entry` | falta la línea de `pg_hba.conf` para esa subred, base o usuario |
| `SQLState 28P01: password authentication failed` | contraseña incorrecta en `database.properties` |
| `SQLState 3D000: database ... does not exist` | `db_name` incorrecto |
| `SQLState 42P01` / `42501` | no se corrió `schema.sql` en esa base |
| `No se encontró el driver JDBC` | falta `make compile` |

Prueba rápida de red desde una PC del laboratorio: `nc -vz 10.1.70.39 5432`
(en Windows: `Test-NetConnection 10.1.70.39 -Port 5432`).

Para borrar los datos de prueba (en la VM, como administrador):

```sql
delete from public.level_plays where participant_id in (select id from public.participants where develop_mode or sequence_group = 'TEST');
delete from public.closure     where participant_id in (select id from public.participants where develop_mode or sequence_group = 'TEST');
delete from public.free_play   where participant_id in (select id from public.participants where develop_mode or sequence_group = 'TEST');
delete from public.participants where develop_mode or sequence_group = 'TEST';
```

## Qué se guarda

| Tabla | Una fila por | Contenido |
|---|---|---|
| `participants` | sesión con consentimiento aceptado | grupo S1–S6, condición de juego libre, niveles sorteados, máquina, commit de git, modo de generación, encuesta inicial |
| `level_plays` | nivel jugado (familiarización, protocolo y juego libre) | grilla exacta jugada, telemetría completa, respuestas por nivel y, para niveles P, la información de generación |
| `closure` | participante | ranking de los 6 niveles y comentarios |
| `free_play` | participante | si se quedó, tiempo, niveles jugados y distintos |

Para los niveles personalizados (P), `level_plays.generation` registra si el nivel se
**generó de verdad o fue un respaldo** (`source`), de dónde salió el prompt
(`prompt_source`: `ollama` / `heuristic` / `default`), la salida del LLM, el prompt exacto
que recibió MarioGPT, el backend (servidor remoto o local) y el tiempo.

Si el participante no acepta el consentimiento, **no se envía nada**.

## Garantía de no pérdida de datos

Cada registro se escribe primero en `experiment/db_pending/` y solo se borra cuando
PostgreSQL confirma que lo guardó. Si la VM no responde, los registros esperan ahí y se
reenvían en orden al iniciar la siguiente sesión (o con `make sync-db`). Reenviar no crea duplicados.

- `db_pending/*.json`: pendientes de envío (normal si hubo un corte de red).
- `db_pending/failed/*.json`: rechazados por el esquema (revisar; no se borran).

**No borrar `db_pending/`** antes de confirmar que quedó vacía.

## Respaldos

La VM es ahora la única copia central. Conviene un respaldo periódico, por ejemplo diario con cron:

```bash
pg_dump -h localhost -U postgres -Fc NOMBRE_DE_LA_BASE > ~/backups/mario_$(date +%F).dump
```

## Análisis

Conectado como administrador (psql, pgAdmin o DBeaver):

```sql
select * from analysis.main_levels;          -- 1 fila por nivel del protocolo, respuestas + telemetría
select * from analysis.rankings;             -- ranking aplanado
select * from analysis.personalized_quality; -- niveles P: ¿generados o respaldo?
```

Exportar a CSV desde psql:

```
\copy (select * from analysis.main_levels) to 'main_levels.csv' csv header
```
