# Entorno base — Generación personalizada de niveles de Mario con IA

Proyecto de investigación (TFG, UCR). Pipeline objetivo:

```
Jugador → Telemetría → LLM (razonador) → Prompt → MarioGPT (generador) → A* (validador) → Jugador
```

Este repositorio contiene el **entorno base**: el motor de simulación (Mario AI Framework),
el pipeline de datos que descarga y filtra niveles humanos reales de **Mario Maker 2**, un
convertidor al formato del framework, y la validación de jugabilidad con el agente **A\***
(robinBaumgarten). Los niveles humanos validados son el **"Nivel 0"** — la base sobre la que
operará el pipeline generativo.

> ⚠️ **Estas instrucciones están escritas para Windows (PowerShell).** Requisitos: JDK 17+,
> Python 3.8+, Git y GNU Make (por ejemplo, `make` incluido en Git Bash).

---

## Estructura del proyecto

```
Mario-Ai/
├── mario_ai_framework/          # Repo clonado y compilado (Java)
│   ├── src/                     # Fuentes del framework + herramientas locales
│   └── bin/                     # .class compilados
├── dataset/
│   ├── filter_mm2.py            # Filtrado por streaming del dataset de HF
│   ├── convert_mm2_to_maf.py    # Conversor MM2 → grilla de texto MAF
│   ├── level.py / level.ksy     # Parser Kaitai del formato binario de MM2
│   ├── mm2_selected.csv         # Metadata de los 25 niveles seleccionados
│   └── mm2_selected_levels.pkl  # Blobs binarios de esos 25 niveles
├── levels/
│   ├── converted/               # Salida regenerable (ignorada por Git)
│   ├── nivel0/                  # Niveles humanos VALIDADOS como jugables (8)
│   └── validation_results.csv   # Resultado de A* por nivel
├── tools/
│   ├── ValidateLevels.java      # Validador headless con agente A*
│   └── PlayHuman.java           # Lanzador local para jugar con teclado
├── Makefile                     # Automatiza setup, compilación y ejecución
└── README.md
```

---

## Compilación rápida con Make

Desde la raíz del proyecto:

```bash
make compile
```

El primer uso clona automáticamente `mario_ai_framework/` si no existe y
compila el framework junto con `ValidateLevels` y `PlayHuman`. Otros comandos:

```bash
make convert     # Genera levels/converted/
make validate    # Ejecuta la validación con A*
make play-human  # Abre el nivel por defecto para jugar con teclado
make help        # Muestra todos los targets disponibles
```

---

## Instalación y setup (Windows PowerShell)

### Paso 1 — Clonar el Mario AI Framework

Desde la **raíz del proyecto** (donde están `dataset/`, `levels/`, `tools/`):

```powershell
git clone https://github.com/amidos2006/Mario-AI-Framework mario_ai_framework
```

---

### Paso 2 — Compilar el framework

```powershell
cd mario_ai_framework

# Crear carpeta bin si no existe
New-Item -ItemType Directory -Force -Path bin

# Compilar todos los .java recursivamente (equivalente a find en Linux)
$javaFiles = Get-ChildItem -Path src -Filter *.java -Recurse | Select-Object -ExpandProperty FullName
javac -d bin -encoding UTF-8 $javaFiles

cd ..
```

### Paso 3 — Agregar y compilar herramientas personalizadas

`ValidateLevels.java` y `PlayHuman.java` no vienen en el repo original del
framework. Están en la carpeta `tools/` de este proyecto. Cópialos y compílalos:

```powershell
# Copiar al src/ del framework
Copy-Item tools\ValidateLevels.java -Destination mario_ai_framework\src\
Copy-Item tools\PlayHuman.java -Destination mario_ai_framework\src\

# Compilarlas
cd mario_ai_framework
javac -cp bin -d bin src\ValidateLevels.java src\PlayHuman.java
cd ..
```

---

### Paso 4 — Instalar dependencias Python

```powershell
pip install datasets huggingface_hub kaitaistruct
```

Verifica que todo esté instalado:

```powershell
python -c "import datasets, kaitaistruct; print('OK')"
```

---

### Paso 5 — Filtrar niveles de Mario Maker 2 (streaming)
Desde la **raíz del proyecto**:

```powershell
python dataset/filter_mm2.py --want 25 --max-scan 40000
```

Esto escanea ~40 000 registros del dataset (streaming, sin descargar los ~100 GB)
y selecciona 25 niveles con:
- `gamestyle == 0` (estilo SMB1)
- `clear_rate` entre 15% y 70%
- `attempts ≥ 200` (popularidad mínima)

