#!/usr/bin/env python3
"""
test_ollama_keys.py — Suite de pruebas unitarias para la rotación y fallback de claves Ollama.
"""

import os
import sys
import json
import time
import shutil
import tempfile
import threading
import unittest
from pathlib import Path
from http.server import HTTPServer, BaseHTTPRequestHandler
from typing import List, Dict, Tuple, Optional

# Añadir src/tools al sys.path
TOOLS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS_DIR))

import ollama_keys
from ollama_keys import (
    key_id,
    mask_key,
    load_keys,
    classify_response,
    Outcome,
    KeyStateManager,
    ollama_chat,
    AllKeysUnavailable,
    NonRotatableError,
)


class MockOllamaServer:
    """Servidor HTTP en memoria para simular el endpoint de Ollama Cloud."""

    def __init__(self, key_responses: Dict[str, List[Tuple[int, dict, dict]]]):
        """
        key_responses: mapeo de key -> lista de (status_code, headers_dict, body_dict_or_str)
        """
        self.key_responses = key_responses
        self.call_history: List[Tuple[str, str, dict]] = []
        self._lock = threading.Lock()
        self.server: Optional[HTTPServer] = None
        self.thread: Optional[threading.Thread] = None
        self.port: int = 0

    def start(self):
        handler_cls = self._build_handler()
        self.server = HTTPServer(("127.0.0.1", 0), handler_cls)
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def stop(self):
        if self.server:
            self.server.shutdown()
            self.server.server_close()

    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}/api/chat"

    def _build_handler(self):
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass  # Silenciar logs HTTP del mock

            def do_POST(self):
                auth = self.headers.get("Authorization", "")
                token = auth.replace("Bearer ", "").strip()
                length = int(self.headers.get("Content-Length", 0))
                body_raw = self.rfile.read(length) if length > 0 else b"{}"
                try:
                    body = json.loads(body_raw.decode("utf-8"))
                except Exception:
                    body = {}

                with outer._lock:
                    outer.call_history.append((token, self.path, body))
                    queue = outer.key_responses.get(token, [])
                    if queue:
                        status, headers, resp_body = queue.pop(0)
                    else:
                        # Por defecto si se agotan las respuestas programadas
                        status = 429
                        headers = {"Content-Type": "application/json"}
                        resp_body = {"error": "rate limit"}

                self.send_response(status)
                for h_k, h_v in (headers or {}).items():
                    self.send_header(h_k, str(h_v))
                if "Content-Type" not in (headers or {}):
                    self.send_header("Content-Type", "application/json")
                self.end_headers()

                if isinstance(resp_body, dict):
                    self.wfile.write(json.dumps(resp_body).encode("utf-8"))
                elif isinstance(resp_body, (str, bytes)):
                    b = resp_body.encode("utf-8") if isinstance(resp_body, str) else resp_body
                    self.wfile.write(b)
                else:
                    self.wfile.write(b"{}")

        return Handler


