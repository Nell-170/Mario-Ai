import java.nio.file.Files;
import java.nio.file.Paths;

import engine.core.MarioGame;
import engine.core.MarioResult;

public class PlayHuman {
    public static void main(String[] args) throws Exception {
        String levelPath = args.length > 0
                ? args[0]
                : "../levels/nivel0/mm2_3005554.txt";
        int timer = args.length > 1 ? Integer.parseInt(args[1]) : 200;
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
}
