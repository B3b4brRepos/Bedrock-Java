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
		boolean quiet = player.onGround() && player.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4
				&& !player.isUsingItem() && !down;
		animator.update(dt, item, edge, quiet);
		if (item == 0) return;

		assets.sword.xyzOrder = cfg.xyzOrder;
		Map<String, BonePose> cur = animator.pose();
		Map<String, BonePose> idle = new HashMap<>();
		assets.anims.get("static_idle").apply(idle, 0);
		Map<String, Matrix4f> mc = assets.sword.computeMatrices(cur, cfg.rotSign, cfg.staticRotSign, cfg.posSign);
		Map<String, Matrix4f> mr = assets.sword.computeMatrices(idle, cfg.rotSign, cfg.staticRotSign, cfg.posSign);
		Matrix4f cm = mc.get(SWORD_BONE), rm = mr.get(SWORD_BONE);
		if (cm == null || rm == null) return;

		// motion of the sword expressed in its own frame: idle -> now
		motion.set(rm).invert().mul(cm);
		if (cfg.moveScale != 1.0f) {
			Vector3f tr = motion.getTranslation(new Vector3f()).mul(cfg.moveScale);
			motion.setTranslation(tr);
		}
		swordCentre = assets.sword.centerOf(SWORD_BONE);
		active = true;
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
		Matrix4f swordM = new Matrix4f(p).mul(motion);                     // local -> L -> place
		Matrix4f dv = new Matrix4f(p).mul(motion).mul(new Matrix4f(p).invert());   // same motion, in view space
		Matrix4f armM = new Matrix4f(dv).mul(armPlacement());

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
