package com.originalsword.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bedrock animation (keyframes only, no Molang - the pack does not use any). */
public final class Animation {
	public final String name;
	public final double length;
	public final boolean loop;
	public final boolean override;
	public final List<SoundKey> sounds = new ArrayList<>();
	private final Map<String, Tracks> bones = new LinkedHashMap<>();

	public record SoundKey(double time, String effect) {}

	private record Key(double time, float[] pre, float[] post, boolean catmull) {}

	private static final class Tracks {
		List<Key> rot, pos, scale;
	}

	private Animation(String name, JsonObject o) {
		this.name = name;
		this.length = o.has("animation_length") ? o.get("animation_length").getAsDouble() : 0.0;
		this.loop = o.has("loop") && o.get("loop").isJsonPrimitive() && o.get("loop").getAsBoolean();
		this.override = o.has("override_previous_animation") && o.get("override_previous_animation").getAsBoolean();

		if (o.has("bones")) {
			for (var e : o.getAsJsonObject("bones").entrySet()) {
				JsonObject b = e.getValue().getAsJsonObject();
				Tracks t = new Tracks();
				t.rot = parse(b.get("rotation"));
				t.pos = parse(b.get("position"));
				t.scale = parse(b.get("scale"));
				bones.put(e.getKey(), t);
			}
		}
		if (o.has("sound_effects")) {
			for (var e : o.getAsJsonObject("sound_effects").entrySet()) {
				double time = Double.parseDouble(e.getKey());
				JsonElement v = e.getValue();
				if (v.isJsonArray()) {
					for (JsonElement x : v.getAsJsonArray()) addSound(time, x);
				} else addSound(time, v);
			}
		}
	}

	private void addSound(double time, JsonElement x) {
		if (x.isJsonObject() && x.getAsJsonObject().has("effect"))
			sounds.add(new SoundKey(time, x.getAsJsonObject().get("effect").getAsString()));
	}

	public static Map<String, Animation> loadAll(JsonObject root) {
		Map<String, Animation> out = new LinkedHashMap<>();
		for (var e : root.entrySet()) out.put(e.getKey(), new Animation(e.getKey(), e.getValue().getAsJsonObject()));
		return out;
	}

	// ---- parsing -------------------------------------------------------------------------------

	private static float[] vec(JsonElement e) {
		if (e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			return new float[]{num(a.get(0)), num(a.get(1)), num(a.get(2))};
		}
		float n = num(e);
		return new float[]{n, n, n};
	}

	private static float num(JsonElement e) {
		var p = e.getAsJsonPrimitive();
		if (p.isNumber()) return p.getAsFloat();
		try { return Float.parseFloat(p.getAsString()); } catch (NumberFormatException ex) { return 0f; } // Molang: unsupported -> 0
	}

	private static List<Key> parse(JsonElement ch) {
		if (ch == null) return null;
		List<Key> keys = new ArrayList<>();
		if (ch.isJsonArray() || ch.isJsonPrimitive()) {
			float[] v = vec(ch);
			keys.add(new Key(0, v, v, false));
			return keys;
		}
		for (var e : ch.getAsJsonObject().entrySet()) {
			double t = Double.parseDouble(e.getKey());
			JsonElement v = e.getValue();
			if (v.isJsonObject()) {
				JsonObject o = v.getAsJsonObject();
				float[] post = o.has("post") ? vec(o.get("post")) : vec(o.get("pre"));
				float[] pre = o.has("pre") ? vec(o.get("pre")) : post;
				boolean cr = o.has("lerp_mode") && o.get("lerp_mode").getAsString().equals("catmullrom");
				keys.add(new Key(t, pre, post, cr));
			} else {
				float[] x = vec(v);
				keys.add(new Key(t, x, x, false));
			}
		}
		keys.sort(Comparator.comparingDouble(Key::time));
		return keys;
	}

	// ---- sampling ------------------------------------------------------------------------------

	private static float[] sample(List<Key> ks, double t) {
		if (ks == null || ks.isEmpty()) return null;
		Key first = ks.get(0), last = ks.get(ks.size() - 1);
		if (t <= first.time) return first.post;
		if (t >= last.time) return last.post;
		for (int i = 0; i < ks.size() - 1; i++) {
			Key k0 = ks.get(i), k1 = ks.get(i + 1);
			if (t < k0.time || t > k1.time) continue;
			double span = k1.time - k0.time;
			float f = span <= 1e-9 ? 1f : (float) ((t - k0.time) / span);
			float[] a = k0.post, b = k1.pre, out = new float[3];
			if (k0.catmull || k1.catmull) {
				float[] p0 = i > 0 ? ks.get(i - 1).post : a;
				float[] p3 = i + 2 < ks.size() ? ks.get(i + 2).pre : b;
				float f2 = f * f, f3 = f2 * f;
				for (int c = 0; c < 3; c++) {
					out[c] = 0.5f * ((2 * a[c]) + (-p0[c] + b[c]) * f
							+ (2 * p0[c] - 5 * a[c] + 4 * b[c] - p3[c]) * f2
							+ (-p0[c] + 3 * a[c] - 3 * b[c] + p3[c]) * f3);
				}
			} else {
				for (int c = 0; c < 3; c++) out[c] = a[c] + (b[c] - a[c]) * f;
			}
			return out;
		}
		return last.post;
	}

	/**
	 * Applies this animation onto {@code pose}. Rotation/position are added, scale multiplied, unless
	 * the animation overrides previous layers (then channels are replaced).
	 */
	public void apply(Map<String, BonePose> pose, double time) {
		double t = loop && length > 0 ? time % length : time;
		for (var e : bones.entrySet()) {
			BonePose bp = pose.computeIfAbsent(e.getKey(), k -> new BonePose());
			Tracks tr = e.getValue();
			float[] r = sample(tr.rot, t), p = sample(tr.pos, t), s = sample(tr.scale, t);
			if (r != null) {
				if (override) { bp.rx = r[0]; bp.ry = r[1]; bp.rz = r[2]; }
				else { bp.rx += r[0]; bp.ry += r[1]; bp.rz += r[2]; }
			}
			if (p != null) {
				if (override) { bp.px = p[0]; bp.py = p[1]; bp.pz = p[2]; }
				else { bp.px += p[0]; bp.py += p[1]; bp.pz += p[2]; }
			}
			if (s != null) {
				if (override) { bp.sx = s[0]; bp.sy = s[1]; bp.sz = s[2]; }
				else { bp.sx *= s[0]; bp.sy *= s[1]; bp.sz *= s[2]; }
			}
		}
	}
}
