# Base de datos del experimento (Supabase)

Cada sesión del experimento se guarda en una base de datos Postgres en Supabase,
además de los TSV locales de `experiment/results/` (que se mantienen como respaldo).

## Configuración inicial (una sola vez)

1. Crear un proyecto en <https://supabase.com> (región sugerida: la más cercana, p. ej. `us-east-1`).
2. En el proyecto: **SQL Editor → New query**, pegar el contenido de [`schema.sql`](./schema.sql) y **Run**.
   El script se puede volver a ejecutar sin perder datos.
3. En **Project Settings → API** (o el botón **Connect**), copiar:
   - **Project URL**
   - **Publishable key** (`sb_publishable_...`) o, si el proyecto solo muestra llaves legacy, la llave **anon**.
   ⚠️ Nunca usar la llave *secret* / *service_role* en las máquinas del laboratorio.

## Configuración de cada máquina del laboratorio

```bash
cd src/experiment
cp database.properties.example database.properties
# editar database.properties con la URL y la llave del paso 3
```

`database.properties` está en `.gitignore`. También se pueden usar las variables de
entorno `SUPABASE_URL` y `SUPABASE_KEY`.

Al iniciar `make start-experiment` la consola muestra `[DB] Supabase configurado: ...`.

## Probar la conexión

```bash
cd src
make test-db   # envía un participante TEST_... y confirma que llegó
make sync-db   # reenvía lo que haya quedado en experiment/db_pending/
```

Para borrar los datos de prueba (SQL Editor, con la cuenta del proyecto):

```sql
delete from public.level_plays where participant_id like 'TEST_%' or participant_id in
  (select id from public.participants where develop_mode or sequence_group = 'TEST');
delete from public.closure   where participant_id in (select id from public.participants where develop_mode or sequence_group = 'TEST');
delete from public.free_play where participant_id in (select id from public.participants where develop_mode or sequence_group = 'TEST');
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
(`prompt_source`: `ollama` / `heuristic` / `default`), la salida del LLM, el prompt final
de 4 atributos que recibió MarioGPT, el backend (servidor remoto o local) y el tiempo.

Si el participante no acepta el consentimiento, **no se envía nada**.

## Garantía de no pérdida de datos

Cada registro se escribe primero en `experiment/db_pending/` y solo se borra cuando
Supabase confirma que lo guardó. Si no hay red o el proyecto está pausado (los proyectos
gratuitos se pausan tras 7 días sin uso), los registros esperan ahí y se reenvían en orden
al iniciar la siguiente sesión. Reenviar no crea duplicados.

- `db_pending/*.json`: pendientes de envío (normal si hubo un corte de red).
- `db_pending/failed/*.json`: rechazados por el esquema (revisar; no se borran).

**No borrar `db_pending/`** antes de confirmar que quedó vacía.

## Seguridad

La llave de las máquinas solo puede **insertar**: no puede leer, modificar ni borrar
(Row Level Security). Los datos se consultan desde el panel de Supabase.

## Análisis

En el SQL Editor (los resultados se exportan a CSV con **Download**):

```sql
select * from analysis.main_levels;          -- 1 fila por nivel del protocolo, respuestas + telemetría
select * from analysis.rankings;             -- ranking aplanado
select * from analysis.personalized_quality; -- niveles P: ¿generados o respaldo?
```
