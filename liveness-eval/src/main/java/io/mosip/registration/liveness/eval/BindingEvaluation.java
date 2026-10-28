package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzer;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.integrity.BundleSource;
import io.mosip.registration.liveness.analyzer.integrity.ClasspathBundleSource;
import io.mosip.registration.liveness.analyzer.integrity.DirectoryBundleSource;
import io.mosip.registration.liveness.analyzer.match.FaceMatcher;
import io.mosip.registration.liveness.analyzer.match.SFaceEmbedder;
import io.mosip.registration.liveness.analyzer.quality.QualityReport;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Face binding (F1) evaluation (development only): how far apart are SFace similarities of the same person and of
 * different people, and where should the threshold be?
 * <pre>
 *   BindingEvaluation [--data eval-data] [--portraits models/tools/.cache/pitch-calibration]
 *                     [--list models/tools/pitch_calibration_urls.txt]
 *                     [--golden liveness-analyzer/src/test/resources/golden] [--out eval-data/reports/binding-yyyyMMdd]
 *                     [--bundle &lt;signed bundle dir&gt;] [--speed] [--warm-up]
 *                     [--labelled &lt;photos&gt;/labels.csv [--labels-confirmed]]
 *                     [--identity [--subject-map s02=p05,s03=p11]]
 * </pre>
 * {@code --labelled} adds a folder of consented photos labelled by person ({@link #labelledSets}); the report calls
 * those numbers provisional until {@code --labels-confirmed} says the owner of the photos checked the labels.
 * {@code --subject-map} says which labelled person a recorded subject is (codes only, never names); a person_id equal
 * to the subject code (s01) needs no entry. {@code --identity} adds the F15 identity binding sets ({@link #identitySets})
 * and the threshold rule ({@link IdentityRule}).
 * {@code --bundle} evaluates another signed bundle (for example a local test bundle with the int8 SFace), verified
 * against the built-in keys as the bundled one is. {@code --speed} only times: preload, the first comparison, and warm
 * comparisons on the golden portrait and its mirror (run it in a fresh JVM). {@code --warm-up} calls
 * {@code warmUpMatcher} before the first comparison and times it.
 * Faces:
 * <ul>
 *   <li>each recorded bona fide take, per subject: its best frame (one face, no quality issue; the sharpest frame
 *       (lapVar) with |yaw| and |pitch| at most 10 degrees, else the most frontal one);</li>
 *   <li>each screen-photo and print take: its best frame. "selfie" conditions show the recorded subject's own photo,
 *       "nasa" conditions a public NASA portrait;</li>
 *   <li>the 49 public-domain calibration portraits (pitch_calibration_urls.txt), each with an identity label: several
 *       people appear in more than one photo, and some files are crops of the same photo;</li>
 *   <li>the four faces of the golden crew photo.</li>
 * </ul>
 * Sets ({@link #recordedSets} for the recorded ones; a subject is compared as the same person only with itself):
 * genuine (each subject's takes with each other; the same NASA person in different photos), same person via a photo
 * (each subject vs the screen photos and prints of their own selfie: binding is not PAD, a photo of the same person
 * matches), and impostors (recorded subjects vs each other, live and vs photos of another subject's selfie; the
 * subjects vs the portraits and vs the NASA screen photos and prints; NASA vs NASA, different people; crew vs crew and
 * vs the portraits). Crops of the same photo are left out.
 *
 * <p>The report (aggregates only: similarity distributions, FNMR and FMR per threshold, timings) goes to the
 * gitignored eval-data/ folder. No image, feature or per-take value is written.
 */
public final class BindingEvaluation {

    static final double[] THRESHOLDS = {0.25, 0.30, 0.35, 0.363, 0.40, 0.45, 0.50, 0.55, 0.60};
    static final double FRONTAL_DEG = 10;

    private BindingEvaluation() { }

    /** A named set of similarities. */
    static final class Scores {
        final String name;
        final boolean genuine;
        final List<Double> values = new ArrayList<Double>();

        Scores(String name, boolean genuine) {
            this.name = name;
            this.genuine = genuine;
        }

        double[] sorted() {
            double[] a = new double[values.size()];
            for (int i = 0; i < a.length; i++) a[i] = values.get(i);
            Arrays.sort(a);
            return a;
        }

        /** Genuine: share below the threshold (false non-match). Impostor: share at or above it (false match). */
        double errorRate(double threshold) {
            if (values.isEmpty()) return Double.NaN;
            int n = 0;
            for (double v : values) if (genuine ? v < threshold : v >= threshold) n++;
            return (double) n / values.size();
        }

        int errors(double threshold) {
            int n = 0;
            for (double v : values) if (genuine ? v < threshold : v >= threshold) n++;
            return n;
        }
    }

    /** One public portrait: its identity and its photograph (crops of one photo share the source). */
    static final class Portrait {
        final String page, identity, source;
        final float[] feature;

        Portrait(String page, float[] feature) {
            this.page = page;
            this.identity = identity(page);
            this.source = source(page);
            this.feature = feature;
        }
    }

    // People who appear in more than one calibration photo (pitch_calibration_urls.txt, checked by hand).
    private static final String[][] SAME_PERSON = {
            {"(?i)aaron_cohen", "Aaron Cohen"}, {"(?i)hoshide", "Akihiko Hoshide"}, {"(?i)worden", "Al Worden"},
            {"(?i)shepard", "Alan Shepard"}, {"(?i)borman", "Frank Borman"}, {"(?i)cooper", "Gordon Cooper"},
            {"WHITE", "Edward White"}, {"(?i)andrew(_m\\.)?_allen", "Andrew Allen"}, {"(?i)menon", "Anil Menon"},
            {"(?i)fisher", "Anna Fisher"}};
    private static final Pattern CROP = Pattern.compile("_\\((cropped|\\dx\\d_cropped)\\)");

    static String identity(String page) {
        for (String[] p : SAME_PERSON) {
            if (Pattern.compile(p[0]).matcher(page).find()) return p[1];
        }
        return page;
    }

    /** The photograph a file comes from: "X_(cropped).jpg" and "X_(3x4_cropped).jpg" are crops of "X.jpg". */
    static String source(String page) {
        return CROP.matcher(page).replaceAll("");
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            ModelBundleTool.Args a = ModelBundleTool.Args.parse(args, 0,
                    Arrays.asList("--speed", "--warm-up", "--labels-confirmed", "--identity"));
            Map<String, String> subjectMap = subjectMap(a.has("--subject-map") ? a.single("--subject-map") : null);
            File data = new File(a.has("--data") ? a.single("--data") : "eval-data");
            File portraits = new File(a.has("--portraits") ? a.single("--portraits") : "models/tools/.cache/pitch-calibration");
            File list = new File(a.has("--list") ? a.single("--list") : "models/tools/pitch_calibration_urls.txt");
            File golden = new File(a.has("--golden") ? a.single("--golden") : "liveness-analyzer/src/test/resources/golden");
            String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            File outDir = new File(a.has("--out") ? a.single("--out") : "eval-data/reports/binding-" + date);
            BundleSource source = a.has("--bundle") ? new DirectoryBundleSource(new File(a.single("--bundle")))
                    : new ClasspathBundleSource();

            // Decoded before timing starts, so the image reader's start-up is not counted.
            Frame portrait = PadEvaluation.load(new File(golden, "portrait_frontal.png"), 0, 0, "golden");
            Frame mirrored = mirror(portrait);
            Speed speed = new Speed();
            long t0 = System.nanoTime();
            OnnxFaceAnalyzerFactory factory = new OnnxFaceAnalyzerFactory(source, AnalyzerConfig.defaults());
            factory.preload();
            speed.preloadMs = ms(t0);
            Map<String, Scores> sets = new LinkedHashMap<String, Scores>();
            Map<String, String> notes = new LinkedHashMap<String, String>();
            String version = factory.modelVersion();
            int subjects = 0;
            IdentityRule rule = null;
            String breakdown = "";
            try (FaceMatcher matcher = factory.createMatcher()) {
                if (a.has("--warm-up")) {
                    long w0 = System.nanoTime();
                    factory.warmUpMatcher();
                    speed.warmUpMs = ms(w0);
                }
                long c0 = System.nanoTime();
                matcher.compare(portrait, mirrored);
                speed.firstCompareMs = ms(c0);
                if (a.has("--speed")) {
                    speed.measureWarm(matcher, portrait, mirrored);
                    out.print(speed.lines(version));
                    return 0;
                }
                // Recorded takes: best frame per take, by subject.
                Recorded recorded = new Recorded();
                for (EvalDataset.TakeRef t : EvalDataset.scan(data)) {
                    Recorded.Kind kind = Recorded.kind(t);
                    if (kind == null) continue;
                    Frame best = bestFrame(factory, t);
                    float[] f = best == null ? null : matcher.featureOfSingleFace(best.rgb(), best.width(), best.height());
                    if (f == null) {
                        recorded.noFace++;
                        continue;
                    }
                    recorded.add(t.subject, kind, f);
                    if (kind == Recorded.Kind.LIVE) recorded.addTake(t.subject, t.take, t.condition, f);
                    out.println("embedded best frame of " + t);
                }
                subjects = recorded.live.size();
                notes.put("recorded takes", recorded.summary());

                // Public portraits.
                List<Portrait> people = new ArrayList<Portrait>();
                int skipped = 0;
                for (String line : Files.readAllLines(list.toPath(), StandardCharsets.UTF_8)) {
                    if (line.trim().isEmpty() || line.startsWith("#")) continue;
                    String[] cols = line.split("\t");
                    File img = new File(portraits, cols[1].substring(0, 16) + ".jpg");
                    String page = cols[2].substring(cols[2].lastIndexOf('/') + 1);
                    Frame fr = PadEvaluation.load(img, 0, 0, "portrait");
                    float[] f = matcher.featureOfSingleFace(fr.rgb(), fr.width(), fr.height());
                    if (f == null) skipped++;
                    else people.add(new Portrait(java.net.URLDecoder.decode(page, "UTF-8"), f));
                }
                notes.put("public portraits", people.size() + " of 49 with exactly one face (" + skipped + " skipped), "
                        + countIdentities(people) + " different people");

                Frame crewFrame = PadEvaluation.load(new File(golden, "crew_multi.png"), 0, 0, "golden");
                List<float[]> crew = matcher.featuresOfAllFaces(crewFrame.rgb(), crewFrame.width(), crewFrame.height());
                notes.put("crew photo", crew.size() + " faces");

                recordedSets(sets, recorded);
                Scores g2 = set(sets, "genuine: the same NASA person in different photos", true);
                Scores i2 = set(sets, "impostor: NASA vs NASA, different people", false);
                for (int i = 0; i < people.size(); i++) {
                    for (int j = i + 1; j < people.size(); j++) {
                        Portrait p = people.get(i), q = people.get(j);
                        if (p.source.equals(q.source)) continue; // crops of one photograph
                        double s = SFaceEmbedder.cosine(p.feature, q.feature);
                        (p.identity.equals(q.identity) ? g2 : i2).values.add(s);
                    }
                }
                Scores i1 = set(sets, "impostor: the recorded subjects vs the public portraits", false);
                for (float[] b : recorded.allLive()) for (Portrait p : people) i1.values.add(SFaceEmbedder.cosine(b, p.feature));
                Scores i3 = set(sets, "impostor: crew faces vs each other and vs the public portraits", false);
                pairs(crew, i3);
                for (float[] c : crew) for (Portrait p : people) i3.values.add(SFaceEmbedder.cosine(c, p.feature));

                Map<String, List<float[]>> labelled = new TreeMap<String, List<float[]>>();
                Map<String, List<String>> photoQuality = new TreeMap<String, List<String>>();
                if (a.has("--labelled")) {
                    Labelled l = Labelled.load(new File(a.single("--labelled")), matcher,
                            a.has("--identity") ? factory : null);
                    List<float[]> nasa = new ArrayList<float[]>();
                    nasa.add(matcher.featureOfSingleFace(portrait.rgb(), portrait.width(), portrait.height()));
                    for (Portrait p : people) nasa.add(p.feature);
                    labelledSets(sets, l.byPerson, recorded, nasa, subjectMap);
                    labelled = l.byPerson;
                    photoQuality = l.quality;
                    notes.put("labelled photos", l.summary(a.has("--labels-confirmed")));
                }
                if (!subjectMap.isEmpty()) notes.put("subject map (codes only)", subjectMap.toString());
                if (a.has("--identity")) {
                    rule = IdentityRule.apply(identitySets(sets, recorded, labelled, subjectMap));
                    breakdown = identityBreakdown(recorded, labelled, photoQuality, subjectMap);
                    notes.put("F15 enrollment takes", recorded.enrollmentSummary());
                }

                speed.measureWarm(matcher, portrait, mirrored);
            } finally {
                factory.close();
            }
            String report = report(sets, notes, version, speed, date, subjects);
            if (rule != null) report += rule.section() + breakdown;
            out.print(report);
            if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("cannot create " + outDir);
            Files.write(new File(outDir, "report.md").toPath(), report.getBytes(StandardCharsets.UTF_8));
            out.println("wrote " + new File(outDir, "report.md"));
            return 0;
        } catch (ModelBundleTool.UsageException | IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            err.println("FAILED: " + e);
            e.printStackTrace(err);
            return 1;
        }
    }

    /** The recorded faces, by subject: live (bona fide) and photos of the subject's own selfie (screen or print). */
    static final class Recorded {
        enum Kind { LIVE, OWN_PHOTO, NASA_PHOTO }

        final Map<String, List<float[]>> live = new TreeMap<String, List<float[]>>();
        final Map<String, List<float[]>> ownPhotos = new TreeMap<String, List<float[]>>();
        final List<float[]> nasaPhotos = new ArrayList<float[]>();
        int noFace;

        /** Bona fide; a screen photo or print of the subject's selfie or of a NASA portrait; else null (not used). */
        static Kind kind(EvalDataset.TakeRef t) {
            if (EvalDataset.BONA_FIDE.equals(t.species)) return Kind.LIVE;
            boolean photo = t.species.startsWith("screen_photo") || t.species.startsWith("print_");
            if (!photo || t.condition == null) return null;
            if (t.condition.startsWith("selfie")) return Kind.OWN_PHOTO;
            if (t.condition.startsWith("nasa")) return Kind.NASA_PHOTO;
            return null;
        }

        void add(String subject, Kind kind, float[] feature) {
            if (kind == Kind.NASA_PHOTO) {
                nasaPhotos.add(feature);
                return;
            }
            Map<String, List<float[]>> m = kind == Kind.LIVE ? live : ownPhotos;
            List<float[]> l = m.get(subject);
            if (l == null) m.put(subject, l = new ArrayList<float[]>());
            l.add(feature);
        }

        List<float[]> allLive() {
            List<float[]> all = new ArrayList<float[]>();
            for (List<float[]> l : live.values()) all.addAll(l);
            return all;
        }

        /** Bona fide takes by subject and take number (F15). */
        final Map<String, TreeMap<Integer, float[]>> takes = new TreeMap<String, TreeMap<Integer, float[]>>();
        /** Their conditions, by subject and take number (the F15 breakdown). */
        final Map<String, Map<Integer, String>> conditions = new TreeMap<String, Map<Integer, String>>();
        /** The F15 enrollment per subject: the lowest-numbered "normal" take with a usable best frame. */
        final Map<String, Integer> enrollment = new TreeMap<String, Integer>();

        void addTake(String subject, int take, String condition, float[] feature) {
            TreeMap<Integer, float[]> m = takes.get(subject);
            if (m == null) takes.put(subject, m = new TreeMap<Integer, float[]>());
            m.put(take, feature);
            Map<Integer, String> c = conditions.get(subject);
            if (c == null) conditions.put(subject, c = new TreeMap<Integer, String>());
            c.put(take, condition == null ? "other" : condition);
            if ("normal".equals(condition)) {
                Integer e = enrollment.get(subject);
                if (e == null || take < e) enrollment.put(subject, take);
            }
        }

        String enrollmentSummary() {
            StringBuilder b = new StringBuilder();
            String sep = "";
            for (Map.Entry<String, TreeMap<Integer, float[]>> e : takes.entrySet()) {
                Integer take = enrollment.get(e.getKey());
                b.append(sep).append(e.getKey()).append(": ").append(take == null ? "no normal take, not enrolled"
                        : "take " + take + " (the first normal take), " + (e.getValue().size() - 1) + " other takes");
                sep = "; ";
            }
            return b.length() == 0 ? "no recorded subjects" : b.toString();
        }

        String summary() {
            StringBuilder b = new StringBuilder().append(live.size()).append(live.size() == 1 ? " subject (" : " subjects (");
            String sep = "";
            for (Map.Entry<String, List<float[]>> e : live.entrySet()) {
                List<float[]> own = ownPhotos.get(e.getKey());
                b.append(sep).append(e.getKey()).append(": ").append(e.getValue().size()).append(" bona fide, ")
                        .append(own == null ? 0 : own.size()).append(" photos of their own selfie");
                sep = "; ";
            }
            return b.append("), ").append(nasaPhotos.size()).append(" photos of a NASA portrait; ").append(noFace)
                    .append(" without a usable single-face frame").toString();
        }
    }

    /**
     * The sets of the recorded faces. A subject is the same person only as itself: genuine pairs are within a
     * subject, every pair across subjects is an impostor, and a photo of a subject's selfie (screen or print) is the
     * same person only for that subject.
     */
    static void recordedSets(Map<String, Scores> sets, Recorded r) {
        Scores genuine = set(sets, "genuine: each recorded subject's bona fide takes, pairwise", true);
        for (List<float[]> l : r.live.values()) pairs(l, genuine);
        Scores between = set(sets, "impostor: recorded subjects vs each other", false);
        List<String> subjects = new ArrayList<String>(r.live.keySet());
        for (int i = 0; i < subjects.size(); i++) {
            for (int j = i + 1; j < subjects.size(); j++) cross(r.live.get(subjects.get(i)), r.live.get(subjects.get(j)), between);
        }
        Scores photo = set(sets, "same person via a photo: each subject vs screen photos and prints of their own selfie",
                true);
        Scores otherPhoto = set(sets, "impostor: a subject vs photos of another subject's selfie", false);
        for (Map.Entry<String, List<float[]>> s : r.live.entrySet()) {
            for (Map.Entry<String, List<float[]>> p : r.ownPhotos.entrySet()) {
                cross(s.getValue(), p.getValue(), s.getKey().equals(p.getKey()) ? photo : otherPhoto);
            }
        }
        Scores nasa = set(sets, "impostor: the subjects vs screen photos and prints of NASA portraits", false);
        cross(r.allLive(), r.nasaPhotos, nasa);
    }

    /**
     * A labelled folder of consented photos (labels.csv: file,person_id; see {@link SlideshowSegmenter#readLabels}):
     * the largest face of each photo, by person. Group photos are used (largest face) and counted.
     */
    static final class Labelled {
        final Map<String, List<float[]>> byPerson = new TreeMap<String, List<float[]>>();
        /** Per photo, in the order of byPerson: the analyzer's view of it ({@link #photoQuality}), for the F15 breakdown. */
        final Map<String, List<String>> quality = new TreeMap<String, List<String>>();
        int photos, groupPhotos, noFace;

        /** factory: when not null, each photo also gets its {@link #photoQuality} line. */
        static Labelled load(File labels, FaceMatcher matcher, OnnxFaceAnalyzerFactory factory) throws Exception {
            Labelled l = new Labelled();
            File dir = labels.getAbsoluteFile().getParentFile();
            for (Map.Entry<String, String> e : SlideshowSegmenter.readLabels(labels).entrySet()) {
                Frame f = PadEvaluation.load(new File(dir, e.getKey()), 0, 0, "photo");
                FaceMatcher.PrimaryFeature p = matcher.featureOfPrimaryFace(f.rgb(), f.width(), f.height());
                if (p == null) {
                    l.noFace++;
                    continue;
                }
                l.photos++;
                if (p.faceCount > 1) l.groupPhotos++;
                List<float[]> list = l.byPerson.get(e.getValue());
                if (list == null) l.byPerson.put(e.getValue(), list = new ArrayList<float[]>());
                list.add(p.feature);
                if (factory == null) continue;
                List<String> q = l.quality.get(e.getValue());
                if (q == null) l.quality.put(e.getValue(), q = new ArrayList<String>());
                q.add(photoQuality(factory, f));
            }
            return l;
        }

        /**
         * One line on how the live analyzer sees a labelled photo: the face width in pixels, the face's mean luma, yaw
         * and pitch, and its quality issues. Numbers only: no image leaves memory.
         */
        static String photoQuality(OnnxFaceAnalyzerFactory factory, Frame f) throws Exception {
            OnnxFaceAnalyzer analyzer = (OnnxFaceAnalyzer) factory.create();
            try {
                FrameAnalysis r = analyzer.analyze(f);
                QualityReport q = analyzer.lastQuality();
                if (r.faceCount != 1 || q == null) return r.faceCount + " faces for the analyzer";
                return String.format(Locale.ROOT, "face %.0f px wide, luma %.0f, yaw %.0f, pitch %.0f, %s", q.faceWidthPx,
                        q.lumaMean, r.yawDeg, r.pitchDeg, r.qualityIssues.isEmpty() ? "no quality issue"
                                : r.qualityIssues.toString().replaceAll("[\\[\\]]", "").replace(", ", "+"));
            } finally {
                analyzer.close();
            }
        }

        String summary(boolean confirmed) {
            int several = 0;
            for (List<float[]> v : byPerson.values()) if (v.size() > 1) several++;
            return photos + " photos of " + byPerson.size() + " people (" + several + " in two or more photos), "
                    + groupPhotos + " group photos (largest face used), " + noFace + " without a face; labels "
                    + (confirmed ? "confirmed by the owner of the photos"
                    : "PROPOSED by face clustering, PROVISIONAL until the owner of the photos confirms them");
        }
    }

    /**
     * The sets of a labelled photo folder: genuine pairs within a person_id; impostors across person_ids, against the
     * recorded subjects (a person_id equal to a subject's code, such as s01, or mapped to it by the subject map, is that
     * subject: those pairs are genuine) and against the NASA portraits.
     */
    static void labelledSets(Map<String, Scores> sets, Map<String, List<float[]>> byPerson, Recorded r,
                             List<float[]> nasa, Map<String, String> subjectMap) {
        Scores genuine = set(sets, "genuine: labelled photos of the same person, pairwise", true);
        Scores between = set(sets, "impostor: labelled photos of different people", false);
        List<String> ids = new ArrayList<String>(byPerson.keySet());
        List<float[]> all = new ArrayList<float[]>();
        for (int i = 0; i < ids.size(); i++) {
            pairs(byPerson.get(ids.get(i)), genuine);
            all.addAll(byPerson.get(ids.get(i)));
            for (int j = i + 1; j < ids.size(); j++) cross(byPerson.get(ids.get(i)), byPerson.get(ids.get(j)), between);
        }
        Scores own = set(sets, "same person: labelled photos vs their own recorded bona fide takes", true);
        Scores subjects = set(sets, "impostor: labelled photos vs the recorded subjects", false);
        for (Map.Entry<String, List<float[]>> p : byPerson.entrySet()) {
            for (Map.Entry<String, List<float[]>> s : r.live.entrySet()) {
                cross(p.getValue(), s.getValue(), p.getKey().equals(personOf(s.getKey(), subjectMap)) ? own : subjects);
            }
        }
        if (own.values.isEmpty()) sets.remove(own.name);
        Scores vsNasa = set(sets, "impostor: labelled photos vs the NASA portraits", false);
        cross(all, nasa, vsNasa);
    }

    /** The labelled person a recorded subject is: the subject map's entry, else the subject code itself. */
    static String personOf(String subject, Map<String, String> subjectMap) {
        String p = subjectMap.get(subject);
        return p == null ? subject : p;
    }

    /**
     * {@code --subject-map s02=p05,s03=p11}: recorded subject codes to labelled person codes. Codes only: anything that
     * is not sNN=pNN is refused, so a name can never be passed in.
     */
    static Map<String, String> subjectMap(String spec) {
        Map<String, String> m = new TreeMap<String, String>();
        if (spec == null || spec.trim().isEmpty()) return m;
        java.util.Set<String> people = new java.util.HashSet<String>();
        for (String part : spec.split(",")) {
            String[] kv = part.trim().split("=");
            if (kv.length != 2 || !kv[0].trim().matches("s\\d{2}") || !kv[1].trim().matches("p\\d{2}")) {
                throw new IllegalArgumentException("--subject-map: expected sNN=pNN entries, got '" + part.trim() + "'");
            }
            if (m.put(kv[0].trim(), kv[1].trim()) != null || !people.add(kv[1].trim())) {
                throw new IllegalArgumentException("--subject-map: " + part.trim() + " repeats a subject or a person");
            }
        }
        return m;
    }

    /**
     * The F15 sets ({@link #identitySets}); photoPairs is context only, not part of the rule. onboardingPeople: the
     * distinct people in the onboarding-like set.
     */
    static final class IdentitySets {
        final Scores onboarding, sameDay, labelledImpostors, enrollmentImpostors, photoPairs;
        final int onboardingPeople;

        IdentitySets(Scores onboarding, Scores sameDay, Scores labelledImpostors, Scores enrollmentImpostors,
                     Scores photoPairs, int onboardingPeople) {
            this.onboarding = onboarding;
            this.sameDay = sameDay;
            this.labelledImpostors = labelledImpostors;
            this.enrollmentImpostors = enrollmentImpostors;
            this.photoPairs = photoPairs;
            this.onboardingPeople = onboardingPeople;
        }
    }

    /**
     * The F15 identity binding sets. Enrollment: each subject's first "normal" bona fide take (the run sheet's take
     * normal#1) stands for the onboarded face, and the subject's other takes are same-day verifications.
     * Onboarding-like: a labelled person's photos (other days, other cameras) against that person's live takes (the
     * subject map, or a person_id equal to the subject code). Labelled impostors: labelled photos of different people,
     * and labelled photos against every other subject's live takes. Enrollment impostors: each subject's enrollment
     * against every other subject's takes. Context only: labelled photos of the same person, pairwise (photo against
     * photo, other days and cameras on both sides).
     */
    static IdentitySets identitySets(Map<String, Scores> sets, Recorded r, Map<String, List<float[]>> labelled,
                                     Map<String, String> subjectMap) {
        Scores onboarding = set(sets, "F15 genuine, onboarding-like: labelled photos vs the same person's live takes", true);
        Scores sameDay = set(sets, "F15 genuine, same day: each subject's enrollment take vs their other takes", true);
        Scores labelledImpostors = set(sets,
                "F15 impostor, labelled: photos of different people, and photos vs other people's live takes", false);
        Scores enrollmentImpostors = set(sets, "F15 impostor: each subject's enrollment vs other subjects' takes", false);
        Scores photoPairs = set(sets, "F15 context, genuine: labelled photos of the same person, pairwise", true);
        List<String> ids = new ArrayList<String>(labelled.keySet());
        for (int i = 0; i < ids.size(); i++) {
            pairs(labelled.get(ids.get(i)), photoPairs);
            for (int j = i + 1; j < ids.size(); j++) cross(labelled.get(ids.get(i)), labelled.get(ids.get(j)), labelledImpostors);
        }
        java.util.Set<String> onboarded = new java.util.TreeSet<String>();
        for (Map.Entry<String, TreeMap<Integer, float[]>> s : r.takes.entrySet()) {
            List<float[]> live = new ArrayList<float[]>(s.getValue().values());
            String person = personOf(s.getKey(), subjectMap);
            for (Map.Entry<String, List<float[]>> p : labelled.entrySet()) {
                boolean same = p.getKey().equals(person);
                cross(p.getValue(), live, same ? onboarding : labelledImpostors);
                if (same && !p.getValue().isEmpty() && !live.isEmpty()) onboarded.add(person);
            }
            Integer e = r.enrollment.get(s.getKey());
            if (e == null) continue;
            float[] enrolled = s.getValue().get(e);
            for (Map.Entry<Integer, float[]> t : s.getValue().entrySet()) {
                if (!t.getKey().equals(e)) sameDay.values.add(SFaceEmbedder.cosine(enrolled, t.getValue()));
            }
            for (Map.Entry<String, TreeMap<Integer, float[]>> other : r.takes.entrySet()) {
                if (other.getKey().equals(s.getKey())) continue;
                for (float[] t : other.getValue().values()) enrollmentImpostors.values.add(SFaceEmbedder.cosine(enrolled, t));
            }
        }
        return new IdentitySets(onboarding, sameDay, labelledImpostors, enrollmentImpostors, photoPairs,
                onboarded.size());
    }

    /** The run sheet's bona fide conditions, in its order; any other condition follows, sorted. */
    static final List<String> CONDITION_ORDER = Arrays.asList("normal", "close", "far", "dim", "moving", "close_whole");
    private static final java.util.Comparator<String> BY_CONDITION = (x, y) -> {
        int i = CONDITION_ORDER.indexOf(x), j = CONDITION_ORDER.indexOf(y);
        if (i < 0) i = CONDITION_ORDER.size();
        if (j < 0) j = CONDITION_ORDER.size();
        return i != j ? Integer.compare(i, j) : x.compareTo(y);
    };
    /** The F15 breakdown's thresholds: production (0.40) and the rule's lowest candidate (0.36). */
    static final double[] BREAKDOWN_THRESHOLDS = {IdentityRule.FALLBACK, IdentityRule.CANDIDATES[0]};

    /**
     * Context for the F15 result, not part of the rule ({@link IdentityRule}): the pairs of {@link #identitySets} split
     * by pair type, by the live take's condition, by subject and by labelled photo, and the onboarding-like pairs
     * scored against the best of the person's photos (several templates, one value per live take). A person's photos
     * are numbered in labels.csv order, with the analyzer's view of each ({@link Labelled#photoQuality}). Codes and
     * aggregates only.
     */
    static String identityBreakdown(Recorded r, Map<String, List<float[]>> labelled, Map<String, List<String>> photoQuality,
                                    Map<String, String> subjectMap) {
        Scores sameDay = new Scores("genuine, same day: each subject's enrollment take (normal#1) vs their other takes", true);
        Map<String, Scores> sameDayByCondition = new TreeMap<String, Scores>(BY_CONDITION);
        Map<String, Scores> sameDayBySubject = new TreeMap<String, Scores>();
        Scores onboarding = new Scores("genuine, onboarding-like: a labelled photo vs the same person's live takes", true);
        Map<String, Scores> onboardingByCondition = new TreeMap<String, Scores>(BY_CONDITION);
        Map<String, Scores> onboardingBySubject = new TreeMap<String, Scores>();
        Map<String, Scores> byPhoto = new LinkedHashMap<String, Scores>();
        Scores bestPhoto = new Scores("best of the person's photos, one value per live take", true);
        Scores photoImpostors = new Scores("impostor: labelled photos of different people", false);
        Scores photoVsLive = new Scores("impostor: a labelled photo vs other people's live takes", false);
        Scores enrollmentImpostors = new Scores("impostor: each subject's enrollment take vs other subjects' takes", false);
        List<String> ids = new ArrayList<String>(labelled.keySet());
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) cross(labelled.get(ids.get(i)), labelled.get(ids.get(j)), photoImpostors);
        }
        for (Map.Entry<String, TreeMap<Integer, float[]>> s : r.takes.entrySet()) {
            String subject = s.getKey(), person = personOf(subject, subjectMap);
            Map<Integer, String> condition = r.conditions.get(subject);
            List<float[]> live = new ArrayList<float[]>(s.getValue().values());
            for (Map.Entry<String, List<float[]>> p : labelled.entrySet()) {
                if (!p.getKey().equals(person)) cross(p.getValue(), live, photoVsLive);
            }
            List<float[]> photos = labelled.get(person);
            List<String> quality = photoQuality.get(person);
            if (photos != null && !photos.isEmpty()) {
                for (Map.Entry<Integer, float[]> t : s.getValue().entrySet()) {
                    double best = Double.NEGATIVE_INFINITY;
                    for (int i = 0; i < photos.size(); i++) {
                        double v = SFaceEmbedder.cosine(photos.get(i), t.getValue());
                        best = Math.max(best, v);
                        onboarding.values.add(v);
                        row(onboardingByCondition, condition.get(t.getKey())).values.add(v);
                        row(onboardingBySubject, subject).values.add(v);
                        row(byPhoto, subject + " photo " + (i + 1) + " of " + photos.size()
                                + (quality != null && i < quality.size() ? " (" + quality.get(i) + ")" : "")).values.add(v);
                    }
                    bestPhoto.values.add(best);
                }
            }
            Integer e = r.enrollment.get(subject);
            if (e == null) continue;
            float[] enrolled = s.getValue().get(e);
            for (Map.Entry<Integer, float[]> t : s.getValue().entrySet()) {
                if (t.getKey().equals(e)) continue;
                double v = SFaceEmbedder.cosine(enrolled, t.getValue());
                sameDay.values.add(v);
                row(sameDayByCondition, condition.get(t.getKey())).values.add(v);
                row(sameDayBySubject, subject).values.add(v);
            }
            for (Map.Entry<String, TreeMap<Integer, float[]>> other : r.takes.entrySet()) {
                if (other.getKey().equals(subject)) continue;
                for (float[] t : other.getValue().values()) enrollmentImpostors.values.add(SFaceEmbedder.cosine(enrolled, t));
            }
        }
        StringBuilder sb = new StringBuilder("\n## F15 breakdown (context, not part of the rule)\n\n");
        sb.append("The rule's pairs, split. Genuine rows: FNMR, the share below the threshold; impostor rows: FMR, the ")
                .append("share at or above it. 0.40 is production, 0.36 the rule's lowest candidate. Same day: both sides ")
                .append("are live takes of one session on one camera. Onboarding-like: a labelled photo (another day and ")
                .append("camera) against live takes. A person's photos are numbered in labels.csv order; in brackets, ")
                .append("how the live analyzer sees the photo.\n\n")
                .append("| pairs | n | FNMR or FMR at 0.40 | at 0.36 | min | median | max |\n|---|---|---|---|---|---|---|\n");
        breakdownRow(sb, "**" + sameDay.name + "**", sameDay);
        for (Map.Entry<String, Scores> c : sameDayByCondition.entrySet()) breakdownRow(sb, "condition " + c.getKey(), c.getValue());
        for (Map.Entry<String, Scores> c : sameDayBySubject.entrySet()) breakdownRow(sb, c.getKey(), c.getValue());
        breakdownRow(sb, "**" + onboarding.name + "**", onboarding);
        for (Map.Entry<String, Scores> c : onboardingByCondition.entrySet()) {
            breakdownRow(sb, "condition " + c.getKey(), c.getValue());
        }
        for (Map.Entry<String, Scores> c : onboardingBySubject.entrySet()) breakdownRow(sb, c.getKey(), c.getValue());
        for (Map.Entry<String, Scores> c : byPhoto.entrySet()) breakdownRow(sb, c.getKey(), c.getValue());
        breakdownRow(sb, bestPhoto.name, bestPhoto);
        Scores allLabelled = new Scores("impostor: the rule's labelled impostors (the two rows above)", false);
        allLabelled.values.addAll(photoImpostors.values);
        allLabelled.values.addAll(photoVsLive.values);
        for (Scores s : new Scores[] {photoImpostors, photoVsLive, allLabelled, enrollmentImpostors}) {
            breakdownRow(sb, "**" + s.name + "**", s);
        }
        return sb.toString();
    }

    private static Scores row(Map<String, Scores> rows, String key) {
        Scores s = rows.get(key);
        if (s == null) rows.put(key, s = new Scores(key, true));
        return s;
    }

    private static void breakdownRow(StringBuilder sb, String label, Scores s) {
        double[] v = s.sorted();
        sb.append("| ").append(label).append(" | ").append(v.length);
        for (double t : BREAKDOWN_THRESHOLDS) {
            sb.append(v.length == 0 ? " | -" : String.format(Locale.ROOT, " | %d/%d (%.1f%%)", s.errors(t), v.length,
                    100 * s.errorRate(t)));
        }
        sb.append(" | ").append(f(v, 0)).append(" | ").append(f(v, 50)).append(" | ").append(f(v, 100)).append(" |\n");
    }

    /**
     * The F15 threshold rule, fixed before the F9 teammate data: among 0.36 to 0.50 in steps of 0.02, the value with
     * the lowest labelled-impostor FMR whose FNMR on the onboarding-like pairs is at most 5% (ties go to the lower
     * value, which rejects fewer genuine users). If no value qualifies, the provisional 0.40 stays. The rule decides
     * only with at least {@value #MIN_PEOPLE} distinct people and {@value #MIN_PAIRS} pairs in the onboarding-like set,
     * and some labelled impostors (guard added 2026-10-27); otherwise 0.40 stays too.
     */
    static final class IdentityRule {
        static final double[] CANDIDATES = {0.36, 0.38, 0.40, 0.42, 0.44, 0.46, 0.48, 0.50};
        static final double MAX_FNMR = 0.05;
        static final double FALLBACK = 0.40;
        static final int MIN_PEOPLE = 3;
        static final int MIN_PAIRS = 50;

        final IdentitySets sets;
        final double chosen;
        final boolean met;

        private IdentityRule(IdentitySets sets, double chosen, boolean met) {
            this.sets = sets;
            this.chosen = chosen;
            this.met = met;
        }

        /** Enough onboarding-like data for the rule to decide anything. */
        static boolean decidable(IdentitySets s) {
            return s.onboardingPeople >= MIN_PEOPLE && s.onboarding.values.size() >= MIN_PAIRS
                    && !s.labelledImpostors.values.isEmpty();
        }

        static IdentityRule apply(IdentitySets s) {
            if (!decidable(s)) {
                return new IdentityRule(s, FALLBACK, false);
            }
            double best = Double.NaN, bestFmr = Double.NaN;
            for (double t : CANDIDATES) {
                if (s.onboarding.errorRate(t) > MAX_FNMR) continue;
                double fmr = s.labelledImpostors.errorRate(t);
                if (Double.isNaN(best) || fmr < bestFmr) {
                    best = t;
                    bestFmr = fmr;
                }
            }
            return Double.isNaN(best) ? new IdentityRule(s, FALLBACK, false) : new IdentityRule(s, best, true);
        }

        String section() {
            StringBuilder sb = new StringBuilder("\n## F15 identity threshold rule\n\n");
            sb.append("Fixed before the F9 teammate data: from 0.36 to 0.50 in steps of 0.02, the value with the lowest ")
                    .append("labelled-impostor FMR whose FNMR on the onboarding-like pairs is at most 5% (ties go to the ")
                    .append("lower value). If no value qualifies, 0.40 stays and older templates should be re-onboarded. ")
                    .append("The rule decides only with at least 3 people and 50 pairs in the onboarding-like set; ")
                    .append("otherwise 0.40 stays. ")
                    .append("F15 is a second factor after the password: its FMR is the rate at which someone who has ")
                    .append("the password AND a similar face gets in.\n\n")
                    .append("| threshold | FNMR onboarding-like | FNMR same day | FMR labelled impostors | ")
                    .append("FMR enrollment impostors | FNMR at most 5% | context: FNMR photo vs photo, same person |\n")
                    .append("|---|---|---|---|---|---|---|\n");
            for (double t : CANDIDATES) {
                String mark = t != chosen ? "" : met ? " (chosen)" : " (stays)";
                sb.append(String.format(Locale.ROOT, "| %.2f%s | %s | %s | %s | %s | %s | %s |%n", t, mark,
                        cell(sets.onboarding, t), cell(sets.sameDay, t), cell(sets.labelledImpostors, t),
                        cell(sets.enrollmentImpostors, t), sets.onboarding.values.isEmpty() ? "-"
                                : sets.onboarding.errorRate(t) <= MAX_FNMR ? "yes" : "no", cell(sets.photoPairs, t)));
            }
            sb.append('\n');
            if (met) {
                sb.append(String.format(Locale.ROOT, "**Result: %.2f** meets the rule.%n", chosen));
            } else if (!decidable(sets)) {
                int people = sets.onboardingPeople;
                sb.append(String.format(Locale.ROOT, "**Result: not decidable: %d %s / %d pairs** in the onboarding-like "
                        + "set%s (the rule needs at least %d people and %d pairs). 0.40 stays.%n", people,
                        people == 1 ? "person" : "people", sets.onboarding.values.size(),
                        sets.labelledImpostors.values.isEmpty() ? ", and no labelled impostors" : "", MIN_PEOPLE,
                        MIN_PAIRS));
            } else {
                sb.append(String.format(Locale.ROOT, "**Result: no value meets the rule.** 0.40 stays, with FNMR %s on the "
                        + "onboarding-like pairs; older templates should be re-onboarded.%n", cell(sets.onboarding, FALLBACK)));
            }
            int n = sets.onboarding.values.size();
            if (n > 0 && n < 100) {
                sb.append(String.format(Locale.ROOT, "%nOnly %d onboarding-like pairs: one more false non-match moves their "
                        + "FNMR by %.1f points.%n", n, 100.0 / n));
            }
            sb.append("\nThe context column is not part of the rule: both sides are photos, from other days and cameras.\n");
            return sb.toString();
        }

        private static String cell(Scores s, double t) {
            return s.values.isEmpty() ? "-" : String.format(Locale.ROOT, "%d/%d (%.1f%%)", s.errors(t), s.values.size(),
                    100 * s.errorRate(t));
        }
    }

    private static Scores set(Map<String, Scores> sets, String name, boolean genuine) {
        Scores s = new Scores(name, genuine);
        sets.put(name, s);
        return s;
    }

    private static void pairs(List<float[]> faces, Scores into) {
        for (int i = 0; i < faces.size(); i++) {
            for (int j = i + 1; j < faces.size(); j++) into.values.add(SFaceEmbedder.cosine(faces.get(i), faces.get(j)));
        }
    }

    private static void cross(List<float[]> a, List<float[]> b, Scores into) {
        for (float[] x : a) for (float[] y : b) into.values.add(SFaceEmbedder.cosine(x, y));
    }

    private static int countIdentities(List<Portrait> people) {
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (Portrait p : people) ids.add(p.identity);
        return ids.size();
    }

    /**
     * The take's best frame: one face with landmarks, no quality issue; among those with |yaw| and |pitch| at most
     * 10 degrees the sharpest (largest lapVar), else the most frontal (smallest |yaw| + |pitch|). A take without
     * such a frame (screen photos held close are often FACE_TOO_LARGE) falls back to its most frontal single-face
     * frame whatever its quality issues. Null when no frame has exactly one face with landmarks.
     */
    static Frame bestFrame(OnnxFaceAnalyzerFactory factory, EvalDataset.TakeRef t) throws Exception {
        OnnxFaceAnalyzer analyzer = (OnnxFaceAnalyzer) factory.create();
        try {
            Frame best = null, mostFrontal = null, anyQuality = null;
            double bestSharpness = -1, bestFrontalness = Double.MAX_VALUE, anyFrontalness = Double.MAX_VALUE;
            List<File> files = t.frames();
            for (int i = 0; i < files.size(); i++) {
                Frame f = PadEvaluation.load(files.get(i), Math.round(i * 1000.0 / t.fps), i, "eval:" + t);
                FrameAnalysis r = analyzer.analyze(f);
                QualityReport q = analyzer.lastQuality();
                if (r.faceCount != 1 || q == null || Float.isNaN(r.yawDeg)) continue;
                double off = Math.abs(r.yawDeg) + Math.abs(r.pitchDeg);
                if (off < anyFrontalness) {
                    anyFrontalness = off;
                    anyQuality = f;
                }
                if (!r.qualityIssues.isEmpty()) continue;
                if (off < bestFrontalness) {
                    bestFrontalness = off;
                    mostFrontal = f;
                }
                if (Math.abs(r.yawDeg) <= FRONTAL_DEG && Math.abs(r.pitchDeg) <= FRONTAL_DEG && q.lapVar > bestSharpness) {
                    bestSharpness = q.lapVar;
                    best = f;
                }
            }
            return best != null ? best : mostFrontal != null ? mostFrontal : anyQuality;
        } finally {
            analyzer.close();
        }
    }

    /** Timings on this computer (ms). The first comparison builds the MATCHER session unless --warm-up did. */
    static final class Speed {
        static final int RUNS = 20;
        double preloadMs = Double.NaN, warmUpMs = Double.NaN, firstCompareMs = Double.NaN, compareMs = Double.NaN,
                embedMs = Double.NaN;

        /** Warm figures: means of RUNS after 3 unmeasured calls. */
        void measureWarm(FaceMatcher matcher, Frame a, Frame b) throws Exception {
            for (int i = 0; i < 3; i++) matcher.compare(a, b);
            long s0 = System.nanoTime();
            for (int i = 0; i < RUNS; i++) matcher.compare(a, b);
            compareMs = ms(s0) / RUNS;
            long e0 = System.nanoTime();
            for (int i = 0; i < RUNS; i++) matcher.featureOfSingleFace(a.rgb(), a.width(), a.height());
            embedMs = ms(e0) / RUNS;
        }

        String lines(String version) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "- model bundle %s%n", version));
            sb.append(String.format(Locale.ROOT, "- factory preload (verify the 5 files, build the 4 per-frame sessions): "
                    + "%.0f ms%n", preloadMs));
            if (!Double.isNaN(warmUpMs)) {
                sb.append(String.format(Locale.ROOT, "- warmUpMatcher (build the MATCHER session): %.0f ms%n", warmUpMs));
            }
            sb.append(String.format(Locale.ROOT, "- first comparison (two images)%s: %.0f ms%n",
                    Double.isNaN(warmUpMs) ? ", which builds the MATCHER session" : ", after the warm-up", firstCompareMs));
            sb.append(String.format(Locale.ROOT, "- comparison, warm: %.1f ms (mean of %d)%n", compareMs, RUNS));
            sb.append(String.format(Locale.ROOT, "- one face (detection + alignment + SFace), warm: %.1f ms (mean of %d)%n",
                    embedMs, RUNS));
            return sb.toString();
        }
    }

    static double ms(long since) {
        return (System.nanoTime() - since) / 1e6;
    }

    /** The frame mirrored horizontally (the golden fixtures' "_flipped" twin; there is no flipped PNG). */
    static Frame mirror(Frame f) {
        int w = f.width(), h = f.height();
        byte[] src = f.rgb(), out = new byte[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) System.arraycopy(src, (y * w + x) * 3, out, (y * w + (w - 1 - x)) * 3, 3);
        }
        return new Frame(out, w, h, f.timestampMs(), f.sequence(), f.deviceId());
    }

    static String report(Map<String, Scores> sets, Map<String, String> notes, String version, Speed speed, String date,
                         int subjects) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Face binding (F1) evaluation: PROVISIONAL, ")
                .append(subjects == 1 ? "SINGLE SUBJECT" : subjects + " SUBJECTS").append("\n\n");
        sb.append("Generated ").append(date).append(" by `liveness-eval` `BindingEvaluation`, model bundle ").append(version)
                .append(", SFace cosine similarity. Aggregates only: no images, features or per-take values.\n\n");
        for (Map.Entry<String, String> e : notes.entrySet()) sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        sb.append("\n## Similarity distributions\n\n| set | pairs | min | p5 | median | p95 | max |\n|---|---|---|---|---|---|---|\n");
        for (Scores s : sets.values()) {
            double[] v = s.sorted();
            sb.append(String.format(Locale.ROOT, "| %s | %d | %s | %s | %s | %s | %s |%n", s.name, v.length, f(v, 0),
                    f(v, 5), f(v, 50), f(v, 95), f(v, 100)));
        }
        sb.append("\n## Error rates by threshold\n\nGenuine and same-person sets: FNMR, the share below the threshold. ")
                .append("Impostor sets: FMR, the share at or above it.\n\n| threshold |");
        for (Scores s : sets.values()) sb.append(' ').append(s.genuine ? "FNMR " : "FMR ").append(shortName(s.name)).append(" |");
        sb.append("\n|---|");
        for (int i = 0; i < sets.size(); i++) sb.append("---|");
        sb.append('\n');
        for (double t : THRESHOLDS) {
            sb.append(String.format(Locale.ROOT, "| %.3f |", t));
            for (Scores s : sets.values()) {
                sb.append(String.format(Locale.ROOT, " %d/%d (%.1f%%) |", s.errors(t), s.values.size(), 100 * s.errorRate(t)));
            }
            sb.append('\n');
        }
        sb.append(String.format(Locale.ROOT, "%n## Speed (this computer)%n%n")).append(speed.lines(version));
        return sb.toString();
    }

    /** Nearest-rank percentile of sorted values, 3 decimals; "-" when empty. */
    static String f(double[] sorted, double p) {
        if (sorted.length == 0) return "-";
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return String.format(Locale.ROOT, "%.3f", sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))]);
    }

    static String shortName(String name) {
        int colon = name.indexOf(':');
        String rest = colon >= 0 ? name.substring(colon + 1).trim() : name;
        return rest.length() > 28 ? rest.substring(0, 28) + "..." : rest;
    }
}
