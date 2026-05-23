package com.minecraftbridge.client.playit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minecraftbridge.BedrockBridge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

// Pure-Java implementation of the Playit claim flow. Replaces the playit-cli
// spawn so we don't depend on a separate CLI binary (Windows doesn't publish
// one — only the daemon). Endpoints validated against api.playit.gg:
// /claim/setup (polling) and /claim/exchange (one-shot).
final class PlayitClaim {

	private static final String API_URL = "https://api.playit.gg";
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();
	private static final SecureRandom RNG = new SecureRandom();

	private PlayitClaim() {}

	// The official CLI uses 5 bytes (10 hex chars). Same format so the server accepts it.
	static String generateCode() {
		byte[] buf = new byte[5];
		RNG.nextBytes(buf);
		return HexFormat.of().formatHex(buf);
	}

	static String claimUrl(String code) {
		return "https://playit.gg/claim/" + code;
	}

	// Blocks until the user accepts the agent in the browser, or throws if they
	// reject it. Polls /claim/setup every 1s.
	static void waitForUserAcceptance(String code) throws IOException, InterruptedException {
		while (true) {
			JsonObject body = new JsonObject();
			body.addProperty("code", code);
			body.addProperty("agent_type", "self-managed");
			body.addProperty("version", "BedrockBridge " + BedrockBridge.MOD_VERSION);

			String status = postClaim("/claim/setup", body);
			switch (status) {
				case "WaitingForUserVisit", "WaitingForUser" -> {
					Thread.sleep(1000);
				}
				case "UserAccepted" -> { return; }
				case "UserRejected" -> throw new IOException("User rejected the agent authorization.");
				default -> {
					BedrockBridge.LOGGER.warn("Unexpected claim/setup status: {}", status);
					Thread.sleep(2000);
				}
			}
		}
	}

	// Once UserAccepted arrives, this endpoint returns the secret_key. Returns
	// the 64-char hex secret ready to be written to playit.toml.
	static String exchangeForSecret(String code) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("code", code);
		// Response shape: {status:"success", data:{secret_key:"..."}}
		JsonObject data = postClaimObject("/claim/exchange", body);
		if (!data.has("secret_key")) {
			throw new IOException("/claim/exchange response missing secret_key: " + data);
		}
		return data.get("secret_key").getAsString();
	}

	// /claim/setup returns a plain string inside "data" (not an object).
	private static String postClaim(String path, JsonObject body) throws IOException, InterruptedException {
		HttpResponse<String> resp = httpPost(path, body.toString());
		JsonObject parsed = JsonParser.parseString(resp.body()).getAsJsonObject();
		if (!"success".equals(parsed.get("status").getAsString())) {
			throw new IOException("Playit " + path + " error: " + resp.body());
		}
		return parsed.get("data").getAsString();
	}

	// /claim/exchange returns {data: {secret_key: ...}}.
	private static JsonObject postClaimObject(String path, JsonObject body) throws IOException, InterruptedException {
		HttpResponse<String> resp = httpPost(path, body.toString());
		JsonObject parsed = JsonParser.parseString(resp.body()).getAsJsonObject();
		if (!"success".equals(parsed.get("status").getAsString())) {
			throw new IOException("Playit " + path + " error: " + resp.body());
		}
		return parsed.get("data").getAsJsonObject();
	}

	private static HttpResponse<String> httpPost(String path, String body) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL + path))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(20))
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() != 200) {
			throw new IOException("Playit " + path + " HTTP " + resp.statusCode() + ": " + resp.body());
		}
		return resp;
	}
}
