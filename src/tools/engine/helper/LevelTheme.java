package engine.helper;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;

/**
 * Temas visuales para los niveles en Mario AI Framework y Experimento.
 * Permite diferenciar visualmente la condición del nivel mediante la atmósfera y cielo:
 * - DAWN   -> Familiarización (F1, F2)
 * - DAY    -> Genéricos / No personalizados (G / N)
 * - SUNSET -> Humanos (H)
 * - NIGHT  -> Personalizados con MarioGPT (P)
 */
public enum LevelTheme {
    DAY("Día", "Cielo diurno clásico"),
    DAWN("Amanecer", "Cielo suave de práctica"),
    SUNSET("Atardecer", "Cielo crepuscular cálido"),
    NIGHT("Noche", "Cielo nocturno estrellado");

    private final String displayName;
    private final String description;

    LevelTheme(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static LevelTheme fromCode(char code) {
        return switch (Character.toUpperCase(code)) {
            case 'F' -> DAWN;
            case 'G', 'N', 'B' -> DAY;
            case 'H', 'C' -> SUNSET;
            case 'P', 'A' -> NIGHT;
            default -> DAY;
        };
    }

    public static LevelTheme fromString(String name) {
        if (name == null || name.isBlank()) return DAY;
        String upper = name.trim().toUpperCase();
        for (LevelTheme t : values()) {
            if (t.name().equals(upper) || t.displayName.equalsIgnoreCase(upper)) {
                return t;
            }
        }
        if (upper.startsWith("F") || upper.contains("FAM")) return DAWN;
        if (upper.startsWith("G") || upper.startsWith("N") || upper.contains("GENERIC")) return DAY;
        if (upper.startsWith("H") || upper.contains("HUMAN")) return SUNSET;
        if (upper.startsWith("P") || upper.contains("PERSON")) return NIGHT;
        return DAY;
    }

    /**
     * Pinta el fondo temático (cielo, gradientes, estrellas y luna) en el lienzo proporcionado.
     */
    public void paintBackground(Graphics2D g, int width, int height) {
        switch (this) {
            case DAWN -> {
                GradientPaint gp = new GradientPaint(0, 0, new Color(84, 120, 184), 0, height, new Color(220, 232, 255));
                g.setPaint(gp);
                g.fillRect(0, 0, width, height);
            }
            case SUNSET -> {
                GradientPaint gp = new GradientPaint(0, 0, new Color(120, 28, 8), 0, height, new Color(248, 168, 40));
                g.setPaint(gp);
                g.fillRect(0, 0, width, height);
                GradientPaint glow = new GradientPaint(0, (int) (height * 0.35), new Color(232, 100, 16, 120), 0, height, new Color(248, 180, 48, 0));
                g.setPaint(glow);
                g.fillRect(0, 0, width, height);
            }
            case NIGHT -> {
                GradientPaint gp = new GradientPaint(0, 0, new Color(3, 6, 18), 0, height, new Color(11, 19, 43));
                g.setPaint(gp);
                g.fillRect(0, 0, width, height);

                // Estrellas estáticas deterministas
                java.util.Random rnd = new java.util.Random(1337);
                int starCount = Math.max(35, width / 6);
                for (int i = 0; i < starCount; i++) {
                    int sx = rnd.nextInt(width);
                    int sy = rnd.nextInt((int) (height * 0.75));
                    int starType = rnd.nextInt(10);
                    if (starType < 6) {
                        g.setColor(rnd.nextBoolean() ? new Color(255, 255, 255, 220) : new Color(200, 230, 255, 180));
                        g.fillRect(sx, sy, 1, 1);
                    } else if (starType < 9) {
                        g.setColor(new Color(255, 244, 180, 230));
                        g.fillRect(sx, sy, 1, 1);
                    } else {
                        g.setColor(new Color(255, 255, 255, 255));
                        g.fillRect(sx, sy, 1, 1);
                        g.setColor(new Color(255, 255, 255, 90));
                        g.fillRect(sx - 1, sy, 1, 1);
                        g.fillRect(sx + 1, sy, 1, 1);
                        g.fillRect(sx, sy - 1, 1, 1);
                        g.fillRect(sx, sy + 1, 1, 1);
                    }
                }

                // Luna creciente en la esquina superior derecha
                int moonR = Math.max(6, Math.min(10, height / 20));
                int moonX = width - Math.max(25, width / 7);
                int moonY = Math.max(14, height / 10);
                g.setColor(new Color(255, 253, 208));
                g.fillOval(moonX - moonR, moonY - moonR, moonR * 2, moonR * 2);
                g.setColor(new Color(4, 8, 22));
                g.fillOval(moonX - moonR + (int) (moonR * 0.55), moonY - moonR - (int) (moonR * 0.2), (int) (moonR * 1.8), (int) (moonR * 1.8));
            }
            case DAY -> {
                g.setColor(new Color(109, 143, 252));
                g.fillRect(0, 0, width, height);
            }
        }
    }
}

