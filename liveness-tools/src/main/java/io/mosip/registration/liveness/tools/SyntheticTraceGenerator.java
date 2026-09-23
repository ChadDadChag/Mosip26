package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.api.FrameAnalysis;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/**
 * Generates labelled SYNTHETIC traces (marked {@code "synthetic":true}) with human-like variation: reaction time,
 * action amplitude and duration, pose jitter, spontaneous blinks, analyzer noise. They exercise the replay/tuning
 * pipeline and serve as regression fixtures until real team recordings replace them. They are not evidence that
 * the thresholds are right for real faces.
 *
 * <pre>java -cp ... io.mosip.registration.liveness.tools.SyntheticTraceGenerator out/dir 3 2026</pre>
 */
public final class SyntheticTraceGenerator {

    public static final String[] LABELS = {"blink", "smile", "turn_left", "turn_right", "look_up", "look_down",
            "neutral", "photo_attack", "screen_attack", "replay_attack"};

    static final int FPS = 15;
    static final long DURATION_MS = 5000;
    static final long PROMPT_AT_MS = 1500;

    private SyntheticTraceGenerator() { }

    public static Trace generate(String label, Random r) {
        List<TraceFrame> frames = new ArrayList<TraceFrame>();
        long step = 1000 / FPS;
        boolean attack = label.endsWith("_attack");
        float scoreBase = attack ? (label.equals("photo_attack") ? 0.10f : 0.20f) : 0.55f + r.nextFloat() * 0.4f;
        float earBase = 0.24f + r.nextFloat() * 0.1f;
        float mouthBase = 0.85f + r.nextFloat() * 0.15f;
        float yawBase = (r.nextFloat() - 0.5f) * 8, pitchBase = (r.nextFloat() - 0.5f) * 6;
        long reaction = 250 + r.nextInt(350);
        long actionStart = PROMPT_AT_MS + reaction;
        long blinkLen = 70 + r.nextInt(180);
        long holdLen = 400 + r.nextInt(500);
        float amplitude = label.startsWith("turn") ? 22 + r.nextFloat() * 12 : 15 + r.nextFloat() * 8;
        long nextSpontaneousBlink = 600 + r.nextInt(2500);
        long spontaneousUntil = -1;
        for (long t = 0, seq = 1; t < DURATION_MS; t += step, seq++) {
            float yaw = yawBase + gauss(r, 0.8f), pitch = pitchBase + gauss(r, 0.6f);
            float ear = earBase + gauss(r, 0.01f), mouth = mouthBase + gauss(r, 0.01f), lift = gauss(r, 0.004f);
            // Spontaneous blinks for live subjects (not for photos/screens).
            if (!attack || label.equals("replay_attack")) {
                if (t >= nextSpontaneousBlink) {
                    spontaneousUntil = t + 100 + r.nextInt(100);
                    nextSpontaneousBlink = t + 2500 + r.nextInt(2500);
                }
                if (t < spontaneousUntil && !label.equals("blink")) ear = earBase * 0.3f;
            }
            long a = t - actionStart;
            if (label.equals("blink") && a >= 0 && a < blinkLen) ear = earBase * (0.15f + r.nextFloat() * 0.25f);
            if (label.equals("smile") && a >= 0 && a < holdLen + 300) {
                float k = Math.min(1f, a / 200f);
                mouth = mouthBase * (1f + k * (0.2f + r.nextFloat() * 0.02f + 0.08f));
                lift = k * 0.05f;
            }
            float move = ramp(a, holdLen) * amplitude;
            if (label.equals("turn_left")) yaw += move;
            if (label.equals("turn_right")) yaw -= move;
            if (label.equals("look_up")) pitch += move;
            if (label.equals("look_down")) pitch -= move;
            if (label.equals("replay_attack")) yaw += (float) Math.sin(t / 180.0) * 25; // head wiggle in a video
            float score = clamp(scoreBase + gauss(r, attack ? 0.05f : 0.06f));
            float[] box = {0.33f + gauss(r, 0.003f), 0.2f + gauss(r, 0.003f), 0.34f, 0.58f};
            frames.add(new TraceFrame(t, seq, 1, box, yaw, pitch, 0f, score, ear, mouth, lift,
                    EnumSet.noneOf(FrameAnalysis.QualityIssue.class), 25 + r.nextInt(20)));
        }
        return new Trace(label, PROMPT_AT_MS, Trace.header(label, PROMPT_AT_MS, "synthetic", "synthetic", true), frames);
    }

    /** 0 before the action, rises over 200 ms, holds, falls back over 200 ms. */
    private static float ramp(long a, long hold) {
        if (a < 0) return 0f;
        if (a < 200) return a / 200f;
        if (a < 200 + hold) return 1f;
        if (a < 400 + hold) return 1f - (a - 200 - hold) / 200f;
        return 0f;
    }

    private static float gauss(Random r, float sd) {
        return (float) (r.nextGaussian() * sd);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "traces");
        int perLabel = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        Random r = new Random(args.length > 2 ? Long.parseLong(args[2]) : 2026);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
        for (String label : LABELS) {
            for (int i = 1; i <= perLabel; i++) {
                File f = new File(dir, "synthetic-" + label + "-" + i + ".jsonl");
                generate(label, r).write(f);
                System.out.println("wrote " + f);
            }
        }
    }
}
