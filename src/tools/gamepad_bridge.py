#!/usr/bin/env python3
"""
gamepad_bridge.py — Puente local de mandos USB/Bluetooth hacia el motor de Mario AI.
Envía paquetes UDP al puerto 29555 (escuchado por GamepadManager.java).

- En Linux: GamepadManager.java ya lee /dev/input/js* de manera nativa sin necesidad de este script.
- En Windows o entornos con Pygame: este script sirve como puente opcional para leer mandos vía XInput o Pygame.

Uso:
    python tools/gamepad_bridge.py
"""

import socket
import sys
import time

UDP_IP = "127.0.0.1"
UDP_PORT = 29555

def create_socket():
    return socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

def run_pygame_bridge(sock):
    import pygame
    pygame.init()
    pygame.joystick.init()

    count = pygame.joystick.get_count()
    if count == 0:
        print("[GamepadBridge] Esperando a que se conecte un mando...")
        while pygame.joystick.get_count() == 0:
            pygame.event.pump()
            pygame.joystick.quit()
            pygame.joystick.init()
            time.sleep(1)

    js = pygame.joystick.Joystick(0)
    js.init()
    print(f"[GamepadBridge] Conectado a mando: {js.get_name()}")

    while True:
        pygame.event.pump()
        
        # Ejes analógicos y Hat/D-pad
        axis_x = js.get_axis(0) if js.get_numaxes() > 0 else 0
        axis_y = js.get_axis(1) if js.get_numaxes() > 1 else 0

        hat_x, hat_y = (0, 0)
        if js.get_numhats() > 0:
            hat_x, hat_y = js.get_hat(0)

        left = 1 if (axis_x < -0.4 or hat_x < 0) else 0
        right = 1 if (axis_x > 0.4 or hat_x > 0) else 0
        down = 1 if (axis_y > 0.4 or hat_y < 0) else 0

        # Botones: A (0) o Y (3) para salto, B (1) o X (2) o gatillos para correr
        btn_jump = 1 if (js.get_button(0) or (js.numbuttons > 3 and js.get_button(3)) or hat_y > 0) else 0
        btn_speed = 1 if (js.get_button(1) or (js.numbuttons > 2 and js.get_button(2))) else 0
        btn_start = 1 if (js.numbuttons > 7 and js.get_button(7)) else 0
        btn_restart = 1 if (js.numbuttons > 6 and js.get_button(6)) else 0

        msg = f"L:{left},R:{right},D:{down},J:{btn_jump},S:{btn_speed},P:{btn_start},RST:{btn_restart}"
        sock.sendto(msg.encode("ascii"), (UDP_IP, UDP_PORT))
        time.sleep(0.016) # ~60 Hz

def main():
    sock = create_socket()
    print(f"[GamepadBridge] Puente de mando UDP hacia {UDP_IP}:{UDP_PORT} iniciado.")

    # Intentar Pygame
    try:
        run_pygame_bridge(sock)
        return
    except ImportError:
        print("[GamepadBridge] Pygame no instalado. Nota: En Linux el soporte es nativo en Java (/dev/input/js*).")
    except Exception as e:
        print(f"[GamepadBridge] Detenido: {e}")

if __name__ == "__main__":
    main()

