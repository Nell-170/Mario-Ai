import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import engine.core.MarioGame;
import engine.core.MarioResult;

public class PlayHuman {
    public static void main(String[] args) throws Exception {
        String levelPath = args.length > 0 ? args[0] : chooseRandomLevel();
        int timer = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        System.out.println("Nivel:      " + levelPath);
        String level = new String(Files.readAllBytes(Paths.get(levelPath)));

        MarioGame game = new MarioGame();
        MarioResult result = game.runGame(
                new agents.human.Agent(),
                level,
                timer,
                0,
                true);

        System.out.println("Estado:     " + result.getGameStatus());
        System.out.println("Completado: " + (result.getCompletionPercentage() * 100) + "%");
        System.out.println("Muertes:    " + result.getKillsTotal());
        System.out.println("Saltos:     " + result.getNumJumps());
    }

    private static String chooseRandomLevel() throws Exception {
        Path levelDirectory = Paths.get("../levels/nivel0");
        List<Path> levels;
        try (java.util.stream.Stream<Path> files = Files.list(levelDirectory)) {
            levels = files
                    .filter(path -> path.toString().endsWith(".txt"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        if (levels.isEmpty()) {
            throw new IllegalStateException("No levels found in " + levelDirectory);
        }
        return levels.get(new Random().nextInt(levels.size())).toString();
    }
}
