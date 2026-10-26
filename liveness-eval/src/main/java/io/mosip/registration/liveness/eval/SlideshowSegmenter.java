package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.match.FaceMatcher;
import io.mosip.registration.liveness.analyzer.match.SFaceEmbedder;
import io.mosip.registration.liveness.api.Frame;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts a continuous recording of many consented people's photos (a phone slideshow, or printed cut-outs held up one
 * after another) into one take per presentation, so that PadEvaluation gets attacks across many identities without
 * labelling by hand (development only; the photos and recordings are personal data and stay out of the repository):
 * <pre>
 *   SlideshowSegmenter --stream eval-data/streams/s01/slideshow --labels "test images/labels.csv"
 *                      --species screen_photo_multi [--condition slideshow] [--out eval-data] [--match 0.45]
 *                      [--margin 0.05] [--min-frames 10] [--trim 2] [--max-gap 2] [--dry-run]
 * </pre>
 * <ul>
 *   <li>Every photo with a {@code person_id} in labels.csv ({@code file,person_id[,...]}, the photos next to it)
 *       gives the SFace feature of its largest face.</li>
 *   <li>Each recorded frame's largest face is compared with them. The frame belongs to person P when its best photo
 *       is one of P's with a cosine of at least {@code --match}, and it beats the best photo of every other person by
 *       {@code --margin}. Otherwise (no face, a transition, a blank screen, an unknown face) it belongs to nobody.</li>
 *   <li>A presentation is a run of frames of one person, with at most {@code --max-gap} frames of nobody inside. It is
 *       trimmed by {@code --trim} frames at each end (the transitions) and dropped when shorter than
 *       {@code --min-frames}.</li>
 *   <li>Each kept run becomes {@code <out>/attack/<species>/<person_id>/<n>/} (the next free take), with its frames
 *       copied in order and a meta.json as DatasetRecorder writes it: subject = the person's code, the recording's
 *       fps, format and device, and {@code segmentedFrom}.</li>
 *   <li>With {@code --unknown-ids u}, faces that match no photo are not dropped: runs of them become unknown people
 *       u01, u02, ... ({@link #assignUnknown}), so a print without a source photo still gives a take.
 *       {@code --exclude-subject eval-data/bona_fide/s01} keeps the presenter's own live face out of those.</li>
 * </ul>
 * Prints counts only (no file names): frames per outcome, runs kept and dropped, people covered.
 */
public final class SlideshowSegmenter {

    static final double MATCH = 0.45, MARGIN = 0.05;
    static final int MIN_FRAMES = 10, TRIM = 2, MAX_GAP = 2;
    /** Unknown people: consecutive frames chain at this cosine; a run joins an earlier unknown person at this one. */
    static final double CONTINUITY = 0.35, SAME_UNKNOWN = 0.45;
    static final int NOBODY = -1;

    private SlideshowSegmenter() { }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            ModelBundleTool.Args a = ModelBundleTool.Args.parse(args, 0, Arrays.asList("--dry-run"));
            File stream = new File(a.required("--stream"));
            File labels = new File(a.required("--labels"));
            String species = a.required("--species");
            if (!DatasetRecorder.LABELS.contains(species) || species.equals("bona_fide")) {
                throw new IllegalArgumentException("--species must be an attack species of " + DatasetRecorder.LABELS);
            }
            Settings s = new Settings(
                    a.has("--match") ? Double.parseDouble(a.single("--match")) : MATCH,
                    a.has("--margin") ? Double.parseDouble(a.single("--margin")) : MARGIN,
                    a.has("--min-frames") ? Integer.parseInt(a.single("--min-frames")) : MIN_FRAMES,
                    a.has("--trim") ? Integer.parseInt(a.single("--trim")) : TRIM,
                    a.has("--max-gap") ? Integer.parseInt(a.single("--max-gap")) : MAX_GAP,
                    a.has("--unknown-ids") ? a.single("--unknown-ids") : null,
                    a.has("--continuity") ? Double.parseDouble(a.single("--continuity")) : CONTINUITY,
                    a.has("--same-unknown") ? Double.parseDouble(a.single("--same-unknown")) : SAME_UNKNOWN);
            String condition = a.has("--condition") ? a.single("--condition") : stream.getName();
            File root = new File(a.has("--out") ? a.single("--out") : "eval-data");
            File exclude = a.has("--exclude-subject") ? new File(a.single("--exclude-subject")) : null;
            Result r = segment(stream, labels, species, condition, root, s, a.has("--dry-run"), exclude);
            out.print(r.summary(a.has("--dry-run")));
            return 0;
        } catch (ModelBundleTool.UsageException | IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println("usage: SlideshowSegmenter --stream DIR --labels labels.csv --species SPECIES [--condition c]"
                    + " [--out eval-data] [--match 0.45] [--margin 0.05] [--min-frames 10] [--trim 2] [--max-gap 2]"
                    + " [--unknown-ids u [--continuity 0.35] [--same-unknown 0.45]] [--exclude-subject DIR] [--dry-run]");
            return 2;
        } catch (Exception e) {
            err.println("FAILED: " + e);
            return 1;
        }
    }

    /** The matching and segmentation settings. */
    static final class Settings {
        final double match, margin, continuity, sameUnknown;
        final int minFrames, trim, maxGap;
        /** Null: unmatched faces belong to nobody. Else the prefix of the codes given to unknown people (u01, ...). */
        final String unknownPrefix;

        Settings(double match, double margin, int minFrames, int trim, int maxGap) {
            this(match, margin, minFrames, trim, maxGap, null, CONTINUITY, SAME_UNKNOWN);
        }

        Settings(double match, double margin, int minFrames, int trim, int maxGap, String unknownPrefix,
                 double continuity, double sameUnknown) {
            if (minFrames < 1 || trim < 0 || maxGap < 0) throw new IllegalArgumentException("bad segmentation settings");
            if (unknownPrefix != null && !unknownPrefix.matches("[a-z]{1,3}")) {
                throw new IllegalArgumentException("--unknown-ids must be 1 to 3 lower-case letters");
            }
            this.match = match;
            this.margin = margin;
            this.minFrames = minFrames;
            this.trim = trim;
            this.maxGap = maxGap;
            this.unknownPrefix = unknownPrefix;
            this.continuity = continuity;
            this.sameUnknown = sameUnknown;
        }
    }

    /** A kept or dropped run: frames [first, last] of person {@code person}. */
    static final class Run {
        final int person, first, last;

        Run(int person, int first, int last) {
            this.person = person;
            this.first = first;
            this.last = last;
        }

        int length() {
            return last - first + 1;
        }
    }

    /** What a segmentation found. */
    static final class Result {
        int frames, noFace, unmatched, matched, photos, photosWithoutFace, groupPhotos, unknownPeople, unknownFrames,
                excludedRuns;
        final List<Run> kept = new ArrayList<Run>(), dropped = new ArrayList<Run>();
        final List<String> people = new ArrayList<String>();
        final List<File> written = new ArrayList<File>();

        String summary(boolean dryRun) {
            Map<String, Integer> perPerson = new TreeMap<String, Integer>();
            for (Run r : kept) {
                String p = people.get(r.person);
                perPerson.put(p, perPerson.containsKey(p) ? perPerson.get(p) + 1 : 1);
            }
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.ROOT, "photos: %d with a face (%d group photos, largest face used), %d without%n",
                    photos, groupPhotos, photosWithoutFace));
            b.append(String.format(Locale.ROOT, "frames: %d; matched %d, no face %d, unmatched (transition, other face) %d%n",
                    frames, matched, noFace, unmatched));
            if (unknownPeople > 0 || excludedRuns > 0) {
                b.append(String.format(Locale.ROOT, "unknown people (no source photo): %d, from %d frames; runs matching"
                        + " the excluded subject: %d%n", unknownPeople, unknownFrames, excludedRuns));
            }
            b.append(String.format(Locale.ROOT, "presentations: %d kept, %d dropped (shorter than the minimum)%n",
                    kept.size(), dropped.size()));
            b.append(String.format(Locale.ROOT, "people covered: %d of %d%n", perPerson.size(),
                    new java.util.HashSet<String>(people).size()));
            b.append(dryRun ? "dry run: nothing written\n" : "wrote " + written.size() + " takes\n");
            return b.toString();
        }
    }

    static Result segment(File stream, File labels, String species, String condition, File root, Settings s,
                          boolean dryRun) throws Exception {
        return segment(stream, labels, species, condition, root, s, dryRun, null);
    }

    /**
     * As above; with {@code excludeSubject} (a recorded subject's folder, such as eval-data/bona_fide/s01), unknown
     * runs that match that subject's live face are dropped, so the presenter's own face never becomes an attack take.
     */
    static Result segment(File stream, File labels, String species, String condition, File root, Settings s,
                          boolean dryRun, File excludeSubject) throws Exception {
        Map<String, String> byFile = readLabels(labels);
        File photoDir = labels.getAbsoluteFile().getParentFile();
        List<File> frames = ClipPacker.frames(stream);
        if (frames.isEmpty()) throw new IOException("no frame_*.jpg or frame_*.png in " + stream);
        Result r = new Result();
        List<float[]> features = new ArrayList<float[]>();
        List<Integer> owner = new ArrayList<Integer>();
        List<float[]> excluded = new ArrayList<float[]>();
        int[] labelsOfFrames;
        float[][] unmatchedFeatures;
        try (OnnxFaceAnalyzerFactory factory = new OnnxFaceAnalyzerFactory();
             FaceMatcher matcher = factory.createMatcher()) {
            if (excludeSubject != null) excluded = subjectFeatures(matcher, excludeSubject);
            for (Map.Entry<String, String> e : byFile.entrySet()) {
                Frame photo = PadEvaluation.load(new File(photoDir, e.getKey()), 0, 0, "photo");
                FaceMatcher.PrimaryFeature f = matcher.featureOfPrimaryFace(photo.rgb(), photo.width(), photo.height());
                if (f == null) {
                    r.photosWithoutFace++;
                    continue;
                }
                r.photos++;
                if (f.faceCount > 1) r.groupPhotos++;
                int p = r.people.indexOf(e.getValue());
                if (p < 0) {
                    r.people.add(e.getValue());
                    p = r.people.size() - 1;
                }
                features.add(f.feature);
                owner.add(p);
            }
            r.frames = frames.size();
            labelsOfFrames = new int[frames.size()];
            unmatchedFeatures = new float[frames.size()][];
            for (int i = 0; i < frames.size(); i++) {
                Frame fr = PadEvaluation.load(frames.get(i), 0, i, "stream");
                FaceMatcher.PrimaryFeature f = matcher.featureOfPrimaryFace(fr.rgb(), fr.width(), fr.height());
                if (f == null) {
                    r.noFace++;
                    labelsOfFrames[i] = NOBODY;
                    continue;
                }
                labelsOfFrames[i] = classify(f.feature, features, owner, s);
                if (labelsOfFrames[i] == NOBODY) {
                    r.unmatched++;
                    unmatchedFeatures[i] = f.feature;
                } else {
                    r.matched++;
                }
            }
        }
        if (s.unknownPrefix != null) assignUnknown(labelsOfFrames, unmatchedFeatures, excluded, s, r);
        for (Run run : runs(labelsOfFrames, s.maxGap)) {
            Run t = new Run(run.person, run.first + s.trim, run.last - s.trim);
            (t.length() >= s.minFrames ? r.kept : r.dropped).add(t);
        }
        if (!dryRun) {
            String streamMeta = new String(Files.readAllBytes(new File(stream, "meta.json").toPath()),
                    StandardCharsets.UTF_8);
            for (Run t : r.kept) r.written.add(write(t, frames, r.people.get(t.person), species, condition, root,
                    streamMeta, stream.getName()));
        }
        return r;
    }

    /**
     * Gives unmatched faces an identity instead of dropping them (a printed person without a source photo): a run of
     * unmatched-face frames (gaps of at most maxGap frames without a face, each frame at least {@code continuity}
     * from the previous one, a known person ends it) long enough to be kept becomes an unknown person: an earlier
     * unknown person when its mean feature is at least {@code sameUnknown} from theirs, else a new code
     * (prefix + 01, 02, ...). A run that matches the excluded subject (at least {@code match}) stays nobody.
     */
    static void assignUnknown(int[] labels, float[][] unmatched, List<float[]> excluded, Settings s, Result r) {
        List<float[]> sums = new ArrayList<float[]>();
        List<Integer> persons = new ArrayList<Integer>();
        int i = 0;
        while (i < labels.length) {
            if (unmatched[i] == null) {
                i++;
                continue;
            }
            List<Integer> members = new ArrayList<Integer>();
            members.add(i);
            float[] sum = unmatched[i].clone();
            int last = i;
            for (int j = i + 1; j < labels.length && j - last <= s.maxGap + 1; j++) {
                if (unmatched[j] == null) {
                    if (labels[j] != NOBODY) break; // a known person ends the run
                    continue;
                }
                if (SFaceEmbedder.cosine(unmatched[j], unmatched[last]) < s.continuity) break;
                members.add(j);
                for (int k = 0; k < sum.length; k++) sum[k] += unmatched[j][k];
                last = j;
            }
            i = last + 1;
            if (last - members.get(0) + 1 - 2 * s.trim < s.minFrames) continue; // too short to be kept anyway
            boolean subject = false;
            for (float[] e : excluded) subject |= SFaceEmbedder.cosine(sum, e) >= s.match;
            if (subject) {
                r.excludedRuns++;
                continue;
            }
            int person = -1, slot = -1;
            double best = s.sameUnknown;
            for (int k = 0; k < sums.size(); k++) {
                double c = SFaceEmbedder.cosine(sum, sums.get(k));
                if (c >= best) {
                    best = c;
                    slot = k;
                }
            }
            if (slot < 0) {
                r.people.add(String.format(Locale.ROOT, "%s%02d", s.unknownPrefix, ++r.unknownPeople));
                person = r.people.size() - 1;
                sums.add(sum);
                persons.add(person);
            } else {
                person = persons.get(slot);
                for (int k = 0; k < sum.length; k++) sums.get(slot)[k] += sum[k];
            }
            for (int m : members) labels[m] = person;
            r.unknownFrames += members.size();
        }
    }

    /** The largest face's feature of the middle frame of each take of a recorded subject (excluded from unknowns). */
    static List<float[]> subjectFeatures(FaceMatcher matcher, File subjectDir) throws Exception {
        List<float[]> out = new ArrayList<float[]>();
        File[] takes = subjectDir.listFiles();
        if (takes == null) throw new IOException("no takes in " + subjectDir);
        Arrays.sort(takes);
        for (File t : takes) {
            List<File> frames = ClipPacker.frames(t);
            if (!t.isDirectory() || frames.isEmpty()) continue;
            Frame f = PadEvaluation.load(frames.get(frames.size() / 2), 0, 0, "subject");
            FaceMatcher.PrimaryFeature p = matcher.featureOfPrimaryFace(f.rgb(), f.width(), f.height());
            if (p != null) out.add(p.feature);
        }
        return out;
    }

    /** The person a frame's feature belongs to, or {@link #NOBODY}. */
    static int classify(float[] feature, List<float[]> photos, List<Integer> owner, Settings s) {
        int bestPerson = NOBODY;
        double best = Double.NEGATIVE_INFINITY;
        Map<Integer, Double> perPerson = new LinkedHashMap<Integer, Double>();
        for (int k = 0; k < photos.size(); k++) {
            double c = SFaceEmbedder.cosine(feature, photos.get(k));
            Integer p = owner.get(k);
            if (!perPerson.containsKey(p) || c > perPerson.get(p)) perPerson.put(p, c);
            if (c > best) {
                best = c;
                bestPerson = p;
            }
        }
        if (best < s.match) return NOBODY;
        for (Map.Entry<Integer, Double> e : perPerson.entrySet()) {
            if (e.getKey() != bestPerson && best - e.getValue() < s.margin) return NOBODY;
        }
        return bestPerson;
    }

    /** Runs of one person, allowing at most maxGap NOBODY frames inside; a different person ends a run. */
    static List<Run> runs(int[] labels, int maxGap) {
        List<Run> out = new ArrayList<Run>();
        int person = NOBODY, first = -1, last = -1;
        for (int i = 0; i < labels.length; i++) {
            int p = labels[i];
            if (p == NOBODY) {
                if (person != NOBODY && i - last > maxGap) {
                    out.add(new Run(person, first, last));
                    person = NOBODY;
                }
                continue;
            }
            if (p != person) {
                if (person != NOBODY) out.add(new Run(person, first, last));
                person = p;
                first = i;
            }
            last = i;
        }
        if (person != NOBODY) out.add(new Run(person, first, last));
        return out;
    }

    private static File write(Run t, List<File> frames, String person, String species, String condition, File root,
                              String streamMeta, String streamName) throws IOException {
        File dir = new File(new File(new File(new File(root, "attack"), species), person), String.valueOf(nextTake(
                new File(new File(new File(root, "attack"), species), person))));
        if (!dir.mkdirs()) throw new IOException("cannot create " + dir);
        String ext = frames.get(t.first).getName().substring(frames.get(t.first).getName().lastIndexOf('.') + 1);
        for (int i = t.first; i <= t.last; i++) {
            Files.copy(frames.get(i).toPath(), new File(dir, String.format("frame_%05d.%s", i - t.first, ext)).toPath());
        }
        Map<String, String> m = EvalDataset.readMeta(streamMeta);
        double fps = m.containsKey("fps") ? Double.parseDouble(m.get("fps")) : 10;
        Matcher device = Pattern.compile("\"device\"\\s*:\\s*\\{[^}]*\\}").matcher(streamMeta);
        StringBuilder b = new StringBuilder("{\n");
        b.append("  \"label\": \"").append(species).append("\",\n");
        b.append("  \"kind\": \"attack\",\n");
        b.append("  \"subject\": \"").append(person).append("\",\n");
        b.append("  \"take\": ").append(dir.getName()).append(",\n");
        b.append("  \"condition\": \"").append(condition).append("\",\n");
        b.append("  \"fps\": ").append(fps).append(",\n");
        b.append("  \"frames\": ").append(t.length()).append(",\n");
        b.append("  \"durationMs\": ").append(Math.round((t.length() - 1) * 1000.0 / fps)).append(",\n");
        for (String k : new String[] {"format", "chroma", "recordedUtc"}) {
            if (m.containsKey(k)) b.append("  \"").append(k).append("\": \"").append(m.get(k)).append("\",\n");
        }
        for (String k : new String[] {"jpegQuality", "width", "height"}) {
            if (m.containsKey(k)) b.append("  \"").append(k).append("\": ").append(m.get(k)).append(",\n");
        }
        if (device.find()) b.append("  ").append(device.group()).append(",\n");
        b.append("  \"segmentedFrom\": \"").append(streamName).append("\",\n");
        b.append("  \"firstFrame\": ").append(t.first).append(",\n");
        b.append("  \"consent\": true\n}\n");
        Files.write(new File(dir, "meta.json").toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    private static int nextTake(File subjectDir) throws IOException {
        int max = 0;
        File[] takes = subjectDir.listFiles();
        if (takes != null) {
            for (File f : takes) if (f.getName().matches("\\d{1,2}")) max = Math.max(max, Integer.parseInt(f.getName()));
        }
        if (max >= 99) throw new IOException(subjectDir + " already has 99 takes");
        return max + 1;
    }

    /**
     * labels.csv: a header with {@code file} and {@code person_id}, then one row per photo (quoted fields allowed).
     * Rows without a person_id are left out. Insertion order is kept.
     */
    static Map<String, String> readLabels(File csv) throws IOException {
        List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IOException(csv + " is empty");
        List<String> header = fields(lines.get(0).replace("﻿", ""));
        int file = header.indexOf("file"), person = header.indexOf("person_id");
        if (file < 0 || person < 0) throw new IOException(csv + ": the header must name file and person_id");
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.trim().isEmpty()) continue;
            List<String> f = fields(line);
            if (f.size() <= Math.max(file, person)) continue;
            String p = f.get(person).trim();
            if (!p.isEmpty()) out.put(f.get(file), p);
        }
        return out;
    }

    /** One CSV line's fields (RFC 4180 quoting). */
    static List<String> fields(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
