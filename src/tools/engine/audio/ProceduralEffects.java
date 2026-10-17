package engine.audio;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import engine.helper.EventType;

public final class ProceduralEffects {
    private static final int SAMPLE_RATE = 22050;
    private static final int BUFFER_FRAMES = 512;
    private final BlockingQueue<EventType> events = new ArrayBlockingQueue<>(12);
    private static final AtomicBoolean WARNING_SHOWN = new AtomicBoolean();

    private volatile boolean playing;
    private volatile SourceDataLine line;
    private Thread playbackThread;

    public synchronized void start() {
        if (playbackThread != null) {
            return;
        }
        playing = true;
        playbackThread = new Thread(this::play, "mario-procedural-effects");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public void playEvent(EventType event) {
        if (playing && isAudible(event) && !events.offer(event)) {
            events.poll();
            events.offer(event);
        }
    }

    public synchronized void stop() {
        playing = false;
        events.clear();
        SourceDataLine activeLine = line;
        if (activeLine != null) {
            activeLine.stop();
            activeLine.close();
            line = null;
        }
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
    }

    private void play() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine output = null;
        try {
            output = (SourceDataLine) AudioSystem.getLine(info);
            output.open(format, BUFFER_FRAMES * 2);
            if (!playing) {
                output.close();
                return;
            }
            line = output;
            output.start();
            byte[] buffer = new byte[BUFFER_FRAMES * 2];
            while (playing) {
                EventType event = events.take();
                double[] notes = notesFor(event);
                for (double frequency : notes) {
                    int totalFrames = (int) (SAMPLE_RATE * 0.065);
                    for (int start = 0; start < totalFrames && playing; start += BUFFER_FRAMES) {
                        int frames = Math.min(BUFFER_FRAMES, totalFrames - start);
                        for (int frame = 0; frame < frames; frame++) {
                            int position = start + frame;
                            double envelope = Math.min(1.0, position / 160.0)
                                    * Math.min(1.0, (totalFrames - position) / 320.0);
                            double sample = Math.sin(2.0 * Math.PI * frequency * position / SAMPLE_RATE)
                                    * envelope * 0.22;
                            short value = (short) (sample * Short.MAX_VALUE);
                            buffer[frame * 2] = (byte) value;
                            buffer[frame * 2 + 1] = (byte) (value >>> 8);
                        }
                        output.write(buffer, 0, frames * 2);
                    }
                }
            }
        } catch (LineUnavailableException | IllegalArgumentException e) {
            if (playing) {
                warnUnavailable(e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (output != null) {
                output.close();
            }
            if (line == output) {
                line = null;
            }
        }
    }

    private static boolean isAudible(EventType event) {
        return event == EventType.JUMP || event == EventType.LAND || event == EventType.COLLECT || event == EventType.HURT
                || event == EventType.STOMP_KILL || event == EventType.FIRE_KILL
                || event == EventType.SHELL_KILL || event == EventType.FALL_KILL
                || event == EventType.BUMP || event == EventType.KICK
                || event == EventType.WIN || event == EventType.LOSE;
    }

    private static double[] notesFor(EventType event) {
        return switch (event) {
            case JUMP -> new double[] {440.0, 587.33};
            case LAND -> new double[] {329.63};
            case COLLECT -> new double[] {880.0, 1174.66};
            case HURT, LOSE -> new double[] {293.66, 196.0};
            case STOMP_KILL, FIRE_KILL, SHELL_KILL, FALL_KILL -> new double[] {587.33, 783.99};
            case BUMP -> new double[] {220.0, 164.81};
            case KICK -> new double[] {392.0};
            case WIN -> new double[] {523.25, 659.25, 783.99};
            default -> new double[0];
        };
    }

    private static void warnUnavailable(String reason) {
        if (WARNING_SHOWN.compareAndSet(false, true)) {
            System.err.println("No se pudieron reproducir los efectos de sonido: " + reason);
        }
    }
}