class TestOllamaKeys(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="mario_test_ollama_")
        self.temp_path = Path(self.temp_dir)
        self.state_file = self.temp_path / ".ollama-key-state.json"
        self.key_file = self.temp_path / "Ollama-Key.txt"

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_load_keys_multiline_comments_dedup(self):
        """Prueba carga de claves múltiples con comentarios, BOM, espacios y duplicados."""
        content = (
            "\ufeff# Archivo de claves de prueba\n"
            "key_uno_123456789\n"
            "\n"
            "   key_dos_987654321   # clave 2 de respaldo\n"
            "# key_comentada_inactiva\n"
            "key_uno_123456789  # duplicada, debe ignorarse\n"
            "key_tres_abcdef123\n"
        )
        self.key_file.write_text(content, encoding="utf-8")

        # Limpiar variables de entorno para aislar prueba de archivo
        old_keys = os.environ.pop("OLLAMA_API_KEYS", None)
        old_single = os.environ.pop("OLLAMA_API_KEY", None)
        try:
            keys = load_keys(custom_file=self.key_file)
            self.assertEqual(len(keys), 3)
            self.assertEqual(keys[0], "key_uno_123456789")
            self.assertEqual(keys[1], "key_dos_987654321")
            self.assertEqual(keys[2], "key_tres_abcdef123")
        finally:
            if old_keys:
                os.environ["OLLAMA_API_KEYS"] = old_keys
            if old_single:
                os.environ["OLLAMA_API_KEY"] = old_single

    def test_mask_and_key_id(self):
        """Verifica que el enmascaramiento y el ID hash nunca revelen la clave completa."""
        secret = "7d6b1ba554d64092974c888b887c4e12.SboI-90VnUNZKTwkTBxsKxCH"
        masked = mask_key(secret)
        self.assertTrue(masked.startswith("7d6b"))
        self.assertTrue(masked.endswith("KxCH"))
        self.assertNotIn("SboI-90VnUNZKTwkTBxs", masked)
        self.assertEqual(len(masked), 16)

        kid = key_id(secret)
        self.assertEqual(len(kid), 10)
        self.assertRegex(kid, r"^[0-9a-f]{10}$")

    def test_classify_response(self):
        """Verifica la clasificación adecuada de códigos HTTP y mensajes."""
        # 200 OK
        out200 = classify_response(200, {}, '{"response": "ok"}')
        self.assertEqual(out200.kind, "ok")
        self.assertFalse(out200.rotate)

        # 429 Cuota semanal
        out429_w = classify_response(429, {}, '{"error": "You have reached your weekly usage limit"}')
        self.assertEqual(out429_w.kind, "quota_weekly")
        self.assertTrue(out429_w.rotate)
        self.assertGreater(out429_w.cooldown_s, 3600)

        # 429 Cuota de sesión
        out429_s = classify_response(429, {}, '{"error": "session limit exceeded"}')
        self.assertEqual(out429_s.kind, "quota_session")
        self.assertTrue(out429_s.rotate)
        self.assertEqual(out429_s.cooldown_s, 3600)

        # 429 con Retry-After
        out429_ra = classify_response(429, {"Retry-After": "45"}, '{"error": "too many"}')
        self.assertEqual(out429_ra.kind, "rate")
        self.assertEqual(out429_ra.cooldown_s, 45.0)

        # 401 Auth error
        out401 = classify_response(401, {}, '{"error": "unauthorized"}')
        self.assertEqual(out401.kind, "auth")
        self.assertTrue(out401.rotate)
        self.assertEqual(out401.cooldown_s, 24 * 3600)

        # 400 Bad Request (no rotar)
        out400 = classify_response(400, {}, '{"error": "invalid parameter"}')
        self.assertEqual(out400.kind, "bad_request")
        self.assertFalse(out400.rotate)

        # 500 Transitorio
        out500 = classify_response(500, {}, '{"error": "internal"}')
        self.assertEqual(out500.kind, "transient")
        self.assertTrue(out500.retry_same)

    def test_state_manager_persistence_and_reset(self):
        """Verifica persistencia en disco y función de reset."""
        sm = KeyStateManager(state_file=self.state_file)
        k1 = "clave_de_prueba_11111111"
        k2 = "clave_de_prueba_22222222"

        # Registrar outcome con cooldown
        outcome = Outcome(kind="quota_session", cooldown_s=3600, rotate=True, error_msg="Session limit")
        sm.record_outcome(k1, outcome, now=1000.0)

        # k1 no debe estar disponible en t=1000
        avail = sm.get_available_keys([k1, k2], now=1000.0)
        self.assertEqual(avail, [k2])

        # En t=5000 (tras 4000s) ya debe estar disponible
        avail_later = sm.get_available_keys([k1, k2], now=5000.0)
        self.assertEqual(avail_later, [k1, k2])

        # Reset explícito
        count = sm.reset_cooldowns([k1, k2])
        self.assertGreaterEqual(count, 1)
        avail_after_reset = sm.get_available_keys([k1, k2], now=1000.0)
        self.assertEqual(avail_after_reset, [k1, k2])

    def test_corrupt_state_file_recovery(self):
        """Verifica que un archivo de estado dañado no interrumpa la ejecución."""
        self.state_file.write_text("{ ESTO_NO_ES_JSON_VALIDO !!!", encoding="utf-8")
        sm = KeyStateManager(state_file=self.state_file)
        status_list = sm.get_status_list(["clave_dummy_12345678"])
        self.assertEqual(len(status_list), 1)
        self.assertEqual(status_list[0]["state"], "lista")

    def test_ollama_chat_rotation_on_429(self):
        """
        Escenario crucial: Clave 1 recibe 429 'weekly usage limit'.
        El sistema rota inmediatamente a Clave 2, la cual responde 200 con éxito.
        """
        k1 = "mock_key_1_111111111111"
        k2 = "mock_key_2_222222222222"

        responses = {
            k1: [
                (429, {}, {"error": "You have reached your weekly usage limit on this key"})
            ],
            k2: [
                (200, {}, {"message": {"content": "MarioGPT level: high elevation with many pipes"}})
            ],
        }

        mock = MockOllamaServer(responses)
        mock.start()
        try:
            sm = KeyStateManager(state_file=self.state_file)
            t_start = time.time()
            res = ollama_chat(
                payload={"model": "gpt-oss:120b", "messages": [{"role": "user", "content": "hi"}]},
                url=mock.url(),
                custom_keys=[k1, k2],
                state_manager=sm,
                budget_s=10.0,
            )
            t_elapsed = time.time() - t_start

            # Verificaciones
            self.assertEqual(res["source"], "ollama")
            self.assertIn("MarioGPT level", res["content"])
            self.assertEqual(res["key_id"], key_id(k2))
            self.assertEqual(res["attempts"], 2)
            self.assertLess(t_elapsed, 2.0, "La rotación inmediata debe ocurrir en menos de 2 segundos.")

            # Comprobar historial del mock
            self.assertEqual(len(mock.call_history), 2)
            self.assertEqual(mock.call_history[0][0], k1)
            self.assertEqual(mock.call_history[1][0], k2)

            # Comprobar que k1 quedó en enfriamiento
            status = sm.get_status_list([k1, k2])
            status_map = {s["id"]: s for s in status}
            self.assertEqual(status_map[key_id(k1)]["state"], "apartada")
            self.assertEqual(status_map[key_id(k2)]["state"], "lista")
        finally:
            mock.stop()

    def test_ollama_chat_skips_cooldown_key_on_second_call(self):
        """
        Segunda llamada: Clave 1 ya está en enfriamiento.
        Debe ir directamente a Clave 2 sin tocar Clave 1.
        """
        k1 = "mock_key_1_111111111111"
        k2 = "mock_key_2_222222222222"

        responses = {
            k1: [(429, {}, {"error": "weekly usage limit"})],
            k2: [
                (200, {}, {"message": {"content": "Respuesta primera"}}),
                (200, {}, {"message": {"content": "Respuesta segunda"}}),
            ],
        }

        mock = MockOllamaServer(responses)
        mock.start()
        try:
            sm = KeyStateManager(state_file=self.state_file)
            # Primera llamada (rota k1 -> k2)
            ollama_chat(
                payload={"messages": []},
                url=mock.url(),
                custom_keys=[k1, k2],
                state_manager=sm,
            )

            # Segunda llamada (k1 en cooldown -> usa directamente k2)
            mock.call_history.clear()
            res2 = ollama_chat(
                payload={"messages": []},
                url=mock.url(),
                custom_keys=[k1, k2],
                state_manager=sm,
            )

            self.assertEqual(res2["key_id"], key_id(k2))
            self.assertEqual(len(mock.call_history), 1)
            self.assertEqual(mock.call_history[0][0], k2)
        finally:
            mock.stop()

    def test_ollama_chat_rotation_on_401_auth(self):
        """Verifica que un error 401 descarte la clave y rote a la siguiente."""
        k1 = "mock_invalid_key_111"
        k2 = "mock_valid_key_222"

        responses = {
            k1: [(401, {}, {"error": "Invalid API key"})],
            k2: [(200, {}, {"message": {"content": "ok prompt"}})],
        }
        mock = MockOllamaServer(responses)
        mock.start()
        try:
            sm = KeyStateManager(state_file=self.state_file)
            res = ollama_chat(
                payload={"messages": []},
                url=mock.url(),
                custom_keys=[k1, k2],
                state_manager=sm,
            )
            self.assertEqual(res["key_id"], key_id(k2))
            self.assertEqual(res["content"], "ok prompt")
        finally:
            mock.stop()

    def test_ollama_chat_transient_retry_500(self):
        """Verifica que ante un error 500 transitorio, reintente la misma clave antes de descartarla."""
        k1 = "mock_flaky_key_111"

        responses = {
            k1: [
                (500, {}, {"error": "Temporary upstream failure"}),
                (200, {}, {"message": {"content": "Recovered response"}}),
            ]
        }
        mock = MockOllamaServer(responses)
        mock.start()
        try:
            sm = KeyStateManager(state_file=self.state_file)
            res = ollama_chat(
                payload={"messages": []},
                url=mock.url(),
                custom_keys=[k1],
                state_manager=sm,
                budget_s=5.0,
            )
            self.assertEqual(res["content"], "Recovered response")
            self.assertEqual(res["key_id"], key_id(k1))
            self.assertEqual(len(mock.call_history), 2)
        finally:
            mock.stop()

    def test_ollama_chat_all_keys_unavailable(self):
        """Verifica que si todas las claves reciben 429, se lance AllKeysUnavailable sin bloquear."""
        k1 = "mock_key_fail_1"
        k2 = "mock_key_fail_2"

        responses = {
            k1: [(429, {}, {"error": "weekly limit"})],
            k2: [(429, {}, {"error": "weekly limit"})],
        }
        mock = MockOllamaServer(responses)
        mock.start()
        try:
            sm = KeyStateManager(state_file=self.state_file)
            with self.assertRaises(AllKeysUnavailable):
                ollama_chat(
                    payload={"messages": []},
                    url=mock.url(),
                    custom_keys=[k1, k2],
                    state_manager=sm,
                    budget_s=3.0,
                )
        finally:
            mock.stop()

    def test_ollama_chat_non_rotatable_error_400(self):
        """Verifica que un error 400 Bad Request no rote claves en vano y falle de inmediato."""
        k1 = "mock_key_1"
        k2 = "mock_key_2"

        responses = {
            k1: [(400, {}, {"error": "Prompt format invalid"})],
            k2: [(200, {}, {"message": {"content": "nunca deberia llamarse"}})],
        }
        mock = MockOllamaServer(responses)
        mock.start()
        try:
            sm = KeyStateManager(state_file=self.state_file)
            with self.assertRaises(NonRotatableError):
                ollama_chat(
                    payload={"messages": []},
                    url=mock.url(),
                    custom_keys=[k1, k2],
                    state_manager=sm,
                )
            # Clave 2 no debió ser contactada
            self.assertEqual(len(mock.call_history), 1)
            self.assertEqual(mock.call_history[0][0], k1)
        finally:
            mock.stop()

    def test_secrets_leak_prevention(self):
        """Verifica que bajo ninguna circunstancia se guarden claves planas en el archivo de estado."""
        secret_key = "super_secret_raw_key_that_must_never_leak_99999"
        sm = KeyStateManager(state_file=self.state_file)
        outcome = Outcome(kind="quota_session", cooldown_s=100.0, rotate=True, error_msg="Quota test")
        sm.record_outcome(secret_key, outcome)

        self.assertTrue(self.state_file.exists())
        state_text = self.state_file.read_text(encoding="utf-8")
        self.assertNotIn(secret_key, state_text)
        self.assertIn(key_id(secret_key), state_text)


if __name__ == "__main__":
    unittest.main()

