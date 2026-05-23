package com.example.client;

// Estado público del túnel Playit, leído por el HUD overlay y escrito por PlayitManager.
// Volatile porque writes vienen del worker thread de Playit y reads del render thread.
public enum PlayitStatus {
	IDLE,           // LAN cerrada o "Compartir con Bedrock" desactivado
	BOOTSTRAPPING,  // bajando binarios, claim, o levantando daemon
	CLAIMING,       // esperando que el usuario apruebe en el browser
	ONLINE,         // túnel activo con endpoint público
	ERROR;          // último intento falló

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
