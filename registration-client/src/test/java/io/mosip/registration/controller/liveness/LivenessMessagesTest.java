package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The Desktop catalogue must stay identical to the one shared with the Android client: the built-in English
 * texts, {@code messages_en.properties} and the block in {@code docs/ui-liveness-spec.md} §5.
 */
public class LivenessMessagesTest {

	@Test
	public void messagesEnHasEveryKeyWithTheSameText() throws IOException {
		Properties p = new Properties();
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("messages_en.properties")) {
			p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
		}
		for (Map.Entry<String, String> e : LivenessMessages.ENGLISH.entrySet()) {
			assertEquals(e.getKey(), e.getValue(), p.getProperty(e.getKey()));
		}
	}

	@Test
	public void theSharedSpecBlockIsTheSameCatalogue() throws IOException {
		Path spec = Paths.get("..", "docs", "ui-liveness-spec.md");
		assumeTrue("docs/ui-liveness-spec.md not next to this module", Files.isRegularFile(spec));
		String md = new String(Files.readAllBytes(spec), StandardCharsets.UTF_8);
		Matcher m = Pattern.compile("```properties\\R(.*?)```", Pattern.DOTALL).matcher(md);
		assertTrue("no properties block in the spec", m.find());
		Properties fromSpec = new Properties();
		fromSpec.load(new StringReader(m.group(1)));
		assertEquals("same keys as the spec", fromSpec.stringPropertyNames(), LivenessMessages.ENGLISH.keySet());
		for (String key : fromSpec.stringPropertyNames()) {
			assertEquals(key, fromSpec.getProperty(key), LivenessMessages.ENGLISH.get(key));
		}
	}

	@Test
	public void formatsArgumentsAndFallsBackToTheKey() {
		LivenessMessages msg = LivenessMessages.english();
		assertEquals("Step 1 of 2", msg.text("liveness.progress.step", 1, 2));
		assertEquals("Attempt 3 of 4", msg.text("liveness.progress.attempt", 3, 4));
		assertEquals("2 attempt(s) left", msg.text("liveness.retry.attempts_left", 2));
		assertEquals("Checking face liveness…", msg.text("liveness.status.checking"));
		assertEquals("no.such.key", msg.text("no.such.key"));
	}

	@Test
	public void noTextExposesTechnicalDetails() {
		for (String text : LivenessMessages.ENGLISH.values()) {
			String t = text.toLowerCase();
			assertFalse(text, t.contains("score") || t.contains("pad") || t.contains("model")
					|| t.contains("threshold") || t.contains("attack") || t.contains("spoof"));
		}
	}
}
