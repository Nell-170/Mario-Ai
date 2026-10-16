import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;

import engine.core.MarioGame;
import engine.core.MarioResult;

public class ExperimentRunner {
    private static final Path SRC = Paths.get("../src");
    private static final Path EXPERIMENT = SRC.resolve("experiment");
    private static final Path RESULTS = EXPERIMENT.resolve("results");

    // Williams 6x6 Latin Square. Letters: A = Personalizado (P), B = Generico (N),
    // C = Humano (H); index 1 or 2.
    private static final Map<String, String> SEQUENCES = new LinkedHashMap<>();
    private static final String[] GROUPS;

    static {
        SEQUENCES.put("S1", "A1 C2 A2 C1 B1 B2");
        SEQUENCES.put("S2", "A2 A1 B1 C2 B2 C1");
        SEQUENCES.put("S3", "B1 A2 B2 A1 C1 C2");
        SEQUENCES.put("S4", "B2 B1 C1 A2 C2 A1");
        SEQUENCES.put("S5", "C1 B2 C2 B1 A1 A2");
        SEQUENCES.put("S6", "C2 C1 A1 B2 A2 B1");
        GROUPS = SEQUENCES.keySet().toArray(new String[0]);
    }

    private record Step(char type, int index, Path level) {
    }

    private record Question(String id, String block, String type, String text, String options) {
    }

    private record PlayedLevelInfo(
            int orderPlayed,
            char type,
            int levelIndex,
            String stepCode,
            Path levelFile,
            String levelContent,
            BufferedImage thumbnail
    ) {
    }

    public enum GenStage {
        PENDING, QUEUED, OLLAMA, MARIOGPT, SANITIZING, READY, FALLBACK_READY, FAILED
    }

    // Fonts & Theme
    private static Font FONT_REGULAR;
    private static Font FONT_BOLD;
    private static Font FONT_SEMIBOLD;

    private static final Color BG_DARK = new Color(11, 12, 16);
    private static final Color CARD_BG = new Color(20, 22, 31);
    private static final Color CARD_BORDER = new Color(38, 42, 59);
    private static final Color ITEM_BG = new Color(27, 30, 42);
    private static final Color ITEM_BORDER = new Color(46, 51, 71);
    private static final Color ACCENT_PRIMARY = new Color(79, 70, 229);
    private static final Color ACCENT_HOVER = new Color(99, 102, 241);
    private static final Color ACCENT_PRESSED = new Color(67, 56, 202);
    private static final Color TEXT_WHITE = new Color(248, 250, 252);
    private static final Color TEXT_MUTED = new Color(148, 163, 184);
    private static final Color TEXT_ACCENT = new Color(165, 180, 252);
    private static final Color DANGER_BG = new Color(136, 19, 55);
    private static final Color DANGER_HOVER = new Color(159, 18, 57);

    private static JFrame masterFrame = null;
    private static GenerationJob activeGenerationJob = null;
    private static MarioGame activeGame = null;
    private static boolean developMode = false;
    private static String currentDevBanner = "";
    private static JPanel currentDevCardPanel = null;
    private static int personalizedPlayedCount = 0;
    private static boolean devSidePanelCollapsed = false;

    private static boolean shouldShowDevGenSidePanel() {
        return developMode
                && activeGenerationJob != null
                && activeGenerationJob.isStarted()
                && personalizedPlayedCount < 2;
    }

    private static void initFonts() {
        try {
            Path regPath = Paths.get("../src/experiment/fonts/Montserrat-Regular.ttf");
            if (!Files.exists(regPath))
                regPath = Paths.get("experiment/fonts/Montserrat-Regular.ttf");
            if (!Files.exists(regPath))
                regPath = Paths.get("src/experiment/fonts/Montserrat-Regular.ttf");
            Path boldPath = Paths.get("../src/experiment/fonts/Montserrat-Bold.ttf");
            if (!Files.exists(boldPath))
                boldPath = Paths.get("experiment/fonts/Montserrat-Bold.ttf");
            if (!Files.exists(boldPath))
                boldPath = Paths.get("src/experiment/fonts/Montserrat-Bold.ttf");
            Path semiPath = Paths.get("../src/experiment/fonts/Montserrat-SemiBold.ttf");
            if (!Files.exists(semiPath))
                semiPath = Paths.get("experiment/fonts/Montserrat-SemiBold.ttf");
            if (!Files.exists(semiPath))
                semiPath = Paths.get("src/experiment/fonts/Montserrat-SemiBold.ttf");

            if (Files.exists(regPath)) {
                FONT_REGULAR = Font.createFont(Font.TRUETYPE_FONT, regPath.toFile());
            } else {
                FONT_REGULAR = new Font("SansSerif", Font.PLAIN, 14);
            }
            if (Files.exists(boldPath)) {
                FONT_BOLD = Font.createFont(Font.TRUETYPE_FONT, boldPath.toFile());
            } else {
                FONT_BOLD = FONT_REGULAR.deriveFont(Font.BOLD);
            }
            if (Files.exists(semiPath)) {
                FONT_SEMIBOLD = Font.createFont(Font.TRUETYPE_FONT, semiPath.toFile());
            } else {
                FONT_SEMIBOLD = FONT_BOLD;
            }
        } catch (Exception e) {
            FONT_REGULAR = new Font("SansSerif", Font.PLAIN, 14);
            FONT_BOLD = new Font("SansSerif", Font.BOLD, 14);
            FONT_SEMIBOLD = FONT_BOLD;
        }
    }

