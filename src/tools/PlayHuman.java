import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import engine.core.MarioGame;
import engine.core.MarioAgentEvent;
import engine.core.MarioEvent;
import engine.core.MarioResult;
import engine.helper.EventType;
import engine.helper.LevelTheme;

public class PlayHuman {
    public static void main(String[] args) throws Exception {
        boolean skipIntro = false;
        String rawLevel = null;
        int timer = 60;
        String customTelemetry = null;
        LevelTheme userTheme = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i].trim();
            if ("--no-intro".equalsIgnoreCase(arg) || "--skip-intro".equalsIgnoreCase(arg)) {
                skipIntro = true;
            } else if ("--theme".equalsIgnoreCase(arg) && i + 1 < args.length) {
                userTheme = LevelTheme.fromString(args[++i].trim());
            } else if ("--level".equalsIgnoreCase(arg) && i + 1 < args.length) {
                rawLevel = args[++i].trim();
            } else if ("--timer".equalsIgnoreCase(arg) && i + 1 < args.length) {
                try {
                    timer = Integer.parseInt(args[++i].trim());
                } catch (NumberFormatException ignored) {}
            } else if (rawLevel == null) {
                // If it's a pure number (like "60") and not a path, treat as timer!
                if (arg.matches("^\\d+$")) {
                    try {
                        timer = Integer.parseInt(arg);
                    } catch (NumberFormatException ignored) {}
                } else {
                    rawLevel = arg;
                }
            } else if (i == 1) {
                try {
                    timer = Integer.parseInt(arg);
                } catch (NumberFormatException ignored) {}
            } else if (i == 2) {
                customTelemetry = arg;
            }
        }

        Path resolvedPath = (rawLevel != null && !rawLevel.trim().isEmpty())
                ? resolveLevelPath(rawLevel)
                : chooseRandomLevelPath();

        if (!Files.exists(resolvedPath)) {
            System.err.println("ERROR: No se encontró el archivo de nivel: " + resolvedPath);
            return;
        }

        String levelPath = resolvedPath.toString();
        String level = Files.readString(resolvedPath);
        LevelTheme activeTheme = (userTheme != null) ? userTheme : LevelTheme.fromString(resolvedPath.getFileName().toString());

        if (!skipIntro) {
            boolean start = showIntroScreen(resolvedPath.getFileName().toString(), levelPath, level, activeTheme);
            if (!start) {
                System.out.println("Partida cancelada desde la pantalla de presentación del nivel.");
                return;
            }
        }

        int sessionSeed = new Random().nextInt(1_000_000);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String sessionFileName = "session_" + timestamp + "_seed" + sessionSeed + ".json";
        String telemetryPath = customTelemetry != null ? customTelemetry : "../src/telemetry/" + sessionFileName;

        System.out.println("Nivel:      " + levelPath);
        System.out.println("Seed:       " + sessionSeed);

        GameSession session = runSession(level, timer, sessionSeed, activeTheme);
        MarioResult result = session.result;

        System.out.println("Estado:     " + result.getGameStatus());
        System.out.println("Completado: " + (result.getCompletionPercentage() * 100) + "%");
        System.out.println("Muertes:    " + result.getKillsTotal());
        while (shouldRestart()) {
            session.close();
            sessionSeed = new Random().nextInt(1_000_000);
            session = runSession(level, timer, sessionSeed);
            result = session.result;
            System.out.println("Estado:     " + result.getGameStatus());
            System.out.println("Completado: " + (result.getCompletionPercentage() * 100) + "%");
            System.out.println("Muertes:    " + result.getKillsTotal());
        }
        session.close();
        System.out.println("Saltos:     " + result.getNumJumps());
        writeTelemetry(result, levelPath, timer, telemetryPath, sessionSeed);
        writeTelemetry(result, levelPath, timer, "../src/telemetry/latest.json", sessionSeed);
        writePointer(telemetryPath);
        System.out.println("Telemetria: " + telemetryPath);
    }

    private static Path resolveLevelPath(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return null;
        }
        rawPath = rawPath.trim();
        if ((rawPath.startsWith("\"") && rawPath.endsWith("\"")) ||
            (rawPath.startsWith("'") && rawPath.endsWith("'"))) {
            rawPath = rawPath.substring(1, rawPath.length() - 1);
        }
        Path p = Paths.get(rawPath);
        if (Files.exists(p)) {
            return p;
        }
        Path[] candidates = new Path[] {
            Paths.get("../src").resolve(rawPath),
            Paths.get("..").resolve(rawPath),
            Paths.get("../src/levels").resolve(rawPath),
            Paths.get("levels").resolve(rawPath),
            Paths.get("../levels").resolve(rawPath),
            Paths.get("src").resolve(rawPath)
        };
        for (Path c : candidates) {
            if (Files.exists(c)) {
                return c;
            }
        }
        // Also search in level subfolders if just a filename or relative name was given
        String[] subfolders = new String[] { "converted", "nivel0", "generated", "generics", "familiarization" };
        for (String sub : subfolders) {
            Path[] subCandidates = new Path[] {
                Paths.get("../src/levels").resolve(sub).resolve(rawPath),
                Paths.get("../levels").resolve(sub).resolve(rawPath),
                Paths.get("src/levels").resolve(sub).resolve(rawPath),
                Paths.get("levels").resolve(sub).resolve(rawPath)
            };
            for (Path sc : subCandidates) {
                if (Files.exists(sc)) {
                    return sc;
                }
            }
        }
        return p;
    }

    private static Path chooseRandomLevelPath() throws Exception {
        Path[] searchDirs = new Path[] {
            Paths.get("../src/levels/nivel0"),
            Paths.get("../levels/nivel0"),
            Paths.get("src/levels/nivel0"),
            Paths.get("levels/nivel0")
        };
        Path levelDirectory = null;
        for (Path dir : searchDirs) {
            if (Files.exists(dir)) {
                levelDirectory = dir;
                break;
            }
        }
        if (levelDirectory == null) {
            throw new IllegalStateException("No se encontró el directorio de niveles nivel0");
        }
        List<Path> levels;
        try (java.util.stream.Stream<Path> files = Files.list(levelDirectory)) {
            levels = files
                    .filter(path -> path.toString().endsWith(".txt"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        if (levels.isEmpty()) {
            throw new IllegalStateException("No se encontraron niveles en " + levelDirectory);
        }
        return levels.get(new Random().nextInt(levels.size()));
    }

    private static GameSession runSession(String level, int timer, int sessionSeed) {
        return runSession(level, timer, sessionSeed, LevelTheme.DAY);
    }

    private static GameSession runSession(String level, int timer, int sessionSeed, LevelTheme theme) {
        MarioGame game = new MarioGame();
        game.setTheme(theme != null ? theme : LevelTheme.DAY);
        MarioResult result = game.runGame(
                new agents.human.Agent(),
                level,
                timer,
                sessionSeed,
                true);
        return new GameSession(game, result);
    }

    private static class GameSession {
        private final MarioGame game;
        private final MarioResult result;

        private GameSession(MarioGame game, MarioResult result) {
            this.game = game;
            this.result = result;
        }

        private void close() {
            game.closeWindow();
        }
    }

    private static boolean shouldRestart() {
        int choice = JOptionPane.showOptionDialog(
                null,
                "La partida terminó. ¿Quieres reiniciar este nivel?",
                "Partida terminada",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                new Object[] {"Reiniciar nivel", "Continuar"},
                "Continuar");
        return choice == 0;
    }

    private static void writePointer(String telemetryPath) throws Exception {
        Path pointer = Paths.get("../src/telemetry/.last_session");
        if (pointer.getParent() != null) {
            Files.createDirectories(pointer.getParent());
        }
        String fileName = Paths.get(telemetryPath).getFileName().toString();
        Files.write(pointer, fileName.getBytes());
    }

    private static void writeTelemetry(
            MarioResult result, String levelPath, int timer, String telemetryPath, int sessionSeed)
            throws Exception {
        Path output = Paths.get(telemetryPath);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.write(output, buildTelemetryJson(result, levelPath, timer, sessionSeed).getBytes());
    }

    public static String buildTelemetryJson(
            MarioResult result, String levelPath, int timer, int sessionSeed) {
        List<String> deathPositions = new ArrayList<>();
        for (MarioEvent event : result.getGameEvents()) {
            if (event.getEventType() == EventType.HURT.getValue()) {
                deathPositions.add(String.format(Locale.US,
                        "{\"x\":%.1f,\"y\":%.1f}",
                        event.getMarioX(), event.getMarioY()));
            }
        }

        List<MarioAgentEvent> agentEvents = result.getAgentEvents();
        double averageSpeed = calculateAverageSpeed(agentEvents);
        double averageTileY = agentEvents.stream()
                .mapToDouble(event -> event.getMarioY() / 16.0)
                .average()
                .orElse(8.0);
        String pathPreference = averageTileY > 8.0 ? "low" : "high";
        double timeUsed = timer - result.getRemainingTime() / 1000.0;

        return "{\n"
                + "  \"level\": \"" + escapeJson(levelPath) + "\",\n"
                + "  \"seed\": " + sessionSeed + ",\n"
                + "  \"status\": \"" + result.getGameStatus() + "\",\n"
                + String.format(Locale.US, "  \"completion\": %.4f,%n", result.getCompletionPercentage())
                + String.format(Locale.US, "  \"time_used_seconds\": %.3f,%n", timeUsed)
                + String.format(Locale.US, "  \"jumps\": %d,%n", result.getNumJumps())
                + String.format(Locale.US, "  \"kills\": %d,%n", result.getKillsTotal())
                + String.format(Locale.US, "  \"hurts\": %d,%n", result.getMarioNumHurts())
                + String.format(Locale.US, "  \"coins\": %d,%n", result.getNumCollectedTileCoins())
                + String.format(Locale.US, "  \"average_speed_pixels_per_second\": %.3f,%n", averageSpeed)
                + String.format(Locale.US, "  \"average_path_tile_y\": %.3f,%n", averageTileY)
                + "  \"path_preference\": \"" + pathPreference + "\",\n"
                + "  \"death_positions\": [" + String.join(",", deathPositions) + "]\n"
                + "}\n";
    }

    private static double calculateAverageSpeed(List<MarioAgentEvent> events) {
        if (events.size() < 2) {
            return 0.0;
        }
        MarioAgentEvent previous = events.get(0);
        double distance = 0.0;
        int elapsedTicks = 0;
        for (int i = 1; i < events.size(); i++) {
            MarioAgentEvent current = events.get(i);
            distance += Math.abs(current.getMarioX() - previous.getMarioX());
            elapsedTicks += Math.max(0, current.getTime() - previous.getTime());
            previous = current;
        }
        return elapsedTicks == 0 ? 0.0 : distance / (elapsedTicks * 0.03);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==========================================
    // LEVEL INTRO SCREEN & 2D THUMBNAIL RENDERER
    // ==========================================

    private static boolean showIntroScreen(String levelName, String displayPath, String levelContent) {
        return showIntroScreen(levelName, displayPath, levelContent, LevelTheme.DAY);
    }

    private static boolean showIntroScreen(String levelName, String displayPath, String levelContent, LevelTheme theme) {
        if (GraphicsEnvironment.isHeadless()) {
            return true;
        }

        CompletableFuture<Boolean> proceedFuture = new CompletableFuture<>();

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Mario AI - Probador de Niveles");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setResizable(false);

            String[] rawLines = levelContent.split("\\r?\\n");
            int rows = 0;
            int cols = 0;
            for (String l : rawLines) {
                if (!l.trim().isEmpty() && !l.startsWith("//") && !l.startsWith(";")) {
                    rows++;
                    cols = Math.max(cols, l.length());
                }
            }

            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int frameW = Math.min(1240, Math.max(1040, screen.width - 60));
            int contentW = frameW - 48; // Padding 24px each side
            int diagramW = (contentW - 14) / 2;
            int diagramH = 225;

            BufferedImage thumb = renderLevelThumbnail(levelContent, contentW, 190, theme);

            JPanel mainPanel = new JPanel();
            mainPanel.setBackground(new Color(15, 23, 42)); // Slate 900
            mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
            mainPanel.setBorder(new EmptyBorder(18, 24, 18, 24));

            // Badge Row
            JPanel badgeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            badgeRow.setOpaque(false);
            badgeRow.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel badge = new JLabel("PROBADOR DE NIVELES");
            badge.setFont(new Font("SansSerif", Font.BOLD, 11));
            badge.setForeground(new Color(224, 231, 255));
            badge.setOpaque(true);
            badge.setBackground(new Color(79, 70, 229)); // Indigo 600
            badge.setBorder(new EmptyBorder(4, 12, 4, 12));
            badgeRow.add(badge);
            mainPanel.add(badgeRow);
            mainPanel.add(Box.createRigidArea(new Dimension(0, 8)));

            // Title
            JLabel titleLabel = new JLabel(levelName);
            titleLabel.setFont(new Font("SansSerif", Font.BOLD, 24));
            titleLabel.setForeground(Color.WHITE);
            titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            mainPanel.add(titleLabel);
            mainPanel.add(Box.createRigidArea(new Dimension(0, 3)));

            // Stats Subtitle
            JLabel statsLabel = new JLabel("Dimensiones: " + rows + " filas × " + cols + " columnas  |  " + displayPath);
            statsLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
            statsLabel.setForeground(new Color(148, 163, 184)); // Slate 400
            statsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            mainPanel.add(statsLabel);
            mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

            // Thumbnail Title
            JLabel thumbTitle = new JLabel("Silueta y vista previa del nivel (Miniatura ampliada):");
            thumbTitle.setFont(new Font("SansSerif", Font.BOLD, 13));
            thumbTitle.setForeground(new Color(165, 180, 252)); // Indigo 300
            thumbTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
            mainPanel.add(thumbTitle);
            mainPanel.add(Box.createRigidArea(new Dimension(0, 5)));

            // Thumbnail Image Card (190px tall)
            JLabel thumbLabel = new JLabel(new ImageIcon(thumb));
            thumbLabel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(99, 102, 241, 140), 1, true),
                    new EmptyBorder(3, 3, 3, 3)));
            thumbLabel.setBackground(new Color(30, 41, 59));
            thumbLabel.setOpaque(true);
            thumbLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            mainPanel.add(thumbLabel);
            mainPanel.add(Box.createRigidArea(new Dimension(0, 12)));

            // Dual Control Diagrams (Gamepad on left, Keyboard on right)
            JPanel diagramsPanel = new JPanel(new GridLayout(1, 2, 14, 0));
            diagramsPanel.setOpaque(false);
            diagramsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            diagramsPanel.setMaximumSize(new Dimension(contentW, diagramH));

            JLabel padDiagram = new JLabel(new ImageIcon(renderControllerDiagram(diagramW, diagramH)));
            JLabel kbDiagram = new JLabel(new ImageIcon(renderKeyboardDiagram(diagramW, diagramH)));
            diagramsPanel.add(padDiagram);
            diagramsPanel.add(kbDiagram);
            mainPanel.add(diagramsPanel);
            mainPanel.add(Box.createRigidArea(new Dimension(0, 14)));

            // Buttons Bar
            JPanel buttonsBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
            buttonsBar.setOpaque(false);
            buttonsBar.setAlignmentX(Component.LEFT_ALIGNMENT);
            buttonsBar.setMaximumSize(new Dimension(contentW, 46));

            IntroButton cancelBtn = new IntroButton("Cancelar (Esc)", new Color(51, 65, 85), new Color(71, 85, 105), new Color(226, 232, 240), 13);
            IntroButton startBtn = new IntroButton("¡Comenzar partida! (Espacio / Enter)", new Color(99, 102, 241), new Color(79, 70, 229), Color.WHITE, 14);

            cancelBtn.addActionListener(e -> {
                frame.dispose();
                proceedFuture.complete(false);
            });

            startBtn.addActionListener(e -> {
                frame.dispose();
                proceedFuture.complete(true);
            });

            KeyAdapter keyAdapter = new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) {
                        frame.dispose();
                        proceedFuture.complete(true);
                    } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        frame.dispose();
                        proceedFuture.complete(false);
                    }
                }
            };

            frame.addKeyListener(keyAdapter);
            startBtn.addKeyListener(keyAdapter);
            cancelBtn.addKeyListener(keyAdapter);

            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    proceedFuture.complete(false);
                }
            });

            buttonsBar.add(cancelBtn);
            buttonsBar.add(startBtn);
            mainPanel.add(buttonsBar);

            frame.setContentPane(mainPanel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            startBtn.requestFocusInWindow();
        });

        try {
            return proceedFuture.get();
        } catch (Exception e) {
            return false;
        }
    }

    private static class IntroButton extends JButton {
        private final Color normalColor;
        private final Color hoverColor;

        IntroButton(String text, Color bg, Color hover, Color fg, int fontSize) {
            super(text);
            this.normalColor = bg;
            this.hoverColor = hover;
            setBackground(bg);
            setForeground(fg);
            setFont(new Font("SansSerif", Font.BOLD, fontSize));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(10, 22, 10, 22));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (getModel().isPressed()) {
                g2.setColor(hoverColor.darker());
            } else if (getModel().isRollover() || isFocusOwner()) {
                g2.setColor(hoverColor);
            } else {
                g2.setColor(normalColor);
            }
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Control Diagrams (Gamepad and Keyboard visual schematics)
    // ─────────────────────────────────────────────────────────────

    public static BufferedImage renderControllerDiagram(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Background container
        g2.setColor(new Color(30, 41, 59)); // Slate 800
        g2.fillRoundRect(0, 0, w, h, 14, 14);
        g2.setColor(new Color(51, 65, 85)); // Slate 700 border
        g2.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);

        // Header Title
        g2.setFont(new Font("SansSerif", Font.BOLD, 13));
        g2.setColor(new Color(165, 180, 252)); // Indigo 300
        g2.drawString("🎮 CONTROL / MANDO (PS4 / XBOX)", 16, 22);

        int cx = w / 2;
        int cy = h / 2 + 14;

        // Gamepad Silhouette (Expanded & Proportional)
        g2.setColor(new Color(51, 65, 85));
        g2.fillRoundRect(cx - 135, cy - 42, 270, 84, 40, 40);
        g2.fillRoundRect(cx - 150, cy - 25, 60, 95, 35, 35); // Left grip
        g2.fillRoundRect(cx + 90, cy - 25, 60, 95, 35, 35);  // Right grip
        g2.setColor(new Color(71, 85, 105));
        g2.drawRoundRect(cx - 135, cy - 42, 270, 84, 40, 40);

        // Bumpers (L1 / R1)
        g2.setColor(new Color(100, 116, 139));
        g2.fillRoundRect(cx - 118, cy - 54, 55, 12, 6, 6);
        g2.fillRoundRect(cx + 63, cy - 54, 55, 12, 6, 6);
        g2.setColor(new Color(148, 163, 184));
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        g2.drawString("L1 (Correr)", cx - 120, cy - 58);
        g2.drawString("R1 (Correr)", cx + 65, cy - 58);

        // Center Touchpad / Logo area
        g2.setColor(new Color(15, 23, 42));
        g2.fillRoundRect(cx - 36, cy - 36, 72, 40, 10, 10);
        g2.setColor(new Color(71, 85, 105));
        g2.drawRoundRect(cx - 36, cy - 36, 72, 40, 10, 10);

        // Share / Options buttons
        g2.setColor(new Color(203, 213, 225));
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        g2.fillOval(cx - 48, cy - 18, 8, 8);
        g2.drawString("Share: R", cx - 94, cy - 24);
        g2.fillOval(cx + 40, cy - 18, 8, 8);
        g2.drawString("Options: P", cx + 38, cy - 24);

        // D-Pad (Left side)
        int dpx = cx - 95;
        int dpy = cy + 4;
        g2.setColor(new Color(15, 23, 42));
        g2.fillRect(dpx - 7, dpy - 20, 14, 40);
        g2.fillRect(dpx - 20, dpy - 7, 40, 14);
        g2.setColor(new Color(56, 189, 248)); // Cyan highlight
        g2.drawRect(dpx - 7, dpy - 20, 14, 40);
        g2.drawRect(dpx - 20, dpy - 7, 40, 14);

        // D-Pad Callout: Moverse
        drawCalloutBadge(g2, "D-Pad: Moverse", cx - 145, cy + 54, new Color(56, 189, 248), Color.BLACK);

        // Action Buttons (Right side - Diamond layout)
        int abx = cx + 95;
        int aby = cy + 4;

        // Bottom button: ✖ Equis (Cross) -> SALTAR
        int crossX = abx;
        int crossY = aby + 18;
        g2.setColor(new Color(34, 197, 94)); // Green
        g2.fillOval(crossX - 11, crossY - 11, 22, 22);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("SansSerif", Font.BOLD, 12));
        g2.drawString("✖", crossX - 5, crossY + 5);

        // Left button: ⬛ Cuadrado (Square) -> CORRER
        int sqX = abx - 18;
        int sqY = aby - 1;
        g2.setColor(new Color(249, 115, 22)); // Orange
        g2.fillOval(sqX - 11, sqY - 11, 22, 22);
        g2.setColor(Color.WHITE);
        g2.drawString("■", sqX - 5, sqY + 5);

        // Right button: ⭕ Círculo (Circle) -> Correr
        int circX = abx + 18;
        int circY = aby - 1;
        g2.setColor(new Color(249, 115, 22, 190));
        g2.fillOval(circX - 11, circY - 11, 22, 22);
        g2.setColor(Color.WHITE);
        g2.drawString("●", circX - 5, circY + 5);

        // Top button: 🔺 Triángulo (Triangle)
        int triX = abx;
        int triY = aby - 20;
        g2.setColor(new Color(100, 116, 139));
        g2.fillOval(triX - 11, triY - 11, 22, 22);
        g2.setColor(Color.WHITE);
        g2.drawString("▲", triX - 5, triY + 5);

        // Callout Square: Correr
        drawCalloutBadge(g2, "■ Cuadrado: Correr", abx - 45, cy - 36, new Color(249, 115, 22), Color.WHITE);

        // Callout Cross: Saltar
        drawCalloutBadge(g2, "✖ Equis: Saltar", abx + 15, cy + 54, new Color(34, 197, 94), Color.WHITE);

        g2.dispose();
        return img;
    }

    public static BufferedImage renderKeyboardDiagram(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Background container
        g2.setColor(new Color(30, 41, 59)); // Slate 800
        g2.fillRoundRect(0, 0, w, h, 14, 14);
        g2.setColor(new Color(51, 65, 85)); // Slate 700 border
        g2.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);

        // Header Title
        g2.setFont(new Font("SansSerif", Font.BOLD, 13));
        g2.setColor(new Color(165, 180, 252)); // Indigo 300
        g2.drawString("⌨️ TECLADO (ERCILLER & FLECHAS)", 16, 22);

        // Top right: P (Pausa) & R (Reiniciar)
        drawKeycap(g2, "P", w - 48, 14, 26, 22, new Color(56, 189, 248), Color.WHITE);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g2.setColor(new Color(203, 213, 225));
        g2.drawString("Pausa", w - 88, 29);

        drawKeycap(g2, "R", w - 120, 14, 26, 22, new Color(244, 63, 94), Color.WHITE);
        g2.drawString("Reiniciar", w - 175, 29);

        // Left Side: Action Keys
        int startX = 20;
        int startY = 46;

        // Callouts top
        drawCalloutBadge(g2, "D / Shift / A: Correr", startX, startY, new Color(249, 115, 22), Color.WHITE);
        drawCalloutBadge(g2, "F / Espacio / S: Saltar", startX + 145, startY, new Color(34, 197, 94), Color.WHITE);

        // Keycaps row
        drawKeycap(g2, "Shift", startX, startY + 26, 56, 32, new Color(249, 115, 22), Color.WHITE);
        drawKeycap(g2, "A", startX + 62, startY + 26, 32, 32, new Color(100, 116, 139), Color.WHITE);
        drawKeycap(g2, "S", startX + 98, startY + 26, 32, 32, new Color(100, 116, 139), Color.WHITE);
        drawKeycap(g2, "D", startX + 134, startY + 26, 34, 32, new Color(249, 115, 22), Color.WHITE);
        drawKeycap(g2, "F", startX + 172, startY + 26, 34, 32, new Color(34, 197, 94), Color.WHITE);

        // Spacebar keycap
        drawKeycap(g2, "Barra Espaciadora", startX + 22, startY + 66, 184, 32, new Color(34, 197, 94), Color.WHITE);

        // Right Side: Arrow Keys [▲] [◀] [▼] [▶]
        int arrowX = w - 170;
        int arrowY = 52;

        drawKeycap(g2, "▲", arrowX + 42, arrowY, 36, 32, new Color(56, 189, 248), Color.BLACK);
        drawKeycap(g2, "◀", arrowX, arrowY + 36, 36, 32, new Color(56, 189, 248), Color.BLACK);
        drawKeycap(g2, "▼", arrowX + 42, arrowY + 36, 36, 32, new Color(56, 189, 248), Color.BLACK);
        drawKeycap(g2, "▶", arrowX + 84, arrowY + 36, 36, 32, new Color(56, 189, 248), Color.BLACK);

        drawCalloutBadge(g2, "Flechas: Moverse / Agacharse", arrowX - 16, arrowY + 76, new Color(56, 189, 248), Color.BLACK);

        g2.dispose();
        return img;
    }

    private static void drawKeycap(Graphics2D g2, String text, int x, int y, int kw, int kh, Color accent, Color textColor) {
        g2.setColor(new Color(15, 23, 42, 160));
        g2.fillRoundRect(x, y + 2, kw, kh, 6, 6);
        g2.setColor(new Color(51, 65, 85));
        g2.fillRoundRect(x, y, kw, kh, 6, 6);
        g2.setColor(accent);
        g2.drawRoundRect(x, y, kw - 1, kh - 1, 6, 6);
        g2.setColor(textColor);
        g2.setFont(new Font("SansSerif", Font.BOLD, text.length() > 3 ? 10 : 12));
        FontMetrics fm = g2.getFontMetrics();
        int tx = x + (kw - fm.stringWidth(text)) / 2;
        int ty = y + (kh + fm.getAscent() - fm.getDescent()) / 2;
        g2.drawString(text, tx, ty);
    }

    private static void drawCalloutBadge(Graphics2D g2, String label, int x, int y, Color bg, Color fg) {
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        FontMetrics fm = g2.getFontMetrics();
        int bw = fm.stringWidth(label) + 12;
        int bh = 18;
        g2.setColor(bg);
        g2.fillRoundRect(x, y, bw, bh, 8, 8);
        g2.setColor(fg);
        g2.drawString(label, x + 6, y + 13);
    }

    private static BufferedImage renderLevelThumbnail(String content, int targetWidth, int targetHeight) {
        return renderLevelThumbnail(content, targetWidth, targetHeight, LevelTheme.DAY);
    }

    private static BufferedImage renderLevelThumbnail(String content, int targetWidth, int targetHeight, LevelTheme theme) {
        if (theme == null) theme = LevelTheme.DAY;
        String[] rawLines = content.split("\\r?\\n");
        List<String> lines = new ArrayList<>();
        for (String l : rawLines) {
            if (!l.trim().isEmpty() && !l.startsWith("//") && !l.startsWith(";")) {
                lines.add(l);
            }
        }
        if (lines.isEmpty()) {
            BufferedImage blank = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = blank.createGraphics();
            theme.paintBackground(g, targetWidth, targetHeight);
            g.dispose();
            return blank;
        }

        int rows = lines.size();
        int cols = 0;
        for (String l : lines) {
            cols = Math.max(cols, l.length());
        }
        if (cols == 0) cols = 1;

        BufferedImage img = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Sky background matching theme
        theme.paintBackground(g2, targetWidth, targetHeight);

        for (int r = 0; r < rows; r++) {
            String line = lines.get(r);
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '-' || Character.isWhitespace(ch)) {
                    continue;
                }
                int x0 = (int) Math.round((double) c * targetWidth / cols);
                int x1 = (int) Math.round((double) (c + 1) * targetWidth / cols);
                int w = Math.max(1, x1 - x0);
                int y0 = (int) Math.round((double) r * targetHeight / rows);
                int y1 = (int) Math.round((double) (r + 1) * targetHeight / rows);
                int h = Math.max(1, y1 - y0);

                drawTileOnGraphics(g2, ch, x0, y0, w, h);
            }
        }
        g2.dispose();
        return img;
    }

    private static void drawTileOnGraphics(Graphics2D g, char ch, int x, int y, int w, int h) {
        switch (ch) {
            case 'X', 'x' -> { // Solid Ground
                g.setColor(new Color(216, 104, 0));
                g.fillRect(x, y, w, h);
                g.setColor(new Color(0, 168, 0));
                int grassH = Math.max(1, Math.min(3, h / 3));
                g.fillRect(x, y, w, grassH);
                if (w > 2 && h > 2) {
                    g.setColor(new Color(108, 52, 0));
                    g.drawRect(x, y, w - 1, h - 1);
                }
            }
            case '#' -> { // Solid Platform / Block
                g.setColor(new Color(252, 152, 56));
                g.fillRect(x, y, w, h);
                if (w > 4 && h > 4) {
                    g.setColor(new Color(108, 52, 0));
                    g.fillRect(x + 1, y + 1, w - 2, h - 2);
                }
            }
            case 'S' -> { // Breakable Brick
                g.setColor(new Color(184, 68, 24));
                g.fillRect(x, y, w, h);
                g.setColor(new Color(20, 20, 20));
                if (w > 2 && h > 2) {
                    g.drawRect(x, y, w - 1, h - 1);
                    if (h > 4) {
                        g.drawLine(x, y + h / 2, x + w - 1, y + h / 2);
                    }
                }
            }
            case '?' -> { // Question Block
                g.setColor(new Color(252, 176, 64));
                g.fillRect(x, y, w, h);
                g.setColor(new Color(108, 52, 0));
                if (w > 2 && h > 2) {
                    g.drawRect(x, y, w - 1, h - 1);
                }
                if (w >= 10 && h >= 10) {
                    g.setFont(new Font("SansSerif", Font.BOLD, Math.max(8, h - 4)));
                    FontMetrics fm = g.getFontMetrics();
                    int tx = x + (w - fm.stringWidth("?")) / 2;
                    int ty = y + (h + fm.getAscent() - fm.getDescent()) / 2;
                    g.drawString("?", tx, ty);
                }
            }
            case 'o' -> { // Coin
                g.setColor(new Color(252, 228, 64));
                int cw = Math.max(2, (int) (w * 0.6));
                int chH = Math.max(2, (int) (h * 0.75));
                int cx = x + (w - cw) / 2;
                int cy = y + (h - chH) / 2;
                g.fillOval(cx, cy, cw, chH);
                g.setColor(new Color(216, 144, 0));
                g.drawOval(cx, cy, cw, chH);
            }
            case '<', '>' -> { // Pipe Top
                g.setColor(new Color(0, 168, 0));
                g.fillRect(x, y, w, h);
                g.setColor(new Color(128, 208, 16));
                g.fillRect(x + 1, y + 1, Math.max(1, w / 4), Math.max(1, h - 2));
                g.setColor(new Color(0, 80, 0));
                g.drawRect(x, y, w - 1, h - 1);
            }
            case '[', ']' -> { // Pipe Body
                g.setColor(new Color(0, 168, 0));
                g.fillRect(x, y, w, h);
                g.setColor(new Color(128, 208, 16));
                g.fillRect(x + 1, y, Math.max(1, w / 4), h);
                g.setColor(new Color(0, 80, 0));
                g.drawRect(x, y, w - 1, h - 1);
            }
            case 'g' -> { // Goomba
                g.setColor(new Color(168, 64, 0));
                g.fillOval(x, y, w, h);
                if (w > 4 && h > 4) {
                    g.setColor(new Color(252, 228, 160));
                    g.fillRect(x + w / 4, y + h / 2, w / 2, h / 3);
                }
            }
            case 'k' -> { // Koopa
                g.setColor(new Color(0, 168, 0));
                g.fillOval(x, y, w, h);
                g.setColor(new Color(0, 60, 0));
                g.drawOval(x, y, w - 1, h - 1);
            }
            case 'y' -> { // Spiny
                g.setColor(new Color(252, 32, 0));
                g.fillOval(x, y, w, h);
            }
            case 'E' -> { // Generic Enemy
                g.setColor(new Color(225, 29, 72));
                g.fillOval(x, y, w, h);
                g.setColor(Color.WHITE);
                if (w > 4 && h > 4) {
                    g.fillRect(x + w / 4, y + h / 4, Math.max(1, w / 4), Math.max(1, h / 4));
                }
            }
            case 'B', 'b' -> { // Bullet Blaster
                g.setColor(new Color(40, 40, 40));
                g.fillRect(x, y, w, h);
                g.setColor(new Color(70, 70, 70));
                g.fillRect(x + 1, y + 1, w - 2, Math.max(1, h / 4));
            }
            case 'M' -> { // Mario
                g.setColor(new Color(239, 68, 68));
                g.fillRect(x, y, w, h);
            }
            case 'F' -> { // Flag
                g.setColor(new Color(34, 197, 94));
                g.fillRect(x, y, w, h);
            }
            default -> {
                if (!Character.isWhitespace(ch) && ch != '-') {
                    g.setColor(new Color(200, 200, 200));
                    g.fillRect(x, y, w, h);
                }
            }
        }
    }
}
