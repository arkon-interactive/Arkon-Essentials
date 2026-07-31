package com.maxazarcon.arkonessentials;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

/**
 * Server-wide settings, read from {@code config/arkonessentials.json}.
 *
 * <p>A plain class with initialised fields rather than a record: Gson leaves a field untouched when
 * the key is absent, so an older config file silently picks up defaults for anything new instead of
 * nulling it out.
 *
 * <p>These are <em>defaults</em>, not overrides. Per-player preferences persist as absent-until-set,
 * so changing a default here moves everyone who never set their own — which is what an operator
 * editing a config file expects.
 */
public final class EssentialsConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "arkonessentials.json";

	private static EssentialsConfig instance = new EssentialsConfig();

	/**
	 * How many homes an ordinary player may keep, counting the unnamed one. 0 withholds {@code /home}
	 * entirely. Overridden per player by the {@code arkonessentials:home.limit} integer permission.
	 */
	public int playerHomes = 1;

	/**
	 * Whether ordinary players may *name* homes. With this false they get only the unnamed home — which
	 * is their spawn point — so {@code playerHomes} above 1 has no effect. Overridden per player by the
	 * {@code arkonessentials:home.named} permission.
	 */
	public boolean playerNamedHomes = false;

	/**
	 * How many named homes an admin may keep under {@code /admin home}. Stored separately from a
	 * player's own homes, so staff locations and personal ones never mix. Overridden per player by the
	 * {@code arkonessentials:admin.home.limit} integer permission.
	 */
	public int adminHomes = 5;

	/** Build Mode reach bonus for a player who has not set their own, in blocks. */
	public int defaultBuildReach = 4;

	/**
	 * When false, {@code /build nv} additionally requires the {@code arkonessentials:build_nv}
	 * permission, so night vision can be granted selectively. Also seeds whether Build Mode starts
	 * with night vision on.
	 */
	public boolean buildNightVisionAvailable = true;

	/** Flight speed multiplier for a player who has not set their own. */
	public int defaultFlySpeed = 2;

	/**
	 * Whether Demigod grants flight. Off by default — Demigod is the tier that still feels the world,
	 * so flight is opt-in. God and Ghost always grant it. Overridden per player by the
	 * {@code arkonessentials:fly.demigod} permission.
	 */
	public boolean demigodFlight = false;

	/**
	 * Seconds of inactivity before a player is marked AFK automatically. 0 disables the timer, leaving
	 * {@code /afk} as the only way in.
	 */
	public int afkTimeoutSeconds = 90;

	/** Announcement for a player going AFK. {@code %s} is their name. */
	public String afkMessage = "%s has gone AFK!";

	/** Announcement when a reason was given. The first {@code %s} is the name, the second the reason. */
	public String afkReasonMessage = "%s has gone AFK. Reason: %s";

	/** Announcement for a player coming back. {@code %s} is their name. */
	public String afkReturnMessage = "%s is no longer AFK.";

	/**
	 * Whether anyone may attach a reason to {@code /afk}. With this false the reason argument requires
	 * the {@code arkonessentials:afk.reason} permission, so free-text broadcasts can be granted
	 * selectively.
	 */
	public boolean afkReasonsAvailable = true;

	/**
	 * Which state {@code /fakeleave} puts a player into when they name none. Any state is valid,
	 * including {@code none}; an unrecognised name falls back to Ghost.
	 */
	public String fakeLeaveDefaultMode = "ghost";

	/**
	 * Whether vanished players are left out of the server-list ping — both the online count and the
	 * sample of names.
	 *
	 * <p>On by default, since a count that drops when staff go on duty is exactly the tell vanish exists
	 * to remove. Turn it off if you would rather the public count stayed truthful, for a queue plugin or
	 * a server list that tracks population.
	 */
	public boolean hideFromPing = true;

	/**
	 * Whether vanished players are hidden on BlueMap and squaremap.
	 *
	 * <p>Only takes effect when one of those is installed. Turn it off if you want staff visible to
	 * whoever can see the web map — it is often a private admin map rather than a public one.
	 */
	public boolean hideFromWebMaps = true;

	/**
	 * Largest Demigod shield, in absorption points — two per gold heart. 10 is five hearts.
	 *
	 * <p>Sits on top of health rather than replacing armour, so it stacks with whatever the player is
	 * wearing.
	 */
	public double demigodShieldCap = 10.0;

	/** Experience level at which the shield reaches {@link #demigodShieldCap}. It scales linearly to there. */
	public int demigodShieldLevels = 20;

	/** Absorption points restored per second, once regeneration resumes. */
	public double demigodShieldRegenPerSecond = 0.5;

	/**
	 * Seconds after taking a hit before the shield starts refilling again.
	 *
	 * <p>The whole reason Demigod is not invulnerable any more. With no pause the pool refills faster
	 * than anyone can spend it, which is the same thing as immunity wearing a different name.
	 */
	public int demigodShieldRegenDelaySeconds = 5;

	/** The configured {@code /fakeleave} state, resolved and validated. */
	public AdminState fakeLeaveMode() {
		for (AdminState state : AdminState.values()) {
			if (state.getSerializedName().equalsIgnoreCase(this.fakeLeaveDefaultMode)) {
				return state;
			}
		}

		return AdminState.GHOST;
	}

	/**
	 * Every editable setting, in one place.
	 *
	 * <p>The {@code /arkon config} command is generated from this list, so a new setting becomes
	 * editable in game the moment it is added here — there is no second place to remember to update.
	 */
	public static final List<Option<?>> OPTIONS = List.of(
		Option.ofInt("playerHomes", 0, 64, c -> c.playerHomes, (c, v) -> c.playerHomes = v,
			"How many homes an ordinary player may keep. 0 disables /home."),
		Option.ofBool("playerNamedHomes", c -> c.playerNamedHomes, (c, v) -> c.playerNamedHomes = v,
			"Whether ordinary players may name homes."),
		Option.ofInt("adminHomes", 0, 64, c -> c.adminHomes, (c, v) -> c.adminHomes = v,
			"How many named homes an admin may keep under /admin home."),
		Option.ofInt("defaultBuildReach", 0, AdminManager.MAX_REACH_BONUS, c -> c.defaultBuildReach,
			(c, v) -> c.defaultBuildReach = v, "Build Mode reach bonus for players who have not set their own."),
		Option.ofBool("buildNightVisionAvailable", c -> c.buildNightVisionAvailable,
			(c, v) -> c.buildNightVisionAvailable = v,
			"When false, /build nv requires the arkonessentials:build_nv permission."),
		Option.ofInt("defaultFlySpeed", AdminManager.MIN_FLY_SPEED, AdminManager.MAX_FLY_SPEED, c -> c.defaultFlySpeed,
			(c, v) -> c.defaultFlySpeed = v, "Flight speed multiplier for players who have not set their own."),
		Option.ofBool("demigodFlight", c -> c.demigodFlight, (c, v) -> c.demigodFlight = v,
			"Whether Demigod grants flight. God and Ghost always do."),
		Option.ofInt("afkTimeoutSeconds", 0, 3600, c -> c.afkTimeoutSeconds, (c, v) -> c.afkTimeoutSeconds = v,
			"Seconds of inactivity before a player goes AFK automatically. 0 disables the timer."),
		Option.ofString("afkMessage", c -> c.afkMessage, (c, v) -> c.afkMessage = v,
			"Announcement for going AFK. %s is the player's name."),
		Option.ofString("afkReasonMessage", c -> c.afkReasonMessage, (c, v) -> c.afkReasonMessage = v,
			"Announcement for going AFK with a reason. First %s is the name, second the reason."),
		Option.ofString("afkReturnMessage", c -> c.afkReturnMessage, (c, v) -> c.afkReturnMessage = v,
			"Announcement for returning from AFK. %s is the player's name."),
		Option.ofBool("afkReasonsAvailable", c -> c.afkReasonsAvailable, (c, v) -> c.afkReasonsAvailable = v,
			"When false, /afk <reason> requires the arkonessentials:afk.reason permission."),
		Option.ofString("fakeLeaveDefaultMode", c -> c.fakeLeaveDefaultMode, (c, v) -> c.fakeLeaveDefaultMode = v,
			"State /fakeleave uses when none is named. Any state, including none. Unknown values fall back to ghost."),
		Option.ofBool("hideFromPing", c -> c.hideFromPing, (c, v) -> c.hideFromPing = v,
			"Whether vanished players are left out of the server-list ping count and sample."),
		Option.ofBool("hideFromWebMaps", c -> c.hideFromWebMaps, (c, v) -> c.hideFromWebMaps = v,
			"Whether vanished players are hidden on BlueMap and squaremap, when installed."),
		Option.ofDouble("demigodShieldCap", 0.0, 40.0, c -> c.demigodShieldCap, (c, v) -> c.demigodShieldCap = v,
			"Largest Demigod shield, in absorption points. Two per gold heart."),
		Option.ofInt("demigodShieldLevels", 1, 100, c -> c.demigodShieldLevels, (c, v) -> c.demigodShieldLevels = v,
			"Experience level at which the Demigod shield reaches its cap."),
		Option.ofDouble("demigodShieldRegenPerSecond", 0.0, 20.0, c -> c.demigodShieldRegenPerSecond,
			(c, v) -> c.demigodShieldRegenPerSecond = v, "Absorption points the Demigod shield restores per second."),
		Option.ofInt("demigodShieldRegenDelaySeconds", 0, 300, c -> c.demigodShieldRegenDelaySeconds,
			(c, v) -> c.demigodShieldRegenDelaySeconds = v,
			"Seconds after a hit before the Demigod shield starts refilling.")
	);

	public static EssentialsConfig get() {
		return instance;
	}

	/**
	 * One editable setting: how to read it, how to write it, and how to parse it from a command.
	 *
	 * @param <T> the setting's type, which drives the Brigadier argument used to edit it
	 */
	public record Option<T>(
		String key,
		String description,
		Function<EssentialsConfig, T> getter,
		BiConsumer<EssentialsConfig, T> setter,
		ArgumentType<T> argumentType,
		Class<T> valueType
	) {
		static Option<Integer> ofInt(
			final String key,
			final int min,
			final int max,
			final Function<EssentialsConfig, Integer> getter,
			final BiConsumer<EssentialsConfig, Integer> setter,
			final String description
		) {
			// The bounds live in the argument type, so out-of-range input is refused at parse time with
			// a caret rather than being silently clamped after the fact.
			return new Option<>(key, description, getter, setter, IntegerArgumentType.integer(min, max), Integer.class);
		}

		/**
		 * A free-text setting, taking the rest of the command line.
		 *
		 * <p>Greedy so a message can contain spaces without quoting. That makes it necessarily the last
		 * argument, which is fine — every option node ends at its value.
		 */
		static Option<String> ofString(
			final String key,
			final Function<EssentialsConfig, String> getter,
			final BiConsumer<EssentialsConfig, String> setter,
			final String description
		) {
			return new Option<>(key, description, getter, setter, StringArgumentType.greedyString(), String.class);
		}

		/** A fractional setting. Bounded like the ints, so bad input is refused at parse time. */
		static Option<Double> ofDouble(
			final String key,
			final double min,
			final double max,
			final Function<EssentialsConfig, Double> getter,
			final BiConsumer<EssentialsConfig, Double> setter,
			final String description
		) {
			return new Option<>(key, description, getter, setter, DoubleArgumentType.doubleArg(min, max), Double.class);
		}

		static Option<Boolean> ofBool(
			final String key,
			final Function<EssentialsConfig, Boolean> getter,
			final BiConsumer<EssentialsConfig, Boolean> setter,
			final String description
		) {
			return new Option<>(key, description, getter, setter, BoolArgumentType.bool(), Boolean.class);
		}

		public String read() {
			return String.valueOf(this.getter.apply(get()));
		}

		public void write(final Object value) {
			this.setter.accept(get(), this.valueType.cast(value));
		}
	}

	/**
	 * Reads the config, filling in anything missing and rewriting the file so new keys appear on disk.
	 *
	 * <p>A file that cannot be parsed is left strictly alone and defaults are used for the run — the
	 * same reasoning as the saved-data guard. Silently overwriting an operator's hand-edited file
	 * because of one stray comma would be the worse failure.
	 */
	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		EssentialsConfig loaded = new EssentialsConfig();

		if (Files.exists(path)) {
			try (BufferedReader reader = Files.newBufferedReader(path)) {
				EssentialsConfig parsed = GSON.fromJson(reader, EssentialsConfig.class);

				if (parsed != null) {
					loaded = parsed;
				}
			} catch (Exception e) {
				ArkonEssentials.LOGGER.error(
					"Could not read {} — using defaults for this run and leaving your file untouched.", path, e
				);
				instance = new EssentialsConfig();
				return;
			}
		}

		loaded.clampToValidRanges();
		instance = loaded;
		write(path, loaded);
	}

	/** Persists the current values, so an in-game edit survives a restart. */
	public static void save() {
		instance.clampToValidRanges();
		write(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME), instance);
	}

	private static void write(final Path path, final EssentialsConfig config) {
		try {
			Files.createDirectories(path.getParent());

			try (BufferedWriter writer = Files.newBufferedWriter(path)) {
				GSON.toJson(config, writer);
			}
		} catch (Exception e) {
			ArkonEssentials.LOGGER.error("Could not write {}; settings still apply for this run.", path, e);
		}
	}

	/** Keeps hand-edited values inside the bounds the commands and codecs enforce. */
	private void clampToValidRanges() {
		this.playerHomes = Math.max(this.playerHomes, 0);
		this.adminHomes = Math.max(this.adminHomes, 0);
		this.defaultBuildReach = Mth.clamp(this.defaultBuildReach, 0, AdminManager.MAX_REACH_BONUS);
		this.defaultFlySpeed = Mth.clamp(this.defaultFlySpeed, AdminManager.MIN_FLY_SPEED, AdminManager.MAX_FLY_SPEED);
		this.afkTimeoutSeconds = Math.max(this.afkTimeoutSeconds, 0);

		// A key present but null defeats the field initialiser, and a blank announcement would broadcast
		// an empty line rather than nothing at all.
		EssentialsConfig defaults = new EssentialsConfig();
		this.afkMessage = orDefault(this.afkMessage, defaults.afkMessage);
		this.afkReasonMessage = orDefault(this.afkReasonMessage, defaults.afkReasonMessage);
		this.afkReturnMessage = orDefault(this.afkReturnMessage, defaults.afkReturnMessage);
	}

	private static String orDefault(final String value, final String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}
