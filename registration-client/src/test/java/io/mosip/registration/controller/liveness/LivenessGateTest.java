package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** When a successful password login is followed by a liveness check. */
public class LivenessGateTest {

	@Test
	public void passwordLoginIsGatedWhenEnabledAndNoFaceStepFollows() {
		assertTrue(LivenessGate.gatesPasswordLogin(true, Collections.<String>emptyList()));
		assertTrue(LivenessGate.gatesPasswordLogin(true, null));
		assertTrue(LivenessGate.gatesPasswordLogin(true, Arrays.asList("OTP", "FINGERPRINT")));
	}

	@Test
	public void notGatedWhenSwitchedOff() {
		assertFalse(LivenessGate.gatesPasswordLogin(false, Collections.<String>emptyList()));
	}

	@Test
	public void notGatedTwiceWhenAFaceLoginStepFollows() {
		assertFalse(LivenessGate.gatesPasswordLogin(true, Arrays.asList("face")));
		assertFalse(LivenessGate.gatesPasswordLogin(true, Arrays.asList("OTP", "FACE")));
	}
}
