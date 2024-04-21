package io.wdsj.alwaysonline;

import io.wdsj.alwaysonline.config.AOConfig;
import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AlwaysOnline implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("AlwaysOnline");

	public static final AOConfig CONFIG = AOConfig.createAndLoad();

	@Override
	public void onInitialize() {

	}
}