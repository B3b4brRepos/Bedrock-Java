package com.originalsword;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

/**
 * Entry points called from the ASM-injected code in FirstPersonHandsAndItemsRenderer.
 * frame(): once per frame, advances the animation.
 * handBegin(): at the start of the per-hand item renderer; if that hand holds a sword it draws the Bedrock
 *              sword + arm and returns true so vanilla skips its own rendering of that hand. Other items
 *              (shield, torch, empty hand...) still render normally.
 * wholeBegin(): fallback if the per-hand method could not be found; draws the main-hand sword and skips vanilla.
 */
public final class FirstPersonHook {
	private static boolean failed;

	private FirstPersonHook() {}

	private static void fail(Throwable t) {
		failed = true;
		OriginalSwordClient.LOGGER.error("OriginalSword failed, restoring vanilla first-person rendering", t);
	}

	public static void frame(Object[] args) {
		if (failed) return;
		try {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player == null) { SwordRenderer.active = false; return; }
			SwordRenderer.frame(player);
		} catch (Throwable t) { fail(t); }
	}

	/** @return true to skip vanilla rendering of this hand */
	public static boolean handBegin(Object[] args) {
		try {
			PoseStack ps = null;
			SubmitNodeCollector collector = null;
			InteractionHand hand = null;
			HumanoidArm arm = null;
			ItemStack stack = null;
			Integer light = null;
			for (Object o : args) {
				if (ps == null && o instanceof PoseStack p) ps = p;
				else if (collector == null && o instanceof SubmitNodeCollector c) collector = c;
				else if (hand == null && o instanceof InteractionHand h) hand = h;
				else if (arm == null && o instanceof HumanoidArm a) arm = a;
				else if (stack == null && o instanceof ItemStack s) stack = s;
				else if (light == null && o instanceof Integer i) light = i;
			}
			LocalPlayer player = Minecraft.getInstance().player;
			if (failed || !SwordRenderer.active || ps == null || collector == null || player == null) return false;
			boolean main = hand != InteractionHand.OFF_HAND;
			ItemStack held = stack != null ? stack : (main ? player.getMainHandItem() : player.getOffhandItem());
			if (!SwordRenderer.isSword(held)) return false;
			boolean right = arm != null ? arm == HumanoidArm.RIGHT : main == (player.getMainArm() == HumanoidArm.RIGHT);
			SwordRenderer.draw(ps, collector, player, held, right, light != null ? light : 15728880);
			return true;
		} catch (Throwable t) { fail(t); return false; }
	}

	/** @return true to skip all vanilla first-person hand rendering */
	public static boolean wholeBegin(Object[] args) {
		frame(args);
		try {
			PoseStack ps = null;
			SubmitNodeCollector collector = null;
			Integer light = null;
			for (Object o : args) {
				if (ps == null && o instanceof PoseStack p) ps = p;
				else if (collector == null && o instanceof SubmitNodeCollector c) collector = c;
				else if (light == null && o instanceof Integer i) light = i;
			}
			LocalPlayer player = Minecraft.getInstance().player;
			if (failed || !SwordRenderer.active || ps == null || collector == null || player == null) return false;
			ItemStack held = player.getMainHandItem();
			if (!SwordRenderer.isSword(held)) return false;
			SwordRenderer.draw(ps, collector, player, held, player.getMainArm() == HumanoidArm.RIGHT,
					light != null ? light : 15728880);
			return true;
		} catch (Throwable t) { fail(t); return false; }
	}
}
