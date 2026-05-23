package com.minecraftbridge.client.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Filters out the noisy broadcasts Minecraft and Geyser dump into chat when LAN
// opens, so only BedrockBridge's structured messages remain. We compare against
// the plain text (Component.getString), which already has translation key
// placeholders resolved.
@Mixin(ChatComponent.class)
public class ChatComponentMixin {

	private static boolean bedrockbridge$shouldFilter(Component component) {
		String text = component.getString();
		// Geyser broadcasts "Started Geyser on UDP port 19132" — redundant with
		// our chat line "Bedrock (LAN): 19132".
		if (text.startsWith("Started Geyser on UDP port")) return true;
		// Vanilla broadcasts "Local game hosted on port X" — redundant with our
		// "Java (LAN): <port>" line.
		if (text.startsWith("Local game hosted on port")) return true;
		if (text.startsWith("Commands are now ")) return true;
		return false;
	}

	@Inject(method = "addClientSystemMessage", at = @At("HEAD"), cancellable = true)
	private void bedrockbridge$filterClient(Component component, CallbackInfo ci) {
		if (bedrockbridge$shouldFilter(component)) ci.cancel();
	}

	@Inject(method = "addServerSystemMessage", at = @At("HEAD"), cancellable = true)
	private void bedrockbridge$filterServer(Component component, CallbackInfo ci) {
		if (bedrockbridge$shouldFilter(component)) ci.cancel();
	}
}
