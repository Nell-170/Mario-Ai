import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import engine.core.MarioGame;
import engine.core.MarioResult;
import engine.helper.GameStatus;

/**
 * Headless batch validator.
 *
 * Usage: java ValidateLevels <inputDir> <outputDir> [timerSeconds]
 *
 * Runs the robinBaumgarten A* agent (no visuals) over every .txt level in
 * <inputDir>. Levels the agent completes (GameStatus.WIN) are copied to
 * <outputDir> and reported as "beatable". Prints a per-level line plus a
 * final summary. Each level is wrapped in try/catch so one bad level never
 * aborts the whole batch.
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

        int beatable = 0, total = 0;
        for (File f : files) {
            total++;
            String name = f.getName();
            try {
                String level = getLevel(f.getAbsolutePath());
                MarioGame game = new MarioGame();
                MarioResult r = game.runGame(
                        new agents.robinBaumgarten.Agent(), level, timer, 0, false);
                boolean win = r.getGameStatus() == GameStatus.WIN;
                System.out.printf("%-24s %-8s completion=%.3f time_left=%d%n",
                        name, r.getGameStatus(), r.getCompletionPercentage(),
                        (int) Math.ceil(r.getRemainingTime() / 1000f));
                if (win) {
                    beatable++;
                    Files.write(Paths.get(outDir, name), level.getBytes());
                }
            } catch (Throwable t) {
                System.out.printf("%-24s ERROR    %s%n", name, t.toString());
            }
        }
        System.out.println("========================================");
        System.out.printf("BEATABLE %d / %d levels%n", beatable, total);
    }
}
