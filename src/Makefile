# ─────────────────────────────────────────────────────────────
#  Cross-platform Makefile  (Windows · macOS · Linux)
#  Detection: ifeq on OS variable (Windows sets OS=Windows_NT)
# ─────────────────────────────────────────────────────────────

FRAMEWORK  := mario_ai_framework
BIN        := $(FRAMEWORK)/bin
SRC        := $(FRAMEWORK)/src
LEVELS     := levels
MARIO_GPT  := mario_gpt

JAVAC      := javac
JAVA       := java

# ── OS detection ──────────────────────────────────────────────
ifeq ($(OS),Windows_NT)
    PLATFORM   := windows
    PYTHON     := python
    PIP_FLAGS  :=
    PATHSEP    := ;
    DIRSEP     := \\
    MKDIR      := if not exist "$(BIN)" mkdir "$(BIN)"
    RM_BIN     := if exist "$(BIN)" rmdir /S /Q "$(BIN)"
    CHECK_FW   := if not exist "$(FRAMEWORK)\.git"
    CHECK_GPT  := if not exist "$(MARIO_GPT)\.git"
    # Use PowerShell for the compile step on Windows
    COMPILE_CMD = powershell -NoProfile -ExecutionPolicy Bypass -File tools\compile-framework.ps1 -Framework "$(FRAMEWORK)"
else
    PLATFORM   := unix
    # Prefer python3, fall back to python
    PYTHON     := $(shell command -v python3 2>/dev/null || echo python)
    PIP_FLAGS  := --break-system-packages
    PATHSEP    := :
    DIRSEP     := /
    MKDIR      := mkdir -p "$(BIN)"
    RM_BIN     := rm -rf "$(BIN)"
    CHECK_FW   := test ! -d "$(FRAMEWORK)/.git" &&
    CHECK_GPT  := test ! -d "$(MARIO_GPT)/.git" &&
    COMPILE_CMD = bash tools/compile-framework.sh "$(FRAMEWORK)"
endif

# ── Phony targets ─────────────────────────────────────────────
.PHONY: help setup install-deps setup-mariogpt install-mariogpt compile \
        filter convert validate play-human play-levels-generated play-and-generate \
        generate-level prompt-telemetry clean

# ── help ──────────────────────────────────────────────────────
help:
	@echo ""
	@echo "  Mario AI — available targets  [platform: $(PLATFORM)]"
	@echo "  ──────────────────────────────────────────────────────"
	@echo "  setup              Clone the Mario AI Framework (if absent)"
	@echo "  install-deps       Install Python dependencies from requirements.txt"
	@echo "  setup-mariogpt     Clone the local MarioGPT dependency (if absent)"
	@echo "  install-mariogpt   Install MarioGPT in editable mode"
	@echo "  compile            Compile the framework and custom Java tools"
	@echo "  filter             Select MM2 levels from the Hugging Face dataset"
	@echo "  convert            Convert selected MM2 levels to MAF format"
	@echo "  validate           Validate converted levels with the A* agent"
	@echo "  play-human         Play a Nivel 0 level and save telemetry"
	@echo "  play-levels-generated  Select and play a generated level graphically"
	@echo "  generate-level     Generate a level from the latest telemetry"
	@echo "  play-and-generate  play-human + generate-level"
	@echo "  prompt-telemetry   Build a compact MarioGPT prompt from telemetry/latest.json"
	@echo "  clean              Remove generated framework class files"
	@echo ""

# ── setup ─────────────────────────────────────────────────────
setup:
ifeq ($(PLATFORM),windows)
	@if not exist "$(FRAMEWORK)\.git" git clone https://github.com/amidos2006/Mario-AI-Framework "$(FRAMEWORK)"
else
	@test -d "$(FRAMEWORK)/.git" || git clone https://github.com/amidos2006/Mario-AI-Framework "$(FRAMEWORK)"
endif

# ── install-deps ──────────────────────────────────────────────
install-deps:
	@$(PYTHON) -m pip install $(PIP_FLAGS) -r requirements.txt

# ── setup-mariogpt ────────────────────────────────────────────
setup-mariogpt:
ifeq ($(PLATFORM),windows)
	@if not exist "$(MARIO_GPT)\.git" git clone https://github.com/shyamsn97/mario-gpt.git "$(MARIO_GPT)"
else
	@test -d "$(MARIO_GPT)/.git" || git clone https://github.com/shyamsn97/mario-gpt.git "$(MARIO_GPT)"
endif

# ── install-mariogpt ──────────────────────────────────────────
install-mariogpt: setup-mariogpt install-deps
	@cd "$(MARIO_GPT)" && $(PYTHON) -m pip install $(PIP_FLAGS) -e .

# ── compile ───────────────────────────────────────────────────
compile: setup
	@echo "Compiling Mario AI Framework..."
	@$(COMPILE_CMD)

WANT            ?= 80
MAX_SCAN        ?= 150000
VALIDATE_TIMER  ?= 40

# ── filter ────────────────────────────────────────────────────
filter:
	@$(PYTHON) dataset/filter_mm2.py --want $(WANT) --max-scan $(MAX_SCAN)

# ── convert ───────────────────────────────────────────────────
convert:
	@$(PYTHON) dataset/convert_mm2_to_maf.py

# ── validate ──────────────────────────────────────────────────
validate: compile convert
	@cd "$(FRAMEWORK)" && $(JAVA) "-Djava.awt.headless=true" \
	    -cp bin ValidateLevels ../$(LEVELS)/converted ../$(LEVELS)/nivel0 $(VALIDATE_TIMER)


# ── play-human ────────────────────────────────────────────────
play-human: compile
	@cd "$(FRAMEWORK)" && $(JAVA) -cp bin PlayHuman
	@$(PYTHON) tools/telemetry_to_mariogpt_prompt.py --telemetry telemetry/latest.json --allow-cloud

# ── play-levels-generated ───────────────────────────────────────
play-levels-generated: compile
	@cd "$(FRAMEWORK)" && $(JAVA) -cp bin LevelSelector

# ── generate-level ────────────────────────────────────────────
generate-level: setup-mariogpt
	@$(PYTHON) tools/mario_gpt_generate.py --telemetry telemetry/latest.json

# ── play-and-generate ─────────────────────────────────────────
play-and-generate: play-human generate-level

# ── prompt-telemetry ──────────────────────────────────────────
prompt-telemetry:
	@$(PYTHON) tools/telemetry_to_mariogpt_prompt.py --telemetry telemetry/latest.json

# ── clean ─────────────────────────────────────────────────────
clean:
ifeq ($(PLATFORM),windows)
	@if exist "$(BIN)" rmdir /S /Q "$(BIN)"
else
	@rm -rf "$(BIN)"
endif
