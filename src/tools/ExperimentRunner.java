import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import engine.core.MarioGame;
import engine.core.MarioResult;

public class ExperimentRunner {
    private static final Path SRC = Paths.get("../src");
    private static final Path EXPERIMENT = SRC.resolve("experiment");
    private static final Path RESULTS = EXPERIMENT.resolve("results");
    private static final String[] GROUPS = {"A", "B", "C"};
    private static final int SCALE_MAX = 7;

    private record Step(char type, Path level, int genIndex) {}

    public static void main(String[] args) throws Exception {
        String python = args.length > 0 ? args[0] : "python";
        Properties config = loadConfig();
        int timer = Integer.parseInt(config.getProperty("timer", "60").trim());
        Path familiarization = SRC.resolve(config.getProperty("familiarization").trim());
        List<Path> normal = pathList(config, "normal");
        List<Path> human = randomHumanLevels(config, familiarization, 2);

        String group = assignGroup();
        String id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_" + group;
        Path dir = RESULTS.resolve(id);
        Files.createDirectories(dir);

        List<Step> steps = buildSteps(group, normal, human);
        Files.writeString(dir.resolve("participant.txt"),
                "id=" + id + "\ngroup=" + group + "\nsteps="
                        + steps.stream().map(s -> String.valueOf(s.type())).collect(Collectors.joining(",")) + "\n");
        System.out.println("Participante: " + id + " (grupo " + group + ")");

        GenerationJob generation = new GenerationJob(python, dir);
        try {
            info("Bienvenido. Participante: " + id + "\nTu grupo es: " + group
                    + "\n\nPrimero jugarás un nivel de familiarización.");
            Path famTelemetry = playLevel(familiarization, timer, dir.resolve("telemetry_fam.json"));

            if (group.equals("A")) {
                generation.start(List.of(famTelemetry, famTelemetry));
            }

            if (!showConsent()) {
                generation.cancel();
                deleteRecursively(dir);
                info("Gracias por tu tiempo. El experimento ha finalizado.");
                System.exit(0);
            }

            List<String> questions = loadQuestions();
            List<Path> telemetries = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                info("Nivel " + (i + 1) + " de " + steps.size() + ". Pulsa Aceptar para comenzar.");
                Path levelFile = step.type() == 'P' ? generation.await(step.genIndex()) : step.level();
                Path telemetry = playLevel(levelFile, timer, dir.resolve("telemetry_L" + (i + 1) + ".json"));
                telemetries.add(telemetry);

                if (group.equals("B") && i == 1) {
                    generation.start(List.of(telemetries.get(0), telemetries.get(1)));
                } else if (group.equals("C") && i == 3) {
                    generation.start(List.of(telemetries.get(2), telemetries.get(3)));
                }
                saveResponses(dir, i + 1, step.type(), levelFile, showQuestionnaire(questions, i + 1));
            }
            info("¡Muchas gracias por participar! El experimento ha finalizado.");
        } catch (Exception e) {
            generation.cancel();
            JOptionPane.showMessageDialog(null, "Error en el experimento: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            throw e;
        }
        System.exit(0);
    }

