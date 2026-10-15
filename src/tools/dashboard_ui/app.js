// Mario AI Control Center — Frontend App Logic
let logIndex = 0;
let isPolling = false;
let autoScroll = true;
let currentViewMode = 'canvas';
let currentLevelText = '';

// DOM Elements
const statusPill = document.getElementById('status-pill');
const statusText = document.getElementById('status-text');
const btnStop = document.getElementById('btn-stop-task');
const currentTaskName = document.getElementById('current-task-name');
const currentTaskDesc = document.getElementById('current-task-desc');
const taskTimer = document.getElementById('task-timer');
const taskPercent = document.getElementById('task-percent');
const progressBar = document.getElementById('progress-bar-fill');
const terminalBody = document.getElementById('terminal-body');
const autoscrollChk = document.getElementById('autoscroll-chk');

// Metrics
const statNivel0 = document.getElementById('stat-nivel0');
const statGenerated = document.getElementById('stat-generated');
const statTelemetry = document.getElementById('stat-telemetry');
const statParticipants = document.getElementById('stat-participants');

// Level Viewer elements
const folderSelect = document.getElementById('level-folder-select');
const fileSelect = document.getElementById('level-file-select');
const levelInfoText = document.getElementById('level-info-text');
const canvasWrapper = document.getElementById('canvas-wrapper');
const levelCanvas = document.getElementById('level-canvas');
const levelTextView = document.getElementById('level-text-view');
const btnViewCanvas = document.getElementById('btn-view-canvas');
const btnViewText = document.getElementById('btn-view-text');

// Init on load
document.addEventListener('DOMContentLoaded', () => {
  autoscrollChk.addEventListener('change', (e) => {
    autoScroll = e.target.checked;
  });

  refreshStats();
  refreshOllamaStatus();
  startStatusPolling();
  loadLevelList();
  loadExperimentSummary();

  // Periodic metrics & Ollama status refresh
  setInterval(() => {
    refreshStats();
    refreshOllamaStatus();
  }, 5000);
});

// Run a make target
async function runTarget(target, label) {
  try {
    const res = await fetch('/api/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ target, label }),
    });

    const data = await res.json();
    if (res.status === 409) {
      alert('Ya hay una tarea en ejecución. Espera a que termine o deténla.');
      return;
    }

    currentTaskName.textContent = label;
    currentTaskDesc.textContent = `Ejecutando: make ${target}...`;
    progressBar.classList.add('running-animated');
    updateButtonsState(true);
  } catch (err) {
    console.error('Error starting task:', err);
  }
}

// Stop current running task
async function stopCurrentTask() {
  if (!confirm('¿Estás seguro de que deseas detener la tarea actual?')) {
    return;
  }
  try {
    await fetch('/api/stop', { method: 'POST' });
  } catch (err) {
    console.error('Error stopping task:', err);
  }
}

function confirmCleanAll() {
  if (confirm('⚠️ ¿Estás seguro de que deseas realizar una LIMPIEZA TOTAL?\nSe borrarán binarios, telemetría, niveles convertidos y cachés.')) {
    runTarget('clean-all', 'Limpieza Total del Proyecto');
  }
}

// Polling loop for status and logs
async function pollStatus() {
  try {
    // 1. Fetch status
    const resStatus = await fetch('/api/status');
    const status = await resStatus.json();

    updateStatusUI(status);

    // 2. Fetch new logs
    const resLogs = await fetch(`/api/logs?since=${logIndex}`);
    const logsData = await resLogs.json();

    if (logsData.logs && logsData.logs.length > 0) {
      appendLogsToTerminal(logsData.logs);
      logIndex = logsData.total;
    }
  } catch (err) {
    // Server might be temporarily busy
  }
}

function startStatusPolling() {
  if (isPolling) return;
  isPolling = true;
  setInterval(pollStatus, 400);
}

