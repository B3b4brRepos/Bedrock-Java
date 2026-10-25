package com.originalsword.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Signature-agnostic hooks into FirstPersonHandsAndItemsRenderer:
 *  - submitHandsWithItems: FirstPersonHook.frame(args) once per frame (advances the animation);
 *  - the per-hand method (PoseStack + InteractionHand + ItemStack): handBegin/handEnd around it so held swords
 *    in either hand are moved by the animation;
 *  - if no per-hand method exists in 26.3, wholeBegin/wholeEnd wrap the whole method instead.
 * If submitHandsWithItems is not found it logs and does nothing, leaving vanilla untouched.
 */
public final class OriginalSwordMixinPlugin implements IMixinConfigPlugin {
	private static final String HOOK = "com/originalsword/FirstPersonHook";
	private static final String[] METHOD_NAMES = {"submitHandsWithItems", "renderHandsWithItems"};

	@Override public void onLoad(String mixinPackage) {}
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

	@Override
	public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
		MethodNode main = null;
		for (MethodNode m : node.methods) {
			boolean named = false;
			for (String n : METHOD_NAMES) if (m.name.equals(n)) named = true;
			if (!named || (m.access & Opcodes.ACC_STATIC) != 0) continue;
			if (Type.getReturnType(m.desc).getSort() != Type.VOID) continue;
			if (main == null || m.desc.contains("PoseStack")) main = m;
		}
		if (main == null) {
			System.err.println("[OriginalSword] Could not find submitHandsWithItems in " + target
					+ " - first-person sword animations disabled (vanilla hands kept).");
			return;
		}

		// Per-hand item renderer: instance method taking PoseStack + InteractionHand + ItemStack.
		List<MethodNode> hands = new ArrayList<>();
		for (MethodNode m : node.methods) {
			if (m == main || (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNTHETIC)) != 0) continue;
			if (m.name.startsWith("lambda$") || m.name.startsWith("<")) continue;
			if (Type.getReturnType(m.desc).getSort() != Type.VOID) continue;
			if (m.desc.contains("PoseStack;") && m.desc.contains("InteractionHand;") && m.desc.contains("ItemStack;")) hands.add(m);
		}

		if (hands.isEmpty()) {
			insertHead(main, "wholeBegin");
			insertBeforeReturns(main, "wholeEnd");
			System.out.println("[OriginalSword] Hooked " + target + "#" + main.name + main.desc
					+ " (whole-view mode: per-hand method not found)");
		} else {
			insertHead(main, "frame");
			for (MethodNode h : hands) {
				insertHead(h, "handBegin");
				insertBeforeReturns(h, "handEnd");
				System.out.println("[OriginalSword] Hooked hand renderer " + target + "#" + h.name + h.desc);
			}
			System.out.println("[OriginalSword] Hooked " + target + "#" + main.name + main.desc);
		}
	}

	private static void insertHead(MethodNode m, String hookMethod) {
		Type[] args = Type.getArgumentTypes(m.desc);
		InsnList il = new InsnList();
		pushInt(il, args.length);
		il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
		int slot = 1; // slot 0 = this
		for (int i = 0; i < args.length; i++) {
			Type t = args[i];
			il.add(new InsnNode(Opcodes.DUP));
			pushInt(il, i);
			il.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
			box(il, t);
			il.add(new InsnNode(Opcodes.AASTORE));
			slot += t.getSize();
		}
		il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, hookMethod, "([Ljava/lang/Object;)V", false));
		AbstractInsnNode first = m.instructions.getFirst();
		if (first == null) m.instructions.add(il); else m.instructions.insertBefore(first, il);
		m.maxStack = Math.max(m.maxStack, 8);
	}

	private static void insertBeforeReturns(MethodNode m, String hookMethod) {
		for (AbstractInsnNode n : m.instructions.toArray()) {
			if (n.getOpcode() == Opcodes.RETURN) {
				m.instructions.insertBefore(n, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, hookMethod, "()V", false));
			}
		}
	}

	private static void pushInt(InsnList il, int v) {
		if (v >= -1 && v <= 5) il.add(new InsnNode(Opcodes.ICONST_0 + v));
		else il.add(new IntInsnNode(Opcodes.BIPUSH, v));
	}

	private static void box(InsnList il, Type t) {
		String owner, desc;
		switch (t.getSort()) {
			case Type.BOOLEAN -> { owner = "java/lang/Boolean"; desc = "(Z)Ljava/lang/Boolean;"; }
			case Type.BYTE -> { owner = "java/lang/Byte"; desc = "(B)Ljava/lang/Byte;"; }
			case Type.CHAR -> { owner = "java/lang/Character"; desc = "(C)Ljava/lang/Character;"; }
			case Type.SHORT -> { owner = "java/lang/Short"; desc = "(S)Ljava/lang/Short;"; }
			case Type.INT -> { owner = "java/lang/Integer"; desc = "(I)Ljava/lang/Integer;"; }
			case Type.FLOAT -> { owner = "java/lang/Float"; desc = "(F)Ljava/lang/Float;"; }
			case Type.LONG -> { owner = "java/lang/Long"; desc = "(J)Ljava/lang/Long;"; }
			case Type.DOUBLE -> { owner = "java/lang/Double"; desc = "(D)Ljava/lang/Double;"; }
			default -> { return; }
		}
		il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf", desc, false));
	}
}
