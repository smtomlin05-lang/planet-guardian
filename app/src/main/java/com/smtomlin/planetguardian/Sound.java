package com.smtomlin.planetguardian;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.Random;

/** Tiny synthesised sound effects - no audio files needed. */
public class Sound {
    static final int RATE = 22050;
    AudioTrack laser, boom, die, bonus, bomb, warp, grab;
    private final Random rnd = new Random(7);

    public Sound() {
        try {
            laser = make(sweep(0.12f, 1900, 250, true, 0.25f));
            boom = make(noise(0.4f, 0.55f, 0.35f));
            die = make(noise(1.3f, 0.8f, 0.12f));
            bonus = make(sweep(0.22f, 500, 1400, true, 0.22f));
            bomb = make(noise(0.8f, 0.8f, 0.2f));
            warp = make(sweep(0.45f, 150, 2200, false, 0.4f));
            grab = make(beeps());
        } catch (Throwable ignored) {
        }
    }

    private AudioTrack make(short[] d) {
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(d.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        t.write(d, 0, d.length);
        return t;
    }

    public void play(AudioTrack t) {
        if (t == null) return;
        try {
            t.stop();
            t.reloadStaticData();
            t.play();
        } catch (Throwable ignored) {
        }
    }

    private short[] sweep(float secs, float f0, float f1, boolean square, float vol) {
        int n = (int) (secs * RATE);
        short[] d = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            float k = i / (float) n;
            double f = f0 + (f1 - f0) * k;
            ph += 2 * Math.PI * f / RATE;
            double s = square ? (Math.sin(ph) >= 0 ? 1 : -1) : Math.sin(ph);
            d[i] = (short) (s * vol * (1 - k) * 32767);
        }
        return d;
    }

    private short[] noise(float secs, float vol, float smooth) {
        int n = (int) (secs * RATE);
        short[] d = new short[n];
        double v = 0;
        for (int i = 0; i < n; i++) {
            float k = i / (float) n;
            double r = rnd.nextDouble() * 2 - 1;
            v += (r - v) * (smooth + (1 - smooth) * (1 - k) * 0.5);
            double env = Math.pow(1 - k, 1.6);
            d[i] = (short) (Math.max(-1, Math.min(1, v * 1.6)) * vol * env * 32767);
        }
        return d;
    }

    private short[] beeps() {
        int n = (int) (0.36f * RATE);
        short[] d = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            int seg = i * 4 / n;
            double f = seg % 2 == 0 ? 880 : 660;
            ph += 2 * Math.PI * f / RATE;
            d[i] = (short) ((Math.sin(ph) >= 0 ? 1 : -1) * 0.18 * 32767);
        }
        return d;
    }
}
