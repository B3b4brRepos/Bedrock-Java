package com.originalsword.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Minimal Bedrock geometry (per-face UV cubes, bone hierarchy, pivots, rotations). */
public final class Geometry {
	public final float texW, texH;
	public final Map<String, Bone> bones = new LinkedHashMap<>();
	/** false = rotation matrix Rz*Ry*Rx, true = Rx*Ry*Rz. */
	public boolean xyzOrder = false;

	public static final class Bone {
		public String name, parent;
		public float[] pivot = {0, 0, 0};
		public float[] rot = {0, 0, 0};
		public final List<Cube> cubes = new ArrayList<>();
		public boolean emissive() { return name.toLowerCase().contains("illuminated"); }
	}

	private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};

	public static final class Cube {
		float[] origin, size;
		float[] pivot, rot;
		float inflate;
		// per face: u, v, w, h, rotation(deg); null if face absent
		final float[][] uv = new float[6][];
	}

	public Geometry(JsonObject root) {
		JsonObject g = root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
		JsonObject d = g.getAsJsonObject("description");
		texW = d.has("texture_width") ? d.get("texture_width").getAsFloat() : 64f;
		texH = d.has("texture_height") ? d.get("texture_height").getAsFloat() : 64f;
		for (JsonElement be : g.getAsJsonArray("bones")) {
			JsonObject bo = be.getAsJsonObject();
			Bone b = new Bone();
			b.name = bo.get("name").getAsString();
			b.parent = bo.has("parent") ? bo.get("parent").getAsString() : null;
			if (bo.has("pivot")) b.pivot = f3(bo.getAsJsonArray("pivot"));
			if (bo.has("rotation")) b.rot = f3(bo.getAsJsonArray("rotation"));
			if (bo.has("cubes")) {
				for (JsonElement ce : bo.getAsJsonArray("cubes")) b.cubes.add(parseCube(ce.getAsJsonObject()));
			}
			bones.put(b.name, b);
		}
	}

	private static float[] f3(JsonArray a) {
		return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
	}

	private static Cube parseCube(JsonObject o) {
		Cube c = new Cube();
		c.origin = f3(o.getAsJsonArray("origin"));
		c.size = f3(o.getAsJsonArray("size"));
		if (o.has("pivot")) c.pivot = f3(o.getAsJsonArray("pivot"));
		if (o.has("rotation")) c.rot = f3(o.getAsJsonArray("rotation"));
		c.inflate = o.has("inflate") ? o.get("inflate").getAsFloat() : 0f;
		if (o.get("uv").isJsonArray()) {
			JsonArray bu = o.getAsJsonArray("uv");
			float u = bu.get(0).getAsFloat(), v = bu.get(1).getAsFloat();
			float w = c.size[0], h = c.size[1], d = c.size[2];
			c.uv[0] = new float[]{u + d, v + d, w, h, 0};
			c.uv[1] = new float[]{u + d + w + d, v + d, w, h, 0};
			c.uv[2] = new float[]{u, v + d, d, h, 0};
			c.uv[3] = new float[]{u + d + w, v + d, d, h, 0};
			c.uv[4] = new float[]{u + d, v, w, d, 0};
			c.uv[5] = new float[]{u + d + w, v + d, w, -d, 0};
			return c;
		}
		JsonObject uv = o.getAsJsonObject("uv");
		for (int i = 0; i < 6; i++) {
			if (!uv.has(FACES[i])) continue;
			JsonObject f = uv.getAsJsonObject(FACES[i]);
			JsonArray p = f.getAsJsonArray("uv"), s = f.getAsJsonArray("uv_size");
			c.uv[i] = new float[]{p.get(0).getAsFloat(), p.get(1).getAsFloat(), s.get(0).getAsFloat(), s.get(1).getAsFloat(),
					f.has("uv_rotation") ? f.get("uv_rotation").getAsFloat() : 0f};
		}
		return c;
	}

	/** Mean centre (model pixels) of all cubes in a bone, before any bone transform. */
	public Vector3f centerOf(String boneName) {
		Bone b = bones.get(boneName);
		Vector3f c = new Vector3f();
		if (b == null || b.cubes.isEmpty()) return c;
		for (Cube q : b.cubes) c.add(q.origin[0] + q.size[0] / 2, q.origin[1] + q.size[1] / 2, q.origin[2] + q.size[2] / 2);
		return c.div(b.cubes.size());
	}

	// ---- bone matrices -------------------------------------------------------------------------

	public Map<String, Matrix4f> computeMatrices(Map<String, BonePose> pose, float[] rotSign, float[] staticSign, float[] posSign) {
		Map<String, Matrix4f> out = new HashMap<>();
		for (String n : bones.keySet()) matrix(n, pose, rotSign, staticSign, posSign, out);
		return out;
	}

	private Matrix4f matrix(String name, Map<String, BonePose> pose, float[] rs, float[] ss, float[] ps, Map<String, Matrix4f> memo) {
		Matrix4f m = memo.get(name);
		if (m != null) return m;
		Bone b = bones.get(name);
		Matrix4f parent = (b.parent != null && bones.containsKey(b.parent)) ? matrix(b.parent, pose, rs, ss, ps, memo) : new Matrix4f();
		BonePose bp = pose.get(name);
		float rx = b.rot[0] * ss[0], ry = b.rot[1] * ss[1], rz = b.rot[2] * ss[2];
		float px = 0, py = 0, pz = 0, sx = 1, sy = 1, sz = 1;
		if (bp != null) {
			rx += bp.rx * rs[0]; ry += bp.ry * rs[1]; rz += bp.rz * rs[2];
			px = bp.px * ps[0]; py = bp.py * ps[1]; pz = bp.pz * ps[2];
			sx = bp.sx; sy = bp.sy; sz = bp.sz;
		}
		m = new Matrix4f(parent).translate(b.pivot[0] + px, b.pivot[1] + py, b.pivot[2] + pz);
		rotate(m, rx, ry, rz);
		m.scale(sx, sy, sz).translate(-b.pivot[0], -b.pivot[1], -b.pivot[2]);
		memo.put(name, m);
		return m;
	}

	// ---- rendering -----------------------------------------------------------------------------

	/** Writes every cube of every bone accepted by {@code filter} into the vertex consumer. */
	public void render(VertexConsumer vc, Matrix4fc base, Map<String, Matrix4f> mats, float[] cubeRotSign,
					   int light, Predicate<Bone> filter) {
		Matrix4f full = new Matrix4f();
		Vector3f[] corner = new Vector3f[8];
		for (int i = 0; i < 8; i++) corner[i] = new Vector3f();
		Vector3f tmp = new Vector3f(), nrm = new Vector3f();
		for (Bone b : bones.values()) {
			if (b.cubes.isEmpty() || !filter.test(b)) continue;
			Matrix4f bm = mats.get(b.name);
			if (bm == null) continue;
			full.set(base).mul(bm);
			for (Cube c : b.cubes) {
				float inf = c.inflate;
				float x0 = c.origin[0] - inf, y0 = c.origin[1] - inf, z0 = c.origin[2] - inf;
				float x1 = c.origin[0] + c.size[0] + inf, y1 = c.origin[1] + c.size[1] + inf, z1 = c.origin[2] + c.size[2] + inf;
				Matrix4f cm = full;
				if (c.rot != null) {
					float[] pv = c.pivot != null ? c.pivot : new float[]{0, 0, 0};
					cm = new Matrix4f(full).translate(pv[0], pv[1], pv[2]);
					rotate(cm, c.rot[0] * cubeRotSign[0], c.rot[1] * cubeRotSign[1], c.rot[2] * cubeRotSign[2]);
					cm.translate(-pv[0], -pv[1], -pv[2]);
				}
				for (int i = 0; i < 8; i++) {
					tmp.set((i & 4) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 1) == 0 ? z0 : z1);
					cm.transformPosition(tmp, corner[i]);
				}
				// corner index = x*4 + y*2 + z   (0 = min, 1 = max)
				// face: TL, TR, BR, BL (as seen from outside, u to the right, v down) + outward normal
				quad(vc, c.uv[0], corner, cm, nrm, 0, 0, -1, idx(1, 1, 0), idx(0, 1, 0), idx(0, 0, 0), idx(1, 0, 0), light); // north
				quad(vc, c.uv[1], corner, cm, nrm, 0, 0, 1, idx(0, 1, 1), idx(1, 1, 1), idx(1, 0, 1), idx(0, 0, 1), light);   // south
				quad(vc, c.uv[2], corner, cm, nrm, 1, 0, 0, idx(1, 1, 1), idx(1, 1, 0), idx(1, 0, 0), idx(1, 0, 1), light);   // east
				quad(vc, c.uv[3], corner, cm, nrm, -1, 0, 0, idx(0, 1, 0), idx(0, 1, 1), idx(0, 0, 1), idx(0, 0, 0), light);  // west
				quad(vc, c.uv[4], corner, cm, nrm, 0, 1, 0, idx(0, 1, 0), idx(1, 1, 0), idx(1, 1, 1), idx(0, 1, 1), light);   // up
				quad(vc, c.uv[5], corner, cm, nrm, 0, -1, 0, idx(0, 0, 1), idx(1, 0, 1), idx(1, 0, 0), idx(0, 0, 0), light);  // down
			}
		}
	}

	private void rotate(Matrix4f m, float rx, float ry, float rz) {
		float x = (float) Math.toRadians(rx), y = (float) Math.toRadians(ry), z = (float) Math.toRadians(rz);
		if (xyzOrder) m.rotateX(x).rotateY(y).rotateZ(z);
		else m.rotateZ(z).rotateY(y).rotateX(x);
	}

	private static int idx(int x, int y, int z) { return x * 4 + y * 2 + z; }

	private void quad(VertexConsumer vc, float[] uv, Vector3f[] corner, Matrix4f cm, Vector3f nrm,
					  float nx, float ny, float nz, int tl, int tr, int br, int bl, int light) {
		if (uv == null) return;
		cm.transformDirection(nx, ny, nz, nrm).normalize();
		// uv rect in texture units, then rotated by uv_rotation (multiples of 90 deg)
		float u0 = uv[0], v0 = uv[1], u1 = uv[0] + uv[2], v1 = uv[1] + uv[3];
		float[][] t = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}}; // TL, TR, BR, BL
		int rot = ((Math.round(uv[4] / 90f) % 4) + 4) % 4;
		int[] order = {tl, tr, br, bl};
		for (int i = 0; i < 4; i++) {
			float[] st = t[(i + rot) % 4];
			Vector3f p = corner[order[i]];
			vc.addVertex(p.x, p.y, p.z)
					.setColor(255, 255, 255, 255)
					.setUv(st[0] / texW, st[1] / texH)
					.setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(light)
					.setNormal(nrm.x, nrm.y, nrm.z);
		}
	}
}
