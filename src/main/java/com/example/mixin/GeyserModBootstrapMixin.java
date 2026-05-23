package com.example.mixin;

import com.example.BedrockBridge;
import com.example.state.BedrockBridgeState;
import org.geysermc.geyser.platform.mod.GeyserModBootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Makes the "Compartir con Bedrock" checkbox actually control Geyser:
// if the user opens LAN with the box unchecked, this skips Geyser's startup
// entirely — no UDP listener, no Floodgate session, nothing.
// Geyser-Fabric calls onGeyserEnable() from its SERVER_STARTED handler, so
// cancelling here is the natural intercept point.
@Mixin(GeyserModBootstrap.class)
public class GeyserModBootstrapMixin {

	@Inject(method = "onGeyserEnable", at = @At("HEAD"), cancellable = true)
	private void bedrockbridge$gateOnShareFlag(CallbackInfo ci) {
		if (!BedrockBridgeState.shareWithBedrock) {
			BedrockBridge.LOGGER.info("Geyser onGeyserEnable suprimido por shareWithBedrock=false.");
			ci.cancel();
		}
	}
}
