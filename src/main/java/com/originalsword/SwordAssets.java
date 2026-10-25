package com.originalsword;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.originalsword.bedrock.Animation;
import com.originalsword.bedrock.Geometry;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Loads the converted Bedrock files straight from the mod jar (independent of the resource-pack system). */
public final class SwordAssets {
	public final Geometry sword, arms;
	public final Map<String, Animation> anims;

	private SwordAssets(Geometry sword, Geometry arms, Map<String, Animation> anims) {
		this.sword = sword; this.arms = arms; this.anims = anims;
	}

	public static SwordAssets load() throws Exception {
		Gson gson = new Gson();
		return new SwordAssets(
				new Geometry(read(gson, "sword.geo.json")),
				new Geometry(read(gson, "arms.geo.json")),
				Animation.loadAll(read(gson, "sword.animations.json")));
	}

	private static JsonObject read(Gson gson, String file) throws Exception {
		try (var in = SwordAssets.class.getResourceAsStream("/assets/originalsword/bedrock/" + file)) {
			if (in == null) throw new IllegalStateException("Missing " + file);
			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				return gson.fromJson(r, JsonObject.class);
			}
		}
	}
}
