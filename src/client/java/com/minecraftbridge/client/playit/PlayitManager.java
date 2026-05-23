package com.minecraftbridge.client.playit;

import com.minecraftbridge.BedrockBridge;
import com.minecraftbridge.client.Chat;
import com.minecraftbridge.client.Lang;
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
	// String because on Windows this is a named pipe (\\.\pipe\...), not a file path.
	// The Rust daemon picks bind() vs CreateNamedPipe() based on the prefix.
	private String daemonSocketArg;

	private volatile Process daemonProcess;
	private volatile Thread daemonReaderThread;
	private volatile Thread claimThread;
	private volatile Thread watchdogThread;
	private volatile Path daemonPath;
	private volatile boolean wantDaemonAlive = false;
	private final AtomicReference<String> lastAnnouncedEndpoint = new AtomicReference<>();

	// Progressive backoff between daemon restart attempts, in seconds.
	private static final int[] RESTART_BACKOFF_SEC = {5, 15, 45};

	private PlayitManager() {}

	private synchronized void initPaths() {
		if (stateDir != null) return;
		stateDir = FabricLoader.getInstance().getGameDir().resolve("bedrockbridge");
		binDir = stateDir.resolve("bin");
		secretPath = stateDir.resolve("playit.toml");
		daemonLogPath = stateDir.resolve("playit.log");
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		if (os.contains("win")) {
			// Dedicated named pipe so we don't collide with a system-installed playitd.
			daemonSocketArg = "\\\\.\\pipe\\bedrockbridge-playit";
		} else {
			// Unix socket file inside the state dir.
			daemonSocketArg = stateDir.resolve("playit.sock").toString();
		}
	}

	// Called from onLanOpened when shareWithBedrock=true. Non-blocking: heavy lifting
	// (download, claim) happens on a worker thread so the client tick isn't stalled.
	public synchronized void start() {
		initPaths();
		if (daemonProcess != null && daemonProcess.isAlive()) {
			BedrockBridge.LOGGER.info("Playit daemon already running, skipping relaunch.");
			return;
		}
		PlayitStatus.set(PlayitStatus.BOOTSTRAPPING, Lang.get("bedrockbridge.status.preparing"));
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
				Chat.error(Lang.get("bedrockbridge.chat.waiting_claim"), Lang.get("bedrockbridge.chat.waiting_claim_hint"));
			}
		} catch (UnsupportedOperationException e) {
			BedrockBridge.LOGGER.warn("Platform has no Playit support: {}", e.getMessage());
			PlayitStatus.set(PlayitStatus.ERROR, Lang.get("bedrockbridge.status.platform_no_tunnel"));
			Chat.error(Lang.get("bedrockbridge.chat.unsupported_platform"), e.getMessage());
		} catch (Exception e) {
			BedrockBridge.LOGGER.error("Failed to start Playit", e);
			PlayitStatus.set(PlayitStatus.ERROR, e.getMessage());
			Chat.error(Lang.get("bedrockbridge.chat.playit_failed_prefix") + e.getMessage(),
					Lang.get("bedrockbridge.chat.check_logs_hint"));
		}
	}

	// Detects when the daemon dies unexpectedly and relaunches it with exponential
	// backoff. Exits when stop() clears wantDaemonAlive or after retries are exhausted.
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
					BedrockBridge.LOGGER.error("Playit daemon died and {} retries exhausted, giving up.", RESTART_BACKOFF_SEC.length);
					PlayitStatus.set(PlayitStatus.ERROR, Lang.get("bedrockbridge.status.daemon_no_recover"));
					Chat.error(Lang.get("bedrockbridge.chat.tunnel_dead"),
							Lang.get("bedrockbridge.chat.tunnel_dead_hint"));
					return;
				}
				int delaySec = RESTART_BACKOFF_SEC[attempt++];
				BedrockBridge.LOGGER.warn("Playit daemon died (exit={}). Relaunching in {}s (attempt {}/{}).",
						p.exitValue(), delaySec, attempt, RESTART_BACKOFF_SEC.length);
				PlayitStatus.set(PlayitStatus.BOOTSTRAPPING, Lang.get("bedrockbridge.status.retrying", attempt, RESTART_BACKOFF_SEC.length));
				Chat.send(Chat.header().append(Chat.warn(Lang.get("bedrockbridge.chat.tunnel_down", delaySec))));
				try {
					Thread.sleep(delaySec * 1000L);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				if (!wantDaemonAlive) return;
				try {
					launchDaemon(daemonPath);
					Chat.send(Chat.header().append(Chat.ok(Lang.get("bedrockbridge.chat.tunnel_restored"))));
					PlayitStatus.set(PlayitStatus.ONLINE, lastAnnouncedEndpoint.get() == null ? Lang.get("bedrockbridge.status.reconnected") : lastAnnouncedEndpoint.get());
				} catch (IOException e) {
					BedrockBridge.LOGGER.error("Daemon restart failed", e);
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
				BedrockBridge.LOGGER.error("Failed to ensure Playit tunnel", e);
				PlayitStatus.set(PlayitStatus.ERROR, Lang.get("bedrockbridge.status.tunnel_api_failed"));
				Chat.error(Lang.get("bedrockbridge.chat.tunnel_create_failed_prefix") + e.getMessage(),
						Lang.get("bedrockbridge.chat.tunnel_create_manual_hint"));
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

	// Pure-Java claim flow. No playit-cli spawn — the daemon is the only binary
	// we need per platform. See PlayitClaim for the /claim/setup and
	// /claim/exchange endpoint details.
	private void runClaimFlow() throws IOException, InterruptedException {
		PlayitStatus.set(PlayitStatus.CLAIMING, Lang.get("bedrockbridge.status.open_link_browser"));
		claimThread = Thread.currentThread();
		String code = PlayitClaim.generateCode();
		BedrockBridge.LOGGER.info("Playit claim code generated: {}", code);
		announceClaimUrl(PlayitClaim.claimUrl(code));

		PlayitClaim.waitForUserAcceptance(code);
		String secret = PlayitClaim.exchangeForSecret(code);
		claimThread = null;
		if (!secret.matches("[0-9a-fA-F]{64}")) {
			throw new IOException("Playit secret has unexpected format: " + secret.length() + " chars");
		}
		Files.writeString(secretPath, "secret_key = \"" + secret + "\"\n", StandardCharsets.UTF_8);
		BedrockBridge.LOGGER.info("Playit secret saved to {}", secretPath);
		PlayitStatus.set(PlayitStatus.BOOTSTRAPPING, Lang.get("bedrockbridge.status.connecting_tunnel"));
		Chat.send(Chat.header().append(Chat.ok(Lang.get("bedrockbridge.chat.claim_accepted"))));
	}

	private void launchDaemon(Path daemonPath) throws IOException {
		// Only on Linux/Mac do we delete the old socket file; on Windows the "socket"
		// is a named pipe that the daemon registers on startup (not a file).
		if (!daemonSocketArg.startsWith("\\\\")) {
			Files.deleteIfExists(Path.of(daemonSocketArg));
		}
		// No -l: daemon writes to stdout/stderr, which we merge and read line-by-line below.
		ProcessBuilder pb = new ProcessBuilder(daemonPath.toString(),
				"--secret-path", secretPath.toString(),
				"--socket-path", daemonSocketArg)
				.redirectErrorStream(true);
		daemonProcess = pb.start();
		BedrockBridge.LOGGER.info("Playit daemon started (pid={}).", daemonProcess.pid());

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
			BedrockBridge.LOGGER.warn("Playit daemon reader terminated: {}", e.getMessage());
		}
	}

	public synchronized void stop() {
		wantDaemonAlive = false; // stop the watchdog before killing the process
		if (watchdogThread != null) {
			watchdogThread.interrupt();
			watchdogThread = null;
		}
		// The claim flow is HTTP polling; interrupting the thread aborts it cleanly.
		Thread claim = claimThread;
		if (claim != null) {
			claim.interrupt();
			claimThread = null;
		}
		if (daemonProcess != null) {
			daemonProcess.destroy();
			BedrockBridge.LOGGER.info("Playit daemon stopped.");
			daemonProcess = null;
		}
		lastAnnouncedEndpoint.set(null);
		PlayitStatus.set(PlayitStatus.IDLE, "");
	}

	private void announceClaimUrl(String url) {
		// First time the user runs the mod: they need to authorize the agent.
		// Show the big clickable link plus a minimal instruction.
		Chat.send(Chat.header().append(Chat.warn(Lang.get("bedrockbridge.chat.first_time_authorize"))));
		MutableComponent line = Chat.muted(Lang.get("bedrockbridge.chat.open_and_accept")).append(Chat.link(url));
		Chat.send(line);
	}

	private void announceEndpoint(String endpoint) {
		PlayitStatus.set(PlayitStatus.ONLINE, endpoint);
		// Replaces the "Internet: preparing tunnel..." placeholder with the real endpoint.
		MutableComponent line = Chat.label(Lang.get("bedrockbridge.chat.label.internet"))
				.append(Chat.copyable(endpoint))
				.append(Component.literal("  "))
				.append(Chat.muted(Lang.get("bedrockbridge.chat.click_to_copy_short")));
		Chat.send(line);
	}
}
