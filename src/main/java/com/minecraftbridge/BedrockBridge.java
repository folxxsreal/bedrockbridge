package com.minecraftbridge;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BedrockBridge implements ModInitializer {
	public static final String MOD_ID = "bedrockbridge";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("¡BedrockBridge cargado! Listo para conectar Java y Bedrock.");
	}
}
