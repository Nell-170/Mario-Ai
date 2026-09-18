package com.marioai.webproof;

import org.teavm.jso.JSExport;

/**
 * Small, browser-compatible slice of game logic.
 *
 * This is intentionally independent from Swing and the current framework. It
 * proves that Java logic can be compiled before we attempt a larger port.
 */
public final class MarioSimulation {
    private static final int MAX_ROWS = 32;
    private static final int MAX_COLUMNS = 512;
    private static final char[][] levelTiles = new char[MAX_ROWS][MAX_COLUMNS];
    private static int levelRows;
    private static int levelColumns;

    // Movimiento horizontal, igual al mapeo del agente humano del framework:
    // ArrowLeft/ArrowRight mueven y la tecla A activa la carrera (SPEED).
    private static final double WALK_SPEED = 1.5;
    private static final double RUN_SPEED = 3.0;
    private static double x;
    private static double velocityX;

    // Salto vertical simple (sin colisión con el nivel todavia), activado con
    // la tecla S, igual que MarioActions.JUMP en el framework.
    private static final double JUMP_IMPULSE = 6.0;
    private static final double GRAVITY = 0.35;
    private static double y;
    private static double velocityY;
    private static boolean onGround;

    private MarioSimulation() {
    }

    public static void main(String[] args) {
        reset();
    }

    @JSExport
    public static void reset() {
        x = 0;
        velocityX = 0;
        y = 0;
        velocityY = 0;
        onGround = true;
    }

    @JSExport
    public static void loadLevel(String level) {
        levelRows = 0;
        levelColumns = 0;
        int column = 0;
        for (int i = 0; i < level.length() && levelRows < MAX_ROWS; i++) {
            char tile = level.charAt(i);
            if (tile == '\r') {
                continue;
            }
            if (tile == '\n') {
                levelColumns = Math.max(levelColumns, column);
                column = 0;
                levelRows++;
            } else if (column < MAX_COLUMNS) {
                levelTiles[levelRows][column++] = tile;
            }
        }
        if (column > 0 && levelRows < MAX_ROWS) {
            levelColumns = Math.max(levelColumns, column);
            levelRows++;
        }
    }

    @JSExport
    public static int getLevelRows() {
        return levelRows;
    }

    @JSExport
    public static int getLevelColumns() {
        return levelColumns;
    }

    @JSExport
    public static int getTile(int row, int column) {
        if (row < 0 || row >= levelRows || column < 0 || column >= levelColumns) {
            return ' ';
        }
        return levelTiles[row][column];
    }

    /**
     * Avanza un paso de la simulacion segun las teclas presionadas.
     *
     * @param moveLeft  ArrowLeft: mover hacia la izquierda.
     * @param moveRight ArrowRight: mover hacia la derecha.
     * @param speed     tecla A: correr (MarioActions.SPEED).
     * @param jump      tecla S: saltar (MarioActions.JUMP).
     */
    @JSExport
    public static void step(boolean moveLeft, boolean moveRight, boolean speed, boolean jump) {
        double horizontalSpeed = speed ? RUN_SPEED : WALK_SPEED;
        if (moveLeft && !moveRight) {
            velocityX = -horizontalSpeed;
        } else if (moveRight && !moveLeft) {
            velocityX = horizontalSpeed;
        } else {
            velocityX = 0;
        }
        x = Math.max(0, x + velocityX);

        if (jump && onGround) {
            velocityY = -JUMP_IMPULSE;
            onGround = false;
        }
        velocityY += GRAVITY;
        y += velocityY;
        if (y >= 0) {
            y = 0;
            velocityY = 0;
            onGround = true;
        }
    }

    @JSExport
    public static double getX() {
        return x;
    }

    @JSExport
    public static double getY() {
        return y;
    }

}
