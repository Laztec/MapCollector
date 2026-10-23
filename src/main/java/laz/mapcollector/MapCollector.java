// ---------------------
// | Generates images of maps and stores hashes for duplicate-detection
// | Intended to aid in collecting map art on servers
// |
// | Very hacky, palette is currently hard-coded
// ---------------------

package laz.mapcollector;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelResource;
import org.yaml.snakeyaml.Yaml;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;

public class MapCollector implements ClientModInitializer {
	private static final String FOLDER = "map-collector";
	private static final String INDEX = "maps.csv";
	private static final String CONFIG_FOLDER = "config";
	private static final String CONFIG_FILE = "map-collector.yml";
	private static final int MAP_SIZE = 128;

	private static final int[] MAP_PALETTE = new int[]{
			0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00,
			0xFFFFFF, 0xA4A8B8, 0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFDFC, 0xD87F33,
			0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99,
			0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919, 0xFAEE4D, 0x5CDBD5,
			0x4A80FF, 0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A,
			0xBA8524, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C,
			0x4C3223, 0x4C522A, 0x8E3C2E, 0x251610, 0xBD3031, 0x94405F, 0x5C191D, 0x167E86,
			0x3A8E8C, 0x562C3E, 0x14B485, 0x646464, 0xD8AF93, 0x7FA796, 0x000000, 0x000000
	};

	private enum DuplicateBehaviour { DENY, WARN, ALLOW }
	private DuplicateBehaviour duplicateBehaviour = DuplicateBehaviour.WARN;
	private int imageSize = 128;
	private String pendingHash, pendingName, pendingMapId;
	private byte[] pendingColors;

