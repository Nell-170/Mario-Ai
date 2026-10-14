import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import engine.core.MarioGame;
import engine.core.MarioResult;
import engine.helper.GameStatus;

/**
 * Parallel Headless Batch Validator.
 *
 * Usage: java ValidateLevels <inputDir> <outputDir> [timerSeconds]
 *
 * Runs the robinBaumgarten A* agent (no visuals) in parallel over every .txt level in
 * <inputDir> using all available CPU cores. Levels the agent completes (GameStatus.WIN)
 * are copied to <outputDir> and reported as "beatable".
 */
public class ValidateLevels {
    static String getLevel(String filepath) throws IOException {
        return new String(Files.readAllBytes(Paths.get(filepath)));
    }

    public static void main(String[] args) throws Exception {
        String inDir = args[0];
        String outDir = args[1];
        int timer = args.length > 2 ? Integer.parseInt(args[2]) : 40;
        new File(outDir).mkdirs();

        File[] files = new File(inDir).listFiles((d, n) -> n.endsWith(".txt"));
        if (files == null) files = new File[0];
        Arrays.sort(files);

        int total = files.length;
        AtomicInteger beatable = new AtomicInteger(0);
        AtomicInteger processed = new AtomicInteger(0);

        int numCores = Runtime.getRuntime().availableProcessors();
        System.out.printf("Iniciando validación: %d niveles en paralelo con %d núcleos CPU...%n", total, numCores);

        Arrays.stream(files).parallel().forEach(f -> {
            String name = f.getName();
            try {
                String level = getLevel(f.getAbsolutePath());
                MarioGame game = new MarioGame();
                MarioResult r = game.runGame(
                        new agents.robinBaumgarten.Agent(), level, timer, 0, false);
                boolean win = r.getGameStatus() == GameStatus.WIN;

                if (win) {
                    beatable.incrementAndGet();
                    Files.write(Paths.get(outDir, name), level.getBytes());
                }

                int done = processed.incrementAndGet();
                printProgress(done, total, beatable.get(), name, r.getGameStatus().toString());
            } catch (Throwable t) {
                int done = processed.incrementAndGet();
                printProgress(done, total, beatable.get(), name, "ERROR");
            }
        });

        System.out.println("\n========================================");
        System.out.printf("Validación finalizada: %d de %d niveles superables guardados en %s%n", beatable.get(), total, outDir);
    }

    private static synchronized void printProgress(int done, int total, int winCount, String name, String status) {
        int width = 20;
        int filled = total > 0 ? (done * width) / total : 0;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? "█" : "░");
        }
        int pct = total > 0 ? (done * 100) / total : 100;
        String shortName = name.length() > 22 ? name.substring(0, 19) + "..." : name;
        System.out.printf("\r\033[1;36m[%3d%%]\033[0m \033[1;32m[%s]\033[0m (%d/%d) [Superables: %d] %-22s -> %-8s",
                pct, bar.toString(), done, total, winCount, shortName, status);
        System.out.flush();
    }
}