# ─────────────────────────────────────────────────────────────
#  Cross-platform Makefile  (Windows · macOS · Linux)
#  Detection: ifeq on OS variable (Windows sets OS=Windows_NT)
# ─────────────────────────────────────────────────────────────

FRAMEWORK  := ../mario_ai_framework
BIN        := $(FRAMEWORK)/bin
LEVELS     := levels
MARIO_GPT  := ../mario_gpt

JAVAC      := javac
JAVA       := java

# Ensure JDK 25 from IDE extension is in PATH if not found globally
ifeq ($(shell which javac 2>/dev/null),)
    IDE_JAVA := /home/marioc/.antigravity-ide/extensions/redhat.java-1.57.0-linux-x64/jre/25.0.4.1-linux-x86_64/bin
    ifneq ($(wildcard $(IDE_JAVA)/javac),)
        export PATH := $(IDE_JAVA):$(PATH)
    endif
endif

# ── OS detection ──────────────────────────────────────────────
ifeq ($(OS),Windows_NT)
    PLATFORM    := windows
    PYTHON      := python
    PIP_FLAGS   :=
    PATCH_CMD    = powershell -NoProfile -ExecutionPolicy Bypass -File tools/apply-framework-patch.ps1 -Framework "$(FRAMEWORK)"
    COMPILE_CMD  = powershell -NoProfile -ExecutionPolicy Bypass -File tools\compile-framework.ps1 -Framework "$(FRAMEWORK)"
else
    PLATFORM    := unix
    PYTHON      := $(shell command -v python3 2>/dev/null || echo python)
    PIP_FLAGS   := --break-system-packages
    PATCH_CMD    = bash tools/apply-framework-patch.sh "$(FRAMEWORK)"
    COMPILE_CMD  = bash tools/compile-framework.sh "$(FRAMEWORK)"
endif

# ── Progress indicator ────────────────────────────────────────
define progress
	@$(PYTHON) -c "import sys; p=$(1); w=20; f=int(p/100*w); b='█'*f + '░'*(w-f); sys.stdout.write(f'\033[1;36m[{p:3d}%]\033[0m \033[1;32m[{b}]\033[0m \033[1m$(2)\033[0m\n')"
endef

# ── Phony targets ─────────────────────────────────────────────
.PHONY: help gui dashboard setup install-deps setup-mariogpt install-mariogpt compile patch-framework \
        filter convert validate play-human play-levels-generated start-experiment \
        start-experiment-nogen generate-level prompt-telemetry play-and-generate \
        clean clean-telemetry clean-levels clean-all

# ── help ──────────────────────────────────────────────────────
help:
	@echo ""
	@echo "  Mario AI — available targets  [platform: $(PLATFORM)]"
	@echo "  ──────────────────────────────────────────────────────"
	@echo "  gui / dashboard        Launch modern Web Control Center GUI in browser"
	@echo "  setup                  Clone the Mario AI Framework (if absent)"
	@echo "  install-deps           Install Python dependencies from requirements.txt"
	@echo "  setup-mariogpt         Clone the local MarioGPT dependency (if absent)"
	@echo "  install-mariogpt       Install MarioGPT in editable mode"
	@echo "  compile                Compile the framework and custom Java tools"
	@echo "  patch-framework        Apply tools/framework.patch to framework"
	@echo "  filter                 Select MM2 levels from the Hugging Face dataset"
	@echo "  convert                Convert selected MM2 levels to MAF format"
	@echo "  validate               Validate converted levels with the A* agent"
	@echo "  play-human             Play a Nivel 0 level and save telemetry"
	@echo "  play-levels-generated  Select and play levels via GUI selector"
	@echo "  start-experiment       Run 6x6 Latin square experiment with MarioGPT"
	@echo "  start-experiment-nogen Run experiment skipping level generation"
	@echo "  generate-level         Generate a level from the latest telemetry"
	@echo "  play-and-generate      play-human + generate-level"
	@echo "  prompt-telemetry       Build a compact MarioGPT prompt from telemetry/latest.json"
	@echo "  clean                  Remove generated Java class files"
	@echo "  clean-telemetry        Remove all recorded telemetry files"
	@echo "  clean-levels           Remove converted, generated and validated levels"
	@echo "  clean-all              Remove ALL generated files (bin, levels, telemetry, caches)"
	@echo ""

