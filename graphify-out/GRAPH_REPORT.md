# Graph Report - Mario-Ai  (2026-10-23)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 602 nodes · 1153 edges · 29 communities (14 shown, 15 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 52 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `96fe44b0`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Community 0
- Community 1
- Community 3
- Community 4
- Community 5
- Community 6
- Community 7
- Community 8
- Community 9
- Community 10
- Community 11
- Community 12
- Community 15
- Community 16
- Community 17
- Community 18
- Community 19
- Community 20
- Community 21
- Community 22
- Community 23
- Community 24
- Community 25

## God Nodes (most connected - your core abstractions)
1. `ExperimentRunner` - 74 edges
2. `HpcTunnelManager` - 27 edges
3. `Level` - 26 edges
4. `GenerationJob` - 21 edges
5. `main()` - 15 edges
6. `DevGenerationSidePanel` - 14 edges
7. `InstallerProgress` - 12 edges
8. `GenerationMode` - 12 edges
9. `build_compact_prompt()` - 12 edges
10. `LevelSelector` - 11 edges

## Surprising Connections (you probably didn't know these)
- `parse_level()` --uses--> `Level`  [INFERRED]
  src/dataset/convert_mm2_to_maf.py → src/dataset/level.py
- `main()` --calls--> `convert()`  [INFERRED]
  src/dataset/filter_mm2.py → src/dataset/convert_mm2_to_maf.py
- `main()` --calls--> `parse_level()`  [INFERRED]
  src/dataset/filter_mm2.py → src/dataset/convert_mm2_to_maf.py
- `resolve_prompt()` --calls--> `build_compact_prompt()`  [EXTRACTED]
  src/tools/mario_gpt_generate.py → src/tools/telemetry_to_mariogpt_prompt.py

## Import Cycles
- None detected.

## Communities (29 total, 15 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.08
Nodes (11): ExperimentRunner, FreePlayAction, EXIT, NEXT, REPLAY, ModernButton, ModernTextField, PlayedLevelInfo (+3 more)

### Community 1 - "Community 1"
Cohesion: 0.05
Nodes (29): Level, AutoscrollSpeed, ClearCondition, ClearConditionCategory, ClearPipe, ClearPipeNode, ExclamationBlock, ExclamationBlockNode (+21 more)

### Community 3 - "Community 3"
Cohesion: 0.08
Nodes (26): convert(), main(), parse_level(), main(), load_json(), main(), next_level_number(), parse_args() (+18 more)

### Community 4 - "Community 4"
Cohesion: 0.08
Nodes (18): detect_java(), ensure_virtual_environment(), find_repo_root(), InstallerProgress, main(), patch_mariogpt(), prompt_ollama_key_gui(), report_step_failure() (+10 more)

### Community 5 - "Community 5"
Cohesion: 0.08
Nodes (6): DocumentListener, GenerationMode, JEISSON_SERVER, NATIVO, TUNEL, HpcTunnelManager

### Community 6 - "Community 6"
Cohesion: 0.09
Nodes (7): ChoiceCard, DevGenerationSidePanel, MouseAdapter, BasicScrollBarUI, LikertPill, MouseAdapter, MultiChoiceCard

### Community 7 - "Community 7"
Cohesion: 0.10
Nodes (15): append_log(), DashboardHandler, extract_progress(), find_free_port(), get_experiment_summary(), get_generation_config(), get_level_content(), get_ollama_status() (+7 more)

### Community 8 - "Community 8"
Cohesion: 0.07
Nodes (23): autoscrollChk, btnStop, btnViewCanvas, btnViewText, canvasWrapper, currentOllamaStatus, currentTaskDesc, currentTaskName (+15 more)

### Community 9 - "Community 9"
Cohesion: 0.10
Nodes (10): GenerationJob, GenStage, FAILED, FALLBACK_READY, MARIOGPT, OLLAMA, PENDING, QUEUED (+2 more)

### Community 15 - "Community 15"
Cohesion: 0.22
Nodes (9): ansiToHtml(), appendLogsToTerminal(), confirmAndRunSetupLab(), confirmCleanAll(), pollStatus(), runTarget(), startStatusPolling(), updateButtonsState() (+1 more)

### Community 17 - "Community 17"
Cohesion: 0.29
Nodes (8): closeGeneratorModal(), copySshCommandToClipboard(), loadGenerationConfig(), pasteKeyFromClipboard(), saveGeneratorModalConfig(), showToast(), switchGenerationMode(), testConnectionFromModal()

### Community 18 - "Community 18"
Cohesion: 0.29
Nodes (7): clearCanvas(), drawTile(), loadExperimentSummary(), loadLevelContent(), loadLevelList(), renderLevelOnCanvas(), switchTab()

### Community 19 - "Community 19"
Cohesion: 0.29
Nodes (7): closeOllamaModal(), handleModalBackdropClick(), openOllamaModal(), openSystemKeyPrompt(), refreshOllamaStatus(), saveOllamaKeyFromModal(), updateModalStatusBox()

### Community 20 - "Community 20"
Cohesion: 0.50
Nodes (4): detectSlurmNodeFromModal(), openGeneratorModal(), selectModalMode(), updateLiveSshCommand()

## Knowledge Gaps
- **14 isolated node(s):** `EXIT`, `NEXT`, `REPLAY`, `JEISSON_SERVER`, `NATIVO` (+9 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 168 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **15 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ExperimentRunner` connect `Community 0` to `Community 2`, `Community 5`, `Community 6`, `Community 9`, `Community 11`, `Community 12`?**
  _High betweenness centrality (0.144) - this node is a cross-community bridge._
- **What connects `EXIT`, `NEXT`, `REPLAY` to the rest of the system?**
  _14 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.07766599597585513 - nodes in this community are weakly interconnected._
- **Why does `GenerationJob` connect `Community 9` to `Community 0`, `Community 6`?**
  _High betweenness centrality (0.019) - this node is a cross-community bridge._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.0506558118498417 - nodes in this community are weakly interconnected._
- **Why does `HpcTunnelManager` connect `Community 5` to `Community 0`?**
  _High betweenness centrality (0.019) - this node is a cross-community bridge._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.034482758620689655 - nodes in this community are weakly interconnected._