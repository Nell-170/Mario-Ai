SHELL := cmd.exe
.SHELLFLAGS := /C

FRAMEWORK := mario_ai_framework
BIN := $(FRAMEWORK)/bin
SRC := $(FRAMEWORK)/src
LEVELS := levels

JAVAC := javac
JAVA := java
PYTHON := python
PS := powershell -NoProfile -ExecutionPolicy Bypass

.PHONY: help setup compile filter tools convert validate play-human play-and-generate prompt-telemetry generate-level clean

help:
	@echo Targets:
	@echo   setup      Clone the Mario AI Framework if it is not present
	@echo   compile    Compile the framework and custom Java tools
	@echo   filter     Select MM2 levels from the Hugging Face dataset
	@echo   convert    Convert selected MM2 levels to MAF format
	@echo   validate   Validate converted levels with the A* agent
	@echo   play-human      Play a Nivel 0 level with the keyboard and save telemetry
	@echo   play-and-generate  Play a level, build the prompt, and generate a level with MarioGPT
	@echo   prompt-telemetry Build a compact MarioGPT prompt from telemetry/latest.json
	@echo   clean           Remove generated framework class files

setup:
	@if not exist "$(FRAMEWORK)\.git" git clone https://github.com/amidos2006/Mario-AI-Framework "$(FRAMEWORK)"

compile: setup
	@echo Compiling Mario AI Framework...
	@$(PS) -File tools\compile-framework.ps1 -Framework "$(FRAMEWORK)"

filter:
	@$(PYTHON) dataset\filter_mm2.py --want 25 --max-scan 40000

convert:
	@$(PYTHON) dataset\convert_mm2_to_maf.py

validate: compile convert
	@cd "$(FRAMEWORK)" && $(JAVA) "-Djava.awt.headless=true" -cp bin ValidateLevels ..\$(LEVELS)\converted ..\$(LEVELS)\nivel0 60

play-human: compile
	@cd "$(FRAMEWORK)" && $(JAVA) -cp bin PlayHuman
	@$(PYTHON) tools\telemetry_to_mariogpt_prompt.py --telemetry telemetry\latest.json --allow-cloud

play-and-generate: play-human
	@$(PYTHON) tools\mario_gpt_generate.py --telemetry telemetry\latest.json

prompt-telemetry:
	@$(PYTHON) tools\telemetry_to_mariogpt_prompt.py --telemetry telemetry\latest.json

clean:
	@if exist "$(BIN)" rmdir /S /Q "$(BIN)"
