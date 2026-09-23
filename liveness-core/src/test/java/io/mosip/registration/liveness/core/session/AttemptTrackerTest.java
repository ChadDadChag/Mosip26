package io.mosip.registration.liveness.core.session;

import io.mosip.registration.liveness.api.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttemptTrackerTest {

    @Test
    void allowsOnePlusMaxRetriesAttemptsThenMaxRetriesExceeded() {
        AttemptTracker t = new AttemptTracker(3);
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(t.attemptNumber()).isEqualTo(attempt);
            AttemptTracker.Decision d = t.onAttemptFailed(ErrorCode.CHALLENGE_FAILED);
            assertThat(d.isFinal).isFalse();
            assertThat(d.code).isEqualTo(ErrorCode.CHALLENGE_FAILED);
            assertThat(d.retriesLeft).isEqualTo(4 - attempt);
            t.beginNextAttempt();
        }
        assertThat(t.attemptNumber()).isEqualTo(4);
        AttemptTracker.Decision last = t.onAttemptFailed(ErrorCode.PAD_FAILURE);
        assertThat(last.isFinal).isTrue();
        assertThat(last.code).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);
        assertThat(t.lastCause()).isEqualTo(ErrorCode.PAD_FAILURE);
    }

    @Test
    void zeroRetriesFailsFinalOnFirstFailure() {
        AttemptTracker.Decision d = new AttemptTracker(0).onAttemptFailed(ErrorCode.CHALLENGE_TIMEOUT);
        assertThat(d.isFinal).isTrue();
        assertThat(d.code).isEqualTo(ErrorCode.MAX_RETRIES_EXCEEDED);
    }

    @Test
    void deviceErrorsNeverConsumeRetries() {
        AttemptTracker t = new AttemptTracker(1);
        for (int i = 0; i < 5; i++) {
            AttemptTracker.Decision d = t.onAttemptFailed(ErrorCode.DEVICE_DISCONNECTED);
            assertThat(d.isFinal).isFalse();
            t.beginNextAttempt();
        }
        assertThat(t.retriesLeft()).isEqualTo(1);
    }

    @Test
    void engineErrorDoesNotConsumeRetryButIsCapped() {
        AttemptTracker t = new AttemptTracker(3);
        AttemptTracker.Decision first = t.onAttemptFailed(ErrorCode.ENGINE_ERROR);
        assertThat(first.isFinal).isFalse();
        t.beginNextAttempt();
        assertThat(t.retriesLeft()).isEqualTo(3);
        AttemptTracker.Decision second = t.onAttemptFailed(ErrorCode.ENGINE_ERROR);
        assertThat(second.isFinal).isTrue();
        assertThat(second.code).isEqualTo(ErrorCode.ENGINE_ERROR);
    }

    @Test
    void modelIntegrityFailureIsImmediatelyFinal() {
        AttemptTracker.Decision d = new AttemptTracker(3).onAttemptFailed(ErrorCode.MODEL_INTEGRITY_FAILED);
        assertThat(d.isFinal).isTrue();
        assertThat(d.code).isEqualTo(ErrorCode.MODEL_INTEGRITY_FAILED);
    }
}