    private static List<Step> buildSteps(String group, List<Path> normal, List<Path> human) {
        String layout = switch (group) {
            case "A" -> "PPNNHH";
            case "B" -> "NNPPHH";
            default -> "NNHHPP";
        };
        List<Step> steps = new ArrayList<>();
        int n = 0;
        int h = 0;
        int p = 0;
        for (char c : layout.toCharArray()) {
            switch (c) {
                case 'P' -> steps.add(new Step(c, null, p++));
                case 'N' -> steps.add(new Step(c, normal.get(n++), -1));
                default -> steps.add(new Step(c, human.get(h++), -1));
            }
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

    private static List<Path> randomHumanLevels(Properties config, Path exclude, int count) throws IOException {
        Path humanDir = SRC.resolve(config.getProperty("human_dir").trim());
        List<Path> pool;
        try (Stream<Path> files = Files.list(humanDir)) {
            pool = files
                    .filter(f -> f.getFileName().toString().endsWith(".txt"))
                    .filter(f -> !f.getFileName().equals(exclude.getFileName()))
                    .collect(Collectors.toList());
        }
        if (pool.size() < count) {
            throw new IllegalStateException("Se necesitan al menos " + count + " niveles en " + humanDir);
        }
        java.util.Collections.shuffle(pool);
        return new ArrayList<>(pool.subList(0, count));
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

    private static Path playLevel(Path levelFile, int timer, Path telemetryFile) throws IOException {
        String level = Files.readString(levelFile);
        int seed = new Random().nextInt(1_000_000);
        MarioGame game = new MarioGame();
        MarioResult result = game.runGame(new agents.human.Agent(), level, timer, seed, true);
        game.closeWindow();
        Files.writeString(telemetryFile, PlayHuman.buildTelemetryJson(result, levelFile.toString(), timer, seed));
        return telemetryFile;
    }

    private static void info(String message) {
        JOptionPane.showMessageDialog(null, message, "Experimento", JOptionPane.INFORMATION_MESSAGE);
    }

    private static boolean showConsent() throws IOException {
        String text = Files.readString(EXPERIMENT.resolve("consentimiento.txt"), StandardCharsets.UTF_8);
        JDialog dialog = new JDialog((java.awt.Frame) null, "Consentimiento informado", true);
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(640, 420));

        boolean[] accepted = {false};
        JButton accept = new JButton("Acepto participar");
        JButton decline = new JButton("No acepto");
        accept.addActionListener(e -> {
            accepted[0] = true;
            dialog.dispose();
        });
        decline.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttons.add(accept);
        buttons.add(decline);

        dialog.setLayout(new BorderLayout());
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        return accepted[0];
    }

    private static List<String> loadQuestions() throws IOException {
        return Files.readAllLines(EXPERIMENT.resolve("minipxi.txt"), StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .collect(Collectors.toList());
    }

    private static int[] showQuestionnaire(List<String> questions, int levelNumber) {
        JDialog dialog = new JDialog((java.awt.Frame) null, "Cuestionario - Nivel " + levelNumber, true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        int[] answers = new int[questions.size()];
        JButton ok = new JButton("Continuar");
        ok.setEnabled(questions.isEmpty());

        JPanel list = new JPanel(new GridLayout(questions.size(), 1, 0, 8));
        for (int q = 0; q < questions.size(); q++) {
            final int index = q;
            JPanel row = new JPanel(new BorderLayout());
            row.add(new JLabel((q + 1) + ". " + questions.get(q)), BorderLayout.NORTH);
            JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT));
            ButtonGroup group = new ButtonGroup();
            for (int v = 1; v <= SCALE_MAX; v++) {
                final int value = v;
                JRadioButton button = new JRadioButton(String.valueOf(v));
                button.addActionListener(e -> {
                    answers[index] = value;
                    ok.setEnabled(Arrays.stream(answers).allMatch(a -> a > 0));
                });
                group.add(button);
                options.add(button);
            }
            row.add(options, BorderLayout.CENTER);
            list.add(row);
        }
        ok.addActionListener(e -> dialog.dispose());

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER));
        south.add(ok);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.add(new JLabel("Indica de 1 (totalmente en desacuerdo) a " + SCALE_MAX
                + " (totalmente de acuerdo)."), BorderLayout.NORTH);
        dialog.add(new JScrollPane(list), BorderLayout.CENTER);
        dialog.add(south, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        return answers;
    }

    private static void saveResponses(Path dir, int levelNumber, char type, Path levelFile, int[] answers)
            throws IOException {
        Path csv = dir.resolve("responses.csv");
        StringBuilder sb = new StringBuilder();
        if (!Files.exists(csv)) {
            sb.append("level_number,level_type,level_file");
            for (int q = 1; q <= answers.length; q++) {
                sb.append(",q").append(q);
            }
            sb.append('\n');
        }
        sb.append(levelNumber).append(',').append(type).append(',').append(levelFile.getFileName());
        for (int a : answers) {
            sb.append(',').append(a);
        }
        sb.append('\n');
        Files.writeString(csv, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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

        private Path generate(int k, Path telemetry) throws Exception {
            Path outDir = dir.resolve("generated").resolve("gen" + (k + 1));
            Files.createDirectories(outDir);
            System.out.println("\n=== Generando nivel personalizado " + (k + 1) + " ===");
            ProcessBuilder promptBuilder = new ProcessBuilder(python, "tools/telemetry_to_mariogpt_prompt.py",
                    "--telemetry", telemetry.toAbsolutePath().toString(), "--allow-cloud", "--json-output");
            promptBuilder.directory(SRC.toFile());
            promptBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process promptProcess = promptBuilder.start();
            current = promptProcess;
            String promptOutput = new String(promptProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int promptExit = promptProcess.waitFor();
            if (cancelled) {
                throw new IllegalStateException("Generación cancelada");
            }
            if (promptExit != 0) {
                throw new IllegalStateException("telemetry_to_mariogpt_prompt.py terminó con código " + promptExit);
            }
            String prompt = extractPrompt(promptOutput);
            System.out.println("Prompt para MarioGPT: " + prompt);

            ProcessBuilder builder = new ProcessBuilder(python, "tools/mario_gpt_generate.py",
                    "--prompt", prompt,
                    "--output-dir", outDir.toAbsolutePath().toString());
            builder.directory(SRC.toFile());
            builder.inheritIO();
            Process process = builder.start();
            current = process;
            int exit = process.waitFor();
            if (cancelled) {
                throw new IllegalStateException("Generación cancelada");
            }
            if (exit != 0) {
                throw new IllegalStateException("mario_gpt_generate.py terminó con código " + exit
                        + " (revisa la consola)");
            }
            try (Stream<Path> files = Files.list(outDir)) {
                return files.filter(f -> f.getFileName().toString().endsWith(".txt")).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No se generó ningún nivel en " + outDir));
            }
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
            if (future.isDone()) {
                return future.get();
            }
            JDialog waiting = new JDialog((java.awt.Frame) null, "Generando nivel", false);
            waiting.add(new JLabel("  Generando tu nivel personalizado, espera un momento...  "));
            waiting.pack();
            waiting.setLocationRelativeTo(null);
            waiting.setVisible(true);
            try {
                return future.get();
            } finally {
                waiting.dispose();
            }
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
