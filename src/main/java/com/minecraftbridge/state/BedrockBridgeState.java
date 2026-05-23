package com.minecraftbridge.state;

// Shared state readable from both Mixins (main sourceset) and the client checkbox
// callback. Volatile because writes come from the render thread and reads from
// the integrated server thread when Geyser fires SERVER_STARTED.
public final class BedrockBridgeState {

	public static volatile boolean shareWithBedrock = true;

	private BedrockBridgeState() {}
}
