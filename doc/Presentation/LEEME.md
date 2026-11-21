# Presentación por filminas

## Estructura

```
presentacion/
├── presentacion.html        ← la presentación completa, lista para abrir
├── armar.py                 ← vuelve a generar presentacion.html
├── filminas/
│   ├── 01-titulo.html
│   ├── 02-introduccion.html
│   ├── 03-pregunta.html
│   ├── 04-antecedentes-prisma.html
│   ├── 05-antecedentes-tabla.html
│   ├── 06-niveles.html
│   ├── 07-metodologia.html
│   ├── 08-resultados.html
│   ├── 09-conclusiones.html
│   ├── 10-agradecimiento.html
│   └── 11-anexos.html
├── img/                   ← imágenes usadas por las filminas (rutas en config.js)
└── comun/
    ├── config.js            ← rutas de videos, sprites y capturas (edítalo aquí)
    ├── base.css             ← colores, escenario, navegación, animaciones compartidas
    ├── motor.js             ← pixel art, cambio de filminas, sub-pasos, efectos
    ├── fuentes.css          ← fuentes incrustadas (no hace falta tocarlo)
    └── plantilla.html       ← esqueleto donde se insertan las filminas
```

Cada archivo de `filminas/` tiene tres bloques: `<style>` (su CSS), `<section>` (su HTML) y `<script>` (su comportamiento).

## Flujo de trabajo

1. Editas la filmina que necesites en `filminas/`.
2. Ejecutas `python armar.py` (o `python3 armar.py`).
3. Abres `presentacion.html` en el navegador.

## Notas

- `data-min` y `data-max` de cada `<section>` definen sus sub-pasos; la barra de progreso se ajusta sola.
- `data-despues-de="id-de-section:paso"` intercala una filmina en medio de otra. Por ejemplo, `08-resultados.html` lleva `data-despues-de="s-flow:10"`: aparece justo después del paso del cuestionario de la metodología y, al terminarla, la presentación vuelve al diagrama en el paso 11.
- Teclado: ◀ ▶ avanzan y retroceden; F pone pantalla completa; los números saltan al paso del contador de abajo a la derecha (`3` → paso 3; `2` `7` → paso 27; salta tras una pausa corta o con Enter).
- `data-sec="Nombre"` agrupa filminas en la barra de secciones de abajo; las que comparten nombre forman una sola sección.
- Para agregar una filmina, crea p. ej. `filminas/12-nueva.html` con los mismos tres bloques. El orden lo da el número del nombre.
- Lo que va en `<script>` de una filmina puede usar lo de `motor.js`: `H['id-de-la-section'] = { enter, leave, step }`, `setStep`, `step`, `burst`, `countTo`, `px`, `heroURL`, etc.
- Si una filmina usa una clase de otra (por ejemplo `.ground` o `.cloud`), esa clase va en `comun/base.css`.

- Contador de diapositivas: cuenta una por filmina. `data-cuenta="paso"` en la `<section>` hace que cada sub-paso cuente como número propio (se usa en la introducción).
- `data-sin-transicion="id-de-section"` omite la cortina pixelada entre esa filmina y la indicada (la tabla de antecedentes entra sin cortina desde el PRISMA).
