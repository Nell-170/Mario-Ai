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
        System.out.printf("Validando %d niveles en paralelo usando %d núcleos CPU...%n", total, numCores);

        Arrays.stream(files).parallel().forEach(f -> {
            String name = f.getName();
            try {
                String level = getLevel(f.getAbsolutePath());
                MarioGame game = new MarioGame();
                MarioResult r = game.runGame(
                        new agents.robinBaumgarten.Agent(), level, timer, 0, false);
                boolean win = r.getGameStatus() == GameStatus.WIN;

                int done = processed.incrementAndGet();
                System.out.printf("[%d/%d] %-24s %-8s completion=%.3f time_left=%d%n",
                        done, total, name, r.getGameStatus(), r.getCompletionPercentage(),
                        (int) Math.ceil(r.getRemainingTime() / 1000f));

                if (win) {
                    beatable.incrementAndGet();
                    Files.write(Paths.get(outDir, name), level.getBytes());
                }
            } catch (Throwable t) {
                processed.incrementAndGet();
                System.out.printf("%-24s ERROR    %s%n", name, t.toString());
            }
        });

        System.out.println("========================================");
        System.out.printf("BEATABLE %d / %d levels%n", beatable.get(), total);
    }
}