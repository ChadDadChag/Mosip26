package io.mosip.registration.liveness.api;

public class LivenessEngineException extends Exception {
    private final ErrorCode code;
    public LivenessEngineException(ErrorCode code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public LivenessEngineException(ErrorCode code, String message) { this(code, message, null); }
    public ErrorCode code() { return code; }
}
