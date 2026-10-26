package com.originalsword;

import com.mojang.blaze3d.vertex.PoseStack;
import com.originalsword.bedrock.BonePose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Drives the Bedrock sword animations as a motion layered on top of vanilla's own first-person sword.
 * Vanilla still draws the item (so texture, enchant glint, offhand, left-handed mode all keep working);
 * each frame we work out how far the animated sword bone has moved from its idle pose and apply that
 * delta to the held sword.
 */
public final class SwordRenderer {
	private static final Item[] SWORDS = {
			Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
			Items.GOLDEN_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD};
	private static final String SWORD_BONE = "bone";
	private static final String[] TEX = {"wooden", "stone", "iron", "golden", "diamond", "netherite"};
	private static final int FULL_BRIGHT = 15728880;

	// Idle placement in view space (model pixels, camera at the origin looking down -z), fitted to the
	// original add-on's idle pose: sword local -> view = T(SW_T) Rx(TILT) Ry(YAW) Rz(ROLL) T(-centre).
	private static final float TILT = -0.626f, ROLL = -0.836f, YAW = -0.196f;
	private static final Vector3f SW_T = new Vector3f(1.843f, -0.735f, -24.562f);
	// Forearm: view = T(ARM_T) Rz(a) Ry(b) Rx(c) T(-6.04688, 0, 0)
	private static final Vector3f ARM_T = new Vector3f(2.588f, -6.405f, -17.517f);
	private static final float ARM_A = 2.15f, ARM_B = -1.363f, ARM_C = 2.037f;

	private static SwordAssets assets;
	private static SwordAnimator animator;
	private static long lastNanos = 0, lastCfgCheck = 0;
	private static boolean lastAttackDown = false;
	private static java.lang.reflect.Method isPausedMethod;
	private static boolean pausedLookedUp;

	/** Result of the last {@link #frame}. */
	public static boolean active;
	/** Sword-local motion relative to idle (model pixels). */
	private static final Matrix4f motion = new Matrix4f();
	private static final Matrix4f motionArm = new Matrix4f();
	private static final double TUNER_PERIOD = 2.6;
	private static long tunerStart = 0;
	private static int tunerSlot = -1;
	private static boolean tunerEdge = false, lastUseDown = false;
	private static double tunerClock = 0;
	private static Vector3f swordCentre = new Vector3f();

	private SwordRenderer() {}

	public static void init(SwordAssets a) {
		assets = a;
		animator = new SwordAnimator(a, SwordRenderer::playSound);
	}

	private static int itemCode(ItemStack s) {
		for (int i = 0; i < SWORDS.length; i++) if (s.is(SWORDS[i])) return i + 1;
		return 0;
	}

	public static boolean isSword(ItemStack s) { return s != null && itemCode(s) != 0; }

