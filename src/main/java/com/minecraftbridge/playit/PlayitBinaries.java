package com.minecraftbridge.playit;

import com.minecraftbridge.BedrockBridge;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

// Descarga lazy del daemon de Playit por OS/arch. Solo daemon — el claim flow
// se hace en Java puro vía PlayitClaim, así no necesitamos el binario CLI
// (que Windows ni siquiera publica en releases).
public final class PlayitBinaries {

	public static final String RELEASE_TAG = "v1.0.4";
	private static final String RELEASE_URL_BASE =
			"https://github.com/playit-cloud/playit-agent/releases/download/" + RELEASE_TAG + "/";

	public record DaemonSpec(String name, String sha256) {
		public String url() { return RELEASE_URL_BASE + name; }
	}

	// Matriz de plataformas soportadas. Agregar una entrada nueva = nada más
	// pinear el SHA256 desde el release v1.0.4 y agregar el case en detect().
	private static final DaemonSpec LINUX_AMD64 = new DaemonSpec(
			"playit-linux-amd64",
			"abc88684b1b535c871fb3fb67a2a8cde08ce99bf80b1cafc00b0b01ab5c3956c");
	private static final DaemonSpec LINUX_AARCH64 = new DaemonSpec(
			"playit-linux-aarch64",
			"d09670f4ccab2a846509109f69ccd236c1031f95110542a2d5cf53d7bbcd2686");
	private static final DaemonSpec WINDOWS_X86_64 = new DaemonSpec(
			"playit-windows-x86_64-signed.exe",
			"88000d40af7a8e5a0548d27d71c0cad7d5f4b91fd85f6e9297237ac8b57fbdc9");

	private PlayitBinaries() {}

	// Returns path to the daemon, downloading it if missing or if the existing
	// file's SHA doesn't match (catches corruption / accidental version mix).
	public static Path ensureDaemon(Path binDir) throws IOException {
		DaemonSpec spec = detectPlatform();
		Files.createDirectories(binDir);
		Path target = binDir.resolve(spec.name());
		if (Files.exists(target) && sha256(target).equals(spec.sha256())) {
			return target;
		}
		BedrockBridge.LOGGER.info("Descargando {} desde {} ...", spec.name(), spec.url());
		download(spec.url(), target);
		String actual = sha256(target);
		if (!actual.equals(spec.sha256())) {
			Files.deleteIfExists(target);
			throw new IOException("SHA256 mismatch para " + spec.name()
					+ " — esperado " + spec.sha256() + " recibido " + actual);
		}
		makeExecutable(target);
		BedrockBridge.LOGGER.info("{} listo en {} (SHA256 verificado).", spec.name(), target);
		return target;
	}

	private static DaemonSpec detectPlatform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		if (os.contains("linux")) {
			if (arch.equals("amd64") || arch.equals("x86_64")) return LINUX_AMD64;
			if (arch.equals("aarch64") || arch.equals("arm64")) return LINUX_AARCH64;
		} else if (os.contains("win")) {
			if (arch.equals("amd64") || arch.equals("x86_64")) return WINDOWS_X86_64;
		} else if (os.contains("mac") || os.contains("darwin")) {
			throw new UnsupportedOperationException(
					"Playit no publica binarios oficiales de macOS. Por ahora BedrockBridge "
					+ "no puede levantar el túnel automáticamente en Mac. Geyser y Floodgate "
					+ "siguen funcionando para conexiones LAN locales.");
		}
		throw new UnsupportedOperationException(
				"Plataforma no soportada por BedrockBridge para Playit: os=" + os + " arch=" + arch
				+ ". Soportadas: Linux (amd64, aarch64) y Windows (x86_64).");
	}

	private static void download(String url, Path target) throws IOException {
		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.ALWAYS)
				.build();
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
		try {
			HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
			if (resp.statusCode() != 200) {
				throw new IOException("HTTP " + resp.statusCode() + " al bajar " + url);
			}
			try (InputStream in = resp.body()) {
				Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrumpido al bajar " + url, e);
		}
	}

	private static String sha256(Path path) throws IOException {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			try (InputStream in = Files.newInputStream(path)) {
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
			}
			return HexFormat.of().formatHex(md.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
		}
	}

	private static void makeExecutable(Path path) throws IOException {
		try {
			Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
			perms.add(PosixFilePermission.OWNER_EXECUTE);
			perms.add(PosixFilePermission.GROUP_EXECUTE);
			perms.add(PosixFilePermission.OTHERS_EXECUTE);
			Files.setPosixFilePermissions(path, perms);
		} catch (UnsupportedOperationException e) {
			// Windows: no hay POSIX perms, .exe es ejecutable por la extensión.
		}
	}
}
