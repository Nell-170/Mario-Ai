import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import engine.core.MarioGame;
import engine.core.MarioResult;

public class ExperimentRunner {
    private static final Path SRC = Paths.get("../src");
    private static final Path EXPERIMENT = SRC.resolve("experiment");
    private static final Path RESULTS = EXPERIMENT.resolve("results");
    // Cuadrado latino de Williams 6x6. Letras: A = personalizado (P), B = generico (N), C = humano (H); el numero es el nivel (1 o 2) dentro del tipo.
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
    private record Step(char type, int index, Path level) {}

    private record Question(String id, String block, String type, String text, String options) {}

    public static void main(String[] args) throws Exception {
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

        GenerationJob generation = new GenerationJob(python, dir);
        try {
            info("Bienvenido. Participante: " + id + "\nTu secuencia es: " + group
                    + "\n\nPrimero jugarás dos niveles de familiarización.");
            List<Path> famTelemetries = new ArrayList<>();
            for (int f = 0; f < familiarization.size(); f++) {
                info("Nivel de familiarización " + (f + 1) + " de " + familiarization.size()
                        + ". Pulsa Aceptar para comenzar.");
                famTelemetries.add(playLevel(familiarization.get(f), timer,
                        dir.resolve("telemetry_fam" + (f + 1) + ".json")));
            }
            if (skipGeneration) {
                System.out.println("Modo prueba: se omite la generación; los niveles personalizados se sustituyen por niveles distintos de nivel0.");
            } else {
                generation.start(famTelemetries);
            }

            if (!showConsent()) {
                generation.cancel();
                deleteRecursively(dir);
                if (choose("No aceptaste participar, así que tus datos no se usarán en el experimento."
                        + "\n\n¿Quieres jugar de todos modos?", "Sí, jugar", "No, terminar")) {
                    freePlay(config, normal, timer);
                }
                info("Gracias por tu tiempo en esta actividad. La sesion ha finalizado.");
                System.exit(0);
            }

            List<Question> questions = loadQuestions();
            List<Question> initial = questionsOf(questions, "initial");
            List<Question> perLevel = questionsOf(questions, "level");
            List<String> initialAnswers = new ArrayList<>(List.of(id, group));
            initialAnswers.addAll(Arrays.asList(ask("Encuesta inicial", "Responde las siguientes preguntas sobre ti.", initial)));
            saveRow(RESULTS.resolve("initial.tsv"), concat(List.of("id", "group"), ids(initial)), initialAnswers);

            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                info("Nivel " + (i + 1) + " de " + steps.size() + ". Pulsa Aceptar para comenzar.");
                Path levelFile = step.type() != 'P' ? step.level()
                        : skipGeneration ? substitutes.get(step.index()) : generation.await(step.index());
                playLevel(levelFile, timer, dir.resolve("telemetry_L" + (i + 1) + ".json"));

                List<String> row = new ArrayList<>(List.of(id, group, String.valueOf(i + 1),
                        step.type() + String.valueOf(step.index() + 1), levelFile.getFileName().toString()));
                row.addAll(Arrays.asList(ask("Cuestionario - Nivel " + (i + 1),
                        "Responde pensando en el nivel que acabas de jugar.", perLevel)));
                saveRow(RESULTS.resolve("responses.tsv"),
                        concat(List.of("id", "group", "level_number", "level_type", "level_file"), ids(perLevel)), row);
            }
            info("¡Muchas gracias por participar! El experimento ha finalizado.");
            if (choose("¿Quieres seguir jugando solo por diversión?\nLos niveles que juegues ahora no se registrarán.",
                    "Jugar por jugar", "Terminar")) {
                freePlay(config, normal, timer);
            }
            info("Gracias por tu tiempo. La sesión ha finalizado.");
        } catch (Exception e) {
            generation.cancel();
            JOptionPane.showMessageDialog(null, "Error en el experimento: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            throw e;
        }
        System.exit(0);
    }

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
        List<Path> pool;
        try (Stream<Path> files = Files.list(humanDir)) {
            pool = files
                    .filter(f -> f.getFileName().toString().endsWith(".txt"))
                    .filter(f -> exclude.stream().noneMatch(e -> e.getFileName().equals(f.getFileName())))
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
        if (telemetryFile != null) {
            Files.writeString(telemetryFile, PlayHuman.buildTelemetryJson(result, levelFile.toString(), timer, seed));
        }
        return telemetryFile;
    }

