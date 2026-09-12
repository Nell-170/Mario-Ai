package com.marioai.webproof;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSExport;

/**
 * Small, browser-compatible slice of game logic.
 *
 * This is intentionally independent from Swing and the current framework. It
 * proves that Java logic can be compiled before we attempt a larger port.
 */
public final class MarioSimulation {
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
        render();
    }

    @JSExport
    public static void step(boolean moveRight) {
        velocityX = moveRight ? 1.5 : 0;
        x += velocityX;
        render();
    }

    @JSExport
    public static double getX() {
        return x;
    }

    private static void render() {
        setMarioX(x);
    }

    @JSBody(params = {"x"}, script = "document.querySelector('#mario').style.left = x + 'px';")
    private static native void setMarioX(double x);

}
