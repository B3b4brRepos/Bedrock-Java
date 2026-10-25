package com.originalsword;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OriginalSwordClient implements ClientModInitializer {
	public static final String MOD_ID = "originalsword";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		try {
			Config.reloadIfChanged();
			SwordRenderer.init(SwordAssets.load());
			LOGGER.info("OriginalSword loaded (preset '{}')", Config.current.preset);
		} catch (Exception e) {
			LOGGER.error("OriginalSword failed to load its Bedrock assets; vanilla hands will be used", e);
		}
	}
}
