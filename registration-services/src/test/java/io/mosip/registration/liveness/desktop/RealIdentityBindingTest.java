package io.mosip.registration.liveness.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.Test;

import io.mosip.biometrics.util.ConvertRequestDto;
import io.mosip.biometrics.util.face.FaceEncoder;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;

/**
 * Identity binding (F15) with real data: onboarded faces as ISO/IEC 19794-5 records made by MOSIP's own encoder,
 * decoded by {@link CapturedFaceDecoder} and compared by the signed SFace model through the client's service. Uses
 * Part 2's public-domain fixtures (NASA portraits, see liveness-analyzer/src/test/resources/golden/README.md).
 */
public class RealIdentityBindingTest {

	private static final Path GOLDEN = Paths.get("..", "liveness-analyzer", "src", "test", "resources", "golden");

	private final RecordingAuditManager audit = new RecordingAuditManager();
	private final List<IdentityBinding.Template> onboarded = new ArrayList<>();
	private final DesktopLivenessService service = new DesktopLivenessService(
			new DesktopLivenessConfig(HashMap::new, DesktopLivenessConfig.loadDefaults()), audit, null, null)
			.withIdentityTemplates(userId -> onboarded);

	@Test(timeout = 120_000)
	public void theOnboardedPersonMatchesAndOthersDoNot() throws Exception {
		Path portrait = GOLDEN.resolve("portrait_frontal.png");
		Path crew = GOLDEN.resolve("crew_multi.png");
		assumeTrue("Part 2 fixtures not next to this module", Files.isRegularFile(portrait) && Files.isRegularFile(crew));
		BufferedImage crewImage = ImageIO.read(crew.toFile());
		byte[] someoneElse = iso(png(crewImage.getSubimage(160, 90, 150, 200)));
		Frame live = CapturedFaceDecoder.toFrame(ImageIO.read(portrait.toFile()));

		onboarded.add(new IdentityBinding.Template(iso(Files.readAllBytes(portrait)), null));
		assertEquals(IdentityBinding.Verdict.Kind.MATCH, verify(live).kind);
		audit.await(AuditEvent.LIVENESS_IDENTITY_BINDING, "threshold=0.40");

		onboarded.clear();
		onboarded.add(new IdentityBinding.Template(someoneElse, null));
		IdentityBinding.Verdict other = verify(live);
		assertEquals(other.toString(), "NO_MATCH", other.reason);
		assertTrue(other.retryable());

		// Several templates: the best one decides; a group photo and garbage are unreadable.
		onboarded.add(new IdentityBinding.Template(iso(Files.readAllBytes(crew)), null));
		onboarded.add(new IdentityBinding.Template(new byte[] { 1, 2, 3 }, null));
		assertEquals("NO_MATCH", verify(live).reason);
		audit.await(AuditEvent.LIVENESS_IDENTITY_BINDING, "templates=3; unreadable=2");
		onboarded.add(new IdentityBinding.Template(iso(Files.readAllBytes(portrait)), null));
		assertEquals(IdentityBinding.Verdict.Kind.MATCH, verify(live).kind);

		onboarded.clear();
		onboarded.addAll(Arrays.asList(new IdentityBinding.Template(iso(Files.readAllBytes(crew)), null),
				new IdentityBinding.Template(new byte[] { 9 }, null)));
		IdentityBinding.Verdict unreadable = verify(live);
		assertEquals("NOT_BOUND_UNREADABLE", unreadable.reason);
		assertTrue("presence only by default", unreadable.accepted());

		onboarded.clear();
		IdentityBinding.Verdict none = verify(live);
		assertEquals("NOT_BOUND_NO_TEMPLATE", none.reason);
		assertTrue(none.accepted());
		assertFalse(audit.await(AuditEvent.LIVENESS_IDENTITY_BINDING, "outcome=NOT_BOUND_NO_TEMPLATE")
				.contains("templateAgeDays"));
	}

	private IdentityBinding.Verdict verify(Frame live) {
		service.binding().arm(WorkflowType.OPERATOR, new LivenessResult(LivenessResult.Decision.PASSED,
				LivenessResult.PassPath.PASSIVE_ONLY, null, live, 0.95f, Collections.<ChallengeType>emptyList(), 1, 1200,
				"real-1", "pad-2026.10.5"));
		return service.identity().verify(WorkflowType.OPERATOR, "op1", IdentityBinding.GATE_PASSWORD_LOGIN, 1);
	}

	private static byte[] png(BufferedImage img) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	private static byte[] iso(byte[] image) throws Exception {
		ConvertRequestDto request = new ConvertRequestDto();
		request.setVersion("ISO19794_5_2011");
		request.setModality("Face");
		request.setPurpose("REGISTRATION");
		request.setImageType(0);
		request.setInputBytes(image);
		return FaceEncoder.convertFaceImageToISO(request);
	}
}
