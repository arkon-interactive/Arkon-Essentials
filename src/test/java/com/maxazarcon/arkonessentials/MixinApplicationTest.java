package com.maxazarcon.arkonessentials;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Loads every class the mod mixes into, so a broken injection fails the build.
 *
 * <p><strong>Compiling proves nothing about a mixin.</strong> Injection points are matched against
 * bytecode when the target class is first loaded, so a wrong descriptor, a renamed method or — the one
 * that actually happened — a field reference naming the declaring class instead of the one javac emitted
 * compiles perfectly and then throws {@code InjectionError} at runtime.
 *
 * <p>The previous check for that was booting a dev server and grepping {@code debug.log}. That works,
 * but it is slow and it has a hole: {@code ChunkMap$TrackedEntity} only loads once an entity enters
 * tracking and {@code PlayerAdvancements} only once somebody joins, so on an empty server neither is
 * ever transformed and both silently pass. Loading the classes directly has no such gap — and the
 * targets are read out of {@code arkonessentials.mixins.json} rather than listed here, so a mixin cannot
 * be added without also being covered.
 *
 * <p>This does not replace running the server. It proves the injections <em>apply</em>; it says nothing
 * about whether they do the right thing.
 */
class MixinApplicationTest {
	private static final Path CONFIG = Path.of("src/main/resources/arkonessentials.mixins.json");

	/** Mixin class simple name to the class it targets, pulled out of the annotation. */
	private record Target(String mixin, String targetClass) {
		@Override
		public String toString() {
			return this.mixin + " → " + this.targetClass;
		}
	}

	@Test
	void everyMixinInTheConfigIsCovered() throws Exception {
		assertTrue(Files.exists(CONFIG), "mixin config missing at " + CONFIG);
		assertEquals(declaredMixins().size(), targets().count(), "every declared mixin needs a resolved target");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("targets")
	void mixinApplies(final Target target) {
		// Class.forName is the whole test: loading runs the transformer, and a failed injection throws
		// from inside it. initialize=false is deliberate — we want the class transformed, not its static
		// blocks run, which for several of these would need a bootstrapped game.
		assertDoesNotThrow(
			() -> Class.forName(target.targetClass(), false, MixinApplicationTest.class.getClassLoader()),
			target.mixin() + " did not apply cleanly to " + target.targetClass()
		);
	}

	private static List<String> declaredMixins() throws Exception {
		JsonObject root;

		try (BufferedReader reader = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
			root = JsonParser.parseReader(reader).getAsJsonObject();
		}

		List<String> names = new ArrayList<>();
		JsonArray mixins = root.getAsJsonArray("mixins");

		for (int i = 0; i < mixins.size(); i++) {
			names.add(mixins.get(i).getAsString());
		}

		return names;
	}

	/**
	 * Reads each mixin's target out of its own source file.
	 *
	 * <p>Source rather than reflection on the annotation, because reading the annotation means loading
	 * the mixin class, and mixin classes are not meant to be loaded outside the transformer. The two
	 * shapes are {@code @Mixin(Foo.class)} — resolved against the imports — and
	 * {@code @Mixin(targets = "a.b.C$D")}, which inner classes need since they have no accessible
	 * class literal.
	 */
	private static Stream<Target> targets() throws Exception {
		List<Target> found = new ArrayList<>();

		for (String simpleName : declaredMixins()) {
			Path source = Path.of("src/main/java/com/maxazarcon/arkonessentials/mixin", simpleName + ".java");
			String text = Files.readString(source, StandardCharsets.UTF_8);

			int at = text.indexOf("@Mixin(");
			assertTrue(at >= 0, "no @Mixin annotation in " + source);

			String annotation = text.substring(at, text.indexOf(')', at));
			String literal = annotation.contains("targets")
				? annotation.substring(annotation.indexOf('"') + 1, annotation.lastIndexOf('"'))
				: resolveImport(text, annotation.substring(annotation.indexOf('(') + 1).replace(".class", "").trim());

			found.add(new Target(simpleName, literal));
		}

		return found.stream();
	}

	/** Turns a simple class name in the annotation back into the fully qualified one, via the imports. */
	private static String resolveImport(final String source, final String simpleName) {
		String suffix = "." + simpleName + ";";

		for (String line : source.lines().toList()) {
			if (line.startsWith("import ") && line.endsWith(suffix)) {
				return line.substring("import ".length(), line.length() - 1);
			}
		}

		throw new AssertionError("no import for " + simpleName + " — cannot resolve its target class");
	}
}
