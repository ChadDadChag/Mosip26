package io.mosip.registration.liveness.device.demo;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessEngineException;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What the liveness harness shows, independent of Swing so it can be tested: the current status and prompt, the
 * last guidance hint, challenge progress, attempt and retries, the final result, a short event log, and the
 * analyzer's latest per-frame view (face count, face box, liveness score).
 *
 * <p>The English texts follow the problem statement's examples. They are a development aid only: the apps (Parts
 * 4 and 5) own the real, translated messages, keyed by {@code ErrorCode.UserMessage} and {@code ChallengeType}.</p>
 *
 * <p>Thread-safe: events arrive on the engine's dispatcher thread, analyses on its worker thread, and the UI reads
 * {@link #snapshot()} on its own thread.</p>
 */
public final class HarnessModel implements LivenessListener {

    /** Notified after every change, on the thread that made it (the view marshals to its UI thread). */
    public interface ChangeListener {
        void changed();
    }

    /** Immutable copy for rendering. */
    public static final class Snapshot {
        public final String status, prompt, guidance, result, attempt;
        public final float progress, lastScore;
        public final int faceCount;
        public final float[] faceBox;
        public final long framesAnalysed;
        public final boolean running;
        public final List<String> log;

        Snapshot(String status, String prompt, String guidance, String result, String attempt, float progress,
                 float lastScore, int faceCount, float[] faceBox, long framesAnalysed, boolean running, List<String> log) {
            this.status = status; this.prompt = prompt; this.guidance = guidance; this.result = result;
            this.attempt = attempt; this.progress = progress; this.lastScore = lastScore; this.faceCount = faceCount;
            this.faceBox = faceBox; this.framesAnalysed = framesAnalysed; this.running = running; this.log = log;
        }
    }

    static final int LOG_LINES = 14;

    private final List<ChangeListener> listeners = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private String status = "Ready";
    private String prompt = "";
    private String guidance = "";
    private String result = "";
    private String attempt = "";
    private float progress;
    private float lastScore = Float.NaN;
    private int faceCount;
    private float[] faceBox;
    private long framesAnalysed;
    private boolean running;
    private LivenessResult lastResult;

    public synchronized void addChangeListener(ChangeListener l) {
        listeners.add(l);
    }

    /** Clears the per-session state when a new session starts. */
    public void sessionStarting(String label) {
        synchronized (this) {
            status = "Starting " + label;
            prompt = guidance = result = attempt = "";
            progress = 0f;
            running = true;
            lastResult = null;
            addLog("--- " + label);
        }
        fire();
    }

    @Override
    public void onEvent(LivenessEvent e) {
        synchronized (this) {
            if (e.attemptNumber > 0) attempt = "Attempt " + e.attemptNumber;
            switch (e.type) {
                case STARTED:
                    status = "Starting";
                    prompt = guidance = "";
                    progress = 0f;
                    break;
                case CHECKING_PASSIVE:
                    status = "Checking face liveness...";
                    guidance = "";
                    break;
                case GUIDANCE:
                    guidance = guidanceText(e.guidance);
                    break;
                case CHALLENGE_PROMPT:
                    status = "Challenge " + e.challengeIndex + " of " + e.challengeCount;
                    prompt = promptText(e.challenge);
                    guidance = "";
                    progress = 0f;
                    break;
                case CHALLENGE_PROGRESS:
                    progress = e.progress;
                    guidance = progress > 0 ? "Please continue" : guidance;
                    break;
                case ACTION_DETECTED:
                    progress = 1f;
                    guidance = "Action detected";
                    break;
                case HOLD_STILL:
                    status = "Hold still";
                    prompt = "";
                    guidance = "";
                    break;
                case PASSED:
                    status = "Good, face captured successfully";
                    prompt = guidance = "";
                    break;
                case ATTEMPT_FAILED:
                    status = errorText(e.error) + " (" + e.retriesLeft + " retr" + (e.retriesLeft == 1 ? "y" : "ies")
                            + " left: press Retry)";
                    prompt = guidance = "";
                    break;
                case FAILED_FINAL:
                    status = errorText(e.error);
                    prompt = guidance = "";
                    break;
                case DEVICE_ERROR:
                    status = errorText(e.error) + (e.recoverable ? " (press Retry)" : "");
                    prompt = guidance = "";
                    break;
                default:
                    break;
            }
            addLog(describe(e));
        }
        fire();
    }

    @Override
    public void onComplete(LivenessResult r) {
        synchronized (this) {
            lastResult = r;
            running = false;
            if (r.decision == LivenessResult.Decision.PASSED) {
                result = "PASSED (" + (r.passPath == LivenessResult.PassPath.PASSIVE_ONLY ? "passive" : "challenges "
                        + r.challengesIssued) + "), score " + score(r.passiveScore) + ", attempts " + r.attempts;
            } else if (r.decision == LivenessResult.Decision.CANCELLED) {
                result = "CANCELLED";
            } else {
                result = "FAILED " + r.failureCode + ", attempts " + r.attempts;
            }
            addLog("RESULT " + result + " (" + r.durationMs + " ms, model " + r.modelVersion + ")");
        }
        fire();
    }

    /** Latest analyzer output for the preview overlay (scores never go to audit from here). */
    public void observe(FrameAnalysis a) {
        synchronized (this) {
            framesAnalysed++;
            lastScore = a.livenessScore;
            faceCount = a.faceCount;
            faceBox = a.faceBox == null ? null : a.faceBox.clone();
        }
        fire();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(status, prompt, guidance, result, attempt, progress, lastScore, faceCount,
                faceBox == null ? null : faceBox.clone(), framesAnalysed, running,
                Collections.unmodifiableList(new ArrayList<>(log)));
    }

    public synchronized LivenessResult lastResult() {
        return lastResult;
    }

    /** Wraps an analyzer so every analysis also reaches this model. */
    public FaceAnalyzer observing(final FaceAnalyzer analyzer) {
        return new FaceAnalyzer() {
            @Override
            public FrameAnalysis analyze(Frame frame) throws LivenessEngineException {
                FrameAnalysis a = analyzer.analyze(frame);
                if (a != null) observe(a);
                return a;
            }

            @Override
            public String modelVersion() {
                return analyzer.modelVersion();
            }

            @Override
            public void close() {
                analyzer.close();
            }
        };
    }

    // ------------------------------------------------------------------ texts

    static String promptText(ChallengeType c) {
        if (c == null) return "";
        switch (c) {
            case BLINK: return "Please blink";
            case SMILE: return "Please smile";
            case TURN_LEFT: return "Please turn your head to the left";
            case TURN_RIGHT: return "Please turn your head to the right";
            case LOOK_UP: return "Please look up";
            case LOOK_DOWN: return "Please look down";
            default: return c.name();
        }
    }

    static String guidanceText(LivenessEvent.Guidance g) {
        if (g == null) return "";
        switch (g) {
            case NO_FACE: return "Ensure your face is clearly visible";
            case MULTIPLE_FACES: return "Only one person should be in front of the camera";
            case MOVE_CLOSER: return "Move closer";
            case MOVE_BACK: return "Move back";
            case CENTER_FACE: return "Keep your face within the capture area";
            case IMPROVE_LIGHTING: return "Improve lighting";
            case HOLD_STEADY: return "Hold steady";
            case LOOK_AT_CAMERA: return "Look directly at the camera";
            default: return g.name();
        }
    }

    static String errorText(ErrorCode code) {
        if (code == null) return "";
        switch (code.userMessage()) {
            case LIVENESS_NOT_VERIFIED: return "We could not verify face liveness. Please try again.";
            case VERIFICATION_NOT_COMPLETED: return "Face verification could not be completed. Please try again.";
            case MAX_ATTEMPTS_REACHED: return "Face verification could not be completed. Use the recovery process.";
            case DEVICE_PROBLEM: return "Camera/device not available. Check the connection and try again.";
            default: return "";
        }
    }

    private static String describe(LivenessEvent e) {
        StringBuilder b = new StringBuilder(e.type.name());
        if (e.challenge != null) b.append(' ').append(e.challenge).append(' ').append(e.challengeIndex).append('/').append(e.challengeCount);
        if (e.type == LivenessEvent.Type.CHALLENGE_PROGRESS) b.append(String.format(Locale.ROOT, " %.2f", e.progress));
        if (e.guidance != null) b.append(' ').append(e.guidance);
        if (e.error != null) b.append(' ').append(e.error).append(e.recoverable ? " (recoverable)" : "");
        return b.toString();
    }

    private static String score(float f) {
        return Float.isNaN(f) ? "n/a" : String.format(Locale.ROOT, "%.2f", f);
    }

    private void addLog(String line) {
        log.add(line);
        while (log.size() > LOG_LINES) log.remove(0);
    }

    private void fire() {
        List<ChangeListener> copy;
        synchronized (this) {
            copy = new ArrayList<>(listeners);
        }
        for (ChangeListener l : copy) l.changed();
    }
}
