package com.originalsword.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Intentionally empty. Minecraft 26.3 replaced ItemInHandRenderer with FirstPersonHandsAndItemsRenderer and its
 * method signature could not be verified, so the actual hook is injected by {@link OriginalSwordMixinPlugin}
 * with ASM, which adapts to whatever arguments submitHandsWithItems takes. This class only makes Mixin
 * apply the plugin to the target class.
 */
@Mixin(targets = "net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer")
public abstract class FirstPersonHandsMixin {
}
