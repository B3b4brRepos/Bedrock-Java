package com.originalsword;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime-tunable settings, stored in config/originalsword.json and hot-reloaded while the game runs.
 *
 * The Bedrock -> Java axis conventions could not be verified without running the game, so they are
 * exposed here. If the sword/arms look rotated, mirrored or mis-placed, try the presets below.
 */
public final class Config {
	public boolean enabled = true;
	public boolean showArms = true;
	public boolean playSounds = true;
	public String preset = "a";

	/** Extra uniform scale on top of 1/16. */
	public float scale = 1.0f;
	/** Bedrock "camera" bone position (model pixels). The scene is shifted so this point sits at the eye. */
	public float[] camera = {7.0f, 9.15625f, 13.05f};
	/** Extra offset in model pixels, applied after the camera shift. */
	public float[] offset = {0, 0, 0};

	/** Sign multipliers for animated + static rotations and for animated positions. */
	public float[] rotSign = {1, 1, 1};       // animated rotations
	public float[] staticRotSign = {1, 1, 1}; // model (bone/cube) rotations
	public float[] posSign = {1, 1, -1};
	/** Multiplier on how far the sword travels during animations. */
	public float moveScale = 1.0f;
	/** Flips of the animation motion as seen on screen (x right, y up, z toward you). Use -1 to invert an axis. */
	public float[] viewSign = {1, 1, 1};
	public boolean mirrorX = false;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("originalsword.json");
	private static long lastModified = -1;

	public static Config current = new Config();

	/** Preset table: name -> {rotSign xyz, posSign xyz, mirrorX(0/1)}. */
	private static float[] preset(String p) {
		// {animRot xyz, pos xyz, mirrorX, staticRot xyz}
		return switch (p == null ? "a" : p.toLowerCase()) {
			case "b" -> new float[]{1, 1, 1, -1, 1, 1, 0, 1, 1, 1};
			case "c" -> new float[]{-1, -1, 1, 1, 1, -1, 0, -1, -1, 1};
			case "d" -> new float[]{-1, -1, 1, -1, 1, 1, 0, -1, -1, 1};
			case "e" -> new float[]{1, -1, 1, -1, 1, 1, 0, 1, -1, 1};
			case "f" -> new float[]{1, 1, 1, 1, 1, 1, 0, 1, 1, 1};
			case "g" -> new float[]{-1, 1, -1, 1, 1, -1, 0, 1, 1, 1};
			case "h" -> new float[]{1, 1, 1, 1, -1, -1, 0, 1, 1, 1};
			default -> new float[]{1, 1, 1, 1, 1, -1, 0, 1, 1, 1};
		};
	}

	/** Re-reads the file if it changed (cheap; call about once a second). */
	public static void reloadIfChanged() {
		try {
			if (!Files.exists(FILE)) {
				writeDefault();
			}
			long m = Files.getLastModifiedTime(FILE).toMillis();
			if (m == lastModified) return;
			lastModified = m;
			JsonObject o = GSON.fromJson(Files.readString(FILE), JsonObject.class);
			Config c = new Config();
			if (o.has("enabled")) c.enabled = o.get("enabled").getAsBoolean();
			if (o.has("showArms")) c.showArms = o.get("showArms").getAsBoolean();
			if (o.has("playSounds")) c.playSounds = o.get("playSounds").getAsBoolean();
			if (o.has("preset")) c.preset = o.get("preset").getAsString();
			float[] pr = preset(c.preset);
			c.rotSign = new float[]{pr[0], pr[1], pr[2]};
			c.posSign = new float[]{pr[3], pr[4], pr[5]};
			c.mirrorX = pr[6] > 0.5f;
			c.staticRotSign = new float[]{pr[7], pr[8], pr[9]};
			if (o.has("scale")) c.scale = o.get("scale").getAsFloat();
			if (o.has("moveScale")) c.moveScale = o.get("moveScale").getAsFloat();
			if (o.has("viewSign")) c.viewSign = arr(o.getAsJsonArray("viewSign"));
			if (o.has("camera")) c.camera = arr(o.getAsJsonArray("camera"));
			if (o.has("offset")) c.offset = arr(o.getAsJsonArray("offset"));
			if (o.has("rotSign")) c.rotSign = arr(o.getAsJsonArray("rotSign"));
			if (o.has("staticRotSign")) c.staticRotSign = arr(o.getAsJsonArray("staticRotSign"));
			if (o.has("posSign")) c.posSign = arr(o.getAsJsonArray("posSign"));
			if (o.has("mirrorX")) c.mirrorX = o.get("mirrorX").getAsBoolean();
			current = c;
		} catch (Exception e) {
			OriginalSwordClient.LOGGER.warn("Could not read originalsword.json, keeping previous settings", e);
		}
	}

	private static float[] arr(JsonArray a) {
		return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
	}

	private static void writeDefault() throws Exception {
		JsonObject o = new JsonObject();
		o.addProperty("_help", "preset a-h picks Bedrock->Java axis conventions. If the sword looks wrong try b ... h. "
				+ "Optional overrides: moveScale (how far the sword travels), viewSign (3 numbers, use -1 to flip an axis of the motion), rotSign, staticRotSign, posSign. Saved changes apply live.");
		o.addProperty("enabled", true);
		o.addProperty("showArms", true);
		o.addProperty("playSounds", true);
		o.addProperty("preset", "a");
		o.addProperty("moveScale", 1.0);
		Files.createDirectories(FILE.getParent());
		Files.writeString(FILE, GSON.toJson(o));
	}
}
