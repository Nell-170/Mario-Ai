-- =====================================================================
-- Esquema de la base de datos del experimento (PostgreSQL 13+)
-- Ejecutar UNA vez en la máquina virtual, conectado a la base del experimento
-- con un usuario administrador (el dueño de la base o postgres):
--
--     psql -h localhost -U postgres -d <nombre_de_la_base> -f schema.sql
--
-- Es idempotente: se puede volver a ejecutar sin perder datos.
-- Crea el rol "mario_lab" (el que usan las PCs del laboratorio) SIN contraseña;
-- después hay que asignársela (ver README.md, paso 3).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Participantes (una fila por sesión con consentimiento aceptado)
-- ---------------------------------------------------------------------
create table if not exists public.participants (
    id                     text primary key,          -- p.ej. 20261105_143000_S3
    created_at             timestamptz not null default now(),
    session_started_at     timestamptz,
    sequence_group         text not null,             -- S1..S6 (cuadrado latino)
    sequence               text,                      -- p.ej. "A1 C2 A2 C1 B1 B2"
    free_condition         text,                      -- P | N | H
    familiarization_levels text[],
    generic_levels         text[],
    human_levels           text[],
    machine                text,                      -- hostname de la máquina del laboratorio
    code_version           text,                      -- commit de git
    generation_mode        text,                      -- JeissonServer | Tunel | Nativo
    skip_generation        boolean not null default false,
    develop_mode           boolean not null default false,
    timer_seconds          int,
    consent                boolean not null default true,
    initial_survey         jsonb                      -- {edad: 21, horas_juego: "...", ...}
);

-- ---------------------------------------------------------------------
-- 2. Niveles jugados (familiarización, los 6 del protocolo y juego libre)
-- ---------------------------------------------------------------------
create table if not exists public.level_plays (
    id             bigint generated always as identity primary key,
    participant_id text not null references public.participants(id),
    phase          text not null check (phase in ('familiarization', 'main', 'free_play')),
    play_order     int  not null,                     -- orden dentro de la fase (1..n)
    level_type     char(1),                           -- P=personalizado, N=genérico, H=humano, F=familiarización
    level_index    int,                               -- 1 o 2 dentro de su condición
    step_code      text,                              -- A1, C2, ... (solo fase main)
    level_file     text,
    level_content  text,                              -- grilla exacta que se jugó (ya sanitizada)
    telemetry      jsonb,                             -- JSON completo de PlayHuman
    responses      jsonb,                             -- respuestas por nivel {disfrute: 5, ...}
    generation     jsonb,                             -- solo niveles P: ver abajo
    played_at      timestamptz not null default now(),
    unique (participant_id, phase, play_order)
);
-- generation (niveles P):
--   source           "generated" | "fallback" (nivel genérico de respaldo) | "substitute_skip_generation"
--   prompt_source    "ollama" (LLM) | "heuristic" (reglas locales) | "default" (prompt fijo)
--   llm_model        modelo del LLM, llm_prompt = línea que produjo el LLM / la heurística
--   generation_mode  JeissonServer | Tunel | Nativo (config.properties)
--   telemetry_file   telemetría que alimentó la generación
--   seconds          tiempo total de generación, error = motivo si hubo respaldo
--   mariogpt         {backend: "remote"|"local_cuda"|"local_cpu", server_url,
--                     input_prompt, mariogpt_prompt (prompt enviado a MarioGPT), seconds}
-- En juego libre, step_code = 'replay' marca que el participante repitió el nivel anterior.

