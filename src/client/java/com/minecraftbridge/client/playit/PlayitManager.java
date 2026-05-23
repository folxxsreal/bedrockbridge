package com.minecraftbridge.client.playit;

import com.minecraftbridge.BedrockBridge;
import com.minecraftbridge.client.Chat;
import com.minecraftbridge.client.PlayitStatus;
import com.minecraftbridge.playit.PlayitBinaries;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Orchestrates Playit lifecycle: ensure binaries → claim if needed → run daemon → announce
// the public endpoint in chat. One instance per JVM; safe to call start/stop repeatedly.
public final class PlayitManager {

	private static final PlayitManager INSTANCE = new PlayitManager();
	public static PlayitManager get() { return INSTANCE; }

	private static final Pattern PUBLIC_ENDPOINT_PATTERN =
			Pattern.compile("([a-z0-9-]+\\.gl\\.at\\.ply\\.gg(?::\\d+)?)");

	private Path stateDir;
	private Path binDir;
	private Path secretPath;
	private Path daemonLogPath;
	private Path daemonSocketPath;

	private volatile Process daemonProcess;
	private volatile Thread daemonReaderThread;
	private volatile Thread claimThread;
	private volatile Thread watchdogThread;
	private volatile Path daemonPath;
	private volatile boolean wantDaemonAlive = false;
	private final AtomicReference<String> lastAnnouncedEndpoint = new AtomicReference<>();

	// Backoff progresivo entre reintentos de restart del daemon, en segundos.
	private static final int[] RESTART_BACKOFF_SEC = {5, 15, 45};

	private PlayitManager() {}

	private synchronized void initPaths() {
		if (stateDir != null) return;
		stateDir = FabricLoader.getInstance().getGameDir().resolve("bedrockbridge");
		binDir = stateDir.resolve("bin");
		secretPath = stateDir.resolve("playit.toml");
		daemonLogPath = stateDir.resolve("playit.log");
		daemonSocketPath = stateDir.resolve("playit.sock");
	}

	// Called from onLanOpened when shareWithBedrock=true. Non-blocking: heavy lifting
	// (download, claim) happens on a worker thread so the client tick isn't stalled.
	public synchronized void start() {
		initPaths();
		if (daemonProcess != null && daemonProcess.isAlive()) {
			BedrockBridge.LOGGER.info("Playit daemon ya está corriendo, no se relanza.");
			return;
		}
		PlayitStatus.set(PlayitStatus.BOOTSTRAPPING, "preparando");
		new Thread(this::startAsync, "BedrockBridge-Playit-Bootstrap").start();
	}

	private void startAsync() {
		try {
			daemonPath = PlayitBinaries.ensureDaemon(binDir);
			if (!hasSecret()) {
				runClaimFlow();
			}
			if (hasSecret()) {
				wantDaemonAlive = true;
				launchDaemon(daemonPath);
				startWatchdog();
				ensureTunnelAsync();
			} else {
				Chat.error("Esperando claim", "abrí el link de arriba en el navegador");
			}
		} catch (UnsupportedOperationException e) {
			BedrockBridge.LOGGER.warn("Plataforma sin soporte de Playit: {}", e.getMessage());
			PlayitStatus.set(PlayitStatus.ERROR, "plataforma sin túnel");
			Chat.error("Playit no soportado en esta plataforma", e.getMessage());
		} catch (Exception e) {
			BedrockBridge.LOGGER.error("Fallo al iniciar Playit", e);
			PlayitStatus.set(PlayitStatus.ERROR, e.getMessage());
			Chat.error("Playit no arrancó: " + e.getMessage(),
					"revisá los logs y reabrí LAN");
		}
	}

