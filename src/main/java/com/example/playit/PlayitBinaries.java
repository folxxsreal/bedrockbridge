package com.example.playit;

import com.example.BedrockBridge;

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

// Lazy-downloads the Playit daemon + CLI binaries for the current OS/arch.
// SHAs are hardcoded — a release-pin failure is preferred over silently running
// a different binary than what we audited.
public final class PlayitBinaries {

	public static final String RELEASE_TAG = "v1.0.4";
	private static final String RELEASE_URL_BASE =
			"https://github.com/playit-cloud/playit-agent/releases/download/" + RELEASE_TAG + "/";

	public record Binary(String name, String sha256) {
		public String url() { return RELEASE_URL_BASE + name; }
	}

	// Platforms we support today. Add new ones by pinning SHAs from the v1.0.4 release.
	public record Platform(Binary daemon, Binary cli) {}

	private static final Platform LINUX_AMD64 = new Platform(
			new Binary("playit-linux-amd64", "abc88684b1b535c871fb3fb67a2a8cde08ce99bf80b1cafc00b0b01ab5c3956c"),
			new Binary("playit-cli-linux-amd64", "4d8cabb16ec1567247f42d39768a414f84ee2bf894491a4a777275e568f97228")
	);

	public record InstalledBinaries(Path daemon, Path cli) {}

	private PlayitBinaries() {}

	// Returns paths to both binaries, downloading them if missing.
	// Idempotent: re-checks SHA on existing files and re-downloads if it doesn't match.
	public static InstalledBinaries ensureInstalled(Path binDir) throws IOException {
		Platform platform = detectPlatform();
		Files.createDirectories(binDir);
		Path daemonPath = binDir.resolve(platform.daemon().name());
		Path cliPath = binDir.resolve(platform.cli().name());
		ensureBinary(platform.daemon(), daemonPath);
		ensureBinary(platform.cli(), cliPath);
		return new InstalledBinaries(daemonPath, cliPath);
	}

	private static Platform detectPlatform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		if (os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"))) {
			return LINUX_AMD64;
		}
		throw new UnsupportedOperationException(
				"Plataforma no soportada por BedrockBridge para Playit: os=" + os + " arch=" + arch
				+ " (por ahora solo Linux amd64; TODO Windows x86_64 y aarch64).");
	}

	private static void ensureBinary(Binary spec, Path target) throws IOException {
		if (Files.exists(target) && sha256(target).equals(spec.sha256())) {
			return;
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
			// Windows: no POSIX perms, ejecutables ya son ejecutables por extensión.
		}
	}
}
