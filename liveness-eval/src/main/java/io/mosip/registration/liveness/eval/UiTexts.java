package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessEvent;

/** The problem statement's on-screen texts for each LivenessEvent (the real apps use message keys). */
final class UiTexts {

    private UiTexts() { }

    static String forEvent(LivenessEvent e) {
        switch (e.type) {
            case STARTED: return "Starting…";
            case CHECKING_PASSIVE: return "Checking face liveness…";
            case GUIDANCE: return guidance(e.guidance);
            case CHALLENGE_PROMPT: return challenge(e.challenge);
            case CHALLENGE_PROGRESS: return "Please continue";
            case ACTION_DETECTED: return "Action detected";
            case HOLD_STILL: return "Hold still";
            case PASSED: return "Good, face captured successfully";
            case ATTEMPT_FAILED:
            case FAILED_FINAL:
            case DEVICE_ERROR: return failure(e.error);
            default: return e.type.name();
        }
    }

    static String challenge(ChallengeType c) {
        if (c == null) return "Please follow the instruction";
        switch (c) {
            case BLINK: return "Please blink";
            case SMILE: return "Please smile";
            case TURN_LEFT: return "Please turn your head to the left";
            case TURN_RIGHT: return "Please turn your head to the right";
            case LOOK_UP: return "Please look up";
            default: return "Please look down";
        }
    }

    static String guidance(LivenessEvent.Guidance g) {
        if (g == null) return "";
        switch (g) {
            case NO_FACE: return "No face detected: look at the camera";
            case MULTIPLE_FACES: return "Only one person should be in view";
            case MOVE_CLOSER: return "Please move closer";
            case MOVE_BACK: return "Please move back";
            case CENTER_FACE: return "Please centre your face";
            case IMPROVE_LIGHTING: return "Please improve the lighting";
            case HOLD_STEADY: return "Please hold steady";
            default: return "Please look at the camera";
        }
    }

    /** PAD failures get the same generic text as other verification failures (contract). */
    static String failure(ErrorCode code) {
        if (code == null) return "We could not verify face liveness. Please try again.";
        switch (code.userMessage()) {
            case VERIFICATION_NOT_COMPLETED: return "Face verification could not be completed. Please try again.";
            case MAX_ATTEMPTS_REACHED: return "Face verification could not be completed. Please contact the supervisor.";
            case DEVICE_PROBLEM: return "Camera not available. Check the connection and try again.";
            case NONE: return "Cancelled";
            default: return "We could not verify face liveness. Please try again.";
        }
    }
}
