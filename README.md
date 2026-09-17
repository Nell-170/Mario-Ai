# Entorno base — Generación personalizada de niveles de Mario con IA

Proyecto de investigación (TFG, Escuela de Ciencias de la Computación e Informática, Universidad de Costa Rica).

## Pipeline de Generación Adaptativa

```
Jugador → Telemetría (PlayHuman) → LLM (razonador / prompt) → MarioGPT (generador) → A* (validador) → Jugador
```

Este repositorio contiene el **entorno base y pipeline de datos**:
1. **Motor de simulación y juego:** [Mario AI Framework](https://github.com/amidos2006/Mario-AI-Framework) (Java).
2. **Dataset de Mario Maker 2:** Filtrado por streaming desde Hugging Face (`TheGreatRambler/mm2_level`) y parseo binario mediante Kaitai Struct.
3. **Conversor MM2 → MAF:** Adaptación a grillas de texto ASCII compatibles con el framework y MarioGPT.
4. **Validación automática de jugabilidad:** Agente de búsqueda heurística **A\*** (*robinBaumgarten*) en modo headless paralelo.
5. **Telemetría y Generación con IA:** Captura de métricas de juego del usuario (tiempo, saltos, bajas, daños, velocidad, ruta), traducción a prompts de diseño (local o mediante Ollama), y generación adaptativa con [MarioGPT](https://github.com/shyamsn97/mario-gpt).
6. **Nivel 0:** Banco inicial de niveles humanos reales validados como jugables.

---

## Estructura del proyecto

El repositorio está organizado con las dependencias externas en la raíz y todo el código fuente, herramientas y datos dentro de `src/`:

```
Mario-Ai/
├── mario_ai_framework/          # Framework de simulación Mario AI (Java) [Clonado en setup]
│   ├── src/                     # Fuentes del motor + herramientas locales compiladas
│   └── bin/                     # Clases .class compiladas
├── mario_gpt/                   # Repositorio de MarioGPT (PyTorch) [Clonado en setup]
├── src/                         # Código fuente, herramientas y datasets del proyecto
│   ├── dataset/
│   │   ├── filter_mm2.py        # Filtrado por streaming del dataset de MM2 en Hugging Face
│   │   ├── convert_mm2_to_maf.py# Conversor MM2 binario → grilla de texto ASCII MAF
│   │   ├── level.py / level.ksy # Parser Kaitai del formato binario de MM2
│   │   ├── mm2_selected.csv     # Metadata de los niveles seleccionados
│   │   └── mm2_selected_levels.pkl # Blobs binarios de los niveles seleccionados
│   ├── levels/
│   │   ├── converted/           # Niveles convertidos de MM2 (salida regenerable, .gitignore)
│   │   ├── nivel0/              # Niveles humanos VALIDADOS por A* como jugables (Nivel 0)
│   │   ├── generated/           # Niveles generados por MarioGPT (.gitignore)
│   │   └── validation_results.csv # Resultados detallados de validación A*
│   ├── telemetry/
│   │   └── latest.json          # Telemetría capturada de la última sesión de juego
│   ├── tools/
│   │   ├── compile-framework.sh # Script de compilación para Linux / macOS (Bash)
│   │   ├── compile-framework.ps1# Script de compilación para Windows (PowerShell)
│   │   ├── ValidateLevels.java  # Validador paralelo headless con agente A*
│   │   ├── PlayHuman.java       # Lanzador de juego para humanos con captura de telemetría
│   │   ├── LevelSelector.java   # Selector gráfico (GUI Swing) para elegir y jugar niveles
│   │   ├── telemetry_to_mariogpt_prompt.py # Traductor telemetría → prompt MarioGPT
│   │   └── mario_gpt_generate.py # Script de inferencia y generación con MarioGPT
│   ├── Makefile                 # Automatización cross-platform (Windows, Linux, macOS)
│   └── requirements.txt         # Dependencias Python
├── .gitignore
└── README.md
```

---

## Requisitos Previos

| Requisito | Versión Mínima | Propósito |
|---|---|---|
| **Java JDK** | 17 o superior | Compilar y ejecutar Mario AI Framework, A* y PlayHuman |
| **Python** | 3.8+ (recomendado 3.10+) | Scripts de dataset, telemetría y modelo MarioGPT |
| **Git** | 2.x | Clonar repositorios externos |
| **GNU Make** | 3.81+ *(Opcional pero recomendado)* | Automatizar comandos cross-platform (`make`) |

> 💡 **Nota sobre Make en Windows:** Puedes usar `make` desde **Git Bash** (incluido con Git para Windows), mediante herramientas como Chocolatey (`choco install make`) o Scoop (`scoop install make`), o ejecutar directamente los comandos de PowerShell descritos en la guía paso a paso.

---

## Inicio Rápido con `make` (Cross-Platform)

Todos los comandos de `make` se ejecutan desde la carpeta `src/`:

```bash
cd src
```

### 1. Configuración y Compilación Inicial

```bash
# 1. Clonar repositorios externos necesarios (Mario AI Framework y MarioGPT)
make setup
make setup-mariogpt

# 2. Instalar dependencias de Python y MarioGPT en modo editable
make install-deps
make install-mariogpt

# 3. Compilar el framework Java y las herramientas personalizadas
make compile
```

### 2. Flujo de Trabajo y Comandos Disponibles

```bash
make filter                            # Filtra niveles de MM2 (WANT=80 MAX_SCAN=150000 por defecto)
make filter WANT=100 MAX_SCAN=200000   # Filtrar con parámetros personalizados
make convert                           # Convierte niveles binarios MM2 a formato texto MAF
make validate                          # Valida jugabilidad de niveles convertidos con A*
make play-human                        # Juega un nivel humano y captura telemetría
make prompt-telemetry                  # Genera el prompt de MarioGPT a partir de la telemetría
make generate-level                    # Genera un nuevo nivel con MarioGPT basado en telemetría
make play-levels-generated             # Abre la interfaz gráfica para seleccionar y jugar niveles
make play-and-generate                 # Flujo continuo: Jugar -> Generar automáticamente
make clean                             # Limpia los binarios compilados de Java (.class)
make clean-telemetry                   # Limpia los archivos de telemetría registrados
make clean-levels                      # Limpia niveles convertidos, generados y de nivel0
make clean-all                         # Limpia TODO (binarios, telemetría, niveles y cachés)
make help                              # Muestra la ayuda de todos los targets disponibles
```

---

## Guía Paso a Paso Detallada por Sistema Operativo

Si no deseas utilizar `make`, puedes seguir las instrucciones nativas para tu plataforma.

### 🪟 Windows (PowerShell)

Abre una terminal de **PowerShell** y navega a la carpeta `src`:

```powershell
cd src
```

#### Paso 1 — Clonar repositorios externos en la raíz del proyecto
```powershell
if (-not (Test-Path "..\mario_ai_framework\.git")) {
    git clone https://github.com/amidos2006/Mario-AI-Framework "..\mario_ai_framework"
}
if (-not (Test-Path "..\mario_gpt\.git")) {
    git clone https://github.com/shyamsn97/mario-gpt.git "..\mario_gpt"
}
```

#### Paso 2 — Instalar dependencias de Python
```powershell
python -m pip install -r requirements.txt
python -m pip install -e "..\mario_gpt"
```

#### Paso 3 — Compilar el framework Java y herramientas personalizadas
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\compile-framework.ps1 -Framework "..\mario_ai_framework"
```

#### Paso 4 — Filtrar y convertir niveles de Mario Maker 2
```powershell
# Filtrar dataset de Hugging Face
python dataset\filter_mm2.py --want 80 --max-scan 150000

# Convertir blobs binarios a grillas ASCII para el framework
python dataset\convert_mm2_to_maf.py
```

#### Paso 5 — Validar niveles con el agente A* (Headless)
```powershell
cd ..\mario_ai_framework
java -Djava.awt.headless=true -cp bin ValidateLevels ..\src\levels\converted ..\src\levels\nivel0 40
cd ..\src
```

#### Paso 6 — Jugar un nivel y registrar telemetría
```powershell
cd ..\mario_ai_framework
java -cp bin PlayHuman
cd ..\src

# Traducir telemetría al prompt de MarioGPT
python tools\telemetry_to_mariogpt_prompt.py --telemetry telemetry\latest.json --allow-cloud
```

#### Paso 7 — Generar y jugar niveles generados
```powershell
# Generar nivel con MarioGPT
python tools\mario_gpt_generate.py --telemetry telemetry\latest.json

# Abrir el selector gráfico de niveles
cd ..\mario_ai_framework
java -cp bin LevelSelector
cd ..\src
```

---

### 🐧 Unix (Linux / macOS)

Abre tu terminal (**Bash / Zsh**) y navega a la carpeta `src`:

```bash
cd src
```

#### Paso 1 — Clonar repositorios externos en la raíz del proyecto
```bash
[ -d "../mario_ai_framework/.git" ] || git clone https://github.com/amidos2006/Mario-AI-Framework "../mario_ai_framework"
[ -d "../mario_gpt/.git" ] || git clone https://github.com/shyamsn97/mario-gpt.git "../mario_gpt"
```

#### Paso 2 — Instalar dependencias de Python
```bash
python3 -m pip install -r requirements.txt
python3 -m pip install -e "../mario_gpt"
```

#### Paso 3 — Compilar el framework Java y herramientas personalizadas
```bash
bash tools/compile-framework.sh "../mario_ai_framework"
```

#### Paso 4 — Filtrar y convertir niveles de Mario Maker 2
```bash
# Filtrar dataset de Hugging Face
python3 dataset/filter_mm2.py --want 80 --max-scan 150000

# Convertir blobs binarios a grillas ASCII para el framework
python3 dataset/convert_mm2_to_maf.py
```

#### Paso 5 — Validar niveles con el agente A* (Headless)
```bash
cd "../mario_ai_framework"
java -Djava.awt.headless=true -cp bin ValidateLevels ../src/levels/converted ../src/levels/nivel0 40
cd "../src"
```

#### Paso 6 — Jugar un nivel y registrar telemetría
```bash
cd "../mario_ai_framework"
java -cp bin PlayHuman
cd "../src"

# Traducir telemetría al prompt de MarioGPT
python3 tools/telemetry_to_mariogpt_prompt.py --telemetry telemetry/latest.json --allow-cloud
```

#### Paso 7 — Generar y jugar niveles generados
```bash
# Generar nivel con MarioGPT
python3 tools/mario_gpt_generate.py --telemetry telemetry/latest.json

# Abrir el selector gráfico de niveles
cd "../mario_ai_framework"
java -cp bin LevelSelector
cd "../src"
```

---

## Herramientas y Pipeline de Telemetría

### 1. `PlayHuman.java` — Registro de Jugabilidad
Permite jugar con teclado (Flechas para moverse, `S` para saltar, `A` para correr/disparar). Al terminar la partida (victoria, muerte o tiempo agotado), guarda automáticamente las métricas en `src/telemetry/latest.json`:
- **Métricas:** Estado final (`WIN`/`LOSE`/`TIME_OUT`), completitud del nivel (%), tiempo consumido, número de saltos, bajas de enemigos, daños recibidos y sus coordenadas exactas, monedas recolectadas, velocidad promedio y preferencia estimada de ruta (`high`/`low`).

### 2. `telemetry_to_mariogpt_prompt.py` — Razonador y Traductor
Transforma el JSON de telemetría en un prompt descriptivo en lenguaje natural para MarioGPT (ej. `many pipes, little enemies, some blocks, high elevation`).
- **Modo Local:** Heurística determinista basada en el rendimiento del jugador.
- **Modo Ollama Cloud (`--allow-cloud`):** Si está configurada la variable de entorno `OLLAMA_API_KEY` o un archivo `mario_ai_framework/Ollama-Key.txt`, consulta un modelo de lenguaje en la nube para adaptar el prompt de forma dinámica según el perfil del jugador.

### 3. `LevelSelector.java` — Selector Gráfico de Niveles
Una interfaz gráfica desarrollada en Java Swing que permite navegar por las carpetas `nivel0/`, `generated/` y `converted/`, seleccionar cualquier archivo `.txt`, visualizar información del nivel y lanzarlo al instante para jugarlo con teclado.

---

## Niveles Validados (Nivel 0)

El agente A* valida niveles de MM2 asegurando que sean mecánicamente completables dentro de las reglas de *Infinite Mario Bros / SMB1*. Algunos ejemplos de niveles validados incluidos:

| data_id | Nombre Original | Clear Rate (MM2) | Tema |
|---|---|---|---|
| `3001459` | ジャンプ | 15.1% | Underground |
| `3004249` | 試作型・一画面スイッチ迷路 | 29.0% | Underground |
| `3005554` | Super Mario Land 1-1 | 55.2% | Desert |
| `3006493` | Undertale MEGALOVANIA | 65.3% | Underground |
| `3008364` | The Slumbering Deep | 56.9% | Forest |
| `3008749` | SMB 1-1 Competitive Race | 30.5% | Overworld |
| `3024914` | 全自動1-1 / Automatic 1-1 | 47.5% | Overworld |
| `3028504` | Ruins of 1-1 | 17.6% | Desert |

> ℹ️ El detalle de validación de cada nivel (estado, % completitud, tiempo de búsqueda A*) se almacena en `src/levels/validation_results.csv`.

---

## Referencia Técnica y Formato de Grillas

### Esquema del Dataset Mario Maker 2
Dataset en Hugging Face: **`TheGreatRambler/mm2_level`** (~26.6 millones de niveles).
- Acceso mediante **streaming** (`streaming=True`) para evitar descargas masivas de 100 GB.
- Descompresión con `zlib` y deserialización binaria con Kaitai Struct (`level.ksy` → `level.py`).

### Mapeo de Tokens MM2 → MAF

| Token MAF | Significado | Elemento de Origen MM2 |
|:---:|---|---|
| `-` | Aire / Vacío | Espacio sin colocar |
| `X` | Suelo / Bloque indestructible | `ground[]`, `hard_block`, `stone`, `ice_block` |
| `S` | Ladrillo rompible | `block`, `hidden_block` |
| `#` | Plataforma semisólida | `semisolid_platform`, `mushroom_platform`, `bridge` |
| `?` | Bloque de interrogación | `question_block` |
| `o` | Moneda | `coin`, `big_coin`, `red_coin` |
| `g` | Goomba | `goomba`, `dry_bones` |
| `k` | Koopa Troopa verde | `koopa`, `buzzy_beetle` |
| `y` | Spiny | `spiny`, `spike_top` |
| `< > [ ]` | Tubería (tope y cuerpo) | `pipe` (2 tiles de ancho × alto variable) |
| `B` / `b` | Cañón Bullet Bill (cabeza / cuerpo) | `bullet_bill_blaster` |

---

## Acknowledgments / Referencias de Terceros

Este proyecto de investigación utiliza las siguientes herramientas y repositorios de código abierto:

- **[Mario AI Framework](https://github.com/amidos2006/Mario-AI-Framework)**: Desarrollado por Ahmed Khalifa, Julian Togelius et al. (Licencia MIT).
- **[MarioGPT](https://github.com/shyamsn97/mario-gpt)**: Desarrollado por Shyam Sudhakaran et al. (Licencia MIT).
- **[Dataset mm2_level](https://huggingface.co/datasets/TheGreatRambler/mm2_level)**: Publicado por TheGreatRambler en Hugging Face Datasets.

> ⚠️ **Nota de descargo de responsabilidad (Disclaimer):**  
> Este es un proyecto de investigación académica (Trabajo de Fin de Grado - TFG) sin fines de lucro y **no está afiliado, asociado, patrocinado ni respaldado por Nintendo Co., Ltd.** Super Mario Bros., Super Mario Maker y sus elementos gráficos asociados son marcas comerciales y derechos reservados de Nintendo.
