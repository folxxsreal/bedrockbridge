package com.minecraftbridge.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.net.URI;

// Consistent styling for every BedrockBridge chat message. Centralized here so
// changing the mod's voice in the future is a single-file edit.
public final class Chat {

	public static final String TAG = "[BedrockBridge]";

	private Chat() {}

	// Dispatches to the render thread automatically so callers don't have to
	// worry about threading. Geyser/Playit lifecycle runs on worker threads.
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

	// Clickable public endpoint: copies to clipboard on click, tooltip on hover.
	public static MutableComponent copyable(String text) {
		return Component.literal(text).withStyle(
				Style.EMPTY
						.withColor(ChatFormatting.GOLD)
						.withUnderlined(true)
						.withClickEvent(new ClickEvent.CopyToClipboard(text))
						.withHoverEvent(new HoverEvent.ShowText(
								Component.literal(Lang.get("bedrockbridge.chat.click_to_copy")).withStyle(ChatFormatting.GRAY))));
	}

	public static MutableComponent link(String url) {
		return Component.literal(url).withStyle(
				Style.EMPTY
						.withColor(ChatFormatting.BLUE)
						.withUnderlined(true)
						.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
						.withHoverEvent(new HoverEvent.ShowText(
								Component.literal(Lang.get("bedrockbridge.chat.click_to_open")).withStyle(ChatFormatting.GRAY))));
	}

	// Helper to send an error plus a hint on a single line.
	public static void error(String message, String hint) {
		MutableComponent c = header()
				.append(err(Lang.get("bedrockbridge.chat.error_prefix") + message))
				.append(Component.literal("  "))
				.append(muted("(" + hint + ")"));
		send(c);
	}
}
