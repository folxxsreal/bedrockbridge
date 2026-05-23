package com.minecraftbridge.client;

// Public Playit tunnel state, read by the HUD overlay and written by PlayitManager.
// Volatile because writes come from the Playit worker thread and reads from the
// render thread.
public enum PlayitStatus {
	IDLE,           // LAN closed or "Share with Bedrock" disabled
	BOOTSTRAPPING,  // downloading binaries, claim, or launching daemon
	CLAIMING,       // waiting for the user to approve in the browser
	ONLINE,         // tunnel active with public endpoint
	ERROR;          // last attempt failed

	private static volatile PlayitStatus current = IDLE;
	private static volatile String detail = "";
	private static volatile boolean hudVisible = true;

	public static PlayitStatus current() { return current; }
	public static String detail() { return detail; }
	public static boolean hudVisible() { return hudVisible; }

	public static void set(PlayitStatus status, String detail) {
		current = status;
		PlayitStatus.detail = detail == null ? "" : detail;
	}

	public static void toggleHud() {
		hudVisible = !hudVisible;
	}
}
