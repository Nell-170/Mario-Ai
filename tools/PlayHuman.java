import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Collectors;

import engine.core.MarioGame;
import engine.core.MarioAgentEvent;
import engine.core.MarioEvent;
import engine.core.MarioResult;
import engine.helper.EventType;

public class PlayHuman {
    public static void main(String[] args) throws Exception {
        String levelPath = args.length > 0 ? args[0] : chooseRandomLevel();
        int timer = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        String telemetryPath = args.length > 2 ? args[2] : "../telemetry/latest.json";
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
        writeTelemetry(result, levelPath, timer, telemetryPath);
        System.out.println("Telemetria: " + telemetryPath);
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

    private static void writeTelemetry(
            MarioResult result, String levelPath, int timer, String telemetryPath)
            throws Exception {
        Path output = Paths.get(telemetryPath);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.write(output, buildTelemetryJson(result, levelPath, timer).getBytes());
    }

    private static String buildTelemetryJson(
            MarioResult result, String levelPath, int timer) {
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
}
