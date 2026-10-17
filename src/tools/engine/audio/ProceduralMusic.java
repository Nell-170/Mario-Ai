package engine.audio;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;

public final class ProceduralMusic {
    private static final int SAMPLE_RATE = 22050;
    private static final int BUFFER_FRAMES = 512;
    private static final double NOTE_SECONDS = 0.22;
    private static final double[] MELODY = {
            523.25, 659.25, 783.99, 659.25, 587.33, 698.46, 880.00, 698.46,
            523.25, 659.25, 783.99, 1046.50, 880.00, 783.99, 659.25, 587.33,
            523.25, 587.33, 659.25, 783.99, 698.46, 659.25, 587.33, 523.25,
            392.00, 523.25, 587.33, 659.25, 587.33, 523.25, 440.00, 523.25
    };
    private static final AtomicBoolean WARNING_SHOWN = new AtomicBoolean();

    private volatile boolean playing;
    private volatile SourceDataLine line;
    private Thread playbackThread;

    public synchronized void start() {
        if (playbackThread != null) {
            return;
        }
        playing = true;
        playbackThread = new Thread(this::play, "mario-procedural-music");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public synchronized void stop() {
        playing = false;
        SourceDataLine activeLine = line;
        if (activeLine != null) {
            activeLine.stop();
            activeLine.close();
            line = null;
        }
        playbackThread = null;
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
            long framePosition = 0;
            while (playing) {
                for (int frame = 0; frame < BUFFER_FRAMES; frame++) {
                    double notePosition = (double) framePosition / SAMPLE_RATE / NOTE_SECONDS;
                    int noteIndex = (int) notePosition % MELODY.length;
                    double phase = notePosition - Math.floor(notePosition);
                    double fadeIn = Math.min(1.0, phase * 12.0);
                    double fadeOut = Math.min(1.0, (1.0 - phase) * 12.0);
                    double envelope = Math.min(fadeIn, fadeOut);
                    double sample = Math.sin(2.0 * Math.PI * MELODY[noteIndex] * framePosition / SAMPLE_RATE)
                            * envelope * 0.12;
                    short value = (short) (sample * Short.MAX_VALUE);
                    int offset = frame * 2;
                    buffer[offset] = (byte) value;
                    buffer[offset + 1] = (byte) (value >>> 8);
                    framePosition++;
                }
                output.write(buffer, 0, buffer.length);
            }
        } catch (LineUnavailableException e) {
            if (playing) {
                warnUnavailable(e.getMessage());
            }
        } catch (IllegalArgumentException e) {
            if (playing) {
                warnUnavailable(e.getMessage());
            }
        } finally {
            if (output != null) {
                output.close();
            }
            if (line == output) {
                line = null;
            }
        }
    }

    private static void warnUnavailable(String reason) {
        if (WARNING_SHOWN.compareAndSet(false, true)) {
            System.err.println("No se pudo reproducir la música: " + reason);
        }
    }
}
