package com.example.client.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Filtra los broadcasts ruidosos que Minecraft y Geyser meten en chat cuando
// se abre LAN, para que solo queden los mensajes estructurados de BedrockBridge.
// Comparamos contra texto plano (Component.getString) que ya tiene resueltos
// los placeholders de las translation keys.
@Mixin(ChatComponent.class)
public class ChatComponentMixin {

	private static boolean bedrockbridge$shouldFilter(Component component) {
		String text = component.getString();
		// Geyser broadcastea "Started Geyser on UDP port 19132" — redundante con
		// nuestra línea de chat "Bedrock (LAN): 19132".
		if (text.startsWith("Started Geyser on UDP port")) return true;
		// Vanilla broadcastea "Local game hosted on port X" — redundante con
		// nuestra línea "Java (LAN): <port>".
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
