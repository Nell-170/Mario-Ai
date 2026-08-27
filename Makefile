SHELL := cmd.exe
.SHELLFLAGS := /C

FRAMEWORK := mario_ai_framework
BIN := $(FRAMEWORK)/bin
SRC := $(FRAMEWORK)/src
LEVELS := levels
DEFAULT_LEVEL := $(LEVELS)/nivel0/mm2_3005554.txt

JAVAC := javac
JAVA := java
PYTHON := python
PS := powershell -NoProfile -ExecutionPolicy Bypass

.PHONY: help setup compile tools convert validate play-human clean

help:
	@echo Targets:
	@echo   setup      Clone the Mario AI Framework if it is not present
	@echo   compile    Compile the framework and custom Java tools
	@echo   convert    Convert selected MM2 levels to MAF format
	@echo   validate   Validate converted levels with the A* agent
	@echo   play-human Play a Nivel 0 level with the keyboard
	@echo   clean      Remove generated framework class files

setup:
	@if not exist "$(FRAMEWORK)\.git" git clone https://github.com/amidos2006/Mario-AI-Framework "$(FRAMEWORK)"

compile: setup
	@echo Compiling Mario AI Framework...
	@$(PS) -File tools\compile-framework.ps1 -Framework "$(FRAMEWORK)"

convert:
	@$(PYTHON) dataset\convert_mm2_to_maf.py

validate: compile convert
	@cd "$(FRAMEWORK)" && $(JAVA) "-Djava.awt.headless=true" -cp bin ValidateLevels ..\$(LEVELS)\converted ..\$(LEVELS)\nivel0 60

play-human: compile
	@cd "$(FRAMEWORK)" && $(JAVA) -cp bin PlayHuman "..\$(DEFAULT_LEVEL)" 200

clean:
	@if exist "$(BIN)" rmdir /S /Q "$(BIN)"