function updateStatusUI(status) {
  const isRunning = status.running;

  // Status Pill
  if (isRunning) {
    statusPill.className = 'status-pill status-running';
    statusText.textContent = `Ejecutando: ${status.label || status.target}`;
    btnStop.classList.remove('hidden');
    progressBar.classList.add('running-animated');
    updateButtonsState(true);
  } else {
    btnStop.classList.add('hidden');
    progressBar.classList.remove('running-animated');
    updateButtonsState(false);

    if (status.status === 'success') {
      statusPill.className = 'status-pill status-idle';
      statusText.textContent = 'Completado con éxito';
    } else if (status.status === 'error' || status.status === 'stopped') {
      statusPill.className = 'status-pill status-error';
      statusText.textContent = status.status === 'stopped' ? 'Cancelado' : 'Error en ejecución';
    } else {
      statusPill.className = 'status-pill status-idle';
      statusText.textContent = 'Listo (En reposo)';
    }
  }

  // Progress Bar & Meta
  const pct = status.progress || 0;
  progressBar.style.width = `${pct}%`;
  taskPercent.textContent = `${pct}%`;

  const dur = status.duration || 0;
  const mins = Math.floor(dur / 60);
  const secs = Math.floor(dur % 60);
  taskTimer.textContent = `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}s`;

  if (isRunning) {
    currentTaskName.textContent = status.label || `make ${status.target}`;
    currentTaskDesc.textContent = `Proceso activo (duración: ${dur}s)`;
  } else if (status.status === 'success') {
    currentTaskName.textContent = `Finalizado: ${status.label || status.target}`;
    currentTaskDesc.textContent = `Completado exitosamente en ${dur} segundos.`;
  } else if (status.status === 'error') {
    currentTaskName.textContent = `Falló: ${status.label || status.target}`;
    currentTaskDesc.textContent = `Terminó con error (código: ${status.exit_code}).`;
  }
}

function updateButtonsState(disabled) {
  const buttons = document.querySelectorAll('.action-btn');
  buttons.forEach(btn => {
    btn.disabled = disabled;
  });
}