# ── gui / dashboard ───────────────────────────────────────────
gui: dashboard

dashboard:
	$(call progress,0,Iniciando Mario AI Web Control Center...)
	@$(PYTHON) tools/dashboard.py

# ── setup ─────────────────────────────────────────────────────
setup:
	$(call progress,0,Verificando Mario AI Framework...)
ifeq ($(PLATFORM),windows)
	@if not exist "$(FRAMEWORK)\.git" git clone https://github.com/amidos2006/Mario-AI-Framework "$(FRAMEWORK)"
else
	@test -d "$(FRAMEWORK)/.git" || git clone https://github.com/amidos2006/Mario-AI-Framework "$(FRAMEWORK)"
endif
	$(call progress,100,Mario AI Framework verificado.)

# ── install-deps ──────────────────────────────────────────────
install-deps:
	$(call progress,0,Instalando dependencias de Python (requirements.txt)...)
	@$(PYTHON) -m pip install $(PIP_FLAGS) -r requirements.txt
	$(call progress,100,Dependencias de Python instaladas.)

# ── setup-mariogpt ────────────────────────────────────────────
setup-mariogpt:
	$(call progress,0,Verificando repositorio MarioGPT...)
ifeq ($(PLATFORM),windows)
	@if not exist "$(MARIO_GPT)\.git" git clone https://github.com/shyamsn97/mario-gpt.git "$(MARIO_GPT)"
else
	@test -d "$(MARIO_GPT)/.git" || git clone https://github.com/shyamsn97/mario-gpt.git "$(MARIO_GPT)"
endif
	$(call progress,100,MarioGPT verificado.)

# ── install-mariogpt ──────────────────────────────────────────
install-mariogpt: setup-mariogpt install-deps
	$(call progress,50,Instalando MarioGPT en modo editable...)
	@cd "$(MARIO_GPT)" && $(PYTHON) -m pip install $(PIP_FLAGS) -e .
	$(call progress,100,MarioGPT instalado correctamente.)

# ── patch-framework ───────────────────────────────────────────
patch-framework:
	$(call progress,20,Aplicando parches de herramientas al framework...)
	@$(PATCH_CMD)
	$(call progress,100,Parches aplicados.)

# ── compile ───────────────────────────────────────────────────
compile: setup
	$(call progress,0,Iniciando proceso de compilación...)
	@$(COMPILE_CMD)
	$(call progress,100,Proceso de compilación finalizado.)

WANT            ?= 80
MAX_SCAN        ?= 150000
VALIDATE_TIMER  ?= 40

# ── filter ────────────────────────────────────────────────────
filter:
	$(call progress,0,Iniciando filtrado de niveles Super Mario Maker 2...)
	@$(PYTHON) dataset/filter_mm2.py --want $(WANT) --max-scan $(MAX_SCAN)
	$(call progress,100,Filtrado completado.)

# ── convert ───────────────────────────────────────────────────
convert:
	$(call progress,0,Iniciando conversión de niveles binarios MM2 a texto MAF...)
	@$(PYTHON) dataset/convert_mm2_to_maf.py
	$(call progress,100,Conversión completada.)

# ── validate ──────────────────────────────────────────────────
validate: compile
	$(call progress,0,Iniciando validación paralela con agente Robin Baumgarten (A*)...)
	@cd "$(FRAMEWORK)" && $(JAVA) "-Djava.awt.headless=true" \
	    -cp bin ValidateLevels ../src/$(LEVELS)/converted ../src/$(LEVELS)/nivel0 $(VALIDATE_TIMER)
	$(call progress,100,Validación de niveles finalizada.)

# ── play-human ────────────────────────────────────────────────
play-human: compile
	$(call progress,0,Iniciando partida humana de Mario...)
	@cd "$(FRAMEWORK)" && $(JAVA) -cp bin PlayHuman
	$(call progress,80,Procesando telemetría y generando prompt...)
	@$(PYTHON) tools/telemetry_to_mariogpt_prompt.py --telemetry telemetry/latest.json --allow-cloud
	$(call progress,100,Partida y telemetría registradas.)

# ── play-levels-generated ───────────────────────────────────────
play-levels-generated: compile
	$(call progress,0,Abriendo selector gráfico de niveles...)
	@cd "$(FRAMEWORK)" && $(JAVA) -cp bin LevelSelector

# ── start-experiment ──────────────────────────────────────────
start-experiment: compile
	$(call progress,0,Iniciando protocolo de experimentación formal...)
	@cd "$(FRAMEWORK)" && $(JAVA) -cp bin ExperimentRunner "$(PYTHON)"
	$(call progress,100,Sesión de experimento finalizada.)

# ── start-experiment-nogen ────────────────────────────────────
start-experiment-nogen: compile
	$(call progress,0,Iniciando experimento en modo rápido (sin generación IA)...)
	@cd "$(FRAMEWORK)" && $(JAVA) -cp bin ExperimentRunner "$(PYTHON)" --skip-generation
	$(call progress,100,Sesión de experimento finalizada.)

# ── generate-level ────────────────────────────────────────────
generate-level: setup-mariogpt
	$(call progress,0,Iniciando generación procedural con MarioGPT...)
	@$(PYTHON) tools/mario_gpt_generate.py --telemetry telemetry/latest.json
	$(call progress,100,Generación completada exitosamente.)

# ── play-and-generate ─────────────────────────────────────────
play-and-generate:
	$(call progress,0,Paso 1/2: Jugando nivel para capturar telemetría...)
	@$(MAKE) play-human
	$(call progress,50,Paso 2/2: Generando nuevo nivel personalizado con MarioGPT...)
	@$(MAKE) generate-level
	$(call progress,100,Flujo completo terminado.)

# ── prompt-telemetry ──────────────────────────────────────────
prompt-telemetry:
	$(call progress,0,Convirtiendo telemetría a prompt MarioGPT...)
	@$(PYTHON) tools/telemetry_to_mariogpt_prompt.py --telemetry telemetry/latest.json
	$(call progress,100,Prompt generado.)

# ── clean ─────────────────────────────────────────────────────
clean:
	$(call progress,0,Limpiando binarios Java...)
ifeq ($(PLATFORM),windows)
	@if exist "$(BIN)" rmdir /S /Q "$(BIN)"
else
	@rm -rf "$(BIN)"
endif
	$(call progress,100,Binarios Java eliminados.)

# ── clean-telemetry ───────────────────────────────────────────
clean-telemetry:
	$(call progress,0,Limpiando archivos de telemetría...)
	@$(PYTHON) -c "import os, glob; [os.remove(f) for f in glob.glob('telemetry/*') if os.path.isfile(f) and not f.endswith('.gitkeep')]; print('Telemetry files removed.')"
	$(call progress,100,Telemetría limpia.)

# ── clean-levels ──────────────────────────────────────────────
clean-levels:
	$(call progress,0,Limpiando directorios de niveles...)
	@$(PYTHON) -c "import os, glob, shutil; \
		[shutil.rmtree(d, ignore_errors=True) or os.makedirs(d, exist_ok=True) for d in ['levels/converted', 'levels/generated', 'levels/nivel0', 'levels/test_output']]; \
		[os.remove(f) for f in glob.glob('levels/*.csv') if os.path.isfile(f)]; \
		print('Levels (converted, generated, nivel0) removed.')"
	$(call progress,100,Niveles limpios.)

# ── clean-all ─────────────────────────────────────────────────
clean-all:
	$(call progress,0,Iniciando limpieza profunda del proyecto...)
	@$(MAKE) clean
	@$(MAKE) clean-telemetry
	@$(MAKE) clean-levels
	@$(PYTHON) -c "import os, glob, shutil; \
		[os.remove(f) for f in glob.glob('dataset/*.pkl') + glob.glob('dataset/*.csv') if os.path.isfile(f)]; \
		[shutil.rmtree(p, ignore_errors=True) for p in glob.glob('**/__pycache__', recursive=True)]; \
		print('✨ Full clean complete (bin, telemetry, levels, dataset caches).')"
	$(call progress,100,Limpieza total completada.)