package com.maxazarcon.arkonessentials.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.maxazarcon.arkonessentials.AdminState;
import com.mojang.serialization.Codec;
import com.maxazarcon.arkonessentials.ArkonEssentials;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.Nullable;

/**
 * How the indicator looks, read from {@code config/arkonessentials-client.json}.
 *
 * <p>Deliberately separate from {@link com.maxazarcon.arkonessentials.EssentialsConfig}: that one is
 * server-wide and operator-owned, whereas everything here is one player's own preference about their
 * own screen. Nothing in this file can change what a player is allowed to do, so it needs no
 * permission and never travels to the server.
 *
 * <p>Same plain-class-with-defaults shape as the server config, for the same reason — Gson leaves a
 * field alone when its key is absent, so an older file picks up defaults for new settings rather than
 * nulling them out.
 */
public final class HudConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "arkonessentials-client.json";

	public static final int MIN_SCALE = 50;
	public static final int MAX_SCALE = 300;
	public static final int MAX_OFFSET = 200;

	/** Defaults for the lines that have no {@link AdminState} of their own to carry them. */
	public static final String FLIGHT_LABEL = "Flight Enabled";

	public static final int FLIGHT_COLOR = 0xFF55FF55;

	public static final String AFK_LABEL = "AFK";

	/** Deliberately a blue: every existing indicator is red, green, gold or a neutral grey. */
	public static final int AFK_COLOR = 0xFF55AAFF;

	public static final String OFFLINE_LABEL = "Offline Mode";

	public static final int OFFLINE_COLOR = 0xFFAA55FF;

	/** Stands in for {@link AdminState#NONE}, which never draws. Avoids a nullable lookup. */
	private static final Indicator NEVER_SHOWN = new Indicator(false);

	private static HudConfig instance = new HudConfig();

	/** Master switch. Off draws nothing at all, whatever the individual indicators say. */
	public boolean enabled = true;

	public Anchor anchor = Anchor.TOP_LEFT;

	/** Inset from the anchored corner, in unscaled GUI pixels. */
	public int offsetX = 20;

	public int offsetY = 20;

	/** Label size as a percentage of normal. */
	public int scalePercent = 100;

	public boolean textShadow = true;

	public Indicator admin = new Indicator(true);
	public Indicator passive = new Indicator(true);
	public Indicator build = new Indicator(true);
	public Indicator god = new Indicator(true);
	public Indicator demigod = new Indicator(true);
	public Indicator ghost = new Indicator(true);
	public Indicator flight = new Indicator(true);
	public Indicator afk = new Indicator(true);
	public Indicator offline = new Indicator(true);

	public static HudConfig get() {
		return instance;
	}

	/**
	 * One indicator's settings.
	 *
	 * <p>{@code color} starts null and means "whatever the state itself says", so an untouched file
	 * follows any future change to a built-in colour instead of pinning today's value. {@link #load()}
	 * writes the resolved colour back out, so the file still shows every colour once it exists.
	 */
	public static final class Indicator {
		public boolean shown = true;

		public @Nullable String color;

		/** Gson needs this; without a declared no-arg constructor it allocates around the initialisers. */
		Indicator() {
		}

		Indicator(final boolean shown) {
			this.shown = shown;
		}

		/** The packed ARGB colour to draw with, falling back to the state's built-in one. */
		public int color(final int fallback) {
			return this.color == null ? fallback : parseColor(this.color).orElse(fallback);
		}

		public String hex(final int fallback) {
			return this.color == null ? toHex(fallback) : this.color;
		}

		/**
		 * Pins the colour to a concrete value, discarding anything unparseable.
		 *
		 * <p>Unlike a whole unreadable config file — which is left strictly alone — one bad colour is
		 * repaired in place. It cannot cost anything but a colour, and leaving it would mean the file
		 * says one thing while the screen shows another.
		 */
		void normalise(final int fallback) {
			if (this.color != null && parseColor(this.color).isEmpty()) {
				ArkonEssentials.LOGGER.warn("Ignoring unreadable indicator colour '{}'; using {}.", this.color, toHex(fallback));
				this.color = null;
			}

			this.color = this.hex(fallback);
		}
	}

	/**
	 * Which corner the block of labels is measured from.
	 *
	 * <p>An anchor rather than raw coordinates so a position survives a change of resolution or window
	 * size: pinned to a corner, the labels stay the same distance from it either way.
	 */
	public enum Anchor implements StringRepresentable {
		TOP_LEFT("top_left", false, false),
		TOP_RIGHT("top_right", true, false),
		BOTTOM_LEFT("bottom_left", false, true),
		BOTTOM_RIGHT("bottom_right", true, true);

		public static final Codec<Anchor> CODEC = StringRepresentable.fromEnum(Anchor::values);

		private final String serializedName;
		private final boolean right;
		private final boolean bottom;

		Anchor(final String serializedName, final boolean right, final boolean bottom) {
			this.serializedName = serializedName;
			this.right = right;
			this.bottom = bottom;
		}

		@Override
		public String getSerializedName() {
			return this.serializedName;
		}

		public boolean right() {
			return this.right;
		}

		public boolean bottom() {
			return this.bottom;
		}

		public String translationKey() {
			return "arkonessentials.options.anchor." + this.serializedName;
		}
	}

	/** An indicator paired with what it is for, so the config screen and the HUD agree on the set. */
	public record Slot(String key, Indicator indicator, int defaultColor) {
		public String translationKey() {
			return "arkonessentials.options.indicator." + this.key;
		}
	}

	/**
	 * Every indicator, in the order the screen lists them.
	 *
	 * <p>The one place the set is defined — adding a state means adding a line here, and both the
	 * screen and {@link #normalise()} pick it up.
	 */
	public List<Slot> slots() {
		return List.of(
			new Slot("admin", this.admin, AdminState.ADMIN.color()),
			new Slot("passive", this.passive, AdminState.PASSIVE.color()),
			new Slot("build", this.build, AdminState.BUILD.color()),
			new Slot("god", this.god, AdminState.GOD.color()),
			new Slot("demigod", this.demigod, AdminState.DEMIGOD.color()),
			new Slot("ghost", this.ghost, AdminState.GHOST.color()),
			new Slot("flight", this.flight, FLIGHT_COLOR),
			new Slot("afk", this.afk, AFK_COLOR),
			new Slot("offline", this.offline, OFFLINE_COLOR)
		);
	}

	public Indicator forState(final AdminState state) {
		return switch (state) {
			case NONE -> NEVER_SHOWN;
			case ADMIN -> this.admin;
			case PASSIVE -> this.passive;
			case BUILD -> this.build;
			case GOD -> this.god;
			case DEMIGOD -> this.demigod;
			case GHOST -> this.ghost;
		};
	}

	/**
	 * Parses {@code #RRGGBB} (or the same without the hash) into a packed opaque ARGB colour.
	 *
	 * @return empty if it is not six hex digits, which is what the config screen uses to tell the
	 *     player their entry is not live yet
	 */
	public static OptionalInt parseColor(final String text) {
		String digits = text.startsWith("#") ? text.substring(1) : text;

		// Length and digit checks both matter: parseInt would happily accept "-FF555" as negative.
		if (digits.length() != 6 || !digits.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
			return OptionalInt.empty();
		}

		return OptionalInt.of(0xFF000000 | Integer.parseInt(digits, 16));
	}

	public static String toHex(final int argb) {
		return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		HudConfig loaded = new HudConfig();

		if (Files.exists(path)) {
			try (BufferedReader reader = Files.newBufferedReader(path)) {
				HudConfig parsed = GSON.fromJson(reader, HudConfig.class);

				if (parsed != null) {
					loaded = parsed;
				}
			} catch (Exception e) {
				// Same reasoning as the server config: a file that will not parse is left untouched rather
				// than overwritten, since one stray comma should not cost someone their settings.
				ArkonEssentials.LOGGER.error("Could not read {} — using defaults and leaving your file untouched.", path, e);
				instance = new HudConfig();
				return;
			}
		}

		loaded.normalise();
		instance = loaded;
		write(path, loaded);
	}

	public static void save() {
		instance.normalise();
		write(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME), instance);
	}

	private static void write(final Path path, final HudConfig config) {
		try {
			Files.createDirectories(path.getParent());

			try (BufferedWriter writer = Files.newBufferedWriter(path)) {
				GSON.toJson(config, writer);
			}
		} catch (Exception e) {
			ArkonEssentials.LOGGER.error("Could not write {}; your settings still apply for this session.", path, e);
		}
	}

	/** Repairs a hand-edited file: missing objects, an unknown corner, or out-of-range numbers. */
	private void normalise() {
		// Gson writes null into a field whose key is present but null, so every object needs checking
		// before anything reads through it.
		this.admin = orNew(this.admin);
		this.passive = orNew(this.passive);
		this.build = orNew(this.build);
		this.god = orNew(this.god);
		this.demigod = orNew(this.demigod);
		this.ghost = orNew(this.ghost);
		this.flight = orNew(this.flight);
		this.afk = orNew(this.afk);
		this.offline = orNew(this.offline);

		if (this.anchor == null) {
			this.anchor = Anchor.TOP_LEFT;
		}

		this.scalePercent = Mth.clamp(this.scalePercent, MIN_SCALE, MAX_SCALE);
		this.offsetX = Mth.clamp(this.offsetX, 0, MAX_OFFSET);
		this.offsetY = Mth.clamp(this.offsetY, 0, MAX_OFFSET);

		for (Slot slot : this.slots()) {
			slot.indicator().normalise(slot.defaultColor());
		}
	}

	private static Indicator orNew(final @Nullable Indicator indicator) {
		return indicator == null ? new Indicator(true) : indicator;
	}
}
