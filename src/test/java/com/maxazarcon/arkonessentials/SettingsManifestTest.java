package com.maxazarcon.arkonessentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Holds {@code assets/arkonessentials/settings.json} to {@link EssentialsConfig#OPTIONS}.
 *
 * <p>The manifest exists so a launcher can label the settings tab properly instead of rendering raw
 * keys like {@code afkTimeoutSeconds}. Its value depends entirely on being current, and a config key is
 * exactly the sort of thing that gets added without anyone remembering a second file — so the
 * comparison runs at build time and both directions fail.
 *
 * <p>Descriptions are compared <strong>exactly</strong>. They are the same strings {@code /arkon config}
 * prints, so there is one wording per setting rather than two that drift apart; if that feels strict,
 * the alternative is a manifest that quietly describes an old behaviour.
 */
class SettingsManifestTest {
	private static final String PATH = "/assets/arkonessentials/settings.json";

	private static JsonObject manifest() {
		try (InputStream stream = SettingsManifestTest.class.getResourceAsStream(PATH)) {
			assertNotNull(stream, PATH + " is missing from the jar — the launcher reads it from there");
			return new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
		} catch (Exception e) {
			throw new AssertionError("could not read " + PATH, e);
		}
	}

	/** key -> declared description, as the shipped file states it. */
	private static Map<String, String> declared() {
		Map<String, String> declared = new TreeMap<>();

		manifest().getAsJsonArray("settings").forEach(element -> {
			JsonObject entry = element.getAsJsonObject();
			declared.put(entry.get("key").getAsString(), entry.get("description").getAsString());
		});

		return declared;
	}

	/** key -> description, as the code actually carries it. */
	private static Map<String, String> actual() {
		Map<String, String> actual = new TreeMap<>();

		for (EssentialsConfig.Option<?> option : EssentialsConfig.OPTIONS) {
			actual.put(option.key(), option.description());
		}

		return actual;
	}

	@Test
	void manifestListsExactlyTheSettingsTheCommandEdits() {
		Set<String> declared = new TreeSet<>(declared().keySet());
		Set<String> actual = new TreeSet<>(actual().keySet());

		Set<String> missing = new TreeSet<>(actual);
		missing.removeAll(declared);

		Set<String> stale = new TreeSet<>(declared);
		stale.removeAll(actual);

		assertTrue(missing.isEmpty(), "editable in game but not in the manifest: " + missing);
		assertTrue(stale.isEmpty(), "in the manifest but no longer a setting: " + stale);
	}

	@Test
	void manifestDescriptionsMatchTheCommand() {
		Map<String, String> declared = declared();
		Map<String, String> mismatched = new LinkedHashMap<>();

		actual().forEach((key, expected) -> {
			String stated = declared.get(key);

			if (stated != null && !stated.equals(expected)) {
				mismatched.put(key, "manifest says \"" + stated + "\", code says \"" + expected + "\"");
			}
		});

		assertTrue(mismatched.isEmpty(), "descriptions have drifted: " + mismatched);
	}

	/**
	 * The declared type has to match what the command will actually accept, or the launcher renders a
	 * checkbox for something that needs a number and the edit is refused at parse time.
	 */
	@Test
	void manifestTypesMatchTheArgumentTypes() {
		Map<String, String> declaredTypes = new TreeMap<>();

		manifest().getAsJsonArray("settings").forEach(element -> {
			JsonObject entry = element.getAsJsonObject();
			declaredTypes.put(entry.get("key").getAsString(), entry.get("type").getAsString());
		});

		for (EssentialsConfig.Option<?> option : EssentialsConfig.OPTIONS) {
			String expected = switch (option.valueType().getSimpleName()) {
				case "Integer" -> "integer";
				case "Double" -> "number";
				case "Boolean" -> "boolean";
				default -> "string";
			};

			assertEquals(expected, declaredTypes.get(option.key()), "wrong type declared for " + option.key());
		}
	}

	/** Every setting needs a label and a category, or the tab has nothing to draw. */
	@Test
	void everySettingIsPresentable() {
		manifest().getAsJsonArray("settings").forEach(element -> {
			JsonObject entry = element.getAsJsonObject();
			String key = entry.get("key").getAsString();

			assertTrue(entry.has("label") && !entry.get("label").getAsString().isBlank(), "no label for " + key);
			assertTrue(entry.has("category") && !entry.get("category").getAsString().isBlank(), "no category for " + key);
			assertTrue(entry.has("default"), "no default for " + key);
		});
	}
}
