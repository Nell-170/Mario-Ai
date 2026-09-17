import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Collectors;

import javax.swing.JOptionPane;

import engine.core.MarioGame;
import engine.core.MarioAgentEvent;
import engine.core.MarioEvent;
import engine.core.MarioResult;
import engine.helper.EventType;

public class PlayHuman {
    public static void main(String[] args) throws Exception {
        String levelPath = args.length > 0 ? args[0] : chooseRandomLevel();
        int timer = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        int sessionSeed = new Random().nextInt(1_000_000);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String sessionFileName = "session_" + timestamp + "_seed" + sessionSeed + ".json";
        String telemetryPath = args.length > 2 ? args[2] : "../src/telemetry/" + sessionFileName;
        System.out.println("Nivel:      " + levelPath);
        System.out.println("Seed:       " + sessionSeed);
        String level = new String(Files.readAllBytes(Paths.get(levelPath)));

        GameSession session = runSession(level, timer, sessionSeed);
        MarioResult result = session.result;

        System.out.println("Estado:     " + result.getGameStatus());
        System.out.println("Completado: " + (result.getCompletionPercentage() * 100) + "%");
        System.out.println("Muertes:    " + result.getKillsTotal());
        while (shouldRestart()) {
            session.close();
            sessionSeed = new Random().nextInt(1_000_000);
            session = runSession(level, timer, sessionSeed);
            result = session.result;
            System.out.println("Estado:     " + result.getGameStatus());
            System.out.println("Completado: " + (result.getCompletionPercentage() * 100) + "%");
            System.out.println("Muertes:    " + result.getKillsTotal());
        }
        session.close();
        System.out.println("Saltos:     " + result.getNumJumps());
        writeTelemetry(result, levelPath, timer, telemetryPath, sessionSeed);
        writeTelemetry(result, levelPath, timer, "../src/telemetry/latest.json", sessionSeed);
        writePointer(telemetryPath);
        System.out.println("Telemetria: " + telemetryPath);
    }

    private static GameSession runSession(String level, int timer, int sessionSeed) {
        MarioGame game = new MarioGame();
        MarioResult result = game.runGame(
                new agents.human.Agent(),
                level,
                timer,
                sessionSeed,
                true);
        return new GameSession(game, result);
    }

    private static class GameSession {
        private final MarioGame game;
        private final MarioResult result;

        private GameSession(MarioGame game, MarioResult result) {
            this.game = game;
            this.result = result;
        }

        private void close() {
            // Window is managed and disposed by MarioGame lifecycle
        }
    }

    private static boolean shouldRestart() {
        int choice = JOptionPane.showOptionDialog(
                null,
                "La partida terminó. ¿Quieres reiniciar este nivel?",
                "Partida terminada",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                new Object[] {"Reiniciar nivel", "Continuar"},
                "Continuar");
        return choice == 0;
    }

    private static void writePointer(String telemetryPath) throws Exception {
        Path pointer = Paths.get("../src/telemetry/.last_session");

        if (pointer.getParent() != null) {
            Files.createDirectories(pointer.getParent());
        }
        String fileName = Paths.get(telemetryPath).getFileName().toString();
        Files.write(pointer, fileName.getBytes());
    }

    private static String chooseRandomLevel() throws Exception {
        Path levelDirectory = Paths.get("../src/levels/nivel0");
        if (!Files.exists(levelDirectory)) {
            levelDirectory = Paths.get("../levels/nivel0");
        }
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
            MarioResult result, String levelPath, int timer, String telemetryPath, int sessionSeed)
            throws Exception {
        Path output = Paths.get(telemetryPath);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.write(output, buildTelemetryJson(result, levelPath, timer, sessionSeed).getBytes());
    }

    private static String buildTelemetryJson(
            MarioResult result, String levelPath, int timer, int sessionSeed) {
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
                + "  \"seed\": " + sessionSeed + ",\n"
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