	@Override
	public void onInitializeClient() {
		loadConfig();
		ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearPending());
		ClientTickEvents.END_CLIENT_TICK.register(client -> { if (client.player == null) clearPending(); });
	}

	private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher,
								  net.minecraft.commands.CommandBuildContext registryAccess) {

		dispatcher.register(ClientCommands.literal("map")

				.then(ClientCommands.literal("save")
						.then(ClientCommands.argument("name", StringArgumentType.greedyString())
								.executes(ctx -> { saveCommand(StringArgumentType.getString(ctx, "name")); return 1; }))
						.executes(ctx -> { saveCommand(null); return 1; }))

				.then(ClientCommands.literal("check")
						.executes(ctx -> { checkCommand(); return 1; }))

				.then(ClientCommands.literal("confirm")
						.executes(ctx -> { confirmCommand(); return 1; }))

				.then(ClientCommands.literal("duplicateBehaviour")
						.then(ClientCommands.argument("mode", StringArgumentType.word())
								.suggests((ctx, builder) -> {
									List<String> options = Arrays.asList("deny", "warn", "allow");
									for (String opt : options) {
										if (opt.startsWith(builder.getRemainingLowerCase())) builder.suggest(opt);
									}
									return builder.buildFuture();
								})
								.executes(ctx -> {
									String mode = StringArgumentType.getString(ctx, "mode").toLowerCase(Locale.ROOT);
									switch (mode) {
										case "deny" -> duplicateBehaviour = DuplicateBehaviour.DENY;
										case "warn" -> duplicateBehaviour = DuplicateBehaviour.WARN;
										case "allow" -> duplicateBehaviour = DuplicateBehaviour.ALLOW;
										default -> { msg("Invalid mode. Use deny/warn/allow."); return 1; }
									}
									saveConfig();
									msg("Duplicate behaviour: " + duplicateBehaviour.name().toLowerCase());
									return 1;
								})))

				.then(ClientCommands.literal("imageSize")
						.then(ClientCommands.argument("size", StringArgumentType.word())
								.suggests((ctx, builder) -> {
									List<String> options = Arrays.asList("128", "256", "512", "1024");
									for (String opt : options) {
										if (opt.startsWith(builder.getRemainingLowerCase())) builder.suggest(opt);
									}
									return builder.buildFuture();
								})
								.executes(ctx -> {
									String size = StringArgumentType.getString(ctx, "size");
									switch (size) {
										case "128" -> imageSize = 128;
										case "256" -> imageSize = 256;
										case "512" -> imageSize = 512;
										case "1024" -> imageSize = 1024;
										default -> { msg("Invalid size. Use 128/256/512/1024."); return 1; }
									}
									saveConfig();
									msg("Image size: " + imageSize);
									return 1;
								}))));
	}

	// ---------------------
	// | Commands
	// ---------------------
	private void saveCommand(String overrideName) {
		clearPending();

		MapData mapData = getHeldMap();
		if (mapData == null) return;

		try {
			String hash = mapHash(mapData.colors);
			String name = (overrideName != null && !overrideName.isBlank()) ? overrideName : mapData.stack.getHoverName().getString();

			Path folder = getWorldFolder();
			Files.createDirectories(folder);
			Path indexPath = folder.resolve(INDEX);
			ensureHeader(indexPath);
			List<String> lines = Files.readAllLines(indexPath, StandardCharsets.UTF_8);

			boolean duplicate = lines.stream().skip(1).anyMatch(line -> {
				String[] parts = line.split(",");
				return parts.length >= 4 && parts[3].equals(hash);
			});

			if (duplicate) {
				switch (duplicateBehaviour) {
					case DENY -> { msg("Duplicate detected! Save cancelled."); return; }
					case WARN -> {
						pendingHash = hash; pendingName = name; pendingMapId = mapData.mapId;
						pendingColors = Arrays.copyOf(mapData.colors, mapData.colors.length);
						msg("Duplicate detected! Type \"/map confirm\" to save.");
						return;
					}
					case ALLOW -> {}
				}
			}

			saveMapData(folder, hash, name, mapData.mapId, mapData.colors);
		} catch (Exception e) { msg("Error: " + e.getMessage()); }
	}

	private void checkCommand() {
		MapData mapData = getHeldMap();
		if (mapData == null) return;

		try {
			String hash = mapHash(mapData.colors);
			Path indexPath = getWorldFolder().resolve(INDEX);
			if (!Files.exists(indexPath)) { msg("No map file found."); return; }
			ensureHeader(indexPath);

			List<String> lines = Files.readAllLines(indexPath, StandardCharsets.UTF_8);
			for (String line : lines.stream().skip(1).toList()) {
				String[] parts = line.split(",");
				if (parts.length >= 4 && parts[3].equals(hash)) {
					msg("Duplicate of '" + parts[1] + "' (map_" + parts[0] + ".png)");
					return;
				}
			}
			msg("This map is not in your collection!");
		} catch (Exception e) { msg("Error: " + e.getMessage()); }
	}

	private void confirmCommand() {
		if (pendingHash == null) { msg("No pending save to confirm."); return; }
		try {
			Path folder = getWorldFolder();
			Files.createDirectories(folder);
			ensureHeader(folder.resolve(INDEX));
			saveMapData(folder, pendingHash, pendingName, pendingMapId, pendingColors);
		} catch (Exception e) { msg("Error: " + e.getMessage()); }
		finally { clearPending(); }
	}

	// ---------------------
	// | Utilities
	// ---------------------
	private record MapData(ItemStack stack, MapItemSavedData state, byte[] colors, String mapId) {}

	private MapData getHeldMap() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null) return null;

		ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
		if (!(held.getItem() instanceof MapItem)) { msg("Hold a map in your main hand."); return null; }
		MapItemSavedData state = MapItem.getSavedData(held, client.level);
		if (state == null) { msg("Map data not loaded yet. Try again."); return null; }
		byte[] colors = state.colors;
		if (colors == null || colors.length != MAP_SIZE * MAP_SIZE) { msg("Map colors missing or invalid."); return null; }

		MapId idComp = held.get(DataComponents.MAP_ID);
		String mapId = (idComp != null) ? String.valueOf(idComp.id()) : "unknown";
		return new MapData(held, state, colors, mapId);
	}

	private BufferedImage mapToImage(byte[] colors) {
		BufferedImage img = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < MAP_SIZE; y++) {
			for (int x = 0; x < MAP_SIZE; x++) {
				int idx = x + y * MAP_SIZE;
				int colorByte = colors[idx] & 0xFF;
				int argb;
				if (colorByte == 0) {
					argb = 0x00000000;
				} else {
					int base = (colorByte >> 2) & 0x3F;
					int shade = colorByte & 3;
					int rgb = MAP_PALETTE[base];
					float m = switch (shade) {
						case 0 -> 0.71f;
						case 1 -> 0.86f;
						case 2 -> 1.0f;
						default -> 0.53f;
					};
					int r = (int) (((rgb >> 16) & 0xFF) * m);
					int g = (int) (((rgb >> 8) & 0xFF) * m);
					int b = (int) ((rgb & 0xFF) * m);
					argb = (0xFF << 24) | (r << 16) | (g << 8) | b;
				}
				img.setRGB(x, y, argb);
			}
		}
		return img;
	}

	private BufferedImage scaleImage(BufferedImage base, int targetSize) {
		BufferedImage scaled = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
		var g = scaled.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(base, 0, 0, targetSize, targetSize, null);
		g.dispose();
		return scaled;
	}

	private Path getWorldFolder() {
		Minecraft client = Minecraft.getInstance();
		Path base = client.gameDirectory.toPath().resolve(FOLDER);

		if (client.isLocalServer() && client.getSingleplayerServer() != null) {
			String worldName = client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).getParent().getFileName().toString();
			return base.resolve("singleplayer_" + worldName);
		} else if (client.getCurrentServer() != null) {
			String address = client.getCurrentServer().ip.split(":")[0];
			return base.resolve(address);
		} else {
			return base.resolve("unknown");
		}
	}

	private void saveMapData(Path folder, String hash, String name, String mapId, byte[] colors) throws IOException {
		Path indexPath = folder.resolve(INDEX);
		int fileId = getNextFileId(indexPath);

		Path pngPath = folder.resolve("map_" + fileId + ".png");
		BufferedImage img = mapToImage(colors);
		if (imageSize != 128) {
			img = scaleImage(img, imageSize);
		}
		try (OutputStream os = Files.newOutputStream(pngPath, StandardOpenOption.CREATE_NEW)) {
			ImageIO.write(img, "png", os);
		}

		String safeName = name.replace(",", "");
		String line = fileId + "," + safeName + "," + mapId + "," + hash;
		Files.write(indexPath, Collections.singletonList(line), StandardCharsets.UTF_8,
				StandardOpenOption.APPEND);

		Component clickable = Component.literal("Saved: '" + safeName + "' (" + pngPath.getFileName() + ")" )
				.withStyle(style -> style.withClickEvent(
						new net.minecraft.network.chat.ClickEvent.OpenFile(
								pngPath.toAbsolutePath().toString()
						)));
		msg(clickable);
	}

	private String mapHash(byte[] data) throws Exception {
		MessageDigest md = MessageDigest.getInstance("MD5");
		byte[] digest = md.digest(data);
		StringBuilder sb = new StringBuilder();
		for (byte b : digest) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	private void ensureHeader(Path indexPath) throws IOException {
		String header = "fileId,name,mapId,hash";
		if (!Files.exists(indexPath)) {
			Files.write(indexPath, Collections.singletonList(header), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE_NEW);
			return;
		}

		List<String> lines = Files.readAllLines(indexPath, StandardCharsets.UTF_8);
		if (!lines.isEmpty() && lines.get(0).equals(header)) return;

		// Preserve older collections created without a CSV header.
		lines.add(0, header);
		Files.write(indexPath, lines, StandardCharsets.UTF_8,
				StandardOpenOption.TRUNCATE_EXISTING);
	}

	private int getNextFileId(Path indexPath) throws IOException {
		List<String> lines = Files.readAllLines(indexPath, StandardCharsets.UTF_8);
		if (lines.size() <= 1) return 1;

		String[] parts = lines.get(lines.size() - 1).split(",");
		try {
			return Integer.parseInt(parts[0]) + 1;
		} catch (Exception e) {
			throw new IOException("Invalid file ID in " + INDEX, e);
		}
	}

	private void msg(Component extra) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			MutableComponent prefix = Component.literal("[Map Collector] ").withStyle(ChatFormatting.GOLD);
			MutableComponent body   = extra.copy().withStyle(ChatFormatting.WHITE);
			client.player.sendSystemMessage(prefix.append(body));
		}
	}

	private void msg(String s) {
		msg(Component.literal(s));
	}

	private void clearPending() { pendingHash = pendingName = pendingMapId = null; pendingColors = null; }

	// ---------------------
	// | Config
	// ---------------------
	private void loadConfig() {
		try {
			Path configPath = Minecraft.getInstance().gameDirectory.toPath().resolve(CONFIG_FOLDER).resolve(CONFIG_FILE);
			if (!Files.exists(configPath)) { saveConfig(); return; }
			Yaml yaml = new Yaml();
			try (var in = Files.newInputStream(configPath)) {
				Map<String, Object> data = yaml.load(in);
				if (data != null) {
					String mode = (String) data.get("duplicateBehaviour");
					if (mode != null) duplicateBehaviour = DuplicateBehaviour.valueOf(mode.toUpperCase(Locale.ROOT));

					Object sizeObj = data.get("imageSize");
					if (sizeObj instanceof Integer i && List.of(128,256,512,1024).contains(i)) {
						imageSize = i;
					}
				}
			}
		} catch (Exception e) { msg("Failed to load config."); }
	}

	private void saveConfig() {
		try {
			Path configPath = Minecraft.getInstance().gameDirectory.toPath().resolve(CONFIG_FOLDER).resolve(CONFIG_FILE);
			Files.createDirectories(configPath.getParent());
			Yaml yaml = new Yaml();
			Map<String, Object> data = new HashMap<>();
			data.put("duplicateBehaviour", duplicateBehaviour.name().toLowerCase());
			data.put("imageSize", imageSize);
			Files.writeString(configPath, yaml.dump(data), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (Exception e) { msg("Failed to save config."); }
	}
}