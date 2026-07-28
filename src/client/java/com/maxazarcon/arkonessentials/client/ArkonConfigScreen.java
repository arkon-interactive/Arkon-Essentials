package com.maxazarcon.arkonessentials.client;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * The Mod Menu configuration screen.
 *
 * <p>Built entirely from vanilla's own option widgets, so it needs no configuration library. That is
 * worth a little extra code here: a library would be a hard dependency every player had to install,
 * and this mod's whole shape is a server that works on its own with the client as a bonus.
 *
 * <p>Edits are written straight into the live {@link HudConfig}, so they show on screen the moment the
 * screen closes; {@link #removed()} is what puts them on disk.
 */
public class ArkonConfigScreen extends OptionsSubScreen {
	/** Colour of a hex entry that does not parse — the signal that what is typed is not applied yet. */
	private static final int UNAPPLIED = 0xFFA0A0A0;

	private static final int FIELD_WIDTH = 150;
	private static final int FIELD_HEIGHT = 20;

	public ArkonConfigScreen(final Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.translatable("arkonessentials.options.title"));
	}

	@Override
	protected void addOptions() {
		OptionsList list = Objects.requireNonNull(this.list, "addOptions runs after the list is built");
		HudConfig config = HudConfig.get();

		list.addSmall(
			OptionInstance.createBoolean("arkonessentials.options.enabled", config.enabled, value -> config.enabled = value),
			OptionInstance.createBoolean("arkonessentials.options.shadow", config.textShadow, value -> config.textShadow = value)
		);

		list.addSmall(anchorOption(config), percentSlider(config));

		list.addSmall(
			slider("arkonessentials.options.offset_x", 0, HudConfig.MAX_OFFSET, config.offsetX, value -> config.offsetX = value),
			slider("arkonessentials.options.offset_y", 0, HudConfig.MAX_OFFSET, config.offsetY, value -> config.offsetY = value)
		);

		list.addHeader(Component.translatable("arkonessentials.options.indicators"));

		for (HudConfig.Slot slot : config.slots()) {
			list.addSmall(toggle(slot), colorField(slot));
		}
	}

	/** A cycle button over the four corners. */
	private OptionInstance<HudConfig.Anchor> anchorOption(final HudConfig config) {
		return new OptionInstance<>(
			"arkonessentials.options.anchor",
			OptionInstance.noTooltip(),
			(caption, value) -> caption.copy().append(": ").append(Component.translatable(value.translationKey())),
			new OptionInstance.Enum<>(Arrays.asList(HudConfig.Anchor.values()), HudConfig.Anchor.CODEC),
			config.anchor,
			value -> config.anchor = value
		);
	}

	private OptionInstance<Integer> percentSlider(final HudConfig config) {
		return new OptionInstance<>(
			"arkonessentials.options.scale",
			OptionInstance.noTooltip(),
			(caption, value) -> caption.copy().append(": " + value + "%"),
			new OptionInstance.IntRange(HudConfig.MIN_SCALE, HudConfig.MAX_SCALE),
			config.scalePercent,
			value -> config.scalePercent = value
		);
	}

	private static OptionInstance<Integer> slider(
		final String captionId, final int min, final int max, final int initial, final Consumer<Integer> setter
	) {
		return new OptionInstance<>(
			captionId,
			OptionInstance.noTooltip(),
			// Built by hand rather than through a vanilla format key, so the label cannot break if that
			// key is ever renamed.
			(caption, value) -> caption.copy().append(": " + value),
			new OptionInstance.IntRange(min, max),
			initial,
			setter::accept
		);
	}

	private AbstractWidget toggle(final HudConfig.Slot slot) {
		return OptionInstance.createBoolean(slot.translationKey(), slot.indicator().shown, value -> slot.indicator().shown = value)
			.createButton(this.options);
	}

	/**
	 * A hex entry whose own text is drawn in the colour it names, so the field previews itself.
	 *
	 * <p>Nothing is applied until it parses. An entry mid-typing is neither rejected nor written — it
	 * just greys out, which keeps a half-typed colour from flashing onto the HUD.
	 */
	private AbstractWidget colorField(final HudConfig.Slot slot) {
		EditBox field = new EditBox(
			this.font, 0, 0, FIELD_WIDTH, FIELD_HEIGHT, Component.translatable(slot.translationKey())
		);

		field.setMaxLength(7);
		field.setValue(slot.indicator().hex(slot.defaultColor()));
		field.setTextColor(slot.indicator().color(slot.defaultColor()));

		field.setResponder(text -> {
			OptionalInt parsed = HudConfig.parseColor(text);

			if (parsed.isPresent()) {
				slot.indicator().color = text;
				field.setTextColor(parsed.getAsInt());
			} else {
				field.setTextColor(UNAPPLIED);
			}
		});

		return field;
	}

	/**
	 * Saves on the way out, by whichever route — Done, Escape, or the window closing.
	 *
	 * <p>Does not call {@code super}, whose only job is to save vanilla's options; this screen never
	 * touches those, and writing options.txt as a side effect of leaving would be a lie about what
	 * happened.
	 */
	@Override
	public void removed() {
		HudConfig.save();
	}
}
