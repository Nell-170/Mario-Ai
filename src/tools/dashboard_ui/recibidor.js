/**
 * MARIO AI — RECIBIDOR MINIMAL (1 BOTÓN "EMPEZAR")
 * Bucle continuo de atención a participantes.
 */

(function () {
  'use strict';

  const TARGET_MAKE = 'start-experiment';
  const RETURN_DELAY_MS = 5000; // 5 segundos en pantalla de agradecimiento antes de volver

  const stateStandby = document.getElementById('state-standby');
  const stateInSession = document.getElementById('state-in-session');
  const stateCompleted = document.getElementById('state-completed');
  const btnStart = document.getElementById('btn-start');

  let isRunning = false;
  let pollTimer = null;
  let returnTimer = null;

  function showState(targetEl) {
    [stateStandby, stateInSession, stateCompleted].forEach(el => {
      if (el) el.classList.remove('state-active');
    });

    if (targetEl) {
      targetEl.classList.add('state-active');
    }

    if (targetEl === stateStandby && btnStart) {
      setTimeout(() => btnStart.focus(), 100);
    }
  }

  async function startExperiment() {
    if (isRunning) return;

    try {
      showState(stateInSession);

      const res = await fetch('/api/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          target: TARGET_MAKE,
          label: 'Experimento Mario AI',
        }),
      });

      if (res.ok) {
        isRunning = true;
      } else {
        const data = await res.json().catch(() => ({}));
        console.warn('Respuesta al iniciar:', data);
        isRunning = true;
      }
    } catch (err) {
      console.error('Error al iniciar experimento:', err);
      showState(stateStandby);
      alert('Error de conexión con el servidor local: ' + err.message);
    }
  }

  async function pollStatus() {
    try {
      const res = await fetch('/api/status?t=' + Date.now());
      if (res.ok) {
        const data = await res.json();
        const currentlyRunning = Boolean(data.running);

        if (currentlyRunning) {
          isRunning = true;
          if (!stateInSession.classList.contains('state-active')) {
            showState(stateInSession);
          }
        } else if (isRunning && !currentlyRunning) {
          // La sesión acaba de finalizar
          isRunning = false;
          onSessionFinished();
        }
      }
    } catch (e) {
      // Ignorar fallas transitorias de red
    }

    pollTimer = setTimeout(pollStatus, isRunning ? 800 : 2000);
  }

  function onSessionFinished() {
    clearTimeout(returnTimer);
    showState(stateCompleted);

    // Retorno automático tras unos segundos
    returnTimer = setTimeout(() => {
      showState(stateStandby);
    }, RETURN_DELAY_MS);
  }

  // Permitir retorno inmediato si se hace clic en la pantalla de agradecimiento
  if (stateCompleted) {
    stateCompleted.addEventListener('click', () => {
      clearTimeout(returnTimer);
      showState(stateStandby);
    });
  }

  // Clic en el botón Empezar
  if (btnStart) {
    btnStart.addEventListener('click', startExperiment);
  }

  // Atajos de teclado: Espacio o Enter inicia
  window.addEventListener('keydown', (e) => {
    if ((e.code === 'Space' || e.code === 'Enter') && stateStandby.classList.contains('state-active')) {
      e.preventDefault();
      startExperiment();
    }
  });

  // Inicializar
  showState(stateStandby);
  pollStatus();

})();