---

### Paso 6 — Convertir MM2 → formato MAF

> ℹ️ **Este paso ya está hecho** — Ejecutar sólo para refiltrar niveles en el paso anterior.

```powershell
python dataset/convert_mm2_to_maf.py
```

Genera una grilla de texto de 16 filas × N columnas en `levels/converted/`.
Esta carpeta es una salida regenerable y está excluida de Git.

---

### Paso 7 — Validar jugabilidad con A*

> ℹ️ **Este paso ya está hecho** — Ejecutar solo si se quieren regenerar los 8 niveles validados.

```powershell
cd mario_ai_framework
java "-Djava.awt.headless=true" -cp bin ValidateLevels ..\levels\converted ..\levels\nivel0 60
cd ..
```

### Paso 8 — Jugar un nivel como humano

Desde la carpeta `mario_ai_framework`:

```powershell
java -cp bin PlayHuman
```

Por defecto se abre `levels/nivel0/mm2_3005554.txt` con gráficos y un límite de
200 segundos. También se puede indicar otro nivel y el tiempo límite:

```powershell
java -cp bin PlayHuman ..\levels\nivel0\mm2_3001459.txt 200
```

## Niveles validados (Nivel 0)

El A* completó **8 de 25 niveles** convertidos. Estos son los "Nivel 0" del
pipeline — niveles humanos reales y jugables que servirán de base para la
generación personalizada:

| data_id | nombre | clear_rate | tema |
|---|---|---|---|
| 3001459 | ジャンプ | 15.1% | Underground |
| 3004249 | 試作型・一画面スイッチ迷路 | 29.0% | Underground |
| 3005554 | Super Mario Land 1-1 | 55.2% | Desert |
| 3006493 | Undertale MEGALOVANIA | 65.3% | Underground |
| 3008364 | The Slumbering Deep | 56.9% | Forest |
| 3008749 | SMB 1-1 Competitive Race | 30.5% | Overworld |
| 3024914 | 全自動1-1 / Automatic 1-1 | 47.5% | Overworld |
| 3028504 | Ruins of 1-1 | 17.6% | Desert |

El detalle por nivel (WIN / LOSE / TIME_OUT y % de avance) está en
`levels/validation_results.csv`.

**Sobre los 17 no jugables:** son consecuencia de la conversión con pérdida, no
del filtrado. Objetos de MM2 sin equivalente en SMB1/MAF (interruptores,
plataformas móviles, mecánicas especiales) se omiten, lo que puede dejar huecos
infranqueables para el A*.

---

## Referencia técnica

### Esquema del dataset Mario Maker 2

Repo Hugging Face: **`TheGreatRambler/mm2_level`** (~26.6 M niveles, 196 shards
Parquet, ~100 GB). Se accede vía streaming (`streaming=True`), sin descarga completa.

| Campo | Tipo | Descripción |
|---|---|---|
| `data_id` | int | ID único del nivel |
| `gamestyle` | int | **0=SMB1**, 1=SMB3, 2=SMW, 3=NSMBU, 4=SM3DW |
| `theme` | int | 0=Overworld, 1=Underground, 2=Castle, 3=Airship… |
| `clear_rate` | float | Tasa de completado en porcentaje (0–100) |
| `attempts`, `clears` | int | Métricas de popularidad |
| `level_data` | bytes | Nivel binario comprimido con zlib |

El blob `level_data` se descomprime con `zlib` y se parsea con el Kaitai struct
(`level.ksy` → `level.py`). Coordenadas de terreno (`ground[]`) en tiles directos;
coordenadas de objetos (`objects[]`) en unidades de 160 por tile. Origen vertical
invertido respecto a MAF: `fila_maf = (H-1) - y_mm2`.

### Mapeo de tiles MM2 → MAF

| Carácter MAF | Significado | Proviene de (MM2) |
|---|---|---|
| `-` | aire | vacío |
| `X` | sólido / suelo | `ground[]`, hard_block, ice_block, stone |
| `S` | ladrillo rompible | block, hidden_block |
| `#` | plataforma | semisolid_platform, mushroom_platform, bridge |
| `?` | bloque de pregunta | question_block |
| `o` | moneda | coin, big_coin, red_coin |
| `g` | Goomba | goomba, dry_bones |
| `k` | Koopa verde | koopa, buzzy_beetle |
| `y` | Spiny | spiny, spike_top |
| `< > [ ]` | tubería | pipe (2 ancho × alto) |
| `B` / `b` | cañón Bullet Bill | bullet_bill_blaster |
