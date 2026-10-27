package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.junit.Test;

import io.mosip.registration.liveness.desktop.DesktopLivenessService.ModelBundleOutcome;

/** F17: every outcome of the offline model install and rollback has a generic, translated text. */
public class ModelBundleTextsTest {

	private Properties bundle(String name) throws IOException {
		Properties p = new Properties();
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
			assertNotNull(name, in);
			// en is UTF-8; the upstream ar and fr copies are ASCII with \\u escapes for the F17 lines.
			p.load(new InputStreamReader(in, name.contains("_en") ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1));
		}
		return p;
	}

	@Test
	public void everyOutcomeHasItsOwnKeyAndAFailedRollbackSaysSo() {
		Set<String> used = new HashSet<>();
		for (ModelBundleOutcome o : ModelBundleOutcome.values()) {
			String install = ModelBundleTexts.messageKey(o, false), rollback = ModelBundleTexts.messageKey(o, true);
			assertTrue(install, ModelBundleTexts.KEYS.contains(install));
			assertTrue(rollback, ModelBundleTexts.KEYS.contains(rollback));
			used.add(install);
			used.add(rollback);
		}
		assertEquals("liveness.model.invalid", ModelBundleTexts.messageKey(ModelBundleOutcome.INVALID, false));
		assertEquals("liveness.model.rollback_failed", ModelBundleTexts.messageKey(ModelBundleOutcome.INVALID, true));
		assertEquals("liveness.model.rollback_failed", ModelBundleTexts.messageKey(ModelBundleOutcome.FAILED, true));
		assertEquals("liveness.model.failed", ModelBundleTexts.messageKey(ModelBundleOutcome.FAILED, false));
		assertEquals(8, used.size()); // the 7 outcomes, plus a rollback's own failure text
	}

	@Test
	public void theVersionIsFilledInLiterallySoApostrophesSurvive() {
		assertEquals("Lot de modèles pad-2026.10.6 installé. Les nouvelles vérifications l'utilisent.",
				ModelBundleTexts.fill("Lot de modèles {0} installé. Les nouvelles vérifications l'utilisent.",
						"pad-2026.10.6"));
		assertEquals("Models rolled back to .", ModelBundleTexts.fill("Models rolled back to {0}.", null));
	}

	@Test
	public void everyKeyIsInEnglishArabicAndFrenchAndTranslated() throws IOException {
		Properties en = bundle("messages_en.properties");
		for (String lang : new String[] { "ar", "fr" }) {
			Properties p = bundle("messages_" + lang + ".properties");
			for (String key : ModelBundleTexts.KEYS) {
				String english = en.getProperty(key), text = p.getProperty(key);
				assertNotNull(key, english);
				assertNotNull(lang + " " + key, text);
				assertNotEquals(lang + " " + key + " is translated", english, text);
				assertEquals(lang + " " + key + " keeps the placeholder", english.contains("{0}"), text.contains("{0}"));
			}
		}
		for (String lang : new String[] { "en", "ar", "fr" }) {
			Properties labels = bundle("labels_" + lang + ".properties");
			assertNotNull(lang, labels.getProperty("installModelBundle"));
			assertNotNull(lang, labels.getProperty("rollbackModelBundle"));
		}
		assertEquals(en.getProperty("liveness.model.install"), bundle("labels_en.properties").getProperty("installModelBundle"));
	}
}
