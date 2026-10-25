package io.mosip.registration.liveness.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.Test;

import io.mosip.biometrics.util.ConvertRequestDto;
import io.mosip.biometrics.util.face.FaceEncoder;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;

/**
 * Face binding with real data: ISO/IEC 19794-5 records made by MOSIP's own encoder (as an SBI device sends them),
 * decoded by {@link CapturedFaceDecoder} and compared by the signed SFace model. Uses Part 2's public-domain fixtures
 * (NASA portraits, see liveness-analyzer/src/test/resources/golden/README.md).
 */
public class RealFaceBindingTest {

	private static final Path GOLDEN = Paths.get("..", "liveness-analyzer", "src", "test", "resources", "golden");

	private final RecordingAuditManager audit = new RecordingAuditManager();
	private final DesktopLivenessService service = new DesktopLivenessService(
			new DesktopLivenessConfig(HashMap::new, DesktopLivenessConfig.loadDefaults()), audit, null, null);

	@Test(timeout = 120_000)
	public void theSamePersonMatchesAndAGroupPhotoIsRejected() throws Exception {
		Path portrait = GOLDEN.resolve("portrait_frontal.png");
		Path crew = GOLDEN.resolve("crew_multi.png");
		assumeTrue("Part 2 fixtures not next to this module", Files.isRegularFile(portrait) && Files.isRegularFile(crew));

		byte[] portraitIso = iso(portrait);
		Frame decoded = new CapturedFaceDecoder().decode(portraitIso);
		assertNotNull("the ISO 19794-5 record decodes", decoded);
		assertEquals(554, decoded.width());

		Frame live = CapturedFaceDecoder.toFrame(ImageIO.read(portrait.toFile()));
		FaceBinding binding = service.binding();

		binding.arm(WorkflowType.RESIDENT, passed(live));
		FaceBinding.Verdict same = binding.verifyCapture(WorkflowType.RESIDENT, capture(portraitIso));
		assertEquals(same.toString(), FaceBinding.Verdict.Kind.MATCH, same.kind);

		binding.arm(WorkflowType.RESIDENT, passed(live));
		FaceBinding.Verdict group = binding.verifyCapture(WorkflowType.RESIDENT, capture(iso(crew)));
		assertFalse(group.accepted());
		assertEquals("MULTIPLE_FACES_B", group.reason);

		binding.arm(WorkflowType.RESIDENT, passed(live));
		FaceBinding.Verdict garbage = binding.verifyCapture(WorkflowType.RESIDENT, capture(new byte[] { 1, 2, 3 }));
		assertEquals("UNDECODABLE", garbage.reason);
	}

	private static byte[] iso(Path png) throws Exception {
		ConvertRequestDto request = new ConvertRequestDto();
		request.setVersion("ISO19794_5_2011");
		request.setModality("Face");
		request.setPurpose("REGISTRATION");
		request.setImageType(0);
		request.setInputBytes(Files.readAllBytes(png));
		return FaceEncoder.convertFaceImageToISO(request);
	}

	private static List<BiometricsDto> capture(byte[] iso) {
		BiometricsDto dto = new BiometricsDto();
		dto.setAttributeISO(iso);
		return Collections.singletonList(dto);
	}

	private static LivenessResult passed(Frame best) {
		return new LivenessResult(LivenessResult.Decision.PASSED, LivenessResult.PassPath.PASSIVE_ONLY, null, best,
				0.95f, Collections.<ChallengeType>emptyList(), 1, 1200, "real-1", "pad-2026.10.5");
	}
}
