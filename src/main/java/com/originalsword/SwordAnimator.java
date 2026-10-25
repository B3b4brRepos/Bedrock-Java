package com.originalsword;

import com.originalsword.bedrock.Animation;
import com.originalsword.bedrock.BonePose;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Java version of controller.animation.mcs.sword:
 * off -> draw -> idle1 -> attack1 -> idle2 -> attack2 -> idle3 -> attack3 -> idle4 -> attack4 -> idle1 ...
 * with the inspect / inspect_wait side states reachable from every idle state.
 */
public final class SwordAnimator {
	private enum Kind { OFF, DRAW, IDLE, ATTACK, INSPECT, INSPECT_WAIT }

	private static final double[] ATTACK_LENGTH = {1.7333, 1.6917, 1.7, 2.1917};
	private static final double[] ATTACK_CHAIN_AT = {0.60, 0.60, 0.60, 1.20};
	private static final double DRAW_LENGTH = 1.275, INSPECT_LENGTH = 5.2333;

	private final SwordAssets assets;
	private final Consumer<String> sound;

	private Kind kind = Kind.OFF;
	private int idx = 1;            // which attack in the combo comes next / is playing (1..4)
	private int activeItem = 0;     // 1..6 = wooden..netherite
	private double life, stateTime, prevStateTime = -1, deadline;
	private boolean busy, buffered;
	private double idleWait;        // 0 = not waiting, 1 = idle (2 s), 2 = inspect wait (8 s)

	private Map<String, BonePose> snapshot = new HashMap<>();
	private double blend;
	private Map<String, BonePose> lastPose = new HashMap<>();

	public SwordAnimator(SwordAssets assets, Consumer<String> sound) {
		this.assets = assets; this.sound = sound;
	}

	/** @param item 0 = no sword in hand, else 1..6 */
	public void update(double dt, int item, boolean attackEdge, boolean quiet) {
		life += dt;
		prevStateTime = stateTime;
		stateTime += dt;

		if (item == 0) { kind = Kind.OFF; activeItem = 0; return; }

		if (idleWait > 0.5 && !quiet) deadline = life + (idleWait > 1.5 ? 8.0 : 2.0);
		if (attackEdge && busy) buffered = true;

		boolean itemChanged = item != activeItem;
		switch (kind) {
			case OFF -> {
				if (attackEdge) enter(Kind.ATTACK, 1, item); else enter(Kind.DRAW, 1, item);
			}
			case DRAW -> {
				if (itemChanged) enter(Kind.DRAW, 1, item);
				else if (attackEdge) enter(Kind.ATTACK, 1, item);
				else if (stateTime >= DRAW_LENGTH) enter(Kind.IDLE, 1, item);
			}
			case IDLE -> {
				if (itemChanged) enter(Kind.DRAW, 1, item);
				else if (attackEdge) enter(Kind.ATTACK, idx, item);
				else if (quiet && life >= deadline) enter(Kind.INSPECT, idx, item);
			}
			case ATTACK -> {
				int next = idx % 4 + 1;
				if (itemChanged) enter(Kind.DRAW, 1, item);
				else if (stateTime >= ATTACK_CHAIN_AT[idx - 1] && (buffered || attackEdge)) enter(Kind.ATTACK, next, item);
				else if (stateTime >= ATTACK_LENGTH[idx - 1]) enter(Kind.IDLE, next, item);
			}
			case INSPECT -> {
				if (itemChanged) enter(Kind.DRAW, 1, item);
				else if (attackEdge) enter(Kind.ATTACK, idx, item);
				else if (stateTime >= INSPECT_LENGTH) enter(Kind.INSPECT_WAIT, idx, item);
			}
			case INSPECT_WAIT -> {
				if (itemChanged) enter(Kind.DRAW, 1, item);
				else if (attackEdge) enter(Kind.ATTACK, idx, item);
				else if (quiet && life >= deadline) enter(Kind.INSPECT, idx, item);
			}
		}
		fireSounds();
	}

	private void enter(Kind k, int index, int item) {
		snapshot = lastPose;
		kind = k; idx = index; activeItem = item;
		stateTime = 0; prevStateTime = -1; buffered = false;
		blend = (k == Kind.ATTACK || k == Kind.INSPECT) ? 0.2 : 0.0;
		busy = k == Kind.DRAW || k == Kind.ATTACK || k == Kind.INSPECT;
		idleWait = switch (k) { case IDLE -> 1.0; case INSPECT_WAIT -> 2.0; default -> 0.0; };
		if (idleWait > 0.5) deadline = life + (idleWait > 1.5 ? 8.0 : 2.0);
	}

	public boolean isActive() { return kind != Kind.OFF; }

	public int item() { return activeItem; }

	// ---- pose ----------------------------------------------------------------------------------

	private List<Animation> layers() {
		var a = assets.anims;
		return switch (kind) {
			case DRAW -> List.of(a.get("draw.weapon"), a.get("draw.hands"));
			case ATTACK -> List.of(a.get("melee_" + idx + ".weapon"), a.get("melee_" + idx + ".hands"));
			case INSPECT -> List.of(a.get("inspect.weapon"), a.get("inspect.hands"));
			case IDLE, INSPECT_WAIT -> List.of(a.get("trail.rest"));
			case OFF -> List.of();
		};
	}

	private void fireSounds() {
		for (Animation an : layers()) {
			for (var s : an.sounds) {
				if (s.time() > prevStateTime && s.time() <= stateTime) sound.accept(s.effect());
			}
		}
	}

	public Map<String, BonePose> pose() {
		Map<String, BonePose> pose = new HashMap<>();
		assets.anims.get("static_idle").apply(pose, 0);
		for (Animation an : layers()) an.apply(pose, stateTime);

		if (blend > 0 && stateTime < blend && !snapshot.isEmpty()) {
			float w = (float) (stateTime / blend);
			Map<String, BonePose> mixed = new HashMap<>();
			for (var e : pose.entrySet()) {
				BonePose from = snapshot.get(e.getKey());
				mixed.put(e.getKey(), from == null ? e.getValue() : BonePose.lerp(from, e.getValue(), w));
			}
			pose = mixed;
		}
		lastPose = pose;
		return pose;
	}
}
