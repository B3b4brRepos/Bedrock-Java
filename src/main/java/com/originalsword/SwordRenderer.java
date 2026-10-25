package com.originalsword;

import com.mojang.blaze3d.vertex.PoseStack;
import com.originalsword.bedrock.BonePose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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
	/** Where vanilla holds the item in view space (blocks); rotations pivot around here. */
	private static final Vector3f PIVOT = new Vector3f(0.56f, -0.45f, -0.7f);

	private static SwordAssets assets;
	private static SwordAnimator animator;
	private static long lastNanos = 0, lastCfgCheck = 0;
	private static boolean lastAttackDown = false;
	private static java.lang.reflect.Method isPausedMethod;
	private static boolean pausedLookedUp;

	/** Result of the last {@link #frame}. */
	public static boolean active;
	private static final Matrix4f deltaRight = new Matrix4f(), deltaLeft = new Matrix4f();

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

	/** Advances the animation and computes this frame's sword delta. Call once per rendered frame. */
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

		Map<String, BonePose> cur = animator.pose();
		Map<String, BonePose> idle = new HashMap<>();
		assets.anims.get("static_idle").apply(idle, 0);

		Map<String, Matrix4f> mc = assets.sword.computeMatrices(cur, cfg.rotSign, cfg.staticRotSign, cfg.posSign);
		Map<String, Matrix4f> mr = assets.sword.computeMatrices(idle, cfg.rotSign, cfg.staticRotSign, cfg.posSign);
		Matrix4f cm = mc.get(SWORD_BONE), rm = mr.get(SWORD_BONE);
		if (cm == null || rm == null) return;

		Vector3f c = assets.sword.centerOf(SWORD_BONE);
		rm.transformPosition(c);                         // rest centre of the sword, model pixels
		Matrix4f d = new Matrix4f(cm).mul(new Matrix4f(rm).invert());
		Vector3f moved = new Vector3f(c);
		d.transformPosition(moved);
		Vector3f t = moved.sub(c);                       // translation of the sword centre, pixels
		Matrix3f rot = new Matrix3f();
		d.get3x3(rot);
		Vector3f col = new Vector3f();                   // drop the bone scale, keep the rotation
		for (int i = 0; i < 3; i++) { rot.getColumn(i, col); col.normalize(); rot.setColumn(i, col); }

		float k = cfg.moveScale / 16f;
		t.mul(k);
		float len = t.length();
		if (len > 1.2f) t.mul(1.2f / len);

		float[] v = cfg.viewSign;
		t.mul(v[0], v[1], v[2]);
		Matrix3f vs = new Matrix3f().scaling(v[0], v[1], v[2]);
		Matrix3f r = new Matrix3f(vs).mul(rot).mul(vs);

		build(deltaRight, r, t, 1);
		build(deltaLeft, r, t, -1);
		active = true;
	}

	/** side = 1 for the right-hand side of the screen, -1 for the left (mirrored). */
	private static void build(Matrix4f out, Matrix3f r, Vector3f t, int side) {
		Matrix3f rr = new Matrix3f(r);
		Vector3f tt = new Vector3f(t);
		Vector3f pv = new Vector3f(PIVOT);
		if (side < 0) {
			Matrix3f m = new Matrix3f().scaling(-1, 1, 1);
			rr = new Matrix3f(m).mul(r).mul(m);
			tt.x = -tt.x;
			pv.x = -pv.x;
		}
		out.identity().translate(tt).translate(pv).mul(new Matrix4f(rr)).translate(-pv.x, -pv.y, -pv.z);
	}

	/** Pushes the stack and applies this frame's sword motion; the caller pops it afterwards. */
	public static void applyTo(PoseStack ps, boolean rightSide) {
		ps.pushPose();
		((Matrix4f) ps.last().pose()).mul(rightSide ? deltaRight : deltaLeft);
	}
}
