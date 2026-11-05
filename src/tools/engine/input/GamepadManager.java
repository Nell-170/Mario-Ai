package engine.input;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import engine.helper.MarioActions;

/**
 * GamepadManager — Soporte universal de mandos (USB / Bluetooth).
 * 
 * Capacidad dual:
 * 1. Linux Nativo: Lee directamente /dev/input/js* (joydev) sin librerías externas ni dependencias JNI.
 * 2. Bridge UDP (Multiplataforma Windows/Linux/macOS): Escucha paquetes en 127.0.0.1:29555
 *    enviados por puentes como gamepad_bridge.py o herramientas externas.
 */
public class GamepadManager {
    private static final GamepadManager INSTANCE = new GamepadManager();

    public static GamepadManager getInstance() {
        return INSTANCE;
    }

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile String controllerName = "Ninguno";

    // Estados de entrada del gamepad
    private volatile boolean padLeft = false;
    private volatile boolean padRight = false;
    private volatile boolean padDown = false;
    private volatile boolean padJump = false;
    private volatile boolean padSpeed = false;

    // Callbacks para funciones especiales del juego
    private volatile Runnable pauseCallback = null;
    private volatile Runnable restartCallback = null;

    private boolean started = false;

    private GamepadManager() {
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;

        // Iniciar hilo para Linux joydev si la plataforma lo soporta
        Thread linuxThread = new Thread(this::runLinuxJoydevLoop, "MarioGamepad-Linux");
        linuxThread.setDaemon(true);
        linuxThread.start();

        // Iniciar hilo para puente UDP en localhost (compatible con Windows y Linux)
        Thread udpThread = new Thread(this::runUdpBridgeLoop, "MarioGamepad-UDPBridge");
        udpThread.setDaemon(true);
        udpThread.start();
    }

    public boolean isConnected() {
        return connected.get();
    }

    public String getControllerName() {
        return controllerName;
    }

    public void setPauseCallback(Runnable r) {
        this.pauseCallback = r;
    }

    public void setRestartCallback(Runnable r) {
        this.restartCallback = r;
    }

    /**
     * Combina las acciones del teclado con las del control usando lógica OR.
     */
    public boolean[] applyActions(boolean[] keyboardActions) {
        if (keyboardActions == null) {
            keyboardActions = new boolean[MarioActions.numberOfActions()];
        }
        boolean[] combined = new boolean[keyboardActions.length];
        System.arraycopy(keyboardActions, 0, combined, 0, keyboardActions.length);

        if (connected.get()) {
            if (padLeft) combined[MarioActions.LEFT.getValue()] = true;
            if (padRight) combined[MarioActions.RIGHT.getValue()] = true;
            if (padDown) combined[MarioActions.DOWN.getValue()] = true;
            if (padJump) combined[MarioActions.JUMP.getValue()] = true;
            if (padSpeed) combined[MarioActions.SPEED.getValue()] = true;
        }
        return combined;
    }

    // ─────────────────────────────────────────────────────────────
    // 1. Linux Joydev Nativo (/dev/input/js*)
    // ─────────────────────────────────────────────────────────────

