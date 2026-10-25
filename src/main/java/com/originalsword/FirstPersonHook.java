package com.originalsword;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;

/**
 * Entry points called from the ASM-injected code in FirstPersonHandsAndItemsRenderer.
 * frame(): once per frame, advances the animation.
 * handBegin/handEnd: wrap the per-hand item rendering and move held swords (main hand and offhand).
 * wholeBegin/wholeEnd: fallback used only if the per-hand method could not be found.
 */
public final class FirstPersonHook {
	private static final Object NONE = new Object();
	private static final ArrayDeque<Object> pushed = new ArrayDeque<>();
	private static boolean failed;

	private FirstPersonHook() {}

	private static void fail(Throwable t) {
		failed = true;
		OriginalSwordClient.LOGGER.error("OriginalSword failed, restoring vanilla first-person rendering", t);
	}

	public static void frame(Object[] args) {
		pushed.clear();
		if (failed) return;
		try {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player == null) { SwordRenderer.active = false; return; }
			SwordRenderer.frame(player);
		} catch (Throwable t) { fail(t); }
	}

	public static void handBegin(Object[] args) {
		try {
			PoseStack ps = null;
			InteractionHand hand = null;
			HumanoidArm arm = null;
			ItemStack stack = null;
			for (Object o : args) {
				if (ps == null && o instanceof PoseStack p) ps = p;
				else if (hand == null && o instanceof InteractionHand h) hand = h;
				else if (arm == null && o instanceof HumanoidArm a) arm = a;
				else if (stack == null && o instanceof ItemStack s) stack = s;
			}
			LocalPlayer player = Minecraft.getInstance().player;
			if (failed || !SwordRenderer.active || ps == null || player == null) { pushed.push(NONE); return; }
			boolean main = hand != InteractionHand.OFF_HAND;
			ItemStack held = stack != null ? stack : (main ? player.getMainHandItem() : player.getOffhandItem());
			if (!SwordRenderer.isSword(held)) { pushed.push(NONE); return; }
			boolean right = arm != null ? arm == HumanoidArm.RIGHT : main == (player.getMainArm() == HumanoidArm.RIGHT);
			SwordRenderer.applyTo(ps, right);
			pushed.push(ps);
		} catch (Throwable t) { pushed.push(NONE); fail(t); }
	}

	public static void handEnd() { pop(); }

	public static void wholeBegin(Object[] args) {
		frame(args);
		try {
			PoseStack ps = null;
			for (Object o : args) if (o instanceof PoseStack p) { ps = p; break; }
			LocalPlayer player = Minecraft.getInstance().player;
			if (failed || !SwordRenderer.active || ps == null || player == null) { pushed.push(NONE); return; }
			SwordRenderer.applyTo(ps, player.getMainArm() == HumanoidArm.RIGHT);
			pushed.push(ps);
		} catch (Throwable t) { pushed.push(NONE); fail(t); }
	}

	public static void wholeEnd() { pop(); }

	private static void pop() {
		Object o = pushed.poll();
		if (o instanceof PoseStack p) {
			try { p.popPose(); } catch (Throwable t) { fail(t); }
		}
	}
}
