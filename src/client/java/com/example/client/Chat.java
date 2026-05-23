package com.example.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.net.URI;

// Estilo consistente para todos los mensajes que BedrockBridge muestra en chat.
// Centralizado acá para que el día de mañana cambiar la voz del mod sea un solo archivo.
public final class Chat {

	public static final String TAG = "[BedrockBridge]";

	private Chat() {}

	// Despacha al render thread automáticamente, así los callers no se preocupan
	// por threading. Geyser/Playit lifecycle corre en hilos de worker.
	public static void send(Component component) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) mc.player.sendSystemMessage(component);
		});
	}

	public static MutableComponent header() {
		return Component.literal(TAG + " ").withStyle(ChatFormatting.AQUA);
	}

	public static MutableComponent label(String text) {
		return Component.literal("  " + text + ": ").withStyle(ChatFormatting.GRAY);
	}

	public static MutableComponent value(Object v) {
		return Component.literal(String.valueOf(v)).withStyle(ChatFormatting.GOLD);
	}

	public static MutableComponent muted(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	public static MutableComponent ok(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GREEN);
	}

	public static MutableComponent warn(String text) {
		return Component.literal(text).withStyle(ChatFormatting.YELLOW);
	}

	public static MutableComponent err(String text) {
		return Component.literal(text).withStyle(ChatFormatting.RED);
	}

	// Endpoint público clickeable: copia al portapapeles en click, muestra tooltip en hover.
	public static MutableComponent copyable(String text) {
		return Component.literal(text).withStyle(
				Style.EMPTY
						.withColor(ChatFormatting.GOLD)
						.withUnderlined(true)
						.withClickEvent(new ClickEvent.CopyToClipboard(text))
						.withHoverEvent(new HoverEvent.ShowText(
								Component.literal("Click para copiar").withStyle(ChatFormatting.GRAY))));
	}

	public static MutableComponent link(String url) {
		return Component.literal(url).withStyle(
				Style.EMPTY
						.withColor(ChatFormatting.BLUE)
						.withUnderlined(true)
						.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
						.withHoverEvent(new HoverEvent.ShowText(
								Component.literal("Click para abrir").withStyle(ChatFormatting.GRAY))));
	}

	// Helper para enviar un error con sugerencia en una sola línea.
	public static void error(String message, String hint) {
		MutableComponent c = header()
				.append(err("Error: " + message))
				.append(Component.literal("  "))
				.append(muted("(" + hint + ")"));
		send(c);
	}
}
