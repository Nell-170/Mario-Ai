package engine.audio;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * ProceduralMusic — Reproductor de música para Mario AI.
 * 
 * Prioridad de reproducción:
 * 1. Pista de audio de alta calidad ("Pixelland.wav" / "Pixelland.mp3" por Kevin MacLeod, CC-BY 4.0).
 * 2. Si no se encuentra ningún archivo de audio, recurre al sintetizador procedural monofónico por software.
 */
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
        playbackThread = new Thread(this::play, "mario-music-playback");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public synchronized void stop() {
        playing = false;
        SourceDataLine activeLine = line;
        if (activeLine != null) {
            try {
                activeLine.stop();
                activeLine.close();
            } catch (Exception ignored) {}
            line = null;
        }
        playbackThread = null;
    }

    private void play() {
        File musicFile = findMusicFile();
        if (musicFile != null && musicFile.exists()) {
            playAudioFile(musicFile);
        } else {
            playSynthesized();
        }
    }

    private static File findMusicFile() {
        String[] wavCandidates = {
            "../src/experiment/audio/Pixelland.wav",
            "src/experiment/audio/Pixelland.wav",
            "experiment/audio/Pixelland.wav",
            "../experiment/audio/Pixelland.wav",
            "../src/experiment/audio/overworld.wav",
            "src/experiment/audio/overworld.wav"
        };
        for (String c : wavCandidates) {
            File f = new File(c);
            if (f.exists() && f.canRead() && f.length() > 0) {
                return f;
            }
        }

        // Si existe el MP3 pero no el WAV, intentar conversión rápida con ffmpeg
        String[] mp3Candidates = {
            "../src/experiment/audio/Pixelland.mp3",
            "src/experiment/audio/Pixelland.mp3",
            "experiment/audio/Pixelland.mp3",
            "../experiment/audio/Pixelland.mp3"
        };
        for (String c : mp3Candidates) {
            File mp3 = new File(c);
            if (mp3.exists() && mp3.canRead() && mp3.length() > 0) {
                File wav = new File(mp3.getParentFile(), "Pixelland.wav");
                tryConvertMp3ToWav(mp3, wav);
                if (wav.exists() && wav.canRead() && wav.length() > 0) {
                    return wav;
                }
            }
        }
        return null;
    }

    private static void tryConvertMp3ToWav(File mp3, File wav) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-y", "-i", mp3.getAbsolutePath(),
                    "-acodec", "pcm_s16le", "-ar", "44100", wav.getAbsolutePath());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private void playAudioFile(File file) {
        while (playing) {
            try (AudioInputStream in = AudioSystem.getAudioInputStream(file)) {
                AudioFormat format = in.getFormat();
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

                if (!AudioSystem.isLineSupported(info)) {
                    AudioFormat pcm = new AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            format.getSampleRate(),
                            16,
                            format.getChannels(),
                            format.getChannels() * 2,
                            format.getSampleRate(),
                            false
                    );
                    try (AudioInputStream pcmIn = AudioSystem.getAudioInputStream(pcm, in)) {
                        streamToLine(pcmIn, pcm);
                    }
                } else {
                    streamToLine(in, format);
                }
            } catch (Exception e) {
                if (playing) {
                    warnUnavailable("Aviso al reproducir " + file.getName() + " (" + e.getMessage() + "), recurriendo a sintetizador.");
                    playSynthesized();
                }
                break;
            }
        }
    }

    private void streamToLine(AudioInputStream ais, AudioFormat format) throws Exception {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine output = (SourceDataLine) AudioSystem.getLine(info);
        output.open(format, 16384);
        if (!playing) {
            output.close();
            return;
        }
        line = output;

        // Ajustar volumen para balancear con los efectos de salto y monedas
        if (output.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            try {
                FloatControl gain = (FloatControl) output.getControl(FloatControl.Type.MASTER_GAIN);
                gain.setValue(-4.0f); // -4 dB
            } catch (Exception ignored) {}
        }

        output.start();

        byte[] buffer = new byte[4096];
        int bytesRead;
        while (playing && (bytesRead = ais.read(buffer, 0, buffer.length)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
        output.drain();
        output.stop();
        output.close();
        if (line == output) {
            line = null;
        }
    }

    private void playSynthesized() {
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
                try { output.close(); } catch (Exception ignored) {}
            }
            if (line == output) {
                line = null;
            }
        }
    }

    private static void warnUnavailable(String reason) {
        if (WARNING_SHOWN.compareAndSet(false, true)) {
            System.err.println("Aviso audio: " + reason);
        }
    }
}
