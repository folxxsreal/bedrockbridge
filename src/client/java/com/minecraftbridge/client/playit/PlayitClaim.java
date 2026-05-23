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

// Implementación Java pura del claim flow de Playit. Reemplaza el spawn de
// playit-cli, así no dependemos de un binario CLI separado (Windows no lo
// publica en releases, solo el daemon). Endpoints validados contra
// api.playit.gg: /claim/setup (polling) y /claim/exchange (one-shot).
final class PlayitClaim {

	private static final String API_URL = "https://api.playit.gg";
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();
	private static final SecureRandom RNG = new SecureRandom();

	private PlayitClaim() {}

	// El CLI oficial usa 5 bytes (10 hex). Mismo formato para que el server lo acepte.
	static String generateCode() {
		byte[] buf = new byte[5];
		RNG.nextBytes(buf);
		return HexFormat.of().formatHex(buf);
	}

	static String claimUrl(String code) {
		return "https://playit.gg/claim/" + code;
	}

	// Bloquea hasta que el usuario acepte el agente en el browser, o lance una
	// excepción si lo rechaza. Hace polling cada 1s contra /claim/setup.
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
				case "UserRejected" -> throw new IOException("El usuario rechazó la autorización del agente.");
				default -> {
					BedrockBridge.LOGGER.warn("Estado inesperado de claim/setup: {}", status);
					Thread.sleep(2000);
				}
			}
		}
	}

	// Una vez que UserAccepted llegó, este endpoint devuelve el secret_key.
	// Devuelve el secret hex de 64 chars listo para guardar en playit.toml.
	static String exchangeForSecret(String code) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("code", code);
		// La respuesta tiene shape {status:"success", data:{secret_key:"..."}}
		JsonObject data = postClaimObject("/claim/exchange", body);
		if (!data.has("secret_key")) {
			throw new IOException("Respuesta de /claim/exchange sin secret_key: " + data);
		}
		return data.get("secret_key").getAsString();
	}

	// /claim/setup devuelve string plano dentro de "data" (no un objeto).
	private static String postClaim(String path, JsonObject body) throws IOException, InterruptedException {
		HttpResponse<String> resp = httpPost(path, body.toString());
		JsonObject parsed = JsonParser.parseString(resp.body()).getAsJsonObject();
		if (!"success".equals(parsed.get("status").getAsString())) {
			throw new IOException("Playit " + path + " error: " + resp.body());
		}
		return parsed.get("data").getAsString();
	}

	// /claim/exchange devuelve {data: {secret_key: ...}}.
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
