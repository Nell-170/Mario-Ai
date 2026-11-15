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
│   └── 06-metodologia.html
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
- Para agregar una filmina, crea p. ej. `filminas/07-resultados.html` con los mismos tres bloques. El orden lo da el número del nombre.
- Lo que va en `<script>` de una filmina puede usar lo de `motor.js`: `H['id-de-la-section'] = { enter, leave, step }`, `setStep`, `step`, `burst`, `countTo`, `px`, `heroURL`, etc.
- Si una filmina usa una clase de otra (por ejemplo `.ground` o `.cloud`), esa clase va en `comun/base.css`.
