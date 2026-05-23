package com.example.client.playit;

import com.example.BedrockBridge;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

// Client for the Playit "v2" REST API at api.playit.gg. The older mod referenced
// api.playit.cloud which now only accepts MinecraftJava — Bedrock tunnels must go
// through this endpoint. All responses are wrapped as {status, data}.
final class PlayitApi {

	private static final String API_URL = "https://api.playit.gg";
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	private PlayitApi() {}

	record Tunnel(String id, String agentId, String displayAddress, int portStart, int portEnd, String allocStatus) {}

	// Returns an existing minecraft-bedrock UDP tunnel pointing at our local port if one exists,
	// otherwise creates one and polls until allocation completes.
	static Tunnel ensureBedrockTunnel(String secret, int localPort) throws IOException, InterruptedException {
		for (Tunnel t : listBedrockUdpTunnels(secret, localPort)) {
			if ("allocated".equals(t.allocStatus()) && t.displayAddress() != null && !t.displayAddress().isEmpty()) {
				BedrockBridge.LOGGER.info("Túnel Playit existente: {} (id={})", t.displayAddress(), t.id());
				return t;
			}
		}

		String agentId = firstAgentId(secret);
		if (agentId == null) {
			throw new IOException("No se pudo encontrar agent_id (¿el daemon todavía no se registró?). Reintenta en 5 s.");
		}
		BedrockBridge.LOGGER.info("Creando túnel Bedrock UDP→{} via API (agent={}).", localPort, agentId);
		createBedrockTunnel(secret, agentId, localPort);

		for (int attempt = 0; attempt < 30; attempt++) {
			Thread.sleep(2000);
			for (Tunnel t : listBedrockUdpTunnels(secret, localPort)) {
				if ("allocated".equals(t.allocStatus()) && t.displayAddress() != null && !t.displayAddress().isEmpty()) {
					return t;
				}
			}
		}
		throw new IOException("El túnel se creó pero no se asignó endpoint público en 60 s.");
	}

	private static java.util.List<Tunnel> listBedrockUdpTunnels(String secret, int wantLocalPort) throws IOException, InterruptedException {
		JsonObject resp = post("/tunnels/list", new JsonObject(), secret);
		java.util.List<Tunnel> out = new java.util.ArrayList<>();
		JsonArray tunnels = resp.has("tunnels") ? resp.getAsJsonArray("tunnels") : new JsonArray();
		for (JsonElement el : tunnels) {
			JsonObject t = el.getAsJsonObject();
			if (!"udp".equals(stringOr(t, "port_type", ""))) continue;
			if (!"minecraft-bedrock".equals(stringOr(t, "tunnel_type", ""))) continue;
			JsonObject origin = t.getAsJsonObject("origin");
			if (origin == null) continue;
			JsonObject originData = origin.getAsJsonObject("data");
			int originLocalPort = originData != null && originData.has("local_port")
					? originData.get("local_port").getAsInt() : -1;
			if (originLocalPort != wantLocalPort) continue;

			String agentId = originData != null ? stringOr(originData, "agent_id", null) : null;
			JsonObject alloc = t.getAsJsonObject("alloc");
			String allocStatus = alloc != null ? stringOr(alloc, "status", "") : "";
			String displayAddress = null;
			int portStart = 0, portEnd = 0;
			if (alloc != null && alloc.has("data")) {
				JsonObject allocData = alloc.getAsJsonObject("data");
				String domain = stringOr(allocData, "assigned_domain", null);
				portStart = allocData.has("port_start") ? allocData.get("port_start").getAsInt() : 0;
				portEnd = allocData.has("port_end") ? allocData.get("port_end").getAsInt() : 0;
				if (domain != null) displayAddress = domain + ":" + portStart;
			}
			out.add(new Tunnel(stringOr(t, "id", ""), agentId, displayAddress, portStart, portEnd, allocStatus));
		}
		return out;
	}

	private static String firstAgentId(String secret) throws IOException, InterruptedException {
		JsonObject resp = post("/tunnels/list", new JsonObject(), secret);
		// Easiest path to the agent UUID: pull it from any tunnel's origin.
		JsonArray tunnels = resp.has("tunnels") ? resp.getAsJsonArray("tunnels") : new JsonArray();
		for (JsonElement el : tunnels) {
			JsonObject origin = el.getAsJsonObject().getAsJsonObject("origin");
			if (origin == null) continue;
			JsonObject originData = origin.getAsJsonObject("data");
			if (originData != null && originData.has("agent_id")) return originData.get("agent_id").getAsString();
		}
		// Fallback: ask /agents/list (requires no body).
		try {
			JsonObject agents = post("/agents/list", new JsonObject(), secret);
			JsonArray arr = agents.has("agents") ? agents.getAsJsonArray("agents") : new JsonArray();
			if (!arr.isEmpty()) return stringOr(arr.get(0).getAsJsonObject(), "id", null);
		} catch (IOException ignored) {}
		return null;
	}

	private static void createBedrockTunnel(String secret, String agentId, int localPort) throws IOException, InterruptedException {
		JsonObject originData = new JsonObject();
		originData.addProperty("agent_id", agentId);
		originData.addProperty("local_ip", "127.0.0.1");
		originData.addProperty("local_port", localPort);

		JsonObject origin = new JsonObject();
		origin.addProperty("type", "agent");
		origin.add("data", originData);

		JsonObject req = new JsonObject();
		req.addProperty("name", "BedrockBridge");
		req.addProperty("tunnel_type", "minecraft-bedrock");
		req.addProperty("port_type", "udp");
		req.addProperty("port_count", 1);
		req.add("origin", origin);
		req.addProperty("enabled", true);
		req.add("alloc", null);
		req.add("firewall_id", null);
		req.add("proxy_protocol", null);

		post("/tunnels/create", req, secret);
	}

	private static JsonObject post(String path, JsonObject body, String secret) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL + path))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.header("Authorization", "agent-key " + secret)
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.timeout(Duration.ofSeconds(20))
				.build();
		HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() != 200) {
			throw new IOException("Playit API " + path + " HTTP " + resp.statusCode() + ": " + resp.body());
		}
		JsonElement parsed = JsonParser.parseString(resp.body());
		if (!parsed.isJsonObject()) return new JsonObject();
		JsonObject obj = parsed.getAsJsonObject();
		String status = stringOr(obj, "status", "");
		if (!"success".equals(status)) {
			throw new IOException("Playit API " + path + " error: " + resp.body());
		}
		JsonElement data = obj.get("data");
		return data != null && data.isJsonObject() ? data.getAsJsonObject() : new JsonObject();
	}

	private static String stringOr(JsonObject o, String key, String def) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
	}
}
