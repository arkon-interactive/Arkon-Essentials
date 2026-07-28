package com.maxazarcon.arkonessentials.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Hands Mod Menu the configuration screen.
 *
 * <p>Mod Menu is optional. This class is only ever loaded by Mod Menu itself asking for the
 * {@code modmenu} entrypoint, so a client without it — and every dedicated server — never touches
 * these classes and never needs the dependency on the classpath.
 */
public class ArkonModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ArkonConfigScreen::new;
	}
}
