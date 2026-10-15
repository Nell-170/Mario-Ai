import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
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
        boolean skipGeneration = args.length > 1 && args[1].equals("--skip-generation");
        Properties config = loadConfig();
        int timer = Integer.parseInt(config.getProperty("timer", "60").trim());
        List<Path> familiarization = pathList(config, "familiarization");
        List<Path> normal = pathList(config, "normal");
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
                        + steps.stream().map(s -> String.valueOf(s.type())).collect(Collectors.joining(",")) + "\n");
        System.out.println("Participante: " + id + " (secuencia " + group + ")");

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
        MarioGame.setEmergencyExitHandler(() -> confirmAndExitExperiment());
        try {
            // 1. Welcome & Instructions Screen
            showWelcomeScreen();

            // 2. Familiarization Levels
            List<Path> famTelemetries = new ArrayList<>();
            for (int f = 0; f < familiarization.size(); f++) {
                showLevelIntroScreen("Familiarización (" + (f + 1) + " de " + familiarization.size() + ")",
                        "Nivel de preparación " + (f + 1),
                        "Jugarás este nivel previo para familiarizarte con el salto, velocidad y los controles.\n"
                                + "Controles: Flechas = Moverse | Flecha Arriba / S / Espacio = Saltar | A / Shift = Correr | R = Reiniciar | Q = Terminar",
                        "Comenzar nivel " + (f + 1));
                famTelemetries.add(playLevel(familiarization.get(f), timer,
                        dir.resolve("telemetry_fam" + (f + 1) + ".json")));
            }

            if (skipGeneration) {
                System.out.println(
                        "Modo prueba: se omite la generación; los niveles personalizados se sustituyen por niveles distintos de nivel0.");
            } else {
                generation.start(famTelemetries);
            }

            // 3. Informed Consent
            if (!showConsentScreen()) {
                generation.cancel();
                deleteRecursively(dir);
                if (showChoiceScreen("Consentimiento no aceptado",
                        "No has aceptado el consentimiento para registrar datos de la partida.\n\n"
                                + "¿Deseas jugar de todos modos libremente para divertirte?",
                        "Sí, jugar libremente", "No, salir del juego")) {
                    freePlay(config, normal, timer);
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
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
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
                    showGeneratingScreen(i + 1, steps.size());
                    levelFile = generation.await(step.index());
                }

                playLevel(levelFile, timer, dir.resolve("telemetry_L" + (i + 1) + ".json"));

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

            // 6. Final Thank You Screen
            boolean wantFreePlay = showChoiceScreen("¡Completado con éxito!",
                    "¡Muchísimas gracias por jugar y por tus respuestas!\n\n"
                            + "Has completado todos los niveles principales de la sesión.\n"
                            + "¿Deseas seguir jugando otros niveles libremente por diversión?",
                    "Jugar más niveles", "Finalizar y salir");
            if (wantFreePlay) {
                freePlay(config, normal, timer);
            }

            showExitScreen("¡Gracias por jugar!", "Esperamos que te hayas divertido. ¡Hasta la próxima!");
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

    private static Path playLevel(Path levelFile, int timer, Path telemetryFile) throws IOException {
        String level = Files.readString(levelFile);
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

    private static void freePlay(Properties config, List<Path> normal, int timer) throws Exception {
        Path humanDir = SRC.resolve(config.getProperty("human_dir").trim());
        List<Path> pool;
        try (Stream<Path> files = Files.list(humanDir)) {
            pool = files.filter(f -> f.getFileName().toString().endsWith(".txt")).collect(Collectors.toList());
        }
        pool.addAll(normal);
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
                "Salir del juego"));
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
            JPanel card = createModernCard(860, 460);

            card.add(createBadge(badge));
            card.add(Box.createRigidArea(new Dimension(0, 14)));

            JLabel title = new JLabel(titleText);
            title.setFont(FONT_BOLD.deriveFont(32f));
            title.setForeground(TEXT_WHITE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(title);
            card.add(Box.createRigidArea(new Dimension(0, 16)));

            for (String paragraph : descText.split("\n")) {
                JLabel p = new JLabel(
                        "<html><body style='width: 780px; color: rgb(156, 163, 175);'>" + paragraph + "</body></html>");
                p.setFont(FONT_REGULAR.deriveFont(18f));
                p.setAlignmentX(Component.LEFT_ALIGNMENT);
                card.add(p);
                card.add(Box.createRigidArea(new Dimension(0, 8)));
            }
            card.add(Box.createRigidArea(new Dimension(0, 24)));

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
            card.add(Box.createRigidArea(new Dimension(0, 24)));

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
    // CUSTOM SWING UI STYLING & COMPONENTS
    // ==========================================

    private static class ScreenContainer extends JPanel {
        private final JPanel centerPanel = new JPanel(new GridBagLayout());

        public ScreenContainer(boolean showEmergency) {
            super(new BorderLayout());
            setBackground(BG_DARK);
            centerPanel.setBackground(BG_DARK);

            if (showEmergency) {
                JPanel topBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 32, 18));
                topBar.setBackground(BG_DARK);

                ModernButton emergencyBtn = new ModernButton(
                        "Salir del juego",
                        new Color(136, 19, 55),
                        new Color(159, 18, 57),
                        new Color(190, 18, 60),
                        Color.WHITE,
                        12);
                emergencyBtn.setFont(FONT_BOLD.deriveFont(15f));
                emergencyBtn.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(220, 38, 38), 1, true),
                        new EmptyBorder(10, 20, 10, 20)));
                emergencyBtn.setToolTipText("Salir y cerrar el juego en cualquier momento.");
                emergencyBtn.addActionListener(e -> confirmAndExitExperiment());
                topBar.add(emergencyBtn);
                super.add(topBar, BorderLayout.NORTH);
            }

            super.add(centerPanel, BorderLayout.CENTER);
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
            super(text);
            this.normalBg = normalBg;
            this.hoverBg = hoverBg;
            this.pressedBg = pressedBg;
            this.radius = radius;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(FONT_BOLD.deriveFont(17f));
            setForeground(textColor);
            setBorder(new EmptyBorder(14, 28, 14, 28));

            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) {
                    isHovered = true;
                    repaint();
                }

                public void mouseExited(MouseEvent e) {
                    isHovered = false;
                    isPressed = false;
                    repaint();
                }

                public void mousePressed(MouseEvent e) {
                    isPressed = true;
                    repaint();
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
            Color bg = isPressed ? pressedBg : (isHovered ? hoverBg : normalBg);
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
            super(text);
            setFont(FONT_REGULAR.deriveFont(16f));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMaximumSize(new Dimension(920, 50));
            setPreferredSize(new Dimension(920, 50));
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
        private volatile List<CompletableFuture<Path>> futures = new ArrayList<>();
        private volatile Process current;
        private volatile boolean cancelled;

        GenerationJob(String python, Path dir) {
            this.python = python;
            this.dir = dir;
        }

        void start(List<Path> telemetries) {
            List<CompletableFuture<Path>> jobs = new ArrayList<>();
            for (int k = 0; k < telemetries.size(); k++) {
                jobs.add(new CompletableFuture<>());
            }
            futures = jobs;
            Thread worker = new Thread(() -> {
                for (int k = 0; k < telemetries.size(); k++) {
                    if (cancelled) {
                        jobs.get(k).completeExceptionally(new IllegalStateException("Generación cancelada"));
                        continue;
                    }
                    try {
                        jobs.get(k).complete(generate(k, telemetries.get(k)));
                    } catch (Exception e) {
                        jobs.get(k).completeExceptionally(e);
                    }
                }
            }, "level-generation");
            worker.setDaemon(true);
            worker.start();
        }

        private Path generate(int k, Path telemetry) {
            Path outDir = dir.resolve("generated").resolve("gen" + (k + 1));
            try {
                Files.createDirectories(outDir);
                System.out.println("\n=== Generando nivel personalizado " + (k + 1) + " ===");

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
                        System.out.println("Prompt para MarioGPT: " + prompt);
                    }
                } catch (Exception pe) {
                    System.err.println("Aviso al obtener prompt de Ollama: " + pe.getMessage());
                }

                if (prompt == null || prompt.isBlank()) {
                    prompt = "difficulty=medium; route=high; jump=medium; enemies=low; hazards=medium; rewards=coins";
                    System.out.println("Usando prompt base para MarioGPT: " + prompt);
                }

                if (!cancelled) {
                    ProcessBuilder builder = new ProcessBuilder(python, "tools/mario_gpt_generate.py",
                            "--prompt", prompt,
                            "--output-dir", outDir.toAbsolutePath().toString());
                    builder.directory(SRC.toFile());
                    builder.inheritIO();
                    Process process = builder.start();
                    current = process;
                    int exit = process.waitFor();
                    if (exit == 0) {
                        try (Stream<Path> files = Files.list(outDir)) {
                            Optional<Path> gen = files.filter(f -> f.getFileName().toString().endsWith(".txt")).findFirst();
                            if (gen.isPresent()) {
                                return gen.get();
                            }
                        }
                    } else {
                        System.err.println("mario_gpt_generate.py terminó con código " + exit);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error durante la generación de nivel: " + e.getMessage());
            }

            return resolveFallbackLevel(k, outDir);
        }

        private Path resolveFallbackLevel(int k, Path outDir) {
            try {
                Files.createDirectories(outDir);
                Path genericDir = SRC.resolve("levels/generics");
                Path genericFile = genericDir.resolve("generic " + ((k % 2) + 1) + ".txt");
                if (!Files.exists(genericFile)) {
                    try (Stream<Path> s = Files.list(SRC.resolve("levels/familiarization"))) {
                        genericFile = s.filter(p -> p.toString().endsWith(".txt")).findFirst().orElse(null);
                    }
                }
                if (genericFile != null && Files.exists(genericFile)) {
                    Path fallbackTarget = outDir.resolve("Nivel generado " + (k + 1) + ".txt");
                    Files.copy(genericFile, fallbackTarget, StandardCopyOption.REPLACE_EXISTING);
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

        Path await(int index) throws Exception {
            CompletableFuture<Path> future = futures.get(index);
            return future.get();
        }

        void cancel() {
            cancelled = true;
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
