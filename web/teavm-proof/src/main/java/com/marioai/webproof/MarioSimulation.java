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
    private static double x;
    private static double velocityX;

    private MarioSimulation() {
    }

    public static void main(String[] args) {
        reset();
    }

    @JSExport
    public static void reset() {
        x = 0;
        velocityX = 0;
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

    @JSExport
    public static void step(boolean moveRight) {
        velocityX = moveRight ? 1.5 : 0;
        x += velocityX;
    }

    @JSExport
    public static double getX() {
        return x;
    }

}
