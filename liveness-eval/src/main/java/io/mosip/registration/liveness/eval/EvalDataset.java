package io.mosip.registration.liveness.eval;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The PAD evaluation dataset on disk ({@code docs/part2-dataset-protocol.md}):
 * {@code <root>/bona_fide/<subject>/<take>/} and {@code <root>/attack/<species>/<subject>/<take>/}, each with
 * {@code meta.json} and {@code frame_NNNNN.jpg|png}. The species comes from the directory, never from the file.
 */
final class EvalDataset {

    static final String BONA_FIDE = "bona_fide";

    /** One recorded take. */
    static final class TakeRef {
        final String species, subject, condition;
        final int take;
        final double fps;
        final File dir;

        TakeRef(String species, String subject, int take, String condition, double fps, File dir) {
            this.species = species; this.subject = subject; this.take = take; this.condition = condition;
            this.fps = fps; this.dir = dir;
        }

        boolean attack() {
            return !species.equals(BONA_FIDE);
        }

        /** Frame files in order. */
        List<File> frames() {
            File[] files = dir.listFiles();
            List<File> out = new ArrayList<File>();
            if (files == null) return out;
            for (File f : files) if (f.getName().matches("frame_\\d{5}\\.(jpg|png)")) out.add(f);
            Collections.sort(out);
            return out;
        }

        @Override public String toString() {
            return species + "/" + subject + "/" + take;
        }
    }

    private EvalDataset() { }

    /** All takes under the root, bona fide first, then attack species in protocol order, then subject and take. */
    static List<TakeRef> scan(File root) throws IOException {
        List<TakeRef> takes = new ArrayList<TakeRef>();
        scanSpecies(BONA_FIDE, new File(root, BONA_FIDE), takes);
        File[] species = new File(root, "attack").listFiles();
        if (species != null) {
            for (File s : species) if (s.isDirectory()) scanSpecies(s.getName(), s, takes);
        }
        final List<String> order = DatasetRecorder.LABELS;
        Collections.sort(takes, new Comparator<TakeRef>() {
            @Override public int compare(TakeRef a, TakeRef b) {
                int c = Integer.compare(rank(order, a.species), rank(order, b.species));
                if (c == 0) c = a.species.compareTo(b.species);
                if (c == 0) c = a.subject.compareTo(b.subject);
                return c != 0 ? c : Integer.compare(a.take, b.take);
            }
        });
        return takes;
    }

    private static int rank(List<String> order, String species) {
        int i = order.indexOf(species);
        return i < 0 ? order.size() : i;
    }

    private static void scanSpecies(String species, File dir, List<TakeRef> out) throws IOException {
        File[] subjects = dir.listFiles();
        if (subjects == null) return;
        for (File subj : subjects) {
            File[] takes = subj.listFiles();
            if (!subj.isDirectory() || takes == null) continue;
            for (File t : takes) {
                File meta = new File(t, "meta.json");
                if (!t.isDirectory() || !t.getName().matches("\\d{1,2}") || !meta.isFile()) continue;
                Map<String, String> m = readMeta(new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8));
                double fps = m.containsKey("fps") ? Double.parseDouble(m.get("fps")) : 10;
                out.add(new TakeRef(species, subj.getName(), Integer.parseInt(t.getName()), m.get("condition"), fps, t));
            }
        }
    }

    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z]+)\"\\s*:\\s*(\"([^\"]*)\"|[-0-9.eE]+|true|false)");

    /** Top-level scalar fields of a recorder meta.json (flat format written by {@link DatasetRecorder}). */
    static Map<String, String> readMeta(String json) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        Matcher x = FIELD.matcher(json);
        while (x.find()) if (!m.containsKey(x.group(1))) m.put(x.group(1), x.group(3) != null ? x.group(3) : x.group(2));
        return m;
    }

    /** Takes per species, in protocol order (species without takes are listed with 0). */
    static Map<String, Integer> counts(List<TakeRef> takes) {
        Map<String, Integer> c = new LinkedHashMap<String, Integer>();
        for (String s : DatasetRecorder.LABELS) c.put(s, 0);
        for (TakeRef t : takes) c.put(t.species, (c.containsKey(t.species) ? c.get(t.species) : 0) + 1);
        return c;
    }

    static boolean isKnownSpecies(String species) {
        return DatasetRecorder.LABELS.contains(species);
    }

    /** The protocol's attack species, in order. */
    static List<String> attackSpecies() {
        return new ArrayList<String>(DatasetRecorder.LABELS.subList(1, DatasetRecorder.LABELS.size()));
    }
}
