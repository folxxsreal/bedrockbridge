package com.example.client;

import com.example.BedrockBridge;
import com.example.client.playit.PlayitManager;
import com.example.state.BedrockBridgeState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public class BedrockBridgeClient implements ClientModInitializer {

	private boolean lanWasOpen = false;

	@Override
	public void onInitializeClient() {
		BedrockBridge.LOGGER.info("BedrockBridgeClient inicializado en el lado del cliente.");
		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
		ScreenEvents.AFTER_INIT.register(this::onScreenAfterInit);
	}

	private void onScreenAfterInit(Minecraft client, net.minecraft.client.gui.screens.Screen screen, int width, int height) {
		if (!(screen instanceof ShareToLanScreen)) {
			return;
		}

		Checkbox checkbox = Checkbox.builder(
				Component.literal("Compartir con Bedrock"),
				Screens.getFont(screen)
			)
			.pos(10, height - 55)
			.selected(BedrockBridgeState.shareWithBedrock)
			.onValueChange((cb, value) -> {
				BedrockBridgeState.shareWithBedrock = value;
				BedrockBridge.LOGGER.info("Checkbox 'Compartir con Bedrock' cambiado a: {}", value);
			})
			.build();

		Screens.getWidgets(screen).add(checkbox);
		BedrockBridge.LOGGER.info("Checkbox inyectado en ShareToLanScreen (estado inicial: {}).", BedrockBridgeState.shareWithBedrock);
	}

	private void onClientTick(Minecraft client) {
		boolean lanIsOpen = client.hasSingleplayerServer()
				&& client.getSingleplayerServer() != null
				&& client.getSingleplayerServer().isPublished();

		if (lanIsOpen && !lanWasOpen) {
			onLanOpened(client);
		}
		if (!lanIsOpen && lanWasOpen) {
			onLanClosed(client);
		}
		lanWasOpen = lanIsOpen;
	}

	private void onLanOpened(Minecraft client) {
		int port = client.getSingleplayerServer().getPort();
		boolean share = BedrockBridgeState.shareWithBedrock;
		BedrockBridge.LOGGER.info("¡Mundo abierto a LAN! Puerto Java: {} (shareWithBedrock={})", port, share);

		if (share) {
			MutableComponent msg = Chat.header().append(Chat.ok("LAN abierta"));
			Chat.send(msg);
			Chat.send(Chat.label("Java (LAN)").append(Chat.value(port)));
			Chat.send(Chat.label("Bedrock (LAN)").append(Chat.value(19132))
					.append(Component.literal(" ")).append(Chat.muted("(Floodgate activo)")));
			Chat.send(Chat.label("Internet").append(Chat.muted("preparando túnel...")));
			PlayitManager.get().start();
		} else {
			MutableComponent msg = Chat.header()
					.append(Chat.ok("LAN abierta en puerto "))
					.append(Chat.value(port))
					.append(Component.literal(" "))
					.append(Chat.muted("(solo Java — Bedrock desactivado)"));
			Chat.send(msg);
		}
	}

	private void onLanClosed(Minecraft client) {
		BedrockBridge.LOGGER.info("Mundo LAN cerrado.");
		PlayitManager.get().stop();
		MutableComponent msg = Chat.header()
				.append(Chat.muted("LAN cerrada · túnel parado"));
		Chat.send(msg);
	}
}