    public static void main(String[] args) throws Exception {
        initFonts();
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try {
            engine.helper.Assets.init(null);
        } catch (Throwable ignored) {
        }

        String python = args.length > 0 ? args[0] : "python";
        boolean skipGeneration = false;
        for (String a : args) {
            if ("--skip-generation".equalsIgnoreCase(a)) {
                skipGeneration = true;
            } else if ("--dev".equalsIgnoreCase(a) || "--develop".equalsIgnoreCase(a)) {
                developMode = true;
            } else if ("--no-dev".equalsIgnoreCase(a)) {
                developMode = false;
            }
        }
        Properties config = loadConfig();
        int timer = Integer.parseInt(config.getProperty("timer", "60").trim());
        if (config.getProperty("develop") != null) {
            developMode = Boolean.parseBoolean(config.getProperty("develop").trim());
        }
        for (String a : args) {
            if ("--dev".equalsIgnoreCase(a) || "--develop".equalsIgnoreCase(a)) {
                developMode = true;
            } else if ("--no-dev".equalsIgnoreCase(a)) {
                developMode = false;
            }
        }

        Path famDir = resolveDirectory(config, "familiarization_dir", "familiarization", "levels/familiarization");
        List<Path> familiarization = randomLevelsFromDir(famDir, 2, null);

        Path genDir = resolveDirectory(config, "generics_dir", "normal", "levels/generics");
        List<Path> normal = randomLevelsFromDir(genDir, 2, null);

        List<Path> used = new ArrayList<>(familiarization);
        used.addAll(normal);
        List<Path> drawn = randomHumanLevels(config, used, skipGeneration ? 4 : 2);
        List<Path> human = drawn.subList(0, 2);
        List<Path> substitutes = drawn.subList(2, drawn.size());

        String group = assignGroup();
        String id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_" + group;
        Path dir = RESULTS.resolve(id);
        Files.createDirectories(dir);

        List<Step> steps = buildSteps(group, normal, human);
        Files.writeString(dir.resolve("participant.txt"),
                "id=" + id + "\ngroup=" + group + "\nsteps="
                        + steps.stream().map(s -> String.valueOf(s.type())).collect(Collectors.joining(","))
                        + "\nfamiliarization=" + familiarization.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(","))
                        + "\ngenerics=" + normal.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(","))
                        + "\nhuman=" + human.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(","))
                        + "\n");
        System.out.println("Participante: " + id + " (secuencia " + group + ")");
        System.out.println("Niveles de familiarización (aleatorios): "
                + familiarization.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", ")));
        System.out.println("Niveles no personalizados / genéricos (aleatorios): "
                + normal.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", ")));
        System.out.println("Niveles humanos (aleatorios de nivel0): "
                + human.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", ")));
        System.out.println("Modo Desarrollo (develop): "
                + (developMode ? "ACTIVADO (develop=true: visualizando tipo de nivel, distribución de Cuadrado Latino e info en pantalla)"
                               : "DESACTIVADO (develop=false: modo ciego para experimento formal)"));

        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        masterFrame = new JFrame("Mario AI");
        masterFrame.setUndecorated(true);
        masterFrame.getContentPane().setBackground(BG_DARK);

        if (gd.isFullScreenSupported()) {
            gd.setFullScreenWindow(masterFrame);
        } else {
            masterFrame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            masterFrame.setBounds(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
            masterFrame.setVisible(true);
        }

        GenerationJob generation = new GenerationJob(python, dir);
        activeGenerationJob = generation;
        personalizedPlayedCount = 0;
        devSidePanelCollapsed = false;
        MarioGame.setEmergencyExitHandler(() -> confirmAndExitExperiment());
        try {
            setDevContext(
                    "ID: " + id + " | Cuadrado Latino: " + group + " (" + SEQUENCES.get(group) + ")",
                    "Bienvenida / Instrucciones",
                    "N/A",
                    "Grupo " + group + " (" + SEQUENCES.get(group) + ")",
                    id,
                    timer
            );

            // 1. Welcome & Instructions Screen
            showWelcomeScreen();

            // 2. Familiarization Levels
            List<Path> famTelemetries = new ArrayList<>();
            for (int f = 0; f < familiarization.size(); f++) {
                Path famLevel = familiarization.get(f);
                setDevContext(
                        "Familiarización " + (f + 1) + "/" + familiarization.size() + " | Archivo: " + famLevel.getFileName() + " | Grupo asignado: " + group,
                        "Familiarización (Práctica previa y captura basal de telemetría)",
                        famLevel.getFileName().toString(),
                        "Fase preliminar (Calibra telemetría para Personalizado " + (f + 1) + ")",
                        id,
                        timer
                );
                showLevelIntroScreen("Familiarización (" + (f + 1) + " de " + familiarization.size() + ")",
                        "Nivel de preparación " + (f + 1),
                        "Jugarás este nivel previo para familiarizarte con el salto, velocidad y los controles.\n"
                                + "Controles: Flechas = Moverse | Flecha Arriba / S / Espacio = Saltar | A / Shift = Correr | R = Reiniciar | Q = Terminar",
                        "Comenzar nivel " + (f + 1));
                Path telPath = playLevel(famLevel, timer,
                        dir.resolve("telemetry_fam" + (f + 1) + ".json"));
                famTelemetries.add(telPath);
                if (!skipGeneration) {
                    generation.enqueue(f, telPath);
                }
            }

            if (skipGeneration) {
                System.out.println(
                        "Modo prueba: se omite la generación; los niveles personalizados se sustituyen por niveles distintos de nivel0.");
            } else {
                generation.start(famTelemetries);
            }

            setDevContext(
                    "ID: " + id + " | Consentimiento y Encuesta Inicial | Grupo " + group,
                    "Consentimiento Informado",
                    "N/A",
                    "Grupo " + group + " (" + SEQUENCES.get(group) + ")",
                    id,
                    timer
            );

            // 3. Informed Consent
            if (!showConsentScreen()) {
                generation.cancel();
                deleteRecursively(dir);
                if (showChoiceScreen("Consentimiento no aceptado",
                        "No has aceptado el consentimiento para registrar datos de la partida.\n\n"
                                + "¿Deseas jugar de todos modos libremente para divertirte?",
                        "Sí, jugar libremente", "No, salir del juego")) {
                    freePlay(config, normal, null, timer);
                }
                showExitScreen("¡Gracias!", "La sesión de juego ha finalizado.");
                System.exit(0);
            }

            // 4. Initial Survey
            List<Question> questions = loadQuestions();
            List<Question> initial = questionsOf(questions, "initial");
            List<Question> perLevel = questionsOf(questions, "level");

            List<String> initialAnswers = new ArrayList<>(List.of(id, group));
            initialAnswers.addAll(Arrays.asList(askScreen("Preguntas iniciales",
                    "Por favor responde las siguientes preguntas sobre tu experiencia con videojuegos antes de comenzar.",
                    initial)));
            saveRow(RESULTS.resolve("initial.tsv"), concat(List.of("id", "group"), ids(initial)), initialAnswers);

            // 5. Experiment Levels Loop
            List<PlayedLevelInfo> playedLevels = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                String stepCode = SEQUENCES.get(group).split(" ")[i];
                String typeName = describeLevelType(step.type());
                String seqProgress = formatSequenceProgress(group, i);

                String previewFileName = (step.type() != 'P')
                        ? step.level().getFileName().toString()
                        : (skipGeneration ? substitutes.get(step.index()).getFileName().toString()
                                          : "Nivel generado " + (step.index() + 1) + ".txt (MarioGPT)");

                setDevContext(
                        "Cuadrado Latino " + group + " | Paso " + (i + 1) + "/6: " + stepCode + " [" + typeName + "] | " + previewFileName,
                        typeName,
                        previewFileName,
                        "Grupo " + group + " : " + seqProgress + "  (A=Personalizado, B=Genérico, C=Humano)",
                        id,
                        timer
                );

                showLevelIntroScreen("Nivel " + (i + 1) + " de " + steps.size(),
                        "Nivel " + (i + 1),
                        "Juega a tu ritmo y explora el nivel lo mejor que puedas.\n"
                                + "Al terminarlo, responderás una breve pregunta sobre tus impresiones de este nivel.\n"
                                + "Controles: Flechas = Moverse | Flecha Arriba / S / Espacio = Saltar | A / Shift = Correr | R = Reiniciar | Q = Terminar",
                        "¡Jugar nivel " + (i + 1) + "!");

                Path levelFile;
                if (step.type() != 'P') {
                    levelFile = step.level();
                } else if (skipGeneration) {
                    levelFile = substitutes.get(step.index());
                } else {
                    setDevContext(
                            "Generando Personalizado " + (step.index() + 1) + " (MarioGPT) | Secuencia: " + group + " (" + stepCode + ")",
                            typeName,
                            "Generando con MarioGPT...",
                            "Grupo " + group + " : " + seqProgress,
                            id,
                            timer
                    );
                    if (!generation.isReady(step.index())) {
                        showGeneratingScreen(i + 1, steps.size());
                    }
                    levelFile = generation.await(step.index());
                }

                setDevContext(
                        "Cuadrado Latino " + group + " | Paso " + (i + 1) + "/6: " + stepCode + " [" + typeName + "] | " + levelFile.getFileName(),
                        typeName,
                        levelFile.getFileName().toString(),
                        "Grupo " + group + " : " + seqProgress + "  (A=Personalizado, B=Genérico, C=Humano)",
                        id,
                        timer
                );

                String levelContent = sanitizeLevel(Files.readString(levelFile));
                BufferedImage thumbnail = renderLevelThumbnail(levelContent, 480, 64);
                playedLevels.add(new PlayedLevelInfo(i + 1, step.type(), step.index(), stepCode, levelFile, levelContent, thumbnail));

                playLevel(levelFile, timer, dir.resolve("telemetry_L" + (i + 1) + ".json"));
                if (step.type() == 'P') {
                    personalizedPlayedCount++;
                }

                List<String> row = new ArrayList<>(List.of(id, group, String.valueOf(i + 1),
                        String.valueOf(step.type()), String.valueOf(step.index() + 1),
                        levelFile.getFileName().toString()));
                row.addAll(Arrays.asList(askScreen("Opinión - Nivel " + (i + 1) + " de " + steps.size(),
                        "Responde las siguientes afirmaciones pensando exclusivamente en el nivel que acabas de jugar.",
                        perLevel)));
                saveRow(RESULTS.resolve("responses.tsv"),
                        concat(List.of("id", "group", "level_number", "level_type", "level_index", "level_file"),
                                ids(perLevel)),
                        row);
            }

            // 6. Fase 5: Juego Voluntario (Opcional según protocolo)
            boolean wantFreePlay = showChoiceScreen("Fase Voluntaria de Juego",
                    "¡Has completado exitosamente los 6 niveles principales de la sesión!\n\n"
                            + "La parte obligatoria del estudio ha concluido. Si deseas, puedes jugar libremente\n"
                            + "durante unos minutos adicionales (hasta 5 minutos) por pura diversión,\n"
                            + "o si prefieres, puedes pasar directamente a la fase final de cierre.\n\n"
                            + "¿Deseas jugar libremente antes del cierre final?",
                    "Jugar libremente", "Pasar al bloque de cierre");
            if (wantFreePlay) {
                freePlay(config, normal, playedLevels, timer);
            }

            // 7. Fase 6: Cierre (Ranking con miniaturas interactivas, Comentarios finales)
            showClosureBlock(playedLevels, id, group, dir);

            // 8. Pantalla final de despedida
            showExitScreen("¡Muchas gracias por participar!",
                    "Tus respuestas y datos de juego han sido guardados con éxito.\n"
                            + "Tu participación es invaluable para nuestra investigación.\n\n"
                            + "¡Esperamos que te hayas divertido!");
        } catch (Exception e) {
            generation.cancel();
            showExitScreen("Ha ocurrido un problema", "Detalle: " + e.getMessage());
            throw e;
        } finally {
            if (masterFrame != null) {
                try {
                    if (gd.getFullScreenWindow() == masterFrame) {
                        gd.setFullScreenWindow(null);
                    }
                    masterFrame.dispose();
                } catch (Exception ignored) {
                }
            }
        }
        System.exit(0);
    }

    public static String sanitizeLevel(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        String[] lines = content.split("\\r?\\n");
        if (lines.length == 0) {
            return content;
        }
        int numRows = lines.length;
        int numCols = lines[0].length();
        char[][] grid = new char[numRows][numCols];
        for (int r = 0; r < numRows; r++) {
            String line = lines[r];
            for (int c = 0; c < numCols; c++) {
                grid[r][c] = (c < line.length()) ? line.charAt(c) : '-';
            }
        }

        // 1. Ensure solid ground at spawn area (columns 0..4)
        int groundStartRow = Math.max(0, numRows - 2);
        for (int c = 0; c < Math.min(5, numCols); c++) {
            for (int r = groundStartRow; r < numRows; r++) {
                grid[r][c] = 'X';
            }
        }

        // 2. Clear hazards, enemies and blocks from columns 0..4 above ground
        for (int c = 0; c < Math.min(5, numCols); c++) {
            for (int r = 0; r < groundStartRow; r++) {
                grid[r][c] = '-';
            }
        }

        // 3. Clear any enemies in the first 7 columns to guarantee safe runway
        for (int c = 0; c < Math.min(7, numCols); c++) {
            for (int r = 0; r < numRows; r++) {
                char ch = grid[r][c];
                if (ch == 'E' || ch == 'g' || ch == 'G' || ch == 'k' || ch == 'K' ||
                    ch == 'r' || ch == 'R' || ch == 'y' || ch == 'Y' || ch == 'T' ||
                    ch == '*' || ch == 'B' || ch == 'b') {
                    grid[r][c] = '-';
                }
            }
        }

        // 4. Place explicit Mario spawn marker 'M' at column 1, row groundStartRow - 1
        int marioRow = Math.max(0, groundStartRow - 1);
        if (numCols > 1) {
            grid[marioRow][1] = 'M';
        }

        // 5. Ensure ground at level finish area (last 4 columns)
        int lastColStart = Math.max(0, numCols - 4);
        for (int c = lastColStart; c < numCols; c++) {
            for (int r = groundStartRow; r < numRows; r++) {
                grid[r][c] = 'X';
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < numRows; r++) {
            sb.append(new String(grid[r]));
            if (r < numRows - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private static Path playLevel(Path levelFile, int timer, Path telemetryFile) throws IOException {
        String level = sanitizeLevel(Files.readString(levelFile));
        int seed = new Random().nextInt(1_000_000);
        MarioGame game = new MarioGame();
        activeGame = game;
        MarioResult result;
        try {
            result = (masterFrame != null)
                    ? game.runGameInWindow(masterFrame, new agents.human.Agent(), level, timer, 0)
                    : game.runGame(new agents.human.Agent(), level, timer, 0, true);
        } finally {
            activeGame = null;
        }
        game.closeWindow();
        if (telemetryFile != null) {
            Files.writeString(telemetryFile, PlayHuman.buildTelemetryJson(result, levelFile.toString(), timer, seed));
        }
        return telemetryFile;
    }

    private static void freePlay(Properties config, List<Path> normal, List<PlayedLevelInfo> playedLevels, int timer) throws Exception {
        Path humanDir = SRC.resolve(config.getProperty("human_dir").trim());
        List<Path> pool = new ArrayList<>();
        if (playedLevels != null && !playedLevels.isEmpty()) {
            for (PlayedLevelInfo pli : playedLevels) {
                if (pli.levelFile() != null && Files.exists(pli.levelFile())) {
                    pool.add(pli.levelFile());
                }
            }
        }
        if (pool.isEmpty()) {
            try (Stream<Path> files = Files.list(humanDir)) {
                pool = files.filter(f -> f.getFileName().toString().endsWith(".txt")).collect(Collectors.toList());
            }
            pool.addAll(normal);
        }
        Random random = new Random();
        Path last = null;
        do {
            Path level;
            do {
                level = pool.get(random.nextInt(pool.size()));
            } while (pool.size() > 1 && level.equals(last));
            last = level;
            playLevel(level, timer, null);
        } while (showChoiceScreen("Modo Juego Libre", "¿Deseas jugar otro nivel?", "Jugar otro nivel",
                "Pasar al bloque de cierre"));
    }

    // ==========================================
    // UI SCREENS IMPLEMENTATION (FULLSCREEN)
    // ==========================================

    private static void showWelcomeScreen() throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            JPanel bg = createScreenContainer();
            JPanel card = createModernCard(960, 580);

            // Badge
            card.add(Box.createRigidArea(new Dimension(0, 14)));

            // Title
            JLabel title = new JLabel("¡Bienvenido/a a la sesión de juego!");
            title.setFont(FONT_BOLD.deriveFont(32f));
            title.setForeground(TEXT_WHITE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(title);
            card.add(Box.createRigidArea(new Dimension(0, 22)));

            // Cards info
            card.add(createInfoBullet("Preguntas durante la partida:",
                    "Tras jugar cada nivel responderás unas breves preguntas para conocer tus impresiones."));
            card.add(Box.createRigidArea(new Dimension(0, 14)));

            card.add(createInfoBullet("Juego libre al terminar:",
                    "Al completar los niveles principales podrás seguir jugando a otros niveles libremente."));
            card.add(Box.createRigidArea(new Dimension(0, 14)));

            card.add(createInfoBullet("Niveles iniciales de prueba:",
                    "Para comenzar, jugarás dos niveles breves para acostumbrarte a los saltos y al control de Mario."));
            card.add(Box.createRigidArea(new Dimension(0, 24)));

            // Controls Box
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
            controls.setBackground(ITEM_BG);
            controls.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ITEM_BORDER, 1, true),
                    new EmptyBorder(10, 18, 10, 18)));
            controls.setAlignmentX(Component.LEFT_ALIGNMENT);
            controls.setMaximumSize(new Dimension(960, 60));
            JLabel cLabel = new JLabel(
                    "Controles:  Flechas = Moverse  |  Flecha Arriba / S / Espacio = Saltar  |  A / Shift = Correr  |  R = Reiniciar  |  Q = Terminar");
            cLabel.setFont(FONT_SEMIBOLD.deriveFont(15f));
            cLabel.setForeground(TEXT_WHITE);
            controls.add(cLabel);
            card.add(controls);
            card.add(Box.createRigidArea(new Dimension(0, 26)));

            // Action Button
            ModernButton btn = new ModernButton("Comenzar a jugar", ACCENT_PRIMARY, ACCENT_HOVER,
                    ACCENT_PRESSED, Color.WHITE, 12);
            btn.setAlignmentX(Component.LEFT_ALIGNMENT);
            btn.addActionListener(e -> future.complete(null));
            card.add(btn);

            bg.add(card);
            masterFrame.setContentPane(bg);
            masterFrame.revalidate();
            masterFrame.repaint();
            btn.requestFocusInWindow();
        });
        future.get();
    }

    private static void showLevelIntroScreen(String badge, String titleText, String descText, String btnText)
            throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            JPanel bg = createScreenContainer();
            JPanel card = createModernCard(880, developMode ? 600 : 460);

            card.add(createBadge(badge));
            card.add(Box.createRigidArea(new Dimension(0, 14)));

            JLabel title = new JLabel(titleText);
            title.setFont(FONT_BOLD.deriveFont(32f));
            title.setForeground(TEXT_WHITE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(title);
            card.add(Box.createRigidArea(new Dimension(0, 14)));

            if (developMode && currentDevCardPanel != null) {
                card.add(currentDevCardPanel);
                card.add(Box.createRigidArea(new Dimension(0, 14)));
            }

            for (String paragraph : descText.split("\n")) {
                JLabel p = new JLabel(
                        "<html><body style='width: 800px; color: rgb(156, 163, 175);'>" + paragraph + "</body></html>");
                p.setFont(FONT_REGULAR.deriveFont(17f));
                p.setAlignmentX(Component.LEFT_ALIGNMENT);
                card.add(p);
                card.add(Box.createRigidArea(new Dimension(0, 6)));
            }
            card.add(Box.createRigidArea(new Dimension(0, 20)));

            ModernButton btn = new ModernButton(btnText + " (Espacio / Enter)", ACCENT_PRIMARY, ACCENT_HOVER,
                    ACCENT_PRESSED, Color.WHITE, 12);
            btn.setAlignmentX(Component.LEFT_ALIGNMENT);
            btn.addActionListener(e -> future.complete(null));
            btn.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) {
                        future.complete(null);
                    }
                }
            });
            card.add(btn);

            bg.add(card);
            masterFrame.setContentPane(bg);
            masterFrame.revalidate();
            masterFrame.repaint();
            btn.requestFocusInWindow();
        });
        future.get();
    }

    private static void showGeneratingScreen(int current, int total) {
        SwingUtilities.invokeLater(() -> {
            JPanel bg = createScreenContainer();
            JPanel card = createModernCard(800, 280);

            card.add(createBadge("NIVEL PERSONALIZADO (" + current + " DE " + total + ")"));
            card.add(Box.createRigidArea(new Dimension(0, 16)));

            JLabel title = new JLabel("Generando tu nivel personalizado...");
            title.setFont(FONT_BOLD.deriveFont(30f));
            title.setForeground(TEXT_WHITE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(title);
            card.add(Box.createRigidArea(new Dimension(0, 12)));

            JLabel desc = new JLabel(
                    "<html><body style='width: 720px; color: rgb(156, 163, 175);'>Generando un nuevo nivel adaptado a tu estilo de juego...</body></html>");
            desc.setFont(FONT_REGULAR.deriveFont(17f));
            desc.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(desc);

            if (developMode && currentDevBanner != null && !currentDevBanner.isBlank()) {
                card.add(Box.createRigidArea(new Dimension(0, 8)));
                JLabel devDesc = new JLabel("<html><body style='width: 720px; color: rgb(250, 204, 21); font-weight: bold;'>[DEV INFO] " + currentDevBanner + "</body></html>");
                devDesc.setFont(FONT_SEMIBOLD.deriveFont(13f));
                devDesc.setAlignmentX(Component.LEFT_ALIGNMENT);
                card.add(devDesc);
            }
            card.add(Box.createRigidArea(new Dimension(0, 20)));

            JProgressBar progress = new JProgressBar();
            progress.setIndeterminate(true);
            progress.setBackground(ITEM_BG);
            progress.setForeground(ACCENT_PRIMARY);
            progress.setBorder(BorderFactory.createEmptyBorder());
            progress.setPreferredSize(new Dimension(720, 12));
            progress.setMaximumSize(new Dimension(720, 12));
            progress.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(progress);

            bg.add(card);
            masterFrame.setContentPane(bg);
            masterFrame.revalidate();
            masterFrame.repaint();
        });
    }

    private static boolean showConsentScreen() throws Exception {
        String consentText = Files.readString(EXPERIMENT.resolve("consentimiento.txt"), StandardCharsets.UTF_8);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            JPanel bg = createScreenContainer();
            JPanel card = createModernCard(1000, 720);

            card.add(createBadge("CONSENTIMIENTO INFORMADO"));
            card.add(Box.createRigidArea(new Dimension(0, 12)));

            JLabel title = new JLabel("Consentimiento informado");
            title.setFont(FONT_BOLD.deriveFont(32f));
            title.setForeground(TEXT_WHITE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(title);
            card.add(Box.createRigidArea(new Dimension(0, 6)));

            JLabel sub = new JLabel(
                    "Por favor lee detenidamente la siguiente información sobre el tratamiento de datos.");
            sub.setFont(FONT_REGULAR.deriveFont(17f));
            sub.setForeground(TEXT_MUTED);
            sub.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(sub);
            card.add(Box.createRigidArea(new Dimension(0, 18)));

            JTextArea area = new JTextArea(consentText);
            area.setFont(FONT_REGULAR.deriveFont(16f));
            area.setForeground(new Color(228, 230, 235));
            area.setBackground(ITEM_BG);
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setCaretPosition(0);
            area.setBorder(new EmptyBorder(16, 18, 16, 18));

            JScrollPane scroll = new JScrollPane(area);
            scroll.setBorder(BorderFactory.createLineBorder(ITEM_BORDER, 1, true));
            scroll.setPreferredSize(new Dimension(920, 410));
            scroll.setMaximumSize(new Dimension(920, 410));
            scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            applyDarkScrollbar(scroll);
            card.add(scroll);
            card.add(Box.createRigidArea(new Dimension(0, 22)));

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 0));
            actions.setBackground(CARD_BG);
            actions.setAlignmentX(Component.LEFT_ALIGNMENT);

            ModernButton acceptBtn = new ModernButton("Acepto participar en el experimento", ACCENT_PRIMARY,
                    ACCENT_HOVER, ACCENT_PRESSED, Color.WHITE, 12);
            acceptBtn.addActionListener(e -> future.complete(true));
            actions.add(acceptBtn);

            ModernButton declineBtn = new ModernButton("No acepto participar", DANGER_BG, DANGER_HOVER,
                    new Color(110, 15, 45), Color.WHITE, 12);
            declineBtn.addActionListener(e -> future.complete(false));
            actions.add(declineBtn);

            card.add(actions);

            bg.add(card);
            masterFrame.setContentPane(bg);
            masterFrame.revalidate();
            masterFrame.repaint();
            acceptBtn.requestFocusInWindow();
        });
        return future.get();
    }

    private static boolean showChoiceScreen(String titleText, String descText, String yesText, String noText)
            throws Exception {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            JPanel bg = createScreenContainer();
            JPanel card = createModernCard(840, 360);

            JLabel title = new JLabel(titleText);
            title.setFont(FONT_BOLD.deriveFont(30f));
            title.setForeground(TEXT_WHITE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(title);
            card.add(Box.createRigidArea(new Dimension(0, 16)));

            for (String p : descText.split("\n")) {
                JLabel l = new JLabel(
                        "<html><body style='width: 760px; color: rgb(156, 163, 175);'>" + p + "</body></html>");
                l.setFont(FONT_REGULAR.deriveFont(18f));
                l.setAlignmentX(Component.LEFT_ALIGNMENT);
                card.add(l);
                card.add(Box.createRigidArea(new Dimension(0, 6)));
            }
            card.add(Box.createRigidArea(new Dimension(0, 24)));

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
            actions.setBackground(CARD_BG);
            actions.setAlignmentX(Component.LEFT_ALIGNMENT);

            ModernButton yesBtn = new ModernButton(yesText, ACCENT_PRIMARY, ACCENT_HOVER, ACCENT_PRESSED, Color.WHITE,
                    12);
            yesBtn.addActionListener(e -> future.complete(true));
            actions.add(yesBtn);

            ModernButton noBtn = new ModernButton(noText, new Color(45, 48, 62), new Color(60, 64, 82),
                    new Color(35, 38, 50), TEXT_WHITE, 12);
            noBtn.addActionListener(e -> future.complete(false));
            actions.add(noBtn);

            card.add(actions);

            bg.add(card);
            masterFrame.setContentPane(bg);
            masterFrame.revalidate();
            masterFrame.repaint();
            yesBtn.requestFocusInWindow();
        });
        return future.get();
    }

    private static void showExitScreen(String titleText, String msgText) throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            JPanel bg = createScreenContainer(false);
            JPanel card = createModernCard(780, 300);

            JLabel title = new JLabel(titleText);
            title.setFont(FONT_BOLD.deriveFont(32f));
            title.setForeground(TEXT_WHITE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(title);
            card.add(Box.createRigidArea(new Dimension(0, 14)));

            JLabel msg = new JLabel(
                    "<html><body style='width: 700px; color: rgb(156, 163, 175);'>" + msgText + "</body></html>");
            msg.setFont(FONT_REGULAR.deriveFont(18f));
            msg.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(msg);
            card.add(Box.createRigidArea(new Dimension(0, 24)));

            ModernButton exitBtn = new ModernButton("Salir", ACCENT_PRIMARY, ACCENT_HOVER,
                    ACCENT_PRESSED, Color.WHITE, 12);
            exitBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
            exitBtn.addActionListener(e -> future.complete(null));
            card.add(exitBtn);

            bg.add(card);
            masterFrame.setContentPane(bg);
            masterFrame.revalidate();
            masterFrame.repaint();
            exitBtn.requestFocusInWindow();
        });
        future.get();
    }

    private static String[] askScreen(String titleText, String introText, List<Question> questions) throws Exception {
        CompletableFuture<String[]> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            JPanel bg = createScreenContainer();
            JPanel card = createModernCard(1040, 780);

            // Header
            card.add(createBadge(titleText.toUpperCase()));
            card.add(Box.createRigidArea(new Dimension(0, 12)));

            JLabel title = new JLabel(titleText);
            title.setFont(FONT_BOLD.deriveFont(30f));
            title.setForeground(TEXT_WHITE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(title);
            card.add(Box.createRigidArea(new Dimension(0, 4)));

            if (developMode && currentDevBanner != null && !currentDevBanner.isBlank()) {
                JLabel devSub = new JLabel("[DEV INFO] " + currentDevBanner);
                devSub.setFont(FONT_SEMIBOLD.deriveFont(13f));
                devSub.setForeground(new Color(250, 204, 21));
                devSub.setAlignmentX(Component.LEFT_ALIGNMENT);
                card.add(devSub);
                card.add(Box.createRigidArea(new Dimension(0, 6)));
            }

            JLabel intro = new JLabel(introText);
            intro.setFont(FONT_REGULAR.deriveFont(17f));
            intro.setForeground(TEXT_MUTED);
            intro.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(intro);
            card.add(Box.createRigidArea(new Dimension(0, 18)));

            // Question List Container
            JPanel questionsList = new JPanel();
            questionsList.setLayout(new BoxLayout(questionsList, BoxLayout.Y_AXIS));
            questionsList.setBackground(CARD_BG);

            List<Supplier<String>> getters = new ArrayList<>();
            JLabel errorLabel = new JLabel(" ");
            errorLabel.setFont(FONT_SEMIBOLD.deriveFont(15f));
            errorLabel.setForeground(new Color(244, 63, 94));
            errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

            for (int q = 0; q < questions.size(); q++) {
                Question question = questions.get(q);

                JPanel qCard = new JPanel();
                qCard.setLayout(new BoxLayout(qCard, BoxLayout.Y_AXIS));
                qCard.setBackground(ITEM_BG);
                qCard.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(ITEM_BORDER, 1, true),
                        new EmptyBorder(16, 20, 16, 20)));
                qCard.setAlignmentX(Component.LEFT_ALIGNMENT);
                qCard.setMaximumSize(new Dimension(960, 360));

                String cleanText = question.text();
                JLabel qTitle = new JLabel(
                        "<html><body style='width:880px'>" + (q + 1) + ". " + cleanText + "</body></html>");
                qTitle.setFont(FONT_BOLD.deriveFont(18f));
                qTitle.setForeground(TEXT_WHITE);
                qTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
                qCard.add(qTitle);
                qCard.add(Box.createRigidArea(new Dimension(0, 14)));

                String[] selected = { null };
                switch (question.type()) {
                    case "number" -> {
                        JTextField field = new ModernTextField(8);
                        field.setAlignmentX(Component.LEFT_ALIGNMENT);
                        qCard.add(field);
                        getters.add(() -> {
                            String val = field.getText().trim();
                            return val.matches("\\d{1,3}") ? val : null;
                        });
                    }
                    case "choice" -> {
                        JPanel choicesPanel = new JPanel();
                        choicesPanel.setLayout(new BoxLayout(choicesPanel, BoxLayout.Y_AXIS));
                        choicesPanel.setBackground(ITEM_BG);
                        choicesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

                        ButtonGroup group = new ButtonGroup();
                        String[] options = question.options().split("\\|");
                        for (String option : options) {
                            String[] parts = option.contains("=") ? option.split("=", 2)
                                    : new String[] { option, option };
                            String label = parts[0].equals(parts[1]) ? parts[1] : parts[0] + " - " + parts[1];

                            ChoiceCard button = new ChoiceCard(label);
                            button.addActionListener(e -> selected[0] = parts[0]);
                            group.add(button);
                            choicesPanel.add(button);
                            choicesPanel.add(Box.createRigidArea(new Dimension(0, 8)));
                        }
                        qCard.add(choicesPanel);
                        getters.add(() -> selected[0]);
                    }
                    case "scale" -> {
                        String[] o = question.options().split("\\|");
                        int min = Integer.parseInt(o[0]);
                        int max = Integer.parseInt(o[1]);
                        String minLabel = o[2];
                        String maxLabel = o[3];

                        JPanel scaleContainer = new JPanel();
                        scaleContainer.setLayout(new BoxLayout(scaleContainer, BoxLayout.Y_AXIS));
                        scaleContainer.setBackground(ITEM_BG);
                        scaleContainer.setAlignmentX(Component.LEFT_ALIGNMENT);

                        JPanel pillsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
                        pillsPanel.setBackground(ITEM_BG);
                        pillsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

                        ButtonGroup group = new ButtonGroup();
                        for (int v = min; v <= max; v++) {
                            String valStr = String.valueOf(v);
                            LikertPill pill = new LikertPill(valStr);
                            pill.addActionListener(e -> selected[0] = valStr);
                            group.add(pill);
                            pillsPanel.add(pill);
                        }
                        scaleContainer.add(pillsPanel);
                        scaleContainer.add(Box.createRigidArea(new Dimension(0, 10)));

                        JLabel legend = new JLabel(min + " = " + minLabel + "   |   " + max + " = " + maxLabel);
                        legend.setFont(FONT_REGULAR.deriveFont(15f));
                        legend.setForeground(TEXT_ACCENT);
                        legend.setBorder(new EmptyBorder(0, 8, 0, 0));
                        scaleContainer.add(legend);

                        qCard.add(scaleContainer);
                        getters.add(() -> selected[0]);
                    }
                    default -> throw new IllegalStateException("Tipo de pregunta desconocido: " + question.type());
                }

                questionsList.add(qCard);
                questionsList.add(Box.createRigidArea(new Dimension(0, 16)));
            }

            JScrollPane scroll = new JScrollPane(questionsList);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setPreferredSize(new Dimension(970, 470));
            scroll.setMaximumSize(new Dimension(970, 470));
            scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            applyDarkScrollbar(scroll);
            card.add(scroll);
            card.add(Box.createRigidArea(new Dimension(0, 12)));

            card.add(errorLabel);
            card.add(Box.createRigidArea(new Dimension(0, 10)));

            ModernButton continueBtn = new ModernButton("Continuar", ACCENT_PRIMARY, ACCENT_HOVER,
                    ACCENT_PRESSED, Color.WHITE, 12);
            continueBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
            continueBtn.addActionListener(e -> {
                boolean allFilled = getters.stream().allMatch(g -> g.get() != null);
                if (allFilled) {
                    future.complete(getters.stream().map(Supplier::get).toArray(String[]::new));
                } else {
                    errorLabel.setText("Por favor responde todas las preguntas antes de continuar.");
                }
            });
            card.add(continueBtn);

            bg.add(card);
            masterFrame.setContentPane(bg);
            masterFrame.revalidate();
            masterFrame.repaint();
            continueBtn.requestFocusInWindow();
        });
        return future.get();
    }

    // ==========================================
    // BLOQUE DE CIERRE (FASE 6: CIERRE)
    // ==========================================

    private static void showClosureBlock(List<PlayedLevelInfo> playedLevels, String id, String group, Path dir)
            throws Exception {
        setDevContext(
                "ID: " + id + " | Fase de Cierre (1/2): Clasificación de Niveles | Grupo " + group,
                "Fase de Cierre (Ranking)",
                "N/A",
                "Grupo " + group + " (" + SEQUENCES.get(group) + ")",
                id,
                0
        );

        // 1. Clasificación / Ranking (del que más me gustó al que menos) con miniaturas interactivas
        List<PlayedLevelInfo> ranked = showRankingScreen(playedLevels, id, group);

        setDevContext(
                "ID: " + id + " | Fase de Cierre (2/2): Comentarios Finales | Grupo " + group,
                "Fase de Cierre (Comentarios)",
                "N/A",
                "Grupo " + group + " (" + SEQUENCES.get(group) + ")",
                id,
                0
        );

        // 2. Comentarios finales opcionales
        String feedback = showCommentsScreen(id, group);

        // 3. Guardar resultados en TSV y carpeta del participante
        saveClosureData(id, group, ranked, feedback, dir);
    }

    private static List<PlayedLevelInfo> showRankingScreen(List<PlayedLevelInfo> playedLevels, String id, String group)
            throws Exception {
        CompletableFuture<List<PlayedLevelInfo>> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            List<PlayedLevelInfo> currentList = new ArrayList<>(playedLevels);

            JPanel bg = createScreenContainer();
            JPanel card = createModernCard(1080, 800);

            card.add(createBadge("FASE DE CIERRE (1 DE 2) — CLASIFICACIÓN DE PREFERENCIA"));
            card.add(Box.createRigidArea(new Dimension(0, 10)));

            JLabel title = new JLabel("Ordena los niveles del que MÁS te gustó al que MENOS");
            title.setFont(FONT_BOLD.deriveFont(28f));
            title.setForeground(TEXT_WHITE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(title);
            card.add(Box.createRigidArea(new Dimension(0, 6)));

            if (developMode && currentDevBanner != null && !currentDevBanner.isBlank()) {
                JLabel devSub = new JLabel("[DEV INFO] " + currentDevBanner);
                devSub.setFont(FONT_SEMIBOLD.deriveFont(13f));
                devSub.setForeground(new Color(250, 204, 21));
                devSub.setAlignmentX(Component.LEFT_ALIGNMENT);
                card.add(devSub);
                card.add(Box.createRigidArea(new Dimension(0, 6)));
            }

            JLabel desc = new JLabel(
                    "<html><body style='width: 980px; color: rgb(156, 163, 175);'>"
                            + "A continuación se presentan los 6 niveles jugados en tu sesión. Reordénalos de manera que la posición <b>#1 sea tu favorito</b> "
                            + "(el que más te gustó) y la posición <b>#6 el que menos te gustó</b>.<br>"
                            + "Usa los botones <b>Subir</b> y <b>Bajar</b> para cambiar de lugar, o haz clic en <b>Ampliar</b> sobre la miniatura para examinar el nivel completo."
                            + "</body></html>");
            desc.setFont(FONT_REGULAR.deriveFont(16f));
            desc.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(desc);
            card.add(Box.createRigidArea(new Dimension(0, 14)));

            JPanel listPanel = new JPanel();
            listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
            listPanel.setBackground(CARD_BG);
            listPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

            Runnable[] refreshRef = new Runnable[1];
            refreshRef[0] = () -> {
                listPanel.removeAll();
                for (int i = 0; i < currentList.size(); i++) {
                    final int idx = i;
                    PlayedLevelInfo info = currentList.get(i);

                    JPanel rowCard = new JPanel(new BorderLayout(12, 0));
                    rowCard.setBackground(ITEM_BG);
                    Color borderColor = (idx == 0) ? new Color(245, 158, 11) : (idx == 5 ? new Color(225, 29, 72) : ITEM_BORDER);
                    rowCard.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(borderColor, (idx == 0 || idx == 5) ? 2 : 1, true),
                            new EmptyBorder(8, 12, 8, 12)));
                    rowCard.setAlignmentX(Component.LEFT_ALIGNMENT);
                    rowCard.setMaximumSize(new Dimension(980, 84));
                    rowCard.setPreferredSize(new Dimension(980, 84));

                    // Left Column: Badge + Subtitle
                    JPanel leftCol = new JPanel();
                    leftCol.setLayout(new BoxLayout(leftCol, BoxLayout.Y_AXIS));
                    leftCol.setBackground(ITEM_BG);
                    leftCol.setPreferredSize(new Dimension(210, 68));

                    leftCol.add(createRankBadge(idx));
                    leftCol.add(Box.createRigidArea(new Dimension(0, 4)));

                    String stepTitle = "Nivel del Paso " + info.orderPlayed();
                    if (developMode) {
                        stepTitle = "Paso " + info.orderPlayed() + " (" + info.stepCode() + " - " + info.type() + (info.levelIndex() + 1) + ")";
                    }
                    JLabel stepLabel = new JLabel(stepTitle);
                    stepLabel.setFont(FONT_SEMIBOLD.deriveFont(13f));
                    stepLabel.setForeground(TEXT_MUTED);
                    leftCol.add(stepLabel);

                    if (developMode) {
                        JLabel fileLabel = new JLabel(info.levelFile().getFileName().toString());
                        fileLabel.setFont(FONT_REGULAR.deriveFont(11f));
                        fileLabel.setForeground(new Color(250, 204, 21));
                        leftCol.add(fileLabel);
                    }
                    rowCard.add(leftCol, BorderLayout.WEST);

                    // Center Column: Interactive Miniature Thumbnail
                    JLabel thumbLabel = new JLabel(new ImageIcon(info.thumbnail()));
                    thumbLabel.setBorder(BorderFactory.createLineBorder(new Color(51, 65, 85), 1, true));
                    thumbLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    thumbLabel.setToolTipText("Haz clic para ampliar este nivel en pantalla completa");
                    thumbLabel.addMouseListener(new MouseAdapter() {
                        @Override
                        public void mouseClicked(MouseEvent e) {
                            String zoomTitle = "Nivel del Paso " + info.orderPlayed() + (developMode ? " (" + info.levelFile().getFileName() + ")" : "");
                            showLevelZoomModal(masterFrame, zoomTitle, info.levelContent());
                        }
                    });
                    rowCard.add(thumbLabel, BorderLayout.CENTER);

                    // Right Column: Controls
                    JPanel rightCol = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 12));
                    rightCol.setBackground(ITEM_BG);
                    rightCol.setPreferredSize(new Dimension(230, 68));

                    ModernButton zoomBtn = new ModernButton("Ampliar", new Color(30, 41, 59), new Color(51, 65, 85),
                            new Color(15, 23, 42), TEXT_WHITE, 8, 8, 12, 13f);
                    zoomBtn.addActionListener(e -> {
                        String zoomTitle = "Nivel del Paso " + info.orderPlayed() + (developMode ? " (" + info.levelFile().getFileName() + ")" : "");
                        showLevelZoomModal(masterFrame, zoomTitle, info.levelContent());
                    });
                    rightCol.add(zoomBtn);

                    ModernButton upBtn = new ModernButton("Subir", new Color(45, 52, 72), new Color(65, 75, 105),
                            new Color(30, 35, 50), Color.WHITE, 8, 8, 12, 13f);
                    upBtn.setEnabled(idx > 0);
                    upBtn.addActionListener(e -> {
                        Collections.swap(currentList, idx, idx - 1);
                        refreshRef[0].run();
                    });
                    rightCol.add(upBtn);

                    ModernButton downBtn = new ModernButton("Bajar", new Color(45, 52, 72), new Color(65, 75, 105),
                            new Color(30, 35, 50), Color.WHITE, 8, 8, 12, 13f);
                    downBtn.setEnabled(idx < currentList.size() - 1);
                    downBtn.addActionListener(e -> {
                        Collections.swap(currentList, idx, idx + 1);
                        refreshRef[0].run();
                    });
                    rightCol.add(downBtn);

                    rowCard.add(rightCol, BorderLayout.EAST);

                    listPanel.add(rowCard);
                    listPanel.add(Box.createRigidArea(new Dimension(0, 8)));
                }
                listPanel.revalidate();
                listPanel.repaint();
            };

            refreshRef[0].run();

            JScrollPane scroll = new JScrollPane(listPanel);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setPreferredSize(new Dimension(1020, 520));
            scroll.setMaximumSize(new Dimension(1020, 520));
            scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            applyDarkScrollbar(scroll);
            card.add(scroll);
            card.add(Box.createRigidArea(new Dimension(0, 16)));

            ModernButton confirmBtn = new ModernButton("Confirmar mi clasificación y continuar", ACCENT_PRIMARY,
                    ACCENT_HOVER, ACCENT_PRESSED, Color.WHITE, 12);
            confirmBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
            confirmBtn.addActionListener(e -> future.complete(new ArrayList<>(currentList)));
            card.add(confirmBtn);

            bg.add(card);
            masterFrame.setContentPane(bg);
            masterFrame.revalidate();
            masterFrame.repaint();
            confirmBtn.requestFocusInWindow();
        });
        return future.get();
    }

    private static String showCommentsScreen(String id, String group) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            JPanel bg = createScreenContainer();
            JPanel card = createModernCard(1000, 680);

            card.add(createBadge("FASE DE CIERRE (2 DE 2) — COMENTARIOS FINALES"));
            card.add(Box.createRigidArea(new Dimension(0, 12)));

            JLabel title = new JLabel("Comentarios y observaciones finales");
            title.setFont(FONT_BOLD.deriveFont(28f));
            title.setForeground(TEXT_WHITE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(title);
            card.add(Box.createRigidArea(new Dimension(0, 8)));

            JLabel desc = new JLabel(
                    "<html><body style='width: 900px; color: rgb(156, 163, 175);'>"
                            + "¿Hay algo más que te gustaría comentar sobre la sesión, los niveles, la dificultad o los controles?<br>"
                            + "<i>(Esta pregunta es totalmente opcional. Si no deseas dejar comentarios puedes pulsar 'Omitir y finalizar').</i>"
                            + "</body></html>");
            desc.setFont(FONT_REGULAR.deriveFont(16f));
            desc.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(desc);
            card.add(Box.createRigidArea(new Dimension(0, 20)));

            JTextArea area = new JTextArea();
            area.setFont(FONT_REGULAR.deriveFont(16f));
            area.setForeground(TEXT_WHITE);
            area.setBackground(ITEM_BG);
            area.setCaretColor(TEXT_WHITE);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setBorder(new EmptyBorder(14, 16, 14, 16));

            JScrollPane scroll = new JScrollPane(area);
            scroll.setBorder(BorderFactory.createLineBorder(ITEM_BORDER, 1, true));
            scroll.setPreferredSize(new Dimension(920, 260));
            scroll.setMaximumSize(new Dimension(920, 260));
            scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            applyDarkScrollbar(scroll);
            card.add(scroll);
            card.add(Box.createRigidArea(new Dimension(0, 24)));

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
            actions.setBackground(CARD_BG);
            actions.setAlignmentX(Component.LEFT_ALIGNMENT);

            ModernButton saveBtn = new ModernButton("Guardar comentarios y finalizar", ACCENT_PRIMARY, ACCENT_HOVER,
                    ACCENT_PRESSED, Color.WHITE, 12);
            saveBtn.addActionListener(e -> future.complete(area.getText().trim()));
            actions.add(saveBtn);

            ModernButton skipBtn = new ModernButton("Omitir y finalizar", new Color(45, 48, 62), new Color(60, 64, 82),
                    new Color(35, 38, 50), TEXT_WHITE, 12);
            skipBtn.addActionListener(e -> future.complete(""));
            actions.add(skipBtn);

            card.add(actions);

            bg.add(card);
            masterFrame.setContentPane(bg);
            masterFrame.revalidate();
            masterFrame.repaint();
            area.requestFocusInWindow();
        });
        return future.get();
    }

    private static void saveClosureData(String id, String group, List<PlayedLevelInfo> ranked,
            String feedback, Path dir) throws IOException {
        // 1. Ranking TSV
        List<String> rankingHeaders = List.of("id", "group", "rank", "order_played", "level_type", "level_index",
                "step_code", "level_file");
        for (int r = 0; r < ranked.size(); r++) {
            PlayedLevelInfo info = ranked.get(r);
            List<String> row = List.of(
                    id,
                    group,
                    String.valueOf(r + 1),
                    String.valueOf(info.orderPlayed()),
                    String.valueOf(info.type()),
                    String.valueOf(info.levelIndex() + 1),
                    info.stepCode(),
                    info.levelFile().getFileName().toString()
            );
            saveRow(RESULTS.resolve("ranking.tsv"), rankingHeaders, row);
            saveRow(dir.resolve("ranking.tsv"), rankingHeaders, row);
        }

        // 2. Feedback TSV
        List<String> feedbackHeaders = List.of("id", "group", "feedback", "timestamp");
        String sanitizedFeedback = (feedback == null || feedback.isBlank()) ? "SIN_COMENTARIOS" : feedback;
        List<String> fbRow = List.of(
                id,
                group,
                sanitizedFeedback,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
        saveRow(RESULTS.resolve("feedback.tsv"), feedbackHeaders, fbRow);
        saveRow(dir.resolve("feedback.tsv"), feedbackHeaders, fbRow);

        // 3. Resumen legible del cierre
        StringBuilder sb = new StringBuilder();
        sb.append("============================================================\n");
        sb.append("FASE DE CIERRE - PROTOCOLO EXPERIMENTAL (PROTOCOLO MOSTRO)\n");
        sb.append("Participante: ").append(id).append(" | Grupo: ").append(group).append('\n');
        sb.append("Fecha: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append("============================================================\n\n");

        sb.append("1. CLASIFICACIÓN DE PREFERENCIA (RANKING 1 A 6):\n");
        for (int r = 0; r < ranked.size(); r++) {
            PlayedLevelInfo info = ranked.get(r);
            sb.append("   #").append(r + 1).append(": Paso ").append(info.orderPlayed())
                    .append(" (").append(info.stepCode()).append(" - ").append(describeLevelType(info.type())).append(")")
                    .append(" -> ").append(info.levelFile().getFileName()).append('\n');
        }

        sb.append("\n2. COMENTARIOS FINALES:\n");
        sb.append("   ").append(sanitizedFeedback).append('\n');
        sb.append("============================================================\n");

        Files.writeString(dir.resolve("cierre.txt"), sb.toString(), StandardCharsets.UTF_8);
        System.out.println("Datos de fase de cierre guardados exitosamente para " + id);
    }

    // ==========================================
    // 2D LEVEL THUMBNAIL & PREVIEW RENDERER
    // ==========================================

    private static BufferedImage renderLevelThumbnail(String content, int targetWidth, int targetHeight) {
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
            g.setColor(new Color(92, 148, 252));
            g.fillRect(0, 0, targetWidth, targetHeight);
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

        // Background Sky #5c94fc
        g2.setColor(new Color(92, 148, 252));
        g2.fillRect(0, 0, targetWidth, targetHeight);

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

    private static BufferedImage renderLevelFull(String content, int tileSize) {
        String[] rawLines = content.split("\\r?\\n");
        List<String> lines = new ArrayList<>();
        for (String l : rawLines) {
            if (!l.trim().isEmpty() && !l.startsWith("//") && !l.startsWith(";")) {
                lines.add(l);
            }
        }
        if (lines.isEmpty()) {
            return new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        }
        int rows = lines.size();
        int cols = 0;
        for (String l : lines) {
            cols = Math.max(cols, l.length());
        }
        int width = cols * tileSize;
        int height = rows * tileSize;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(92, 148, 252));
        g2.fillRect(0, 0, width, height);

        for (int r = 0; r < rows; r++) {
            String line = lines.get(r);
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '-' || Character.isWhitespace(ch)) continue;
                int x = c * tileSize;
                int y = r * tileSize;
                drawTileOnGraphics(g2, ch, x, y, tileSize, tileSize);
            }
        }
        g2.dispose();
        return img;
    }

    private static void drawTileOnGraphics(Graphics2D g, char ch, int x, int y, int w, int h) {
        switch (ch) {
            case 'X', 'x' -> { // Solid Ground
                g.setColor(new Color(216, 104, 0)); // #d86800 SMB ground
                g.fillRect(x, y, w, h);
                // Grass top strip
                g.setColor(new Color(0, 168, 0)); // #00a800
                int grassH = Math.max(1, Math.min(3, h / 3));
                g.fillRect(x, y, w, grassH);
                if (w > 2 && h > 2) {
                    g.setColor(new Color(108, 52, 0));
                    g.drawRect(x, y, w - 1, h - 1);
                }
            }
            case '#' -> { // Solid Platform / Block
                g.setColor(new Color(252, 152, 56)); // #fc9838
                g.fillRect(x, y, w, h);
                if (w > 4 && h > 4) {
                    g.setColor(new Color(108, 52, 0));
                    g.fillRect(x + 1, y + 1, w - 2, h - 2);
                }
            }
            case 'S' -> { // Breakable Brick
                g.setColor(new Color(184, 68, 24)); // #b84418
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
                g.setColor(new Color(252, 176, 64)); // #fcb040
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
                g.setColor(new Color(252, 228, 64)); // #fce440
                int cw = Math.max(2, (int) (w * 0.6));
                int chH = Math.max(2, (int) (h * 0.75));
                int cx = x + (w - cw) / 2;
                int cy = y + (h - chH) / 2;
                g.fillOval(cx, cy, cw, chH);
                g.setColor(new Color(216, 144, 0));
                g.drawOval(cx, cy, cw, chH);
            }
            case '<', '>' -> { // Pipe Top
                g.setColor(new Color(0, 168, 0)); // #00a800
                g.fillRect(x, y, w, h);
                g.setColor(new Color(128, 208, 16)); // #80d010
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
                g.setColor(new Color(168, 64, 0)); // #a84000
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
                g.setColor(new Color(225, 29, 72)); // #e11d48
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

    private static void showLevelZoomModal(JFrame parent, String levelTitle, String levelContent) {
        JDialog dialog = new JDialog(parent, "Vista Detallada - " + levelTitle, true);
        dialog.setLayout(new BorderLayout());
        dialog.getContentPane().setBackground(BG_DARK);

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(CARD_BG);
        header.setBorder(new EmptyBorder(16, 20, 16, 20));

        JLabel titleLabel = new JLabel("Mapa: " + levelTitle);
        titleLabel.setFont(FONT_BOLD.deriveFont(22f));
        titleLabel.setForeground(TEXT_WHITE);
        header.add(titleLabel);
        header.add(Box.createRigidArea(new Dimension(0, 6)));

        JLabel subLabel = new JLabel("Desplaza la barra inferior hacia la derecha para recorrer todo el nivel horizontalmente.");
        subLabel.setFont(FONT_REGULAR.deriveFont(15f));
        subLabel.setForeground(TEXT_MUTED);
        header.add(subLabel);

        dialog.add(header, BorderLayout.NORTH);

        BufferedImage fullImg = renderLevelFull(levelContent, 18);
        JLabel imageLabel = new JLabel(new ImageIcon(fullImg));
        imageLabel.setBackground(new Color(92, 148, 252));
        imageLabel.setOpaque(true);

        JPanel centerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        centerPanel.setBackground(BG_DARK);
        centerPanel.add(imageLabel);

        JScrollPane scroll = new JScrollPane(centerPanel);
        scroll.setBorder(BorderFactory.createLineBorder(ITEM_BORDER, 1));
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        scroll.getHorizontalScrollBar().setUnitIncrement(36);
        applyDarkScrollbar(scroll);

        dialog.add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 12));
        footer.setBackground(CARD_BG);

        ModernButton closeBtn = new ModernButton("Cerrar vista previa (ESC)", ACCENT_PRIMARY, ACCENT_HOVER, ACCENT_PRESSED, Color.WHITE, 10, 10, 20, 14f);
        closeBtn.addActionListener(e -> dialog.dispose());
        footer.add(closeBtn);

        dialog.add(footer, BorderLayout.SOUTH);

        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.setSize(1060, 460);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static JLabel createRankBadge(int rankIndex) {
        String text;
        Color bg;
        Color border;
        Color fg = Color.WHITE;
        switch (rankIndex) {
            case 0 -> {
                text = "#1  (El que más me gustó)";
                bg = new Color(146, 64, 14);
                border = new Color(245, 158, 11);
            }
            case 1 -> {
                text = "#2";
                bg = new Color(51, 65, 85);
                border = new Color(148, 163, 184);
            }
            case 2 -> {
                text = "#3";
                bg = new Color(120, 53, 15);
                border = new Color(217, 119, 6);
            }
            case 3 -> {
                text = "#4";
                bg = new Color(30, 41, 59);
                border = new Color(71, 85, 105);
                fg = new Color(203, 213, 225);
            }
            case 4 -> {
                text = "#5";
                bg = new Color(30, 41, 59);
                border = new Color(71, 85, 105);
                fg = new Color(203, 213, 225);
            }
            default -> {
                text = "#6  (El que menos me gustó)";
                bg = new Color(136, 19, 55);
                border = new Color(225, 29, 72);
            }
        }
        JLabel badge = new JLabel("  " + text + "  ");
        badge.setFont(FONT_BOLD.deriveFont(13f));
        badge.setForeground(fg);
        badge.setOpaque(true);
        badge.setBackground(bg);
        badge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, 1, true),
                new EmptyBorder(4, 10, 4, 10)));
        badge.setAlignmentX(Component.LEFT_ALIGNMENT);
        return badge;
    }

    // ==========================================
    // DEVELOPER HELPERS & UI COMPONENTS
    // ==========================================

    private static String describeLevelType(char type) {
        return switch (type) {
            case 'P', 'A' -> "Personalizado (MarioGPT adaptado por telemetría)";
            case 'N', 'B' -> "No Personalizado (Genérico de control)";
            case 'H', 'C' -> "Humano (Super Mario Maker 2 - Nivel 0)";
            default -> "Familiarización (Preparatorio)";
        };
    }

    private static String formatSequenceProgress(String group, int currentStepIndex) {
        String seq = SEQUENCES.getOrDefault(group, "");
        if (seq.isBlank()) return group;
        String[] codes = seq.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codes.length; i++) {
            if (i > 0) sb.append(" -> ");
            if (i == currentStepIndex) {
                sb.append("[").append(codes[i]).append("*]");
            } else {
                sb.append(codes[i]);
            }
        }
        return sb.toString();
    }

    private static void setDevContext(String bannerText, String levelType, String fileName, String latinSquareDist, String participantId, int timerSec) {
        currentDevBanner = bannerText;
        try {
            MarioGame.setDevBannerText(developMode ? bannerText : null);
        } catch (Throwable ignored) {}

        if (developMode) {
            currentDevCardPanel = createDevCardPanel(levelType, latinSquareDist, fileName, participantId, timerSec);
        } else {
            currentDevCardPanel = null;
        }
    }

    private static JPanel createDevCardPanel(String levelType, String latinSquareDist, String fileName, String participantId, int timerSec) {
        JPanel devPanel = new JPanel();
        devPanel.setLayout(new BoxLayout(devPanel, BoxLayout.Y_AXIS));
        devPanel.setBackground(new Color(24, 21, 15));
        devPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(245, 158, 11), 1, true),
                new EmptyBorder(10, 16, 10, 16)));
        devPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        devPanel.setMaximumSize(new Dimension(840, 160));

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        header.setBackground(new Color(24, 21, 15));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel badge = new JLabel("  MODO DESARROLLO (develop=true)  ");
        badge.setFont(FONT_BOLD.deriveFont(11f));
        badge.setForeground(Color.BLACK);
        badge.setBackground(new Color(245, 158, 11));
        badge.setOpaque(true);
        badge.setBorder(new EmptyBorder(2, 5, 2, 5));
        header.add(badge);
        devPanel.add(header);
        devPanel.add(Box.createRigidArea(new Dimension(0, 6)));

        devPanel.add(createDevRow("Tipo de Nivel:", levelType, new Color(254, 240, 138)));
        devPanel.add(Box.createRigidArea(new Dimension(0, 3)));
        devPanel.add(createDevRow("Cuadrado Latino (6x6):", latinSquareDist, new Color(253, 230, 138)));
        devPanel.add(Box.createRigidArea(new Dimension(0, 3)));
        devPanel.add(createDevRow("Archivo de Nivel:", fileName, TEXT_WHITE));
        devPanel.add(Box.createRigidArea(new Dimension(0, 3)));
        devPanel.add(createDevRow("Participante / Timer:", participantId + "   |   " + timerSec + "s", TEXT_MUTED));

        return devPanel;
    }

    private static JPanel createDevRow(String label, String value, Color valueColor) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setBackground(new Color(24, 21, 15));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(label);
        l.setFont(FONT_BOLD.deriveFont(13f));
        l.setForeground(new Color(217, 119, 6));
        row.add(l);

        JLabel v = new JLabel(value);
        v.setFont(FONT_SEMIBOLD.deriveFont(13f));
        v.setForeground(valueColor);
        row.add(v);
        return row;
    }

    // ==========================================
    // CUSTOM SWING UI STYLING & COMPONENTS
    // ==========================================

    private static class DevGenerationSidePanel extends JPanel {
        private final GenerationJob job;
        private final Timer timer;
        private final JLabel clockLabel;
        private final JLabel p1Badge;
        private final JLabel p1Detail;
        private final JProgressBar p1Bar;
        private final JLabel p2Badge;
        private final JLabel p2Detail;
        private final JProgressBar p2Bar;
        private final JPanel summaryBox;
        private final JLabel summaryTitle;
        private final JLabel summarySubtitle;
        private final JPanel contentPanel;
        private final JPanel miniPanel;
        private final JLabel miniClock;

        public DevGenerationSidePanel(GenerationJob job) {
            super(new BorderLayout());
            this.job = job;
            setBackground(CARD_BG);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(55, 65, 81)),
                    new EmptyBorder(14, 14, 14, 14)
            ));

            contentPanel = new JPanel();
            contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
            contentPanel.setBackground(CARD_BG);

            // 1. Header with Title, Badge and Toggle
            JPanel topRow = new JPanel(new BorderLayout());
            topRow.setBackground(CARD_BG);

            JPanel leftBadges = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            leftBadges.setBackground(CARD_BG);

            JLabel devBadge = new JLabel(" DEV IA ");
            devBadge.setFont(FONT_BOLD.deriveFont(10f));
            devBadge.setForeground(Color.BLACK);
            devBadge.setBackground(new Color(245, 158, 11));
            devBadge.setOpaque(true);
            devBadge.setBorder(new EmptyBorder(2, 5, 2, 5));
            leftBadges.add(devBadge);

            clockLabel = new JLabel("T+ 00:00");
            clockLabel.setFont(FONT_BOLD.deriveFont(12f));
            clockLabel.setForeground(new Color(253, 230, 138));
            leftBadges.add(clockLabel);
            topRow.add(leftBadges, BorderLayout.WEST);

            JButton toggleBtn = new JButton("—");
            toggleBtn.setFont(FONT_BOLD.deriveFont(13f));
            toggleBtn.setForeground(TEXT_MUTED);
            toggleBtn.setBackground(CARD_BG);
            toggleBtn.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            toggleBtn.setFocusPainted(false);
            toggleBtn.setContentAreaFilled(false);
            toggleBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            toggleBtn.setToolTipText("Minimizar barra de progreso");
            toggleBtn.addActionListener(e -> toggleCollapse());
            topRow.add(toggleBtn, BorderLayout.EAST);

            contentPanel.add(topRow);
            contentPanel.add(Box.createRigidArea(new Dimension(0, 8)));

            JLabel headerTitle = new JLabel("Progreso de Niveles");
            headerTitle.setFont(FONT_BOLD.deriveFont(15f));
            headerTitle.setForeground(TEXT_WHITE);
            headerTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
            contentPanel.add(headerTitle);

            JLabel headerSub = new JLabel("Personalizados (MarioGPT + Ollama)");
            headerSub.setFont(FONT_REGULAR.deriveFont(11f));
            headerSub.setForeground(TEXT_MUTED);
            headerSub.setAlignmentX(Component.LEFT_ALIGNMENT);
            contentPanel.add(headerSub);

            contentPanel.add(Box.createRigidArea(new Dimension(0, 10)));
            JSeparator sep = new JSeparator();
            sep.setForeground(new Color(55, 65, 81));
            sep.setBackground(new Color(55, 65, 81));
            sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
            sep.setAlignmentX(Component.LEFT_ALIGNMENT);
            contentPanel.add(sep);
            contentPanel.add(Box.createRigidArea(new Dimension(0, 12)));

            // 2. Personalizado 1 Card
            JPanel p1Card = createLevelCardPanel();
            JPanel p1Header = new JPanel(new BorderLayout());
            p1Header.setBackground(ITEM_BG);

            JLabel p1Title = new JLabel("Personalizado 1");
            p1Title.setFont(FONT_BOLD.deriveFont(13f));
            p1Title.setForeground(TEXT_WHITE);
            p1Header.add(p1Title, BorderLayout.WEST);

            p1Badge = createPillLabel("PENDIENTE", new Color(55, 65, 81), new Color(209, 213, 219));
            p1Header.add(p1Badge, BorderLayout.EAST);
            p1Card.add(p1Header);
            p1Card.add(Box.createRigidArea(new Dimension(0, 6)));

            p1Detail = new JLabel("Esperando telemetría...");
            p1Detail.setFont(FONT_REGULAR.deriveFont(11f));
            p1Detail.setForeground(new Color(156, 163, 175));
            p1Detail.setAlignmentX(Component.LEFT_ALIGNMENT);
            p1Card.add(p1Detail);
            p1Card.add(Box.createRigidArea(new Dimension(0, 8)));

            p1Bar = new JProgressBar(0, 100);
            styleProgressBar(p1Bar);
            p1Card.add(p1Bar);

            contentPanel.add(p1Card);
            contentPanel.add(Box.createRigidArea(new Dimension(0, 12)));

            // 3. Personalizado 2 Card
            JPanel p2Card = createLevelCardPanel();
            JPanel p2Header = new JPanel(new BorderLayout());
            p2Header.setBackground(ITEM_BG);

            JLabel p2Title = new JLabel("Personalizado 2");
            p2Title.setFont(FONT_BOLD.deriveFont(13f));
            p2Title.setForeground(TEXT_WHITE);
            p2Header.add(p2Title, BorderLayout.WEST);

            p2Badge = createPillLabel("PENDIENTE", new Color(55, 65, 81), new Color(209, 213, 219));
            p2Header.add(p2Badge, BorderLayout.EAST);
            p2Card.add(p2Header);
            p2Card.add(Box.createRigidArea(new Dimension(0, 6)));

            p2Detail = new JLabel("Esperando telemetría...");
            p2Detail.setFont(FONT_REGULAR.deriveFont(11f));
            p2Detail.setForeground(new Color(156, 163, 175));
            p2Detail.setAlignmentX(Component.LEFT_ALIGNMENT);
            p2Card.add(p2Detail);
            p2Card.add(Box.createRigidArea(new Dimension(0, 8)));

            p2Bar = new JProgressBar(0, 100);
            styleProgressBar(p2Bar);
            p2Card.add(p2Bar);

            contentPanel.add(p2Card);
            contentPanel.add(Box.createRigidArea(new Dimension(0, 14)));

            // 4. Overall Status Box
            summaryBox = new JPanel();
            summaryBox.setLayout(new BoxLayout(summaryBox, BoxLayout.Y_AXIS));
            summaryBox.setBackground(new Color(30, 41, 59));
            summaryBox.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(79, 70, 229, 120), 1, true),
                    new EmptyBorder(10, 10, 10, 10)
            ));
            summaryBox.setAlignmentX(Component.LEFT_ALIGNMENT);

            summaryTitle = new JLabel("• Generando en 2° plano");
            summaryTitle.setFont(FONT_BOLD.deriveFont(12f));
            summaryTitle.setForeground(new Color(224, 231, 255));
            summaryTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
            summaryBox.add(summaryTitle);
            summaryBox.add(Box.createRigidArea(new Dimension(0, 4)));

            summarySubtitle = new JLabel("<html><body style='width: 250px; color: rgb(148, 163, 184); font-size: 10px;'>"
                    + "Continúa normalmente con la sesión; los niveles se preparan de forma transparente."
                    + "</body></html>");
            summarySubtitle.setFont(FONT_REGULAR.deriveFont(10.5f));
            summarySubtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
            summaryBox.add(summarySubtitle);

            contentPanel.add(summaryBox);

            // Mini collapsed panel
            miniPanel = new JPanel();
            miniPanel.setLayout(new BoxLayout(miniPanel, BoxLayout.Y_AXIS));
            miniPanel.setBackground(CARD_BG);
            miniPanel.setBorder(new EmptyBorder(8, 4, 8, 4));

            JButton expandBtn = new JButton("◀");
            expandBtn.setFont(FONT_BOLD.deriveFont(11f));
            expandBtn.setForeground(new Color(245, 158, 11));
            expandBtn.setBackground(CARD_BG);
            expandBtn.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            expandBtn.setFocusPainted(false);
            expandBtn.setContentAreaFilled(false);
            expandBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            expandBtn.setToolTipText("Expandir barra de generación");
            expandBtn.addActionListener(e -> toggleCollapse());
            expandBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
            miniPanel.add(expandBtn);
            miniPanel.add(Box.createRigidArea(new Dimension(0, 8)));

            JLabel miniBadge = new JLabel("DEV");
            miniBadge.setFont(FONT_BOLD.deriveFont(9f));
            miniBadge.setForeground(Color.BLACK);
            miniBadge.setBackground(new Color(245, 158, 11));
            miniBadge.setOpaque(true);
            miniBadge.setBorder(new EmptyBorder(2, 3, 2, 3));
            miniBadge.setAlignmentX(Component.CENTER_ALIGNMENT);
            miniPanel.add(miniBadge);
            miniPanel.add(Box.createRigidArea(new Dimension(0, 8)));

            miniClock = new JLabel("00m");
            miniClock.setFont(FONT_SEMIBOLD.deriveFont(10f));
            miniClock.setForeground(new Color(253, 230, 138));
            miniClock.setAlignmentX(Component.CENTER_ALIGNMENT);
            miniPanel.add(miniClock);

            if (devSidePanelCollapsed) {
                setPreferredSize(new Dimension(42, 0));
                add(miniPanel, BorderLayout.NORTH);
            } else {
                setPreferredSize(new Dimension(315, 0));
                add(contentPanel, BorderLayout.NORTH);
            }

            timer = new Timer(300, e -> updateProgress());
            timer.start();
            updateProgress();
        }

        private void toggleCollapse() {
            devSidePanelCollapsed = !devSidePanelCollapsed;
            removeAll();
            if (devSidePanelCollapsed) {
                setPreferredSize(new Dimension(42, 0));
                add(miniPanel, BorderLayout.NORTH);
            } else {
                setPreferredSize(new Dimension(315, 0));
                add(contentPanel, BorderLayout.NORTH);
            }
            Container parent = getParent();
            if (parent instanceof ScreenContainer sc) {
                sc.updateWestSpacer();
            }
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }
        }

        @Override
        public void removeNotify() {
            if (timer != null && timer.isRunning()) {
                timer.stop();
            }
            super.removeNotify();
        }

        private void updateProgress() {
            if (job == null) return;

            long elapsed = job.getElapsedSeconds();
            clockLabel.setText(String.format("T+ %02d:%02d", elapsed / 60, elapsed % 60));
            miniClock.setText(String.format("%02dm", elapsed / 60));

            updateLevelUI(0, p1Badge, p1Detail, p1Bar);
            updateLevelUI(1, p2Badge, p2Detail, p2Bar);

            boolean allDone = job.isAllDone();
            if (allDone) {
                summaryBox.setBackground(new Color(6, 78, 59, 140));
                summaryBox.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(16, 185, 129), 1, true),
                        new EmptyBorder(10, 10, 10, 10)
                ));
                summaryTitle.setText("✓ ¡Niveles listos!");
                summaryTitle.setForeground(new Color(167, 243, 208));
                summarySubtitle.setText("<html><body style='width: 250px; color: rgb(209, 250, 229); font-size: 10px;'>"
                        + "2/2 niveles listos en disco. Cero tiempos de espera al jugarlos."
                        + "</body></html>");
            } else {
                summaryBox.setBackground(new Color(30, 41, 59));
                summaryBox.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(79, 70, 229, 120), 1, true),
                        new EmptyBorder(10, 10, 10, 10)
                ));
                summaryTitle.setText("• Generando en 2° plano");
                summaryTitle.setForeground(new Color(224, 231, 255));
                summarySubtitle.setText("<html><body style='width: 250px; color: rgb(148, 163, 184); font-size: 10px;'>"
                        + "Continúa normalmente con la sesión; los niveles se preparan de forma transparente."
                        + "</body></html>");
            }
        }

        private void updateLevelUI(int index, JLabel badge, JLabel detail, JProgressBar bar) {
            GenStage stage = job.getStage(index);
            String det = job.getStageDetail(index);
            int pct = job.getProgressPercent(index);

            bar.setValue(pct);
            detail.setText("<html><body style='width: 250px; color: rgb(156, 163, 175); font-size: 10.5px;'>" + det + "</body></html>");

            switch (stage) {
                case PENDING -> {
                    badge.setText("PENDIENTE");
                    badge.setBackground(new Color(55, 65, 81));
                    badge.setForeground(new Color(209, 213, 219));
                    bar.setForeground(new Color(75, 85, 99));
                }
                case QUEUED -> {
                    badge.setText("EN COLA");
                    badge.setBackground(new Color(30, 58, 138));
                    badge.setForeground(new Color(191, 219, 254));
                    bar.setForeground(new Color(59, 130, 246));
                }
                case OLLAMA -> {
                    badge.setText("1/3 OLLAMA");
                    badge.setBackground(new Color(67, 56, 202));
                    badge.setForeground(new Color(224, 231, 255));
                    bar.setForeground(new Color(99, 102, 241));
                }
                case MARIOGPT -> {
                    badge.setText("2/3 MARIOGPT");
                    badge.setBackground(new Color(109, 40, 217));
                    badge.setForeground(new Color(237, 233, 254));
                    bar.setForeground(new Color(168, 85, 247));
                }
                case SANITIZING -> {
                    badge.setText("3/3 SANITIZ.");
                    badge.setBackground(new Color(180, 83, 9));
                    badge.setForeground(new Color(254, 243, 199));
                    bar.setForeground(new Color(245, 158, 11));
                }
                case READY -> {
                    badge.setText("LISTO ✓");
                    badge.setBackground(new Color(6, 95, 70));
                    badge.setForeground(new Color(209, 250, 229));
                    bar.setForeground(new Color(16, 185, 129));
                }
                case FALLBACK_READY -> {
                    badge.setText("RESPALDO ⚠");
                    badge.setBackground(new Color(146, 64, 14));
                    badge.setForeground(new Color(254, 243, 199));
                    bar.setForeground(new Color(245, 158, 11));
                }
                case FAILED -> {
                    badge.setText("FALLÓ ✕");
                    badge.setBackground(new Color(153, 27, 27));
                    badge.setForeground(new Color(254, 202, 202));
                    bar.setForeground(new Color(239, 68, 68));
                }
            }
        }

        private JPanel createLevelCardPanel() {
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBackground(ITEM_BG);
            p.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ITEM_BORDER, 1, true),
                    new EmptyBorder(10, 10, 10, 10)
            ));
            p.setAlignmentX(Component.LEFT_ALIGNMENT);
            return p;
        }

        private JLabel createPillLabel(String text, Color bg, Color fg) {
            JLabel l = new JLabel(text);
            l.setFont(FONT_BOLD.deriveFont(9.5f));
            l.setBackground(bg);
            l.setForeground(fg);
            l.setOpaque(true);
            l.setBorder(new EmptyBorder(2, 6, 2, 6));
            return l;
        }

        private void styleProgressBar(JProgressBar bar) {
            bar.setBackground(new Color(17, 24, 39));
            bar.setForeground(ACCENT_PRIMARY);
            bar.setBorder(BorderFactory.createEmptyBorder());
            bar.setPreferredSize(new Dimension(240, 8));
            bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
            bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
    }

    private static class ScreenContainer extends JPanel {
        private final JPanel centerPanel = new JPanel(new GridBagLayout());
        private JPanel westSpacer = null;

        public ScreenContainer(boolean showEmergency) {
            super(new BorderLayout());
            setBackground(BG_DARK);
            centerPanel.setBackground(BG_DARK);

            boolean hasDevBanner = developMode && currentDevBanner != null && !currentDevBanner.isBlank();
            if (showEmergency || hasDevBanner) {
                JPanel topBar = new JPanel(new BorderLayout());
                topBar.setBackground(BG_DARK);
                topBar.setBorder(new EmptyBorder(12, 28, 12, 28));

                if (hasDevBanner) {
                    JPanel devLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
                    devLeft.setBackground(BG_DARK);

                    JLabel devBadge = new JLabel("  DEV MODE  ");
                    devBadge.setFont(FONT_BOLD.deriveFont(11f));
                    devBadge.setForeground(Color.BLACK);
                    devBadge.setBackground(new Color(245, 158, 11));
                    devBadge.setOpaque(true);
                    devBadge.setBorder(new EmptyBorder(3, 6, 3, 6));
                    devLeft.add(devBadge);

                    JLabel devText = new JLabel(currentDevBanner);
                    devText.setFont(FONT_SEMIBOLD.deriveFont(13f));
                    devText.setForeground(new Color(253, 230, 138));
                    devLeft.add(devText);

                    topBar.add(devLeft, BorderLayout.WEST);
                }

                if (showEmergency) {
                    JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
                    rightPanel.setBackground(BG_DARK);

                    ModernButton emergencyBtn = new ModernButton(
                            "Salir del juego",
                            new Color(136, 19, 55),
                            new Color(159, 18, 57),
                            new Color(190, 18, 60),
                            Color.WHITE,
                            12);
                    emergencyBtn.setFont(FONT_BOLD.deriveFont(14f));
                    emergencyBtn.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(new Color(220, 38, 38), 1, true),
                            new EmptyBorder(8, 18, 8, 18)));
                    emergencyBtn.setToolTipText("Salir y cerrar el juego en cualquier momento.");
                    emergencyBtn.addActionListener(e -> confirmAndExitExperiment());
                    rightPanel.add(emergencyBtn);

                    topBar.add(rightPanel, BorderLayout.EAST);
                }

                super.add(topBar, BorderLayout.NORTH);
            }

            if (shouldShowDevGenSidePanel()) {
                DevGenerationSidePanel sidePanel = new DevGenerationSidePanel(activeGenerationJob);
                super.add(sidePanel, BorderLayout.EAST);

                int screenWidth = 1920;
                try {
                    if (masterFrame != null && masterFrame.getWidth() > 0) {
                        screenWidth = masterFrame.getWidth();
                    } else if (!GraphicsEnvironment.isHeadless()) {
                        screenWidth = Toolkit.getDefaultToolkit().getScreenSize().width;
                    }
                } catch (Exception ignored) {
                }
                if (screenWidth >= 1500) {
                    westSpacer = new JPanel();
                    westSpacer.setOpaque(false);
                    int spacerW = devSidePanelCollapsed ? 42 : 315;
                    westSpacer.setPreferredSize(new Dimension(spacerW, 10));
                    super.add(westSpacer, BorderLayout.WEST);
                }
            }

            super.add(centerPanel, BorderLayout.CENTER);
        }

        public void updateWestSpacer() {
            if (westSpacer != null) {
                int spacerW = devSidePanelCollapsed ? 42 : 315;
                westSpacer.setPreferredSize(new Dimension(spacerW, 10));
            }
        }

        @Override
        public Component add(Component comp) {
            return centerPanel.add(comp);
        }

        @Override
        public void add(Component comp, Object constraints) {
            centerPanel.add(comp, constraints);
        }
    }

    private static JPanel createScreenContainer() {
        return createScreenContainer(true);
    }

    private static JPanel createScreenContainer(boolean showEmergency) {
        return new ScreenContainer(showEmergency);
    }

    public static void confirmAndExitExperiment() {
        JDialog dialog = new JDialog(masterFrame, "Salir del juego", true);
        dialog.setUndecorated(true);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(CARD_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 38, 38), 2, true),
                new EmptyBorder(32, 40, 32, 40)));

        JLabel title = new JLabel("¿Deseas salir del juego?");
        title.setFont(FONT_BOLD.deriveFont(26f));
        title.setForeground(new Color(254, 202, 202));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createRigidArea(new Dimension(0, 14)));

        JLabel msg = new JLabel("<html><body style='text-align: center; width: 440px; color: rgb(209, 213, 219); font-size: 15px;'>"
                + "Si sales ahora, la sesión de juego finalizará.<br><br>"
                + "¿Estás seguro/a de que deseas salir?</body></html>");
        msg.setFont(FONT_REGULAR.deriveFont(16f));
        msg.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(msg);
        panel.add(Box.createRigidArea(new Dimension(0, 26)));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 0));
        buttons.setBackground(CARD_BG);
        buttons.setAlignmentX(Component.CENTER_ALIGNMENT);

        ModernButton confirmBtn = new ModernButton(
                "Sí, salir del juego",
                new Color(185, 28, 28),
                new Color(220, 38, 38),
                new Color(127, 29, 29),
                Color.WHITE,
                10);
        confirmBtn.setFont(FONT_BOLD.deriveFont(16f));
        confirmBtn.addActionListener(e -> {
            dialog.dispose();
            terminateExperimentImmediately();
        });
        buttons.add(confirmBtn);

        ModernButton cancelBtn = new ModernButton(
                "Continuar jugando",
                new Color(45, 48, 62),
                new Color(60, 64, 82),
                new Color(35, 38, 50),
                TEXT_WHITE,
                10);
        cancelBtn.setFont(FONT_BOLD.deriveFont(16f));
        cancelBtn.addActionListener(e -> dialog.dispose());
        buttons.add(cancelBtn);

        panel.add(buttons);

        dialog.setContentPane(panel);
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.pack();
        dialog.setLocationRelativeTo(masterFrame);
        dialog.setVisible(true);
    }

    private static void terminateExperimentImmediately() {
        if (activeGenerationJob != null) {
            try {
                activeGenerationJob.cancel();
            } catch (Exception ignored) {}
        }
        if (activeGame != null) {
            try {
                activeGame.finishLevel();
            } catch (Exception ignored) {}
        }
        if (masterFrame != null) {
            try {
                GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().setFullScreenWindow(null);
                masterFrame.dispose();
            } catch (Exception ignored) {}
        }
        System.exit(0);
    }

    private static JPanel createModernCard(int width, int height) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CARD_BORDER, 1, true),
                new EmptyBorder(28, 36, 28, 36)));
        card.setPreferredSize(new Dimension(width, height));
        card.setMaximumSize(new Dimension(width, height));
        return card;
    }

    private static JPanel createModernCard(int width) {
        return createModernCard(width, 700);
    }

    private static JLabel createBadge(String text) {
        JLabel badge = new JLabel("  " + text + "  ");
        badge.setFont(FONT_BOLD.deriveFont(13f));
        badge.setForeground(new Color(199, 210, 254));
        badge.setOpaque(true);
        badge.setBackground(new Color(49, 46, 129));
        badge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(79, 70, 229), 1, true),
                new EmptyBorder(4, 10, 4, 10)));
        badge.setAlignmentX(Component.LEFT_ALIGNMENT);
        return badge;
    }

    private static JPanel createInfoBullet(String heading, String description) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setBackground(CARD_BG);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel h = new JLabel(heading);
        h.setFont(FONT_SEMIBOLD.deriveFont(18f));
        h.setForeground(TEXT_WHITE);
        row.add(h);
        row.add(Box.createRigidArea(new Dimension(0, 4)));

        JLabel d = new JLabel("<html><body style='width: 860px; color: rgb(156, 163, 175);'>&nbsp;&nbsp;" + description
                + "</body></html>");
        d.setFont(FONT_REGULAR.deriveFont(16f));
        row.add(d);
        return row;
    }

    private static void applyDarkScrollbar(JScrollPane scrollPane) {
        scrollPane.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                this.thumbColor = new Color(55, 60, 80);
                this.trackColor = CARD_BG;
            }

            @Override
            protected JButton createDecreaseButton(int orientation) {
                return createZeroButton();
            }

            @Override
            protected JButton createIncreaseButton(int orientation) {
                return createZeroButton();
            }

            private JButton createZeroButton() {
                JButton jbutton = new JButton();
                jbutton.setPreferredSize(new Dimension(0, 0));
                return jbutton;
            }
        });
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
    }

    static class ModernButton extends JButton {
        private Color normalBg;
        private Color hoverBg;
        private Color pressedBg;
        private int radius;
        private boolean isHovered = false;
        private boolean isPressed = false;

        public ModernButton(String text, Color normalBg, Color hoverBg, Color pressedBg, Color textColor, int radius) {
            this(text, normalBg, hoverBg, pressedBg, textColor, radius, 14, 28, 17f);
        }

        public ModernButton(String text, Color normalBg, Color hoverBg, Color pressedBg, Color textColor, int radius,
                int padTopBottom, int padLeftRight, float fontSize) {
            super(text);
            this.normalBg = normalBg;
            this.hoverBg = hoverBg;
            this.pressedBg = pressedBg;
            this.radius = radius;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(FONT_BOLD.deriveFont(fontSize));
            setForeground(textColor);
            setBorder(new EmptyBorder(padTopBottom, padLeftRight, padTopBottom, padLeftRight));

            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) {
                    if (isEnabled()) {
                        isHovered = true;
                        repaint();
                    }
                }

                public void mouseExited(MouseEvent e) {
                    isHovered = false;
                    isPressed = false;
                    repaint();
                }

                public void mousePressed(MouseEvent e) {
                    if (isEnabled()) {
                        isPressed = true;
                        repaint();
                    }
                }

                public void mouseReleased(MouseEvent e) {
                    isPressed = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color bg;
            if (!isEnabled()) {
                bg = new Color(24, 27, 36);
            } else if (isPressed) {
                bg = pressedBg;
            } else if (isHovered) {
                bg = hoverBg;
            } else {
                bg = normalBg;
            }
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static class LikertPill extends JToggleButton {
        public LikertPill(String val) {
            super(val);
            setFont(FONT_BOLD.deriveFont(18f));
            setPreferredSize(new Dimension(54, 50));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean selected = isSelected();
            Color bg = selected ? ACCENT_PRIMARY
                    : (getModel().isRollover() ? new Color(42, 47, 65) : new Color(32, 36, 50));
            Color border = selected ? new Color(129, 140, 248) : new Color(55, 60, 80);
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g2.setColor(border);
            g2.setStroke(new BasicStroke(selected ? 2.5f : 1f));
            g2.drawRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 12, 12);
            g2.setColor(selected ? Color.WHITE : new Color(210, 215, 225));
            FontMetrics fm = g2.getFontMetrics(getFont());
            int x = (getWidth() - fm.stringWidth(getText())) / 2;
            int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(getText(), x, y);
            g2.dispose();
        }
    }

    static class ChoiceCard extends JToggleButton {
        public ChoiceCard(String text) {
            this(text, 920, 50);
        }

        public ChoiceCard(String text, int width, int height) {
            super(text);
            setFont(FONT_REGULAR.deriveFont(16f));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMaximumSize(new Dimension(width, height));
            setPreferredSize(new Dimension(width, height));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean selected = isSelected();
            Color bg = selected ? new Color(49, 46, 129)
                    : (getModel().isRollover() ? new Color(38, 43, 60) : new Color(28, 31, 44));
            Color border = selected ? ACCENT_PRIMARY : new Color(50, 56, 76);
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g2.setColor(border);
            g2.setStroke(new BasicStroke(selected ? 2f : 1f));
            g2.drawRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 12, 12);

            // Radio indicator circle
            int circleD = 18;
            int circleX = 16;
            int circleY = (getHeight() - circleD) / 2;
            g2.setColor(selected ? ACCENT_PRIMARY : new Color(60, 66, 88));
            g2.fillOval(circleX, circleY, circleD, circleD);
            if (selected) {
                g2.setColor(Color.WHITE);
                g2.fillOval(circleX + 5, circleY + 5, 8, 8);
            }

            g2.setColor(selected ? Color.WHITE : new Color(225, 230, 240));
            FontMetrics fm = g2.getFontMetrics(getFont());
            int textX = circleX + circleD + 14;
            int textY = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(getText(), textX, textY);
            g2.dispose();
        }
    }

    static class ModernTextField extends JTextField {
        public ModernTextField(int columns) {
            super(columns);
            setFont(FONT_SEMIBOLD.deriveFont(18f));
            setBackground(new Color(28, 31, 44));
            setForeground(TEXT_WHITE);
            setCaretColor(TEXT_WHITE);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(55, 60, 80), 1, true),
                    new EmptyBorder(10, 14, 10, 14)));
            setMaximumSize(new Dimension(180, 48));
            setPreferredSize(new Dimension(180, 48));
        }
    }

    // ==========================================
    // DATA AND UTILITY HELPERS
    // ==========================================

    private static List<Step> buildSteps(String group, List<Path> normal, List<Path> human) {
        List<Step> steps = new ArrayList<>();
        for (String code : SEQUENCES.get(group).split(" ")) {
            int index = code.charAt(1) - '1';
            steps.add(switch (code.charAt(0)) {
                case 'A' -> new Step('P', index, null);
                case 'B' -> new Step('N', index, normal.get(index));
                default -> new Step('H', index, human.get(index));
            });
        }
        return steps;
    }

    private static Properties loadConfig() throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(EXPERIMENT.resolve("config.properties"), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    private static Path resolveDirectory(Properties config, String dirKey, String fallbackKey, String defaultPath) {
        String val = config.getProperty(dirKey);
        if (val == null || val.isBlank()) {
            val = config.getProperty(fallbackKey);
        }
        if (val != null && !val.isBlank()) {
            String candidate = val.trim();
            if (candidate.contains(",")) {
                String first = candidate.split(",")[0].trim();
                Path p = SRC.resolve(first);
                if (Files.isRegularFile(p) && p.getParent() != null) {
                    return p.getParent();
                }
            } else {
                Path p = SRC.resolve(candidate);
                if (Files.isDirectory(p)) {
                    return p;
                } else if (Files.isRegularFile(p) && p.getParent() != null) {
                    return p.getParent();
                }
            }
        }
        return SRC.resolve(defaultPath);
    }

    private static List<Path> randomLevelsFromDir(Path dir, int count, List<Path> exclude) throws IOException {
        List<Path> pool = new ArrayList<>();
        if (Files.exists(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                pool = files
                        .filter(f -> f.getFileName().toString().endsWith(".txt"))
                        .filter(f -> exclude == null || exclude.stream().noneMatch(e -> e.getFileName().equals(f.getFileName())))
                        .collect(Collectors.toList());
            }
        }
        if (pool.size() < count && Files.exists(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                pool = files.filter(f -> f.getFileName().toString().endsWith(".txt")).collect(Collectors.toList());
            }
        }
        if (pool.isEmpty()) {
            throw new IllegalStateException("No se encontraron niveles jugables (.txt) en " + dir);
        }
        java.util.Collections.shuffle(pool);
        List<Path> chosen = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            chosen.add(pool.get(i % pool.size()));
        }
        return chosen;
    }

    private static List<Path> pathList(Properties config, String key) {
        List<Path> paths = Arrays.stream(config.getProperty(key).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(SRC::resolve)
                .collect(Collectors.toList());
        if (paths.size() < 2) {
            throw new IllegalStateException("config.properties: '" + key + "' necesita al menos 2 niveles");
        }
        return paths;
    }

    private static List<Path> randomHumanLevels(Properties config, List<Path> exclude, int count) throws IOException {
        Path humanDir = SRC.resolve(config.getProperty("human_dir").trim());
        List<Path> pool = new ArrayList<>();
        if (Files.exists(humanDir)) {
            try (Stream<Path> files = Files.list(humanDir)) {
                pool = files
                        .filter(f -> f.getFileName().toString().endsWith(".txt"))
                        .filter(f -> exclude.stream().noneMatch(e -> e.getFileName().equals(f.getFileName())))
                        .collect(Collectors.toList());
            }
        }
        if (pool.size() < count && Files.exists(humanDir)) {
            try (Stream<Path> files = Files.list(humanDir)) {
                pool = files.filter(f -> f.getFileName().toString().endsWith(".txt")).collect(Collectors.toList());
            }
        }
        if (pool.size() < count) {
            Path famDir = SRC.resolve("levels/familiarization");
            if (Files.exists(famDir)) {
                try (Stream<Path> files = Files.list(famDir)) {
                    files.filter(f -> f.getFileName().toString().endsWith(".txt")).forEach(pool::add);
                }
            }
            Path genDir = SRC.resolve("levels/generics");
            if (Files.exists(genDir)) {
                try (Stream<Path> files = Files.list(genDir)) {
                    files.filter(f -> f.getFileName().toString().endsWith(".txt")).forEach(pool::add);
                }
            }
        }
        if (pool.isEmpty()) {
            throw new IllegalStateException("No se encontraron niveles jugables en " + humanDir);
        }
        java.util.Collections.shuffle(pool);
        List<Path> chosen = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            chosen.add(pool.get(i % pool.size()));
        }
        return chosen;
    }

    private static String assignGroup() throws IOException {
        int[] counts = new int[GROUPS.length];
        if (Files.isDirectory(RESULTS)) {
            try (Stream<Path> dirs = Files.list(RESULTS)) {
                for (Path d : (Iterable<Path>) dirs::iterator) {
                    Path meta = d.resolve("participant.txt");
                    if (!Files.exists(meta)) {
                        continue;
                    }
                    String text = Files.readString(meta);
                    for (int g = 0; g < GROUPS.length; g++) {
                        if (text.contains("group=" + GROUPS[g])) {
                            counts[g]++;
                        }
                    }
                }
            }
        }
        int min = Arrays.stream(counts).min().getAsInt();
        List<String> candidates = new ArrayList<>();
        for (int g = 0; g < GROUPS.length; g++) {
            if (counts[g] == min) {
                candidates.add(GROUPS[g]);
            }
        }
        return candidates.get(new Random().nextInt(candidates.size()));
    }

    private static List<Question> loadQuestions() throws IOException {
        List<Question> questions = new ArrayList<>();
        for (String line : Files.readAllLines(EXPERIMENT.resolve("preguntas.tsv"), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] c = line.split("\t", -1);
            if (c.length < 5) {
                throw new IllegalStateException("preguntas.tsv: línea con menos de 5 columnas: " + line);
            }
            questions.add(new Question(c[0].trim(), c[1].trim(), c[2].trim(), c[3].trim(), c[4].trim()));
        }
        return questions;
    }

    private static List<Question> questionsOf(List<Question> all, String block) {
        return all.stream().filter(q -> q.block().equals(block)).collect(Collectors.toList());
    }

    private static List<String> ids(List<Question> questions) {
        return questions.stream().map(Question::id).collect(Collectors.toList());
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }

    private static void saveRow(Path tsv, List<String> headers, List<String> values) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (!Files.exists(tsv)) {
            sb.append(String.join("\t", headers)).append('\n');
        }
        sb.append(values.stream().map(v -> v.replaceAll("[\\t\\r\\n]+", " ")).collect(Collectors.joining("\t")))
                .append('\n');
        Files.writeString(tsv, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static class GenerationJob {
        private final String python;
        private final Path dir;
        private final int totalLevels = 2;
        private final List<CompletableFuture<Path>> futures = new ArrayList<>();
        private final BlockingQueue<Integer> queue = new LinkedBlockingQueue<>();
        private final ConcurrentHashMap<Integer, Path> telemetries = new ConcurrentHashMap<>();
        private final AtomicReferenceArray<GenStage> stages = new AtomicReferenceArray<>(2);
        private final AtomicReferenceArray<String> stageDetails = new AtomicReferenceArray<>(2);
        private final AtomicReferenceArray<String> prompts = new AtomicReferenceArray<>(2);
        private final AtomicReferenceArray<Path> resultPaths = new AtomicReferenceArray<>(2);
        private volatile Process current;
        private volatile boolean cancelled;
        private volatile boolean started;
        private volatile long jobStartTime = 0;
        private volatile Thread workerThread;

        GenerationJob(String python, Path dir) {
            this.python = python;
            this.dir = dir;
            for (int i = 0; i < totalLevels; i++) {
                futures.add(new CompletableFuture<>());
                stages.set(i, GenStage.PENDING);
                stageDetails.set(i, "Pendiente de telemetría (Familiarización " + (i + 1) + ")");
            }
        }

        synchronized void enqueue(int k, Path telemetry) {
            if (k < 0 || k >= totalLevels) return;
            if (telemetries.containsKey(k)) return;
            telemetries.put(k, telemetry);
            if (stages.get(k) == GenStage.PENDING) {
                stages.set(k, GenStage.QUEUED);
                stageDetails.set(k, "En cola para generación");
            }
            if (!started) {
                jobStartTime = System.currentTimeMillis();
                started = true;
            }
            ensureWorkerStarted();
            queue.offer(k);
            System.out.println("[DEV] Nivel personalizado " + (k + 1) + " encolado inmediatamente (" + telemetry.getFileName() + ")");
        }

        synchronized void start(List<Path> telemetriesList) {
            if (telemetriesList == null) return;
            for (int k = 0; k < telemetriesList.size(); k++) {
                enqueue(k, telemetriesList.get(k));
            }
        }

        private synchronized void ensureWorkerStarted() {
            if (workerThread != null && workerThread.isAlive()) {
                return;
            }
            workerThread = new Thread(() -> {
                while (!cancelled) {
                    try {
                        Integer k = queue.poll(1, TimeUnit.SECONDS);
                        if (k == null) {
                            if (isAllDone()) {
                                break;
                            }
                            continue;
                        }
                        Path tel = telemetries.get(k);
                        if (tel != null) {
                            try {
                                Path res = generate(k, tel);
                                resultPaths.set(k, res);
                                futures.get(k).complete(res);
                            } catch (Exception e) {
                                stages.set(k, GenStage.FAILED);
                                stageDetails.set(k, "Error: " + e.getMessage());
                                futures.get(k).completeExceptionally(e);
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "level-generation-worker");
            workerThread.setDaemon(true);
            workerThread.start();
        }

        boolean isStarted() {
            return started;
        }

        boolean isAllDone() {
            if (!started) return false;
            for (int i = 0; i < totalLevels; i++) {
                GenStage s = stages.get(i);
                if (s != GenStage.READY && s != GenStage.FALLBACK_READY && s != GenStage.FAILED) {
                    return false;
                }
            }
            return true;
        }

        GenStage getStage(int k) {
            return (k >= 0 && k < totalLevels) ? stages.get(k) : GenStage.PENDING;
        }

        String getStageDetail(int k) {
            return (k >= 0 && k < totalLevels) ? stageDetails.get(k) : "";
        }

        int getProgressPercent(int k) {
            if (k < 0 || k >= totalLevels) return 0;
            return switch (stages.get(k)) {
                case PENDING -> 0;
                case QUEUED -> 10;
                case OLLAMA -> 35;
                case MARIOGPT -> 75;
                case SANITIZING -> 92;
                case READY, FALLBACK_READY -> 100;
                case FAILED -> 0;
            };
        }

        long getElapsedSeconds() {
            if (jobStartTime <= 0) return 0;
            return (System.currentTimeMillis() - jobStartTime) / 1000;
        }

        private Path generate(int k, Path telemetry) {
            Path outDir = dir.resolve("generated").resolve("gen" + (k + 1));
            try {
                Files.createDirectories(outDir);
                System.out.println("\n=== Generando nivel personalizado " + (k + 1) + " ===");

                stages.set(k, GenStage.OLLAMA);
                stageDetails.set(k, "Analizando telemetría con Ollama...");
                String prompt = null;
                try {
                    ProcessBuilder promptBuilder = new ProcessBuilder(python, "tools/telemetry_to_mariogpt_prompt.py",
                            "--telemetry", telemetry.toAbsolutePath().toString(), "--allow-cloud", "--json-output");
                    promptBuilder.directory(SRC.toFile());
                    promptBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
                    Process promptProcess = promptBuilder.start();
                    current = promptProcess;
                    String promptOutput = new String(promptProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    int promptExit = promptProcess.waitFor();
                    if (!cancelled && promptExit == 0) {
                        prompt = extractPrompt(promptOutput);
                        prompts.set(k, prompt);
                        System.out.println("Prompt para MarioGPT: " + prompt);
                    }
                } catch (Exception pe) {
                    System.err.println("Aviso al obtener prompt de Ollama: " + pe.getMessage());
                }

                if (prompt == null || prompt.isBlank()) {
                    prompt = "difficulty=medium; route=high; jump=medium; enemies=low; hazards=medium; rewards=coins";
                    prompts.set(k, prompt);
                    System.out.println("Usando prompt base para MarioGPT: " + prompt);
                }

                if (!cancelled) {
                    stages.set(k, GenStage.MARIOGPT);
                    stageDetails.set(k, "Generando mapa con MarioGPT...");
                    ProcessBuilder builder = new ProcessBuilder(python, "tools/mario_gpt_generate.py",
                            "--prompt", prompt,
                            "--output-dir", outDir.toAbsolutePath().toString());
                    builder.directory(SRC.toFile());
                    builder.inheritIO();
                    Process process = builder.start();
                    current = process;
                    int exit = process.waitFor();
                    if (exit == 0) {
                        stages.set(k, GenStage.SANITIZING);
                        stageDetails.set(k, "Sanitizando y validando estructura...");
                        try (Stream<Path> files = Files.list(outDir)) {
                            Optional<Path> gen = files.filter(f -> f.getFileName().toString().endsWith(".txt")).findFirst();
                            if (gen.isPresent()) {
                                Path genFile = gen.get();
                                String raw = Files.readString(genFile);
                                String sanitized = sanitizeLevel(raw);
                                Files.writeString(genFile, sanitized, StandardCharsets.UTF_8);
                                stages.set(k, GenStage.READY);
                                stageDetails.set(k, "✔ Listo (" + genFile.getFileName() + ")");
                                resultPaths.set(k, genFile);
                                return genFile;
                            }
                        }
                    } else {
                        System.err.println("mario_gpt_generate.py terminó con código " + exit);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error durante la generación de nivel: " + e.getMessage());
            }

            stages.set(k, GenStage.FALLBACK_READY);
            stageDetails.set(k, "Nivel de respaldo listo");
            Path fallback = resolveFallbackLevel(k, outDir);
            resultPaths.set(k, fallback);
            return fallback;
        }

        private Path resolveFallbackLevel(int k, Path outDir) {
            try {
                Files.createDirectories(outDir);
                Path genericDir = SRC.resolve("levels/generics");
                Path genericFile = null;
                if (Files.exists(genericDir)) {
                    try (Stream<Path> s = Files.list(genericDir)) {
                        List<Path> list = s.filter(p -> p.toString().endsWith(".txt")).collect(Collectors.toList());
                        if (!list.isEmpty()) {
                            genericFile = list.get(k % list.size());
                        }
                    }
                }
                if (genericFile == null || !Files.exists(genericFile)) {
                    Path famDir = SRC.resolve("levels/familiarization");
                    if (Files.exists(famDir)) {
                        try (Stream<Path> s = Files.list(famDir)) {
                            genericFile = s.filter(p -> p.toString().endsWith(".txt")).findFirst().orElse(null);
                        }
                    }
                }
                if (genericFile != null && Files.exists(genericFile)) {
                    Path fallbackTarget = outDir.resolve("Nivel generado " + (k + 1) + ".txt");
                    String raw = Files.readString(genericFile);
                    String sanitized = sanitizeLevel(raw);
                    Files.writeString(fallbackTarget, sanitized, StandardCharsets.UTF_8);
                    System.out.println("Se utilizó el nivel de respaldo: " + fallbackTarget);
                    return fallbackTarget;
                }
            } catch (Exception e) {
                System.err.println("Error al copiar nivel de respaldo: " + e.getMessage());
            }
            return SRC.resolve("levels/generics/generic 1.txt");
        }

        private static String extractPrompt(String output) {
            String text = output.strip();
            int start = text.indexOf("{\"prompt\"");
            if (start < 0) {
                throw new IllegalStateException("No se pudo leer el prompt de Ollama: " + text);
            }
            int i = text.indexOf('"', text.indexOf(':', start) + 1) + 1;
            StringBuilder sb = new StringBuilder();
            while (i < text.length()) {
                char c = text.charAt(i++);
                if (c == '"') {
                    break;
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = text.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(text.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(e);
                }
            }
            if (sb.isEmpty()) {
                throw new IllegalStateException("El prompt de Ollama llegó vacío");
            }
            return sb.toString();
        }

        boolean isReady(int index) {
            if (index < 0 || index >= futures.size()) {
                return false;
            }
            return futures.get(index).isDone();
        }

        Path await(int index) throws Exception {
            CompletableFuture<Path> future = futures.get(index);
            return future.get();
        }

        void cancel() {
            cancelled = true;
            for (int i = 0; i < totalLevels; i++) {
                if (!futures.get(i).isDone()) {
                    stages.set(i, GenStage.FAILED);
                    stageDetails.set(i, "Cancelado");
                    futures.get(i).completeExceptionally(new IllegalStateException("Generación cancelada"));
                }
            }
            Process process = current;
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
