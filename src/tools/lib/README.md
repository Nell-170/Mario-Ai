# Dependencias Java incluidas

| Archivo | Proyecto | Versión | Licencia |
|---|---|---|---|
| `postgresql-42.7.4.jar` | [PostgreSQL JDBC Driver (pgjdbc)](https://jdbc.postgresql.org/) | 42.7.4 | BSD-2-Clause |

Fuente: release oficial `REL42.7.4` de <https://github.com/pgjdbc/pgjdbc/releases>
(mismo artefacto que `org.postgresql:postgresql:42.7.4` en Maven Central).

- SHA-1:   `264310fd7b2cd76738787dc0b9f7ea2e3b11adc1`
- SHA-256: `188976721ead8e8627eb6d8389d500dccc0c9bebd885268a3047180274a6031e`

Los scripts de compilación (`compile-framework.sh`, `compile-framework.ps1`, `install.py`)
extraen el jar dentro de `mario_ai_framework/bin/`, así todos los comandos `java -cp bin ...`
encuentran el driver sin cambiar el classpath. Requiere Java 8+ (el proyecto usa 17+).
