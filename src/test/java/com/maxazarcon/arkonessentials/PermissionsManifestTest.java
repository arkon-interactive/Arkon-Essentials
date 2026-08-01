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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Holds {@code assets/arkonessentials/permissions.json} to the code it describes.
 *
 * <p>The manifest exists so a launcher or permission manager can read the mod's permission surface
 * straight out of the jar — no server running, no protocol, no scraping compiled classes. That only
 * has value if it is <em>true</em>, and a hand-maintained list of forty-odd nodes drifts the moment
 * someone adds a feature and forgets it.
 *
 * <p>So this compares both directions. A node in the code but not the manifest fails; a node in the
 * manifest but not the code fails too — the second catches a node that was renamed or removed and left
 * behind, which would send an operator hunting for a permission that no longer does anything.
 *
 * <p>It also checks the declared default matches {@link AdminPermissions.Gate#defaultKind}, since a
 * manifest that says "public" about an operator-gated node is worse than no manifest: it reads as
 * authoritative.
 */
class PermissionsManifestTest {
	private static final String PATH = "/assets/arkonessentials/permissions.json";

	private static JsonObject manifest() {
		try (InputStream stream = PermissionsManifestTest.class.getResourceAsStream(PATH)) {
			assertNotNull(stream, PATH + " is missing from the jar — the launcher reads it from there");
			return new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
		} catch (Exception e) {
			throw new AssertionError("could not read " + PATH, e);
		}
	}

	/** node path -> declared default, as the shipped file states it. */
	private static Map<String, String> declared() {
		Map<String, String> declared = new TreeMap<>();

		manifest().getAsJsonArray("permissions").forEach(element -> {
			JsonObject entry = element.getAsJsonObject();
			String id = entry.get("id").getAsString();

			assertTrue(id.startsWith("arkonessentials:"), "unexpected namespace on " + id);

			// The two spellings must agree, or a consumer picking either one gets a different answer.
			assertEquals(
				"arkonessentials." + id.substring("arkonessentials:".length()),
				entry.get("node").getAsString(),
				"node and id disagree for " + id
			);

			declared.put(id.substring("arkonessentials:".length()), entry.get("default").getAsString());
		});

		return declared;
	}

	/** node path -> default, as the code actually behaves. */
	private static Map<String, String> actual() {
		Map<String, String> actual = new TreeMap<>();

		for (AdminPermissions.Gate gate : AdminPermissions.GATES) {
			actual.put(gate.node().getPath(), gate.defaultKind().name().toLowerCase(Locale.ROOT));
		}

		// Valued nodes are not Gates — they carry a number or a flag rather than a yes/no — but the
		// manifest still has to list them, so they are checked here too.
		for (AdminPermissions.Valued valued : AdminPermissions.VALUED) {
			actual.put(valued.node().getPath(), "config");
		}

		return actual;
	}

	@Test
	void manifestListsExactlyTheNodesTheCodeChecks() {
		Set<String> declared = new TreeSet<>(declared().keySet());
		Set<String> actual = new TreeSet<>(actual().keySet());

		Set<String> missing = new TreeSet<>(actual);
		missing.removeAll(declared);

		Set<String> stale = new TreeSet<>(declared);
		stale.removeAll(actual);

		assertTrue(missing.isEmpty(), "in the code but not the manifest: " + missing);
		assertTrue(stale.isEmpty(), "in the manifest but not the code: " + stale);
	}

	@Test
	void manifestDefaultsMatchTheRealFallbacks() {
		Map<String, String> declared = declared();
		Map<String, String> mismatched = new LinkedHashMap<>();

		actual().forEach((node, expected) -> {
			String stated = declared.get(node);

			if (stated != null && !stated.equals(expected)) {
				mismatched.put(node, stated + " declared, " + expected + " in code");
			}
		});

		assertTrue(mismatched.isEmpty(), "manifest misstates these defaults: " + mismatched);
	}

	/**
	 * Schema 2's {@code kind} has to agree with the code, because a consumer renders each kind
	 * differently — a value is a number field, an immunity defaults the opposite way to everything else,
	 * and a mode is one of a mutually exclusive set.
	 *
	 * <p>The two checkable against code are {@code value} and {@code grant}: both are generated from
	 * {@link AdminPermissions#VALUED} and {@link AdminState} rather than listed by hand, so a mismatch
	 * means the manifest has fallen behind a real change.
	 */
	@Test
	void kindsAgreeWithTheCode() {
		Map<String, String> kinds = new TreeMap<>();

		manifest().getAsJsonArray("permissions").forEach(element -> {
			JsonObject entry = element.getAsJsonObject();
			String path = entry.get("id").getAsString().split(":", 2)[1];

			assertTrue(entry.has("kind"), "no kind declared for " + path);
			kinds.put(path, entry.get("kind").getAsString());
		});

		Set<String> declaredValues = new TreeSet<>();
		Set<String> declaredGrants = new TreeSet<>();
		Set<String> declaredModes = new TreeSet<>();

		kinds.forEach((path, kind) -> {
			assertTrue(
				Set.of("mode", "ability", "value", "immunity", "grant").contains(kind),
				"unknown kind '" + kind + "' on " + path
			);

			switch (kind) {
				case "value" -> declaredValues.add(path);
				case "grant" -> declaredGrants.add(path);
				case "mode" -> declaredModes.add(path);
				default -> { }
			}
		});

		Set<String> actualValues = new TreeSet<>();
		AdminPermissions.VALUED.forEach(valued -> actualValues.add(valued.node().getPath()));
		assertEquals(actualValues, declaredValues, "kind:value must be exactly the config-backed nodes");

		Set<String> actualGrants = new TreeSet<>();
		for (AdminState state : AdminState.values()) {
			if (state != AdminState.NONE) {
				actualGrants.add(AdminPermissions.grantNode(state).getPath());
			}
		}
		assertEquals(actualGrants, declaredGrants, "kind:grant must be exactly the generated grant nodes");

		// One mode node per state, so a new state cannot be added without declaring its toggle.
		assertEquals(
			AdminState.values().length - 1, declaredModes.size(),
			"expected one kind:mode node per state, got " + declaredModes
		);
	}

	/**
	 * Every mode is in the same exclusive group, and nothing else claims to be.
	 *
	 * <p>This is what lets a launcher render Modes as a radio set. It is not a convention — the states
	 * are mutually exclusive by construction, since {@code setState} permits exactly one at a time.
	 */
	@Test
	void modesDeclareOneExclusiveGroup() {
		manifest().getAsJsonArray("permissions").forEach(element -> {
			JsonObject entry = element.getAsJsonObject();
			String path = entry.get("id").getAsString().split(":", 2)[1];
			boolean isMode = "mode".equals(entry.get("kind").getAsString());

			assertEquals(
				isMode, entry.has("exclusiveGroup"),
				"exclusiveGroup belongs on modes and only on modes; wrong for " + path
			);

			if (isMode) {
				assertEquals("mode", entry.get("exclusiveGroup").getAsString(), "wrong group on " + path);
				assertTrue(entry.has("grantCommand"), "a mode should say how to grant it: " + path);
			}
		});
	}

	/** A {@code parent} that names a node which does not exist would nest a toggle under nothing. */
	@Test
	void parentsPointAtRealNodes() {
		Set<String> nodes = new TreeSet<>();
		manifest().getAsJsonArray("permissions")
			.forEach(element -> nodes.add(element.getAsJsonObject().get("node").getAsString()));

		manifest().getAsJsonArray("permissions").forEach(element -> {
			JsonObject entry = element.getAsJsonObject();

			if (entry.has("parent")) {
				assertTrue(
					nodes.contains(entry.get("parent").getAsString()),
					entry.get("id").getAsString() + " nests under a node that is not declared: " + entry.get("parent").getAsString()
				);
			}
		});
	}

	/** Every node needs a label and a category, or the launcher has nothing to draw. */
	@Test
	void everyEntryIsPresentable() {
		manifest().getAsJsonArray("permissions").forEach(element -> {
			JsonObject entry = element.getAsJsonObject();
			String id = entry.get("id").getAsString();

			assertTrue(entry.has("label") && !entry.get("label").getAsString().isBlank(), "no label for " + id);
			assertTrue(entry.has("category") && !entry.get("category").getAsString().isBlank(), "no category for " + id);
			assertTrue(
				Set.of("boolean", "integer").contains(entry.get("type").getAsString()),
				"unknown type for " + id
			);
		});
	}
}
