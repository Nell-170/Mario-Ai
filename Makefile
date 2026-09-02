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

.PHONY: help setup setup-mariogpt install-mariogpt compile filter convert validate play-human play-and-generate generate-level prompt-telemetry clean

MARIO_GPT := mario_gpt

help:
	@echo Targets:
	@echo   setup          Clone the Mario AI Framework if it is not present
	@echo   setup-mariogpt Clone the local MarioGPT dependency if it is not present
	@echo   install-mariogpt Install MarioGPT in editable mode for local generation
	@echo   compile        Compile the framework and custom Java tools
	@echo   filter         Select MM2 levels from the Hugging Face dataset
	@echo   convert        Convert selected MM2 levels to MAF format
	@echo   validate       Validate converted levels with the A* agent
	@echo   play-human     Play a Nivel 0 level with the keyboard and save telemetry
	@echo   generate-level Generate a level from the latest telemetry with MarioGPT
	@echo   play-and-generate  Play a level, build the prompt, and generate a level with MarioGPT
	@echo   prompt-telemetry  Build a compact MarioGPT prompt from telemetry/latest.json
	@echo   clean          Remove generated framework class files

setup:
	@if not exist "$(FRAMEWORK)\.git" git clone https://github.com/amidos2006/Mario-AI-Framework "$(FRAMEWORK)"

setup-mariogpt:
	@if not exist "$(MARIO_GPT)\.git" git clone https://github.com/shyamsn97/mario-gpt.git "$(MARIO_GPT)"

install-mariogpt: setup-mariogpt
	@cd "$(MARIO_GPT)" && $(PYTHON) -m pip install -e .

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

generate-level: setup-mariogpt
	@$(PYTHON) tools\mario_gpt_generate.py --telemetry telemetry\latest.json

play-and-generate: play-human generate-level

prompt-telemetry:
	@$(PYTHON) tools\telemetry_to_mariogpt_prompt.py --telemetry telemetry\latest.json

clean:
	@if exist "$(BIN)" rmdir /S /Q "$(BIN)"