// Terminal Log Rendering with ANSI to HTML conversion
function ansiToHtml(text) {
  // Convert ANSI escape sequences into styled spans
  let html = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');

  // Colors
  html = html
    .replace(/\033\[1;36m/g, '<span style="color:#38bdf8;font-weight:bold;">')
    .replace(/\033\[1;32m/g, '<span style="color:#4ade80;font-weight:bold;">')
    .replace(/\033\[1;33m/g, '<span style="color:#facc15;font-weight:bold;">')
    .replace(/\033\[1;31m/g, '<span style="color:#f87171;font-weight:bold;">')
    .replace(/\033\[1m/g, '<span style="font-weight:bold;">')
    .replace(/\033\[0m/g, '</span>')
    .replace(/\033\[[0-9;]*m/g, ''); // strip remaining ANSI

  return html;
}

function appendLogsToTerminal(logs) {
  const frag = document.createDocumentFragment();
  logs.forEach(log => {
    const row = document.createElement('div');
    row.className = 'log-line';

    const time = document.createElement('span');
    time.className = 'log-time';
    time.textContent = log.time;

    const content = document.createElement('span');
    content.className = `log-text log-${log.type}`;
    content.innerHTML = ansiToHtml(log.text);

    row.appendChild(time);
    row.appendChild(content);
    frag.appendChild(row);
  });

  terminalBody.appendChild(frag);

  if (autoScroll) {
    terminalBody.scrollTop = terminalBody.scrollHeight;
  }
}

function clearConsole() {
  terminalBody.innerHTML = '<div class="terminal-welcome">Consola limpia. Esperando nuevos registros...</div>';
}

function copyConsoleLogs() {
  const text = Array.from(terminalBody.querySelectorAll('.log-line'))
    .map(line => line.innerText)
    .join('\n');
  navigator.clipboard.writeText(text).then(() => {
    alert('Logs copiados al portapapeles');
  });
}

// Stats Refresher
async function refreshStats() {
  try {
    const res = await fetch('/api/stats');
    const data = await res.json();
    statNivel0.textContent = data.levels_nivel0 ?? 0;
    statGenerated.textContent = data.levels_generated ?? 0;
    statTelemetry.textContent = data.telemetry_sessions ?? 0;
    statParticipants.textContent = data.experiment_participants ?? 0;
  } catch (err) {
    console.error('Error refreshing stats:', err);
  }
}

// Tab Switching
function switchTab(tabId) {
  document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.tab === tabId);
  });
  document.querySelectorAll('.tab-content').forEach(content => {
    content.classList.toggle('active', content.id === `tab-${tabId}`);
  });

  if (tabId === 'levels' && !currentLevelText) {
    loadLevelContent();
  } else if (tabId === 'experiment') {
    loadExperimentSummary();
  }
}

// ─────────────────────────────────────────────────────────────
// Level Viewer & 2D Tile Canvas Renderer
// ─────────────────────────────────────────────────────────────

async function loadLevelList() {
  const folder = folderSelect.value;
  fileSelect.innerHTML = '<option value="">Cargando lista...</option>';

  try {
    const res = await fetch(`/api/levels?folder=${folder}`);
    const files = await res.json();

    if (files.length === 0) {
      fileSelect.innerHTML = '<option value="">(No hay niveles en esta carpeta)</option>';
      clearCanvas();
      return;
    }

    fileSelect.innerHTML = files.map(f => {
      return `<option value="${f.name}">${f.name} (${f.modified})</option>`;
    }).join('');

    loadLevelContent();
  } catch (err) {
    fileSelect.innerHTML = '<option value="">Error al cargar niveles</option>';
  }
}

async function loadLevelContent() {
  const folder = folderSelect.value;
  const fileName = fileSelect.value;

  if (!fileName) return;

  levelInfoText.textContent = `Cargando nivel ${fileName}...`;

  try {
    const res = await fetch(`/api/level-content?folder=${folder}&file=${encodeURIComponent(fileName)}`);
    const data = await res.json();

    currentLevelText = data.content;
    levelTextView.textContent = data.content;

    const lines = data.content.split('\n').filter(l => l.trim().length > 0);
    const cols = lines.length > 0 ? lines[0].length : 0;
    levelInfoText.innerHTML = `<strong>${fileName}</strong> &bull; Dimensiones: <strong>16 filas &times; ${cols} columnas</strong>`;

    renderLevelOnCanvas(lines);
  } catch (err) {
    levelInfoText.textContent = `Error al cargar contenido de ${fileName}`;
  }
}

function setViewMode(mode) {
  currentViewMode = mode;
  btnViewCanvas.classList.toggle('active', mode === 'canvas');
  btnViewText.classList.toggle('active', mode === 'text');

  if (mode === 'canvas') {
    canvasWrapper.classList.remove('hidden');
    levelTextView.classList.add('hidden');
  } else {
    canvasWrapper.classList.add('hidden');
    levelTextView.classList.remove('hidden');
  }
}

function clearCanvas() {
  const ctx = levelCanvas.getContext('2d');
  ctx.clearRect(0, 0, levelCanvas.width, levelCanvas.height);
}

// 2D Side-Scrolling Mario Tile Renderer
function renderLevelOnCanvas(lines) {
  if (!lines || lines.length === 0) return;

  const rows = lines.length;
  const cols = lines[0].length;
  const tileSize = 20; // 20px per tile -> 16 * 20 = 320px high

  levelCanvas.width = cols * tileSize;
  levelCanvas.height = rows * tileSize;

  const ctx = levelCanvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;

  // Background Sky
  ctx.fillStyle = '#5c94fc';
  ctx.fillRect(0, 0, levelCanvas.width, levelCanvas.height);

  for (let r = 0; r < rows; r++) {
    const line = lines[r];
    for (let c = 0; c < line.length; c++) {
      const ch = line[c];
      const x = c * tileSize;
      const y = r * tileSize;

      drawTile(ctx, ch, x, y, tileSize);
    }
  }
}

function drawTile(ctx, ch, x, y, size) {
  switch (ch) {
    case 'X': // Solid Ground
      ctx.fillStyle = '#d86800'; // SMB ground brown
      ctx.fillRect(x, y, size, size);
      ctx.strokeStyle = '#6c3400';
      ctx.strokeRect(x, y, size, size);
      // Grass top if near surface
      ctx.fillStyle = '#00a800';
      ctx.fillRect(x, y, size, 3);
      break;

    case '#': // Solid Platform
      ctx.fillStyle = '#fc9838';
      ctx.fillRect(x, y, size, size);
      ctx.fillStyle = '#6c3400';
      ctx.fillRect(x + 2, y + 2, size - 4, size - 4);
      break;

    case 'S': // Breakable Brick
      ctx.fillStyle = '#b84418';
      ctx.fillRect(x, y, size, size);
      ctx.strokeStyle = '#000000';
      ctx.strokeRect(x, y, size, size);
      // Brick mortar line
      ctx.fillStyle = '#000000';
      ctx.fillRect(x, y + size / 2, size, 1);
      ctx.fillRect(x + size / 2, y, 1, size / 2);
      break;

    case '?': // Question Block
      ctx.fillStyle = '#fcb040';
      ctx.fillRect(x, y, size, size);
      ctx.strokeStyle = '#6c3400';
      ctx.strokeRect(x, y, size, size);
      ctx.fillStyle = '#6c3400';
      ctx.font = 'bold 12px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('?', x + size / 2, y + size - 5);
      break;

    case 'o': // Coin
      ctx.fillStyle = '#fce440';
      ctx.beginPath();
      ctx.ellipse(x + size / 2, y + size / 2, 4, 7, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = '#d89000';
      ctx.stroke();
      break;

    case '<': // Pipe Top Left
      ctx.fillStyle = '#00a800';
      ctx.fillRect(x, y, size, size);
      ctx.fillStyle = '#80d010';
      ctx.fillRect(x + 2, y + 2, 4, size - 4);
      ctx.strokeStyle = '#005000';
      ctx.strokeRect(x, y, size, size);
      break;

    case '>': // Pipe Top Right
      ctx.fillStyle = '#00a800';
      ctx.fillRect(x, y, size, size);
      ctx.strokeStyle = '#005000';
      ctx.strokeRect(x, y, size, size);
      break;

    case '[': // Pipe Body Left
      ctx.fillStyle = '#00a800';
      ctx.fillRect(x + 2, y, size - 2, size);
      ctx.fillStyle = '#80d010';
      ctx.fillRect(x + 4, y, 3, size);
      break;

    case ']': // Pipe Body Right
      ctx.fillStyle = '#00a800';
      ctx.fillRect(x, y, size - 2, size);
      break;

    case 'g': // Goomba
      ctx.fillStyle = '#a84000';
      ctx.beginPath();
      ctx.arc(x + size / 2, y + size / 2 - 2, 6, 0, Math.PI, true);
      ctx.fill();
      ctx.fillStyle = '#fce4a0';
      ctx.fillRect(x + size / 2 - 4, y + size / 2 - 2, 8, 6);
      ctx.fillStyle = '#000';
      ctx.fillRect(x + size / 2 - 3, y + size / 2, 2, 2);
      ctx.fillRect(x + size / 2 + 1, y + size / 2, 2, 2);
      break;

    case 'k': // Koopa
      ctx.fillStyle = '#00a800';
      ctx.beginPath();
      ctx.ellipse(x + size / 2, y + size / 2, 6, 8, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = '#000';
      ctx.stroke();
      break;

    case 'y': // Spiny
      ctx.fillStyle = '#fc2000';
      ctx.beginPath();
      ctx.arc(x + size / 2, y + size / 2, 6, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#fff';
      ctx.fillRect(x + size / 2 - 1, y + 2, 2, 4);
      break;

    case 'B': // Bullet Blaster Head
      ctx.fillStyle = '#202020';
      ctx.fillRect(x, y, size, size);
      ctx.fillStyle = '#505050';
      ctx.fillRect(x + 2, y + 2, size - 4, 3);
      break;

    case 'b': // Bullet Blaster Body
      ctx.fillStyle = '#383838';
      ctx.fillRect(x + 2, y, size - 4, size);
      break;

    case '-': // Air
    default:
      // Transparent sky
      break;
  }
}

// ─────────────────────────────────────────────────────────────
// Experiment Summary
// ─────────────────────────────────────────────────────────────

async function loadExperimentSummary() {
  const tbody = document.getElementById('experiment-tbody');
  try {
    const res = await fetch('/api/experiment-summary');
    const data = await res.json();

    if (!data.participants || data.participants.length === 0) {
      tbody.innerHTML = '<tr><td colspan="4" class="text-center" style="color:var(--text-dim);padding:2rem;">Aún no hay participantes registrados en experiment/results.</td></tr>';
      return;
    }

    tbody.innerHTML = data.participants.map(p => {
      return `
        <tr>
          <td><code style="color:var(--accent-cyan);">${p.id}</code></td>
          <td><span class="badge" style="background:rgba(139,92,246,0.2);color:#c4b5fd;">${p.group}</span></td>
          <td><code style="color:#fcd34d;">${p.steps}</code></td>
          <td style="color:var(--text-muted);">${p.modified}</td>
        </tr>
      `;
    }).join('');
  } catch (err) {
    tbody.innerHTML = '<tr><td colspan="4" class="text-center" style="color:var(--accent-red);">Error al cargar resumen de experimentos</td></tr>';
  }
}

// ─────────────────────────────────────────────────────────────
// Ollama API Key Management & Modal Dialog
// ─────────────────────────────────────────────────────────────

let currentOllamaStatus = { configured: false, masked_key: '', key_path: '' };

async function refreshOllamaStatus() {
  const pill = document.getElementById('ollama-pill');
  const text = document.getElementById('ollama-text');
  if (!pill || !text) return;

  try {
    const res = await fetch('/api/ollama-key');
    const data = await res.json();
    currentOllamaStatus = data;

    pill.classList.remove('configured', 'missing');
    if (data.configured) {
      pill.classList.add('configured');
      text.textContent = 'Ollama: Lista ✓';
      pill.title = `Ollama Cloud activa (${data.masked_key})\nArchivo: ${data.key_path}\nHaga clic para ver o editar`;
    } else {
      pill.classList.add('missing');
      text.textContent = 'Ollama: No configurada ⚠';
      pill.title = 'No se encontró archivo de clave Ollama-Key.txt\nHaga clic para ingresar su API Key';
    }

    updateModalStatusBox(data);
  } catch (err) {
    console.error('Error fetching Ollama status:', err);
  }
}

function updateModalStatusBox(data) {
  const box = document.getElementById('key-status-box');
  const icon = document.getElementById('key-status-icon');
  const title = document.getElementById('key-status-title');
  const sub = document.getElementById('key-status-sub');
  if (!box || !title) return;

  box.classList.remove('configured', 'missing');
  if (data.configured) {
    box.classList.add('configured');
    icon.textContent = '🟢';
    title.textContent = `Clave activa y vinculada (${data.masked_key})`;
    sub.textContent = `Archivo destino: ${data.key_path}`;
  } else {
    box.classList.add('missing');
    icon.textContent = '🟠';
    title.textContent = 'No se detectó clave de Ollama Cloud';
    sub.textContent = 'La síntesis de niveles usará el modo local determinista hasta ingresar la clave.';
  }
}

function openOllamaModal() {
  const modal = document.getElementById('ollama-modal');
  if (!modal) return;
  modal.classList.remove('hidden');
  refreshOllamaStatus();
  const input = document.getElementById('ollama-key-input');
  if (input) {
    input.value = '';
    setTimeout(() => input.focus(), 150);
  }
}

function closeOllamaModal() {
  const modal = document.getElementById('ollama-modal');
  if (modal) modal.classList.add('hidden');
}

function handleModalBackdropClick(event) {
  if (event.target && event.target.id === 'ollama-modal') {
    closeOllamaModal();
  }
}

function toggleKeyVisibility() {
  const input = document.getElementById('ollama-key-input');
  const btn = document.getElementById('btn-toggle-key');
  if (!input || !btn) return;
  if (input.type === 'password') {
    input.type = 'text';
    btn.textContent = '🔒';
    btn.title = 'Ocultar clave';
  } else {
    input.type = 'password';
    btn.textContent = '👁️';
    btn.title = 'Mostrar clave';
  }
}

async function pasteKeyFromClipboard() {
  const input = document.getElementById('ollama-key-input');
  if (!input) return;
  try {
    const text = await navigator.clipboard.readText();
    if (text) {
      input.value = text.trim();
      showToast('Clave pegada desde el portapapeles');
    }
  } catch (err) {
    input.focus();
    showToast('Presiona Ctrl+V para pegar manualmente', true);
  }
}

async function saveOllamaKeyFromModal() {
  const input = document.getElementById('ollama-key-input');
  if (!input) return;
  const key = input.value.trim();
  if (!key) {
    alert('Por favor, ingresa o pega una API Key válida de Ollama Cloud.');
    input.focus();
    return;
  }

  try {
    const res = await fetch('/api/ollama-key', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key })
    });
    const data = await res.json();
    if (res.ok && data.configured) {
      showToast('✓ API Key guardada exitosamente en mario_ai_framework/Ollama-Key.txt');
      input.value = '';
      refreshOllamaStatus();
      closeOllamaModal();
    } else {
      alert('Error al guardar la clave: ' + (data.error || 'Respuesta inválida'));
    }
  } catch (err) {
    console.error('Error saving Ollama key:', err);
    alert('Fallo de conexión al guardar la clave en el servidor.');
  }
}

function openSystemKeyPrompt() {
  closeOllamaModal();
  runTarget('install-key', 'Configuración de Ollama (Ventana del Sistema)');
}

function showToast(message, isError = false) {
  const container = document.getElementById('toast-container');
  if (!container) return;
  const toast = document.createElement('div');
  toast.className = 'toast' + (isError ? ' toast-error' : '');
  toast.innerHTML = `<span>${isError ? '⚠️' : '✓'}</span><span>${message}</span>`;
  container.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transform = 'translateY(10px)';
    toast.style.transition = 'all 0.3s ease';
    setTimeout(() => toast.remove(), 300);
  }, 4000);
}

