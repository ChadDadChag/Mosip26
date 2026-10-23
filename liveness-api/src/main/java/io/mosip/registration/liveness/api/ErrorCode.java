package io.mosip.registration.liveness.api;

/**
 * One code per error case in the problem statement (section 9). The UI maps each code to a message key;
 * PAD_FAILURE deliberately maps to the same generic text as other verification failures.
 */
public enum ErrorCode {
    DEVICE_UNAVAILABLE, DEVICE_CONNECTION_FAILED, DEVICE_DISCONNECTED, CAMERA_UNAVAILABLE,
    FACE_NOT_DETECTED, MULTIPLE_FACES, POOR_QUALITY,
    LIVENESS_BELOW_THRESHOLD, PAD_FAILURE,
    CHALLENGE_FAILED, CHALLENGE_TIMEOUT, MAX_RETRIES_EXCEEDED,
    INVALID_FRAME, ENGINE_ERROR, MODEL_INTEGRITY_FAILED, CANCELLED;

    /** What the user may see. PAD_FAILURE is hidden behind VERIFICATION_NOT_COMPLETED. */
    public UserMessage userMessage() {
        switch (this) {
            case DEVICE_UNAVAILABLE: case DEVICE_CONNECTION_FAILED: case DEVICE_DISCONNECTED:
            case CAMERA_UNAVAILABLE: return UserMessage.DEVICE_PROBLEM;
            case PAD_FAILURE: case ENGINE_ERROR: case MODEL_INTEGRITY_FAILED: case INVALID_FRAME:
                return UserMessage.VERIFICATION_NOT_COMPLETED;
            case MAX_RETRIES_EXCEEDED: return UserMessage.MAX_ATTEMPTS_REACHED;
            case CANCELLED: return UserMessage.NONE;
            default: return UserMessage.LIVENESS_NOT_VERIFIED;
        }
    }

    /** Message keys shared by Desktop (messages_*.properties) and Android (Flutter l10n). */
    public enum UserMessage {
        NONE,
        LIVENESS_NOT_VERIFIED,      // "We could not verify face liveness. Please try again."
        VERIFICATION_NOT_COMPLETED, // "Face verification could not be completed. Please try again."
        MAX_ATTEMPTS_REACHED,       // "Face verification could not be completed. <recovery guidance>"
        DEVICE_PROBLEM              // "Camera/device not available. Check the connection and try again."
    }
}
