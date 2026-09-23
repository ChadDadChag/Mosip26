package io.mosip.registration.liveness.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A labelled trace: JSONL, first line is the header, then one {@link TraceFrame} per line.
 * <pre>
 * {"type":"header","label":"blink","promptAtMs":2000,"model":"pad-2026.09.1","device":"SBI-123","synthetic":false}
 * {"t":0,"seq":1,"faces":1,"box":[0.33,0.2,0.34,0.58],"yaw":1.2,...}
 * </pre>
 * Labels: {@code blink, smile, turn_left, turn_right, look_up, look_down} (action performed after
 * {@code promptAtMs}), {@code neutral} (live person, no action), and {@code photo_attack, screen_attack,
 * replay_attack} (presentation attacks).
 */
public final class Trace {

    public static final String[] ATTACK_LABELS = {"photo_attack", "screen_attack", "replay_attack"};

    public final String label;
    public final long promptAtMs;
    public final Map<String, Object> header;
    public final List<TraceFrame> frames;

    public Trace(String label, long promptAtMs, Map<String, Object> header, List<TraceFrame> frames) {
        this.label = label;
        this.promptAtMs = promptAtMs;
        this.header = header;
        this.frames = Collections.unmodifiableList(new ArrayList<TraceFrame>(frames));
    }

    public boolean isAttack() {
        for (String a : ATTACK_LABELS) if (a.equals(label)) return true;
        return false;
    }

    public static Map<String, Object> header(String label, long promptAtMs, String model, String device,
                                             boolean synthetic) {
        Map<String, Object> h = new LinkedHashMap<String, Object>();
        h.put("type", "header");
        h.put("label", label);
        h.put("promptAtMs", promptAtMs);
        if (model != null) h.put("model", model);
        if (device != null) h.put("device", device);
        h.put("synthetic", synthetic);
        return h;
    }

    public void write(Writer w) throws IOException {
        w.write(Json.write(header));
        w.write('\n');
        for (TraceFrame f : frames) {
            w.write(Json.write(f.toJson()));
            w.write('\n');
        }
        w.flush();
    }

    public void write(File file) throws IOException {
        Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
        try {
            write(w);
        } finally {
            w.close();
        }
    }

    public static Trace read(Reader r) throws IOException {
        BufferedReader br = new BufferedReader(r);
        String first = br.readLine();
        if (first == null) throw new IOException("empty trace");
        Map<String, Object> h = Json.parse(first);
        if (!"header".equals(h.get("type"))) throw new IOException("first line must be the header");
        List<TraceFrame> frames = new ArrayList<TraceFrame>();
        String line;
        while ((line = br.readLine()) != null) {
            if (!line.trim().isEmpty()) frames.add(TraceFrame.fromJson(Json.parse(line)));
        }
        Object p = h.get("promptAtMs");
        return new Trace((String) h.get("label"), p instanceof Number ? ((Number) p).longValue() : 0, h, frames);
    }

    public static Trace read(File file) throws IOException {
        Reader r = new InputStreamReader(new FileInputStream(file), "UTF-8");
        try {
            return read(r);
        } finally {
            r.close();
        }
    }

    /** All *.jsonl traces in a directory, sorted by file name. */
    public static List<Trace> readDir(File dir) throws IOException {
        File[] files = dir.listFiles();
        List<Trace> out = new ArrayList<Trace>();
        if (files == null) return out;
        java.util.Arrays.sort(files);
        for (File f : files) if (f.getName().endsWith(".jsonl")) out.add(read(f));
        return out;
    }
}