    private static boolean choose(String message, String yes, String no) {
        Object[] options = {yes, no};
        return JOptionPane.showOptionDialog(null, message, "Experimento", JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]) == 0;
    }

    private static void freePlay(Properties config, List<Path> normal, int timer) throws IOException {
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
        } while (choose("¿Quieres jugar otro nivel?", "Otro nivel", "Terminar"));
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

    private static String[] ask(String title, String intro, List<Question> questions) {
        JDialog dialog = new JDialog((java.awt.Frame) null, title, true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        List<Supplier<String>> getters = new ArrayList<>();
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        for (int q = 0; q < questions.size(); q++) {
            Question question = questions.get(q);
            JPanel row = new JPanel(new BorderLayout());
            row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            String text = question.text().replace("&", "&amp;").replace("<", "&lt;");
            row.add(new JLabel("<html><body style='width:520px'>" + (q + 1) + ". " + text + "</body></html>"),
                    BorderLayout.NORTH);
            String[] selected = {null};
            JPanel input = new JPanel();
            switch (question.type()) {
                case "number" -> {
                    JTextField field = new JTextField(6);
                    input.setLayout(new FlowLayout(FlowLayout.LEFT));
                    input.add(field);
                    getters.add(() -> {
                        String value = field.getText().trim();
                        return value.matches("\\d{1,3}") ? value : null;
                    });
                }
                case "choice" -> {
                    input.setLayout(new BoxLayout(input, BoxLayout.Y_AXIS));
                    ButtonGroup group = new ButtonGroup();
                    for (String option : question.options().split("\\|")) {
                        String[] parts = option.contains("=") ? option.split("=", 2) : new String[] {option, option};
                        String label = parts[0].equals(parts[1]) ? parts[1] : parts[0] + " - " + parts[1];
                        JRadioButton button = new JRadioButton(label);
                        button.addActionListener(e -> selected[0] = parts[0]);
                        group.add(button);
                        input.add(button);
                    }
                    getters.add(() -> selected[0]);
                }
                case "scale" -> {
                    String[] o = question.options().split("\\|");
                    input.setLayout(new BoxLayout(input, BoxLayout.Y_AXIS));
                    JPanel radios = new JPanel(new FlowLayout(FlowLayout.LEFT));
                    ButtonGroup group = new ButtonGroup();
                    for (int v = Integer.parseInt(o[0]); v <= Integer.parseInt(o[1]); v++) {
                        String value = String.valueOf(v);
                        JRadioButton button = new JRadioButton(value);
                        button.addActionListener(e -> selected[0] = value);
                        group.add(button);
                        radios.add(button);
                    }
                    input.add(radios);
                    input.add(new JLabel("   " + o[0] + " = " + o[2] + "   |   " + o[1] + " = " + o[3]));
                    getters.add(() -> selected[0]);
                }
                default -> throw new IllegalStateException("preguntas.tsv: tipo desconocido '" + question.type() + "'");
            }
            row.add(input, BorderLayout.CENTER);
            list.add(row);
        }

        JButton ok = new JButton("Continuar");
        ok.addActionListener(e -> {
            if (getters.stream().allMatch(g -> g.get() != null)) {
                dialog.dispose();
            } else {
                JOptionPane.showMessageDialog(dialog, "Responde todas las preguntas antes de continuar.");
            }
        });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER));
        south.add(ok);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(640, 480));
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.add(new JLabel("  " + intro), BorderLayout.NORTH);
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(south, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        return getters.stream().map(Supplier::get).toArray(String[]::new);
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
