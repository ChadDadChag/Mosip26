package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessResult;

/** Side effect requested by {@link SessionStateMachine}; executed by the session wiring. */
public final class Effect {

    public enum Kind { EMIT, COMPLETE, STOP_SOURCE, RESTART_SOURCE, TRIP_COOLDOWN }

    public final Kind kind;
    public final LivenessEvent event;
    public final LivenessResult result;

    private Effect(Kind kind, LivenessEvent event, LivenessResult result) {
        this.kind = kind; this.event = event; this.result = result;
    }

    static Effect emit(LivenessEvent e) { return new Effect(Kind.EMIT, e, null); }
    static Effect complete(LivenessResult r) { return new Effect(Kind.COMPLETE, null, r); }
    static Effect of(Kind k) { return new Effect(k, null, null); }

    @Override
    public String toString() {
        if (kind == Kind.EMIT) return "EMIT " + event.type + (event.error != null ? "[" + event.error + "]" : "");
        if (kind == Kind.COMPLETE) return "COMPLETE " + result.decision + (result.failureCode != null ? "[" + result.failureCode + "]" : "");
        return kind.name();
    }
}
