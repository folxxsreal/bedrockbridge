package com.minecraftbridge.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minecraftbridge.BedrockBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

// Picks between bundled en_us / es_mx string tables based on the client's
// configured language. Any es_* variant (es_es, es_ar, es_uy, ...) gets the
// Spanish strings; everything else falls back to English. Done programmatically
// so we don't ship 20 identical copies of the Spanish file for every locale.
public final class Lang {

	private static final Map<String, String> EN = load("en_us");
	private static final Map<String, String> ES = load("es_mx");

	private Lang() {}

	public static String get(String key, Object... args) {
		Map<String, String> table = isSpanish() ? ES : EN;
		String template = table.getOrDefault(key, EN.getOrDefault(key, key));
		if (args.length == 0) return template;
		try {
			return String.format(template, args);
		} catch (Exception e) {
			return template;
		}
	}

	public static MutableComponent text(String key, Object... args) {
		return Component.literal(get(key, args));
	}

	private static boolean isSpanish() {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null) return false;
			String lang = mc.options.languageCode;
			return lang != null && lang.toLowerCase().startsWith("es");
		} catch (Throwable t) {
			return false;
		}
	}

	private static Map<String, String> load(String code) {
		Map<String, String> out = new HashMap<>();
		String resource = "/assets/" + BedrockBridge.MOD_ID + "/lang/" + code + ".json";
		try (InputStream in = Lang.class.getResourceAsStream(resource)) {
			if (in == null) return out;
			String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
			obj.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
		} catch (IOException ignored) {}
		return out;
	}
}
