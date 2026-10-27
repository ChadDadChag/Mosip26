package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** When a successful password login is followed by a liveness check, and the identity binding (F15) retry rule. */
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

	@Test
	public void identityBindingAllowsOneRetryAndOnlyWhenItCanHelp() {
		assertEquals(2, LivenessGate.IDENTITY_ATTEMPTS);
		assertEquals(LivenessGate.IdentityStep.PROCEED, LivenessGate.identityStep(true, false, 1));
		assertEquals(LivenessGate.IdentityStep.PROCEED, LivenessGate.identityStep(true, false, 2));
		assertEquals("a different face: one new session", LivenessGate.IdentityStep.RETRY,
				LivenessGate.identityStep(false, true, 1));
		assertEquals("then blocked", LivenessGate.IdentityStep.NOT_VERIFIED, LivenessGate.identityStep(false, true, 2));
		assertEquals("no readable onboarded face while one is required: a retry cannot help",
				LivenessGate.IdentityStep.NOT_VERIFIED, LivenessGate.identityStep(false, false, 1));
		assertFalse(LivenessGate.Outcome.IDENTITY_NOT_VERIFIED.proceed());
		assertEquals("liveness.error.identity_not_verified", LivenessGate.IDENTITY_NOT_VERIFIED_KEY);
		assertTrue(LivenessMessages.ENGLISH.containsKey(LivenessGate.IDENTITY_NOT_VERIFIED_KEY));
	}
}