-- ---------------------------------------------------------------------
-- 3. Cierre (ranking de los 6 niveles y comentarios)
-- ---------------------------------------------------------------------
create table if not exists public.closure (
    participant_id text primary key references public.participants(id),
    ranking        jsonb not null,                    -- [{rank:1, order_played:3, level_type:"P", ...}, ...]
    feedback       text,
    created_at     timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- 4. Resumen del juego libre (medida conductual de satisfacción)
-- ---------------------------------------------------------------------
create table if not exists public.free_play (
    participant_id  text primary key references public.participants(id),
    free_condition  text,
    stayed          boolean not null,
    seconds_played  int,
    levels_played   int,
    distinct_levels int,
    created_at      timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- 5. Seguridad: el rol de las PCs del laboratorio SOLO puede insertar.
--    No puede leer, modificar ni borrar. Los datos se consultan con un
--    usuario administrador (psql, pgAdmin, DBeaver...).
-- ---------------------------------------------------------------------
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'mario_lab') then
        create role mario_lab nologin;   -- la contraseña y LOGIN se asignan aparte
    end if;
end
$$;

revoke all on public.participants, public.level_plays, public.closure, public.free_play from mario_lab;
grant usage on schema public to mario_lab;
grant insert on public.participants, public.level_plays, public.closure, public.free_play to mario_lab;
-- Reenvíos sin duplicados: las claves únicas rechazan un registro repetido (error 23505)
-- y el cliente lo interpreta como "ya guardado". Por eso mario_lab no necesita leer.

-- ---------------------------------------------------------------------
-- 6. Vistas de análisis (solo para administradores).
--    Exportar a CSV:  \copy (select * from analysis.main_levels) to 'main_levels.csv' csv header
-- ---------------------------------------------------------------------
create schema if not exists analysis;
revoke all on schema analysis from public;

-- Una fila por nivel del protocolo con respuestas y telemetría aplanadas.
create or replace view analysis.main_levels as
select
    p.id                                   as participant_id,
    p.sequence_group,
    lp.play_order                          as level_number,
    lp.level_type,
    lp.level_index,
    lp.step_code,
    lp.level_file,
    lp.generation ->> 'source'             as generation_source,
    lp.generation ->> 'prompt_source'      as prompt_source,
    lp.generation ->> 'generation_mode'    as generation_mode,
    lp.generation -> 'mariogpt' ->> 'backend'          as generation_backend,
    lp.generation -> 'mariogpt' ->> 'mariogpt_prompt'  as mariogpt_prompt,
    (lp.responses ->> 'disfrute')::numeric     as disfrute,
    (lp.responses ->> 'competencia')::numeric  as competencia,
    lp.responses ->> 'dificultad'              as dificultad,
    (lp.responses ->> 'ajuste_reto')::numeric  as ajuste_reto,
    (lp.responses ->> 'variedad')::numeric     as variedad,
    (lp.responses ->> 'rejugar')::numeric      as rejugar,
    lp.telemetry ->> 'status'                          as status,
    (lp.telemetry ->> 'completion')::numeric           as completion,
    (lp.telemetry ->> 'time_used_seconds')::numeric    as time_used_seconds,
    (lp.telemetry ->> 'jumps')::int                    as jumps,
    (lp.telemetry ->> 'kills')::int                    as kills,
    (lp.telemetry ->> 'hurts')::int                    as hurts,
    (lp.telemetry ->> 'coins')::int                    as coins,
    (lp.telemetry ->> 'average_speed_pixels_per_second')::numeric as avg_speed,
    lp.telemetry ->> 'path_preference'                 as path_preference,
    lp.played_at
from public.level_plays lp
join public.participants p on p.id = lp.participant_id
where lp.phase = 'main';

-- Ranking aplanado: una fila por nivel rankeado.
create or replace view analysis.rankings as
select
    c.participant_id,
    (r ->> 'rank')::int          as rank,
    (r ->> 'order_played')::int  as order_played,
    r ->> 'level_type'           as level_type,
    (r ->> 'level_index')::int   as level_index,
    r ->> 'step_code'            as step_code,
    r ->> 'level_file'           as level_file
from public.closure c
cross join lateral jsonb_array_elements(c.ranking) as r;

-- Control de calidad: niveles P que NO fueron generados realmente.
create or replace view analysis.personalized_quality as
select participant_id, phase, play_order, step_code,
       generation ->> 'source'        as source,
       generation ->> 'prompt_source' as prompt_source,
       generation -> 'mariogpt' ->> 'backend' as backend,
       generation ->> 'error'         as error
from public.level_plays
where level_type = 'P';