	// Detecta si el daemon muere inesperadamente y lo relanza con backoff exponencial.
	// Termina cuando stop() limpia wantDaemonAlive o tras agotar los reintentos.
	private void startWatchdog() {
		if (watchdogThread != null && watchdogThread.isAlive()) return;
		watchdogThread = new Thread(() -> {
			int attempt = 0;
			while (wantDaemonAlive) {
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				Process p = daemonProcess;
				if (p == null || p.isAlive()) {
					attempt = 0;
					continue;
				}
				if (!wantDaemonAlive) return;
				if (attempt >= RESTART_BACKOFF_SEC.length) {
					BedrockBridge.LOGGER.error("Daemon Playit murió y agotamos {} reintentos, me rindo.", RESTART_BACKOFF_SEC.length);
					PlayitStatus.set(PlayitStatus.ERROR, "daemon no se recupera");
					Chat.error("El túnel cayó y no se recupera tras varios intentos",
							"cerrá LAN y reabrila para volver a probar");
					return;
				}
				int delaySec = RESTART_BACKOFF_SEC[attempt++];
				BedrockBridge.LOGGER.warn("Daemon Playit murió (exit={}). Relanzando en {}s (intento {}/{}).",
						p.exitValue(), delaySec, attempt, RESTART_BACKOFF_SEC.length);
				PlayitStatus.set(PlayitStatus.BOOTSTRAPPING, "reintentando túnel (" + attempt + "/" + RESTART_BACKOFF_SEC.length + ")");
				Chat.send(Chat.header().append(Chat.warn("Túnel cayó · reintentando en " + delaySec + "s")));
				try {
					Thread.sleep(delaySec * 1000L);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				if (!wantDaemonAlive) return;
				try {
					launchDaemon(daemonPath);
					Chat.send(Chat.header().append(Chat.ok("Túnel restablecido")));
					PlayitStatus.set(PlayitStatus.ONLINE, lastAnnouncedEndpoint.get() == null ? "reconectado" : lastAnnouncedEndpoint.get());
				} catch (IOException e) {
					BedrockBridge.LOGGER.error("Restart de daemon falló", e);
				}
			}
		}, "BedrockBridge-Playit-Watchdog");
		watchdogThread.setDaemon(true);
		watchdogThread.start();
	}

	// Resolves the public Bedrock endpoint via the Playit REST API, creating the tunnel if
	// the account has none. Runs on its own thread so the daemon reader stays unblocked.
	private void ensureTunnelAsync() {
		new Thread(() -> {
			try {
				String secret = readSecret();
				if (secret == null) return;
				PlayitApi.Tunnel t = PlayitApi.ensureBedrockTunnel(secret, 19132);
				if (t.displayAddress() != null && !t.displayAddress().isEmpty()
						&& !t.displayAddress().equals(lastAnnouncedEndpoint.get())) {
					lastAnnouncedEndpoint.set(t.displayAddress());
					announceEndpoint(t.displayAddress());
				}
			} catch (Exception e) {
				BedrockBridge.LOGGER.error("Fallo al asegurar túnel Playit", e);
				PlayitStatus.set(PlayitStatus.ERROR, "túnel API falló");
				Chat.error("No se pudo crear el túnel: " + e.getMessage(),
						"creá uno manual en playit.gg/account/tunnels (UDP, local 19132)");
			}
		}, "BedrockBridge-Playit-TunnelResolver").start();
	}

	private boolean hasSecret() throws IOException {
		return readSecret() != null;
	}

	private String readSecret() throws IOException {
		if (!Files.exists(secretPath)) return null;
		for (String line : Files.readAllLines(secretPath, StandardCharsets.UTF_8)) {
			Matcher m = SECRET_LINE.matcher(line);
			if (m.matches()) return m.group(1);
		}
		return null;
	}

	private static final Pattern SECRET_LINE =
			Pattern.compile("\\s*secret_key\\s*=\\s*\"([0-9a-fA-F]{64})\"\\s*");

	// Claim flow purely en Java. Sin spawn de playit-cli — el daemon es el único
	// binario que necesitamos por plataforma. Ver PlayitClaim para el detalle de
	// los endpoints /claim/setup y /claim/exchange.
	private void runClaimFlow() throws IOException, InterruptedException {
		PlayitStatus.set(PlayitStatus.CLAIMING, "abrí el link en el browser");
		claimThread = Thread.currentThread();
		String code = PlayitClaim.generateCode();
		BedrockBridge.LOGGER.info("Playit claim code generado: {}", code);
		announceClaimUrl(PlayitClaim.claimUrl(code));

		PlayitClaim.waitForUserAcceptance(code);
		String secret = PlayitClaim.exchangeForSecret(code);
		claimThread = null;
		if (!secret.matches("[0-9a-fA-F]{64}")) {
			throw new IOException("Secret de Playit con formato inesperado: " + secret.length() + " chars");
		}
		Files.writeString(secretPath, "secret_key = \"" + secret + "\"\n", StandardCharsets.UTF_8);
		BedrockBridge.LOGGER.info("Secret de Playit guardado en {}", secretPath);
		PlayitStatus.set(PlayitStatus.BOOTSTRAPPING, "conectando túnel");
		Chat.send(Chat.header().append(Chat.ok("Claim aceptado · arrancando túnel...")));
	}

	private void launchDaemon(Path daemonPath) throws IOException {
		Files.deleteIfExists(daemonSocketPath);
		// No -l: daemon writes to stdout/stderr, which we merge and read line-by-line below.
		ProcessBuilder pb = new ProcessBuilder(daemonPath.toString(),
				"--secret-path", secretPath.toString(),
				"--socket-path", daemonSocketPath.toString())
				.redirectErrorStream(true);
		daemonProcess = pb.start();
		BedrockBridge.LOGGER.info("Playit daemon arrancado (pid={}).", daemonProcess.pid());

		daemonReaderThread = new Thread(this::readDaemonOutput, "BedrockBridge-Playit-Daemon-Reader");
		daemonReaderThread.setDaemon(true);
		daemonReaderThread.start();
	}

	private void readDaemonOutput() {
		Process p = daemonProcess;
		if (p == null) return;
		try (BufferedReader r = new BufferedReader(
				new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				BedrockBridge.LOGGER.info("[playit] {}", line);
				Matcher m = PUBLIC_ENDPOINT_PATTERN.matcher(line);
				if (m.find()) {
					String endpoint = m.group(1);
					if (!endpoint.equals(lastAnnouncedEndpoint.get())) {
						lastAnnouncedEndpoint.set(endpoint);
						announceEndpoint(endpoint);
					}
				}
			}
		} catch (IOException e) {
			BedrockBridge.LOGGER.warn("Reader del daemon Playit terminó: {}", e.getMessage());
		}
	}

	public synchronized void stop() {
		wantDaemonAlive = false; // pará el watchdog antes de matar el proceso
		if (watchdogThread != null) {
			watchdogThread.interrupt();
			watchdogThread = null;
		}
		// El claim flow es polling HTTP; interrumpir el hilo lo aborta limpio.
		Thread claim = claimThread;
		if (claim != null) {
			claim.interrupt();
			claimThread = null;
		}
		if (daemonProcess != null) {
			daemonProcess.destroy();
			BedrockBridge.LOGGER.info("Playit daemon parado.");
			daemonProcess = null;
		}
		lastAnnouncedEndpoint.set(null);
		PlayitStatus.set(PlayitStatus.IDLE, "");
	}

	private void announceClaimUrl(String url) {
		// Primera vez que el usuario usa el mod: necesita autorizar el agente.
		// Mostramos el link grande y clickeable + instrucción mínima.
		Chat.send(Chat.header().append(Chat.warn("Primera vez · necesitamos autorizar el túnel")));
		MutableComponent line = Chat.muted("  Abrí este link y aceptá: ").append(Chat.link(url));
		Chat.send(line);
	}

	private void announceEndpoint(String endpoint) {
		PlayitStatus.set(PlayitStatus.ONLINE, endpoint);
		// Reemplazo del placeholder "Internet: preparando túnel..." con el endpoint real.
		MutableComponent line = Chat.label("Internet")
				.append(Chat.copyable(endpoint))
				.append(Component.literal("  "))
				.append(Chat.muted("[click para copiar]"));
		Chat.send(line);
	}
}