	private static void playSound(String effect) {
		if (!Config.current.playSounds) return;
		String name = switch (effect) {
			case "sword_swing1" -> "swing1";
			case "sword_swing2" -> "swing2";
			case "sword_swing3" -> "swing3";
			case "sword_strong" -> "strong";
			case "sword_draw" -> "draw";
			case "sword_put_away" -> "put_away";
			case "sword_inspect" -> "inspect";
			default -> null;
		};
		if (name == null) return;
		SoundEvent ev = new SoundEvent(Identifier.fromNamespaceAndPath(OriginalSwordClient.MOD_ID, "sword." + name), Optional.empty());
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev, 1.0F, 1.0F));
	}

	/** Looked up reflectively because pause handling moved around in 26.2/26.3. */
	private static boolean isPaused() {
		try {
			if (!pausedLookedUp) {
				pausedLookedUp = true;
				try { isPausedMethod = Minecraft.class.getMethod("isPaused"); } catch (NoSuchMethodException ignored) {}
			}
			return isPausedMethod != null && (Boolean) isPausedMethod.invoke(Minecraft.getInstance());
		} catch (Throwable t) {
			return false;
		}
	}

	/** Advances the animation and computes this frame's motion. Call once per rendered frame. */
	public static void frame(LocalPlayer player) {
		active = false;
		if (assets == null) return;
		long now = System.nanoTime();
		if (now - lastCfgCheck > 1_000_000_000L) { Config.reloadIfChanged(); lastCfgCheck = now; }
		Config cfg = Config.current;

		boolean down = Minecraft.getInstance().options.keyAttack.isDown();
		boolean edge = down && !lastAttackDown;
		lastAttackDown = down;

		double dt = lastNanos == 0 ? 0 : Math.min((now - lastNanos) / 1e9, 0.1);
		lastNanos = now;
		if (isPaused()) dt = 0;

		int mainCode = itemCode(player.getMainHandItem());
		int offCode = itemCode(player.getOffhandItem());
		int item = cfg.enabled ? (mainCode != 0 ? mainCode : offCode) : 0;
		boolean quiet = player.getDeltaMovement().horizontalDistanceSqr() < 0.004
				&& !player.isUsingItem() && !down;
		boolean autoEdge = tunerEdge;
		tunerEdge = false;
		animator.update(dt, item, edge || autoEdge, quiet);
		if (item == 0) return;

		// --- calibration: 96 conventions = 48 signed axis permutations x (ZYX | XYZ) rotation order ---
		int conv = cfg.conv;
		if (cfg.tuner) {
			// swings loop by themselves; press SEC (use) to move to the next convention
			boolean use = Minecraft.getInstance().options.keyUse.isDown();
			if (use && !lastUseDown) {
				tunerSlot = (tunerSlot + 1) % 100;
				say("OriginalSword test " + tunerSlot + " / 100   (SEC = next)");
			}
			lastUseDown = use;
			if (tunerSlot < 0) { tunerSlot = 98; say("OriginalSword test 98 / 100   (SEC = next)"); }
			conv = tunerSlot;
			tunerClock += dt;
			if (cfg.autoSwing && tunerClock >= 2.0 && animator.restingNow()) { tunerClock = 0; tunerEdge = true; }
		}
		conv = Math.floorMod(conv, 100);
		boolean legacy = conv == 96 || conv == 97;
		boolean fitted = conv >= 98;
		assets.sword.xyzOrder = conv >= 48 && conv < 96;
		float[] one = {1, 1, 1};
		float[] lsg = legacy ? new float[]{1, -1, 1} : one;
		float[] rsg = legacy ? lsg : (fitted ? (conv == 98 ? new float[]{1, -1, 1} : new float[]{-1, -1, -1}) : one);
		Map<String, BonePose> cur = animator.pose();
		Map<String, BonePose> idle = new HashMap<>();
		assets.anims.get("static_idle").apply(idle, 0);
		Map<String, Matrix4f> mc = assets.sword.computeMatrices(cur, rsg, lsg, one);
		Map<String, Matrix4f> mr = assets.sword.computeMatrices(idle, rsg, lsg, one);
		Matrix4f cm = mc.get(SWORD_BONE), rm = mr.get(SWORD_BONE);
		if (cm == null || rm == null) return;

		// model-space change idle -> now, carried into view space through a signed axis permutation
		Matrix4f delta = new Matrix4f(cm).mul(new Matrix4f(rm).invert());
		if (cfg.moveScale != 1.0f) {
			Vector3f tr = delta.getTranslation(new Vector3f()).mul(cfg.moveScale);
			delta.setTranslation(tr);
		}
		swordCentre = assets.sword.centerOf(SWORD_BONE);
		swordCentre = assets.sword.centerOf(SWORD_BONE);
		if (legacy) {
			// build 1.1.0's motion maths: translation of the sword centre + rotation about the vanilla hold point
			Vector3f c0 = rm.transformPosition(new Vector3f(swordCentre), new Vector3f());
			Vector3f moved = delta.transformPosition(new Vector3f(c0), new Vector3f());
			Vector3f tl = moved.sub(c0);
			float len = tl.length();
			if (len > 19.2f) tl.mul(19.2f / len);
			Matrix3f r3 = new Matrix3f();
			delta.get3x3(r3);
			for (int ci = 0; ci < 3; ci++) { Vector3f col = r3.getColumn(ci, new Vector3f()).normalize(); r3.setColumn(ci, col); }
			Vector3f pv = new Vector3f(8.96f, -7.2f, -11.2f);
			motion.identity().translate(tl).translate(pv).mul(new Matrix4f(r3)).translate(-pv.x, -pv.y, -pv.z);
			motionArm.set(motion);
			active = true;
			return;
		}
		if (fitted) {
			// absolute Bedrock motion through the camera transform fitted to the original's idle: view = P * Mrest^-1 * Mcur
			Matrix4f pp = swordPlacement0();
			motion.set(pp).mul(new Matrix4f(rm).invert()).mul(cm).mul(new Matrix4f(pp).invert());
			motionArm.set(motion);
			try {
				assets.arms.xyzOrder = false;
				Map<String, Matrix4f> ac = assets.arms.computeMatrices(cur, rsg, one, one);
				Map<String, Matrix4f> ar = assets.arms.computeMatrices(idle, rsg, one, one);
				Matrix4f acm = ac.get("righthand_pos"), arm0 = ar.get("righthand_pos");
				if (acm != null && arm0 != null) {
					Matrix4f pa = armPlacement();
					motionArm.set(pa).mul(new Matrix4f(arm0).invert()).mul(acm).mul(new Matrix4f(pa).invert());
				}
			} catch (Throwable ignored) {}
			active = true;
			return;
		}
		Matrix4f w = convMatrix(conv % 48);
		Vector3f restCentre = rm.transformPosition(assets.sword.centerOf(SWORD_BONE), new Vector3f());
		Vector3f placed = swordPlacement0().transformPosition(assets.sword.centerOf(SWORD_BONE), new Vector3f());
		Vector3f mapped = w.transformPosition(restCentre, new Vector3f());
		w.setTranslation(placed.sub(mapped));
		motion.set(w).mul(delta).mul(new Matrix4f(w).invert());

		// The forearm hangs off gun_and_righthand only: it must not inherit the sword's own spin/stretch (woopie).
		motionArm.set(motion);
		try {
			assets.arms.xyzOrder = conv >= 48;
			Map<String, Matrix4f> ac = assets.arms.computeMatrices(cur, one, one, one);
			Map<String, Matrix4f> ar = assets.arms.computeMatrices(idle, one, one, one);
			Matrix4f acm = ac.get("righthand_pos"), arm0 = ar.get("righthand_pos");
			if (acm != null && arm0 != null) {
				Matrix4f d2 = new Matrix4f(acm).mul(new Matrix4f(arm0).invert());
				if (cfg.moveScale != 1.0f) d2.setTranslation(d2.getTranslation(new Vector3f()).mul(cfg.moveScale));
				Matrix4f wa = convMatrix(conv % 48);
				Vector3f anchor = new Vector3f(6.04688f, 0, 0);
				Vector3f restA = arm0.transformPosition(anchor, new Vector3f());
				Vector3f mappedA = wa.transformPosition(restA, new Vector3f());
				wa.setTranslation(new Vector3f(ARM_T).sub(mappedA));
				motionArm.set(wa).mul(d2).mul(new Matrix4f(wa).invert());
			}
		} catch (Throwable ignored) {}
		swordCentre = assets.sword.centerOf(SWORD_BONE);
		active = true;
	}

	/** Model space -> view space, as fitted to the original pack's idle frames (ZYX Bedrock rotations). */
	private static Matrix4f camW() {
		return new Matrix4f(
				0.22196f, 0.93513f, 0.27616f, 0f,
				-0.97506f, 0.21316f, 0.06189f, 0f,
				-0.00099f, -0.28301f, 0.95912f, 0f,
				1.63511f, 6.54189f, -24.44192f, 1f);
	}

	/** Signed axis permutation number i (0..47), same enumeration order as the offline search. */
	private static Matrix4f convMatrix(int i) {
		int[][] perms = {{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};
		int pm = i / 8, sg = i % 8;
		float[] m = new float[9];
		for (int r = 0; r < 3; r++) {
			int bit = (sg >> (2 - r)) & 1;
			m[r * 3 + perms[pm][r]] = bit == 0 ? 1f : -1f;
		}
		// row-major m -> JOML column-major
		return new Matrix4f(m[0], m[3], m[6], 0, m[1], m[4], m[7], 0, m[2], m[5], m[8], 0, 0, 0, 0, 1);
	}

	private static Matrix4f swordPlacement0() {
		return new Matrix4f().translate(SW_T).rotateX(TILT).rotateY(YAW).rotateZ(ROLL)
				.translate(-swordCentre.x, -swordCentre.y, -swordCentre.z);
	}

	private static void say(String text) {
		try {
			Minecraft mc = Minecraft.getInstance();
			Object comp = net.minecraft.network.chat.Component.literal(text);
			for (java.lang.reflect.Method m : mc.player.getClass().getMethods()) {
				if (m.getName().equals("displayClientMessage") && m.getParameterCount() == 2) {
					m.invoke(mc.player, comp, true);
					return;
				}
			}
			for (java.lang.reflect.Method m : mc.player.getClass().getMethods()) {
				if (m.getName().equals("sendSystemMessage") && m.getParameterCount() == 1) {
					m.invoke(mc.player, comp);
					return;
				}
			}
		} catch (Throwable ignored) {}
	}

	private static Matrix4f swordPlacement() {
		return new Matrix4f().translate(SW_T).rotateX(TILT).rotateY(YAW).rotateZ(ROLL)
				.translate(-swordCentre.x, -swordCentre.y, -swordCentre.z);
	}

	private static Matrix4f armPlacement() {
		return new Matrix4f().translate(ARM_T).rotateZ(ARM_A).rotateY(ARM_B).rotateX(ARM_C)
				.translate(-6.04688f, 0, 0);
	}

	/** Draws the Bedrock sword (and arm) for one hand, in place of vanilla's item. */
	public static void draw(PoseStack ps, SubmitNodeCollector collector, LocalPlayer player, ItemStack stack,
							boolean rightSide, int light) {
		Config cfg = Config.current;
		int item = itemCode(stack);
		if (item == 0) return;
		Matrix4f p = swordPlacement();
		Matrix4f swordM = new Matrix4f(motion).mul(p);                     // idle placement, then view-space motion
		Matrix4f armM = new Matrix4f(motionArm).mul(armPlacement());

		Map<String, Matrix4f> swordMats = new HashMap<>();
		swordMats.put(SWORD_BONE, swordM);
		Map<String, Matrix4f> armMats = new HashMap<>();
		armMats.put("righthand_pos", armM);
		armMats.put("mcs_rightSleeve", armM);

		Identifier tex = Identifier.fromNamespaceAndPath(OriginalSwordClient.MOD_ID,
				"textures/sword/sword_" + TEX[item - 1] + ".png");
		float[] rs = cfg.staticRotSign;

		ps.pushPose();
		if (!rightSide) ps.scale(-1f, 1f, 1f);
		float s = cfg.scale / 16f;
		ps.translate(cfg.offset[0] / 16f, cfg.offset[1] / 16f, cfg.offset[2] / 16f);
		ps.scale(s, s, s);
		collector.submitCustomGeometry(ps, RenderTypes.entityTranslucent(tex),
				(pp, vc) -> assets.sword.render(vc, pp.pose(), swordMats, rs, light, b -> b.name.equals(SWORD_BONE)));
		if (cfg.showArms) {
			Identifier skin = player.getSkin().body().texturePath();
			collector.submitCustomGeometry(ps, RenderTypes.entityTranslucent(skin),
					(pp, vc) -> assets.arms.render(vc, pp.pose(), armMats, rs, light,
							b -> b.name.equals("righthand_pos") || b.name.equals("mcs_rightSleeve")));
		}
		ps.popPose();
	}
}