    private void runLinuxJoydevLoop() {
        File devInput = new File("/dev/input");
        if (!devInput.exists() || !devInput.isDirectory()) {
            // No es Linux o no hay subsistema de input
            return;
        }

        byte[] eventBuffer = new byte[8];

        while (!Thread.currentThread().isInterrupted()) {
            File jsDevice = findFirstJoystick();
            if (jsDevice == null) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }

            try (FileInputStream fis = new FileInputStream(jsDevice)) {
                connected.set(true);
                controllerName = "Mando USB/BT (" + jsDevice.getName() + ")";
                System.out.println("[GamepadManager] Mando conectado: " + jsDevice.getAbsolutePath());

                // Estado de ejes analógicos y D-Pad
                int axisStickX = 0;
                int axisStickY = 0;
                int axisDpadX = 0;
                int axisDpadY = 0;
                boolean buttonA = false;
                boolean buttonB = false;
                boolean buttonX = false;
                boolean buttonY = false;
                boolean buttonLb = false;
                boolean buttonRb = false;
                boolean triggerRt = false;

                while (true) {
                    int readBytes = 0;
                    while (readBytes < 8) {
                        int r = fis.read(eventBuffer, readBytes, 8 - readBytes);
                        if (r < 0) {
                            throw new IOException("EOF en dispositivo joystick");
                        }
                        readBytes += r;
                    }

                    // Parsear js_event (8 bytes little endian)
                    // Bytes 0-3: time (ignorado)
                    // Bytes 4-5: value (short con signo)
                    short val = (short) ((eventBuffer[4] & 0xFF) | ((eventBuffer[5] & 0xFF) << 8));
                    // Byte 6: type (1 = button, 2 = axis, bit 0x80 = init)
                    int type = eventBuffer[6] & 0xFF;
                    int cleanType = type & ~0x80;
                    // Byte 7: number
                    int number = eventBuffer[7] & 0xFF;

                    if (cleanType == 1) {
                        // Botón presionado (1) o soltado (0)
                        boolean pressed = (val != 0);

                        switch (number) {
                            case 0 -> buttonA = pressed; // A / Cruz -> Salto
                            case 1 -> buttonB = pressed; // B / Círculo -> Correr
                            case 2 -> buttonX = pressed; // X / Cuadrado -> Correr
                            case 3 -> buttonY = pressed; // Y / Triángulo -> Salto
                            case 4 -> buttonLb = pressed; // LB / L1 -> Correr
                            case 5 -> buttonRb = pressed; // RB / R1 -> Correr
                            case 6 -> { // Back / Select / Share -> Reiniciar
                                if (pressed && restartCallback != null) {
                                    restartCallback.run();
                                }
                            }
                            case 7 -> { // Start / Options -> Pausar
                                if (pressed && pauseCallback != null) {
                                    pauseCallback.run();
                                }
                            }
                        }
                    } else if (cleanType == 2) {
                        // Eje analógico
                        switch (number) {
                            case 0 -> axisStickX = val; // Stick Izquierdo Horizontal
                            case 1 -> axisStickY = val; // Stick Izquierdo Vertical
                            case 2, 5 -> triggerRt = (val > 8000); // Gatillo RT/R2
                            case 6 -> axisDpadX = val; // D-Pad Horizontal (en muchos pads)
                            case 7 -> axisDpadY = val; // D-Pad Vertical (en muchos pads)
                        }
                    }

                    // Resolver estados combinados
                    padLeft = (axisStickX < -12000) || (axisDpadX < -1000);
                    padRight = (axisStickX > 12000) || (axisDpadX > 1000);
                    padDown = (axisStickY > 12000) || (axisDpadY > 1000);
                    padJump = buttonA || buttonY || (axisDpadY < -1000);
                    padSpeed = buttonB || buttonX || buttonLb || buttonRb || triggerRt;
                }
            } catch (Exception e) {
                // Desconexión o error al leer dispositivo
                resetInputs();
                connected.set(false);
                controllerName = "Ninguno";
                System.out.println("[GamepadManager] Mando desconectado o no disponible.");
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
    }

    private File findFirstJoystick() {
        for (int i = 0; i < 4; i++) {
            File f = new File("/dev/input/js" + i);
            if (f.exists() && f.canRead()) {
                return f;
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // 2. Puente UDP Multiplataforma (127.0.0.1:29555)
    // ─────────────────────────────────────────────────────────────

    private void runUdpBridgeLoop() {
        final int port = 29555;
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            byte[] buf = new byte[64];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);

            while (!Thread.currentThread().isInterrupted()) {
                socket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength()).trim();
                if (msg.isEmpty()) continue;

                // Formato: "L:1,R:0,D:0,J:1,S:0" o comandos especiales "P:1" (Pausa), "RST:1"
                parseUdpMessage(msg);
                connected.set(true);
                if (!controllerName.startsWith("Mando USB")) {
                    controllerName = "Puente de Control (UDP)";
                }
            }
        } catch (Exception ignored) {
            // Socket cerrado o puerto en uso
        }
    }

    private void parseUdpMessage(String msg) {
        String[] tokens = msg.split("[,;]");
        for (String token : tokens) {
            String[] kv = token.split("[:=]");
            if (kv.length != 2) continue;
            String key = kv[0].trim().toUpperCase();
            boolean val = "1".equals(kv[1].trim()) || "TRUE".equalsIgnoreCase(kv[1].trim());

            switch (key) {
                case "L", "LEFT" -> padLeft = val;
                case "R", "RIGHT" -> padRight = val;
                case "D", "DOWN" -> padDown = val;
                case "J", "JUMP" -> padJump = val;
                case "S", "SPEED" -> padSpeed = val;
                case "P", "PAUSE" -> {
                    if (val && pauseCallback != null) pauseCallback.run();
                }
                case "RST", "RESTART" -> {
                    if (val && restartCallback != null) restartCallback.run();
                }
            }
        }
    }

    private void resetInputs() {
        padLeft = false;
        padRight = false;
        padDown = false;
        padJump = false;
        padSpeed = false;
    }
}

