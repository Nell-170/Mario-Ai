package engine.input;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import engine.helper.MarioActions;

/**
 * GamepadManager — Soporte universal de mandos (USB / Bluetooth).
 * 
 * Capacidad dual:
 * 1. Linux Nativo: Lee directamente /dev/input/js* (joydev) sin librerías externas ni dependencias JNI.
 * 2. Bridge UDP (Multiplataforma Windows/Linux/macOS): Escucha paquetes en 127.0.0.1:29555
 *    enviados por puentes como gamepad_bridge.py o herramientas externas.
 * 
 * Mapeo configurable mediante src/gamepad.properties:
 * Por defecto configurado para PS4 y Xbox:
 * - Equis (X) / A  -> Saltar (JUMP)
 * - Cuadrado (□) / X / Círculo (O) / B -> Correr (SPEED)
 * - Options / Start -> Pausar (PAUSE)
 * - Share / Back   -> Reiniciar (RESTART)
 */
public class GamepadManager {
    public enum PadAction {
        NONE, JUMP, SPEED, PAUSE, RESTART
    }

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

    // Valores analógicos de los ejes para depuración
    private volatile int axisStickX = 0;
    private volatile int axisStickY = 0;
    private volatile int axisDpadX = 0;
    private volatile int axisDpadY = 0;

    // Callbacks para funciones especiales del juego
    private volatile Runnable pauseCallback = null;
    private volatile Runnable restartCallback = null;

    // Mapeo dinámico de botones (hasta 32 botones)
    private final PadAction[] buttonMapping = new PadAction[32];
    private final boolean[] buttonStates = new boolean[32];
    private final java.util.Set<Integer> ignoredDevicesLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private boolean started = false;

    private GamepadManager() {
        loadConfig();
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
     * Carga o recarga la configuración desde gamepad.properties.
     */
    public synchronized void loadConfig() {
        // 1. Valores por defecto (Cuadrado / X = Correr, Equis / A = Saltar)
        Arrays.fill(buttonMapping, PadAction.NONE);
        buttonMapping[0] = PadAction.JUMP;    // PS4 ✖ Equis / Xbox A
        buttonMapping[1] = PadAction.SPEED;   // PS4 ⭕ Círculo / Xbox B
        buttonMapping[2] = PadAction.SPEED;   // Xbox ⬛ X / PS4 Triángulo alternativo
        buttonMapping[3] = PadAction.SPEED;   // PS4 ⬛ Cuadrado (BTN_WEST) -> Correr
        buttonMapping[4] = PadAction.SPEED;   // L1 / LB
        buttonMapping[5] = PadAction.SPEED;   // R1 / RB
        buttonMapping[6] = PadAction.RESTART; // Xbox Back
        buttonMapping[7] = PadAction.PAUSE;   // Xbox Start
        buttonMapping[8] = PadAction.RESTART; // PS4 Share
        buttonMapping[9] = PadAction.PAUSE;   // PS4 Options
        buttonMapping[10] = PadAction.PAUSE;  // Botón PS Guía

        Path cfg = findConfigFile();
        if (cfg == null || !Files.exists(cfg)) {
            return;
        }

        try (InputStream in = Files.newInputStream(cfg)) {
            Properties props = new Properties();
            props.load(in);
            for (String key : props.stringPropertyNames()) {
                String k = key.trim().toLowerCase();
                if (k.startsWith("button.")) {
                    try {
                        int idx = Integer.parseInt(k.substring("button.".length()).trim());
                        if (idx >= 0 && idx < buttonMapping.length) {
                            String act = props.getProperty(key).trim().toUpperCase();
                            buttonMapping[idx] = PadAction.valueOf(act);
                        }
                    } catch (Exception ignored) {}
                }
            }
            System.out.println("[GamepadManager] Mapeo de control cargado desde: " + cfg);
        } catch (Exception e) {
            System.err.println("[GamepadManager] Advertencia al leer configuración: " + e.getMessage());
        }
    }

    private Path findConfigFile() {
        Path[] searchPaths = new Path[] {
            Paths.get("../src/gamepad.properties"),
            Paths.get("src/gamepad.properties"),
            Paths.get("gamepad.properties"),
            Paths.get("../gamepad.properties")
        };
        for (Path p : searchPaths) {
            if (Files.exists(p)) {
                return p;
            }
        }
        return searchPaths[0];
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
                String sysName = readSysfsDeviceName(parseDeviceIndex(jsDevice.getName()));
                controllerName = (sysName != null && !sysName.isEmpty()) ? sysName : ("Mando (" + jsDevice.getName() + ")");
                System.out.println("[GamepadManager] Mando conectado: " + jsDevice.getAbsolutePath() + " (" + controllerName + ")");

                axisStickX = 0;
                axisStickY = 0;
                axisDpadX = 0;
                axisDpadY = 0;
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
                    short val = (short) ((eventBuffer[4] & 0xFF) | ((eventBuffer[5] & 0xFF) << 8));
                    int type = eventBuffer[6] & 0xFF;
                    int cleanType = type & ~0x80;
                    int number = eventBuffer[7] & 0xFF;

                    if (cleanType == 1) {
                        // Botón presionado (1) o soltado (0)
                        boolean pressed = (val != 0);
                        if (number >= 0 && number < buttonStates.length) {
                            buttonStates[number] = pressed;
                        }

                        PadAction act = (number >= 0 && number < buttonMapping.length)
                                ? buttonMapping[number]
                                : PadAction.NONE;

                        if (pressed) {
                            if (act == PadAction.PAUSE && pauseCallback != null) {
                                pauseCallback.run();
                            } else if (act == PadAction.RESTART && restartCallback != null) {
                                restartCallback.run();
                            }
                        }
                    } else if (cleanType == 2) {
                        // Eje analógico
                        switch (number) {
                            case 0 -> axisStickX = val; // Stick Izquierdo Horizontal
                            case 1 -> axisStickY = val; // Stick Izquierdo Vertical
                            case 2, 5 -> triggerRt = (val > 8000); // Gatillo RT / R2
                            case 6 -> axisDpadX = val; // D-Pad Horizontal
                            case 7 -> axisDpadY = val; // D-Pad Vertical
                        }
                    }

                    // Resolver estados combinados
                    padLeft = (axisStickX < -12000) || (axisDpadX < -1000);
                    padRight = (axisStickX > 12000) || (axisDpadX > 1000);
                    padDown = (axisStickY > 12000) || (axisDpadY > 1000);

                    boolean jump = (axisDpadY < -1000);
                    boolean speed = triggerRt;

                    for (int i = 0; i < buttonStates.length; i++) {
                        if (buttonStates[i]) {
                            PadAction a = (i < buttonMapping.length) ? buttonMapping[i] : PadAction.NONE;
                            if (a == PadAction.JUMP) jump = true;
                            if (a == PadAction.SPEED) speed = true;
                        }
                    }

                    padJump = jump;
                    padSpeed = speed;
                }
            } catch (Exception e) {
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
        if (!isGamepadEnabled()) {
            return null;
        }
        for (int i = 0; i < 8; i++) {
            File f = new File("/dev/input/js" + i);
            if (f.exists() && f.canRead()) {
                String devName = readSysfsDeviceName(i);
                if (isIgnoredDevice(devName)) {
                    if (!ignoredDevicesLogged.contains(i)) {
                        ignoredDevicesLogged.add(i);
                        System.out.println("[GamepadManager] Dispositivo /dev/input/js" + i
                                + " ignorado (detectado ratón/virtual: \"" + devName + "\").");
                    }
                    continue;
                }
                return f;
            }
        }
        return null;
    }

    private int parseDeviceIndex(String name) {
        try {
            if (name.startsWith("js")) {
                return Integer.parseInt(name.substring(2));
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String readSysfsDeviceName(int index) {
        Path p = Paths.get("/sys/class/input/js" + index + "/device/name");
        if (Files.exists(p)) {
            try {
                return Files.readString(p).trim();
            } catch (Exception ignored) {}
        }
        return "";
    }

    private boolean isIgnoredDevice(String name) {
        if (name == null || name.isEmpty()) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("mouse") || lower.contains("mouce") ||
               lower.contains("touchpad") || lower.contains("trackpad") ||
               lower.contains("trackball") || lower.contains("keyboard") ||
               lower.contains("teclado") || lower.contains("accelerometer") ||
               lower.contains("acelerometro") || lower.contains("accel") ||
               lower.contains("sensor") || lower.contains("gyro") ||
               lower.contains("motion") || lower.contains("lis3lv02dl") ||
               lower.contains("headset") || lower.contains("audio");
    }

    private boolean isGamepadEnabled() {
        Path[] cfgPaths = new Path[] {
            Paths.get("../src/experiment/config.properties"),
            Paths.get("src/experiment/config.properties"),
            Paths.get("experiment/config.properties"),
            Paths.get("../src/gamepad.properties"),
            Paths.get("src/gamepad.properties")
        };
        for (Path p : cfgPaths) {
            if (Files.exists(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    Properties props = new Properties();
                    props.load(in);
                    String val = props.getProperty("enable_gamepad", props.getProperty("gamepad", null));
                    if (val != null) {
                        return Boolean.parseBoolean(val.trim());
                    }
                } catch (Exception ignored) {}
            }
        }
        return true;
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

                parseUdpMessage(msg);
                connected.set(true);
                if (!controllerName.startsWith("Mando")) {
                    controllerName = "Puente de Control (UDP)";
                }
            }
        } catch (Exception ignored) {
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
        axisStickX = 0;
        axisStickY = 0;
        axisDpadX = 0;
        axisDpadY = 0;
        Arrays.fill(buttonStates, false);
    }

    public int getAxisStickX() {
        return axisStickX;
    }

    public int getAxisStickY() {
        return axisStickY;
    }

    public int getAxisDpadX() {
        return axisDpadX;
    }

    public int getAxisDpadY() {
        return axisDpadY;
    }

    public boolean isPadLeft() {
        return padLeft;
    }

    public boolean isPadRight() {
        return padRight;
    }

    public boolean isPadDown() {
        return padDown;
    }

    public boolean isPadJump() {
        return padJump;
    }

    public boolean isPadSpeed() {
        return padSpeed;
    }

    public String getActiveButtonsString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < buttonStates.length; i++) {
            if (buttonStates[i]) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("B").append(i);
            }
        }
        return sb.toString();
    }
}
