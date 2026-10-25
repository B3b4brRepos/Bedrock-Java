package com.originalsword.bedrock;

/** Animated channels for one bone (degrees / pixels). Scale is multiplicative. */
public final class BonePose {
	public float rx, ry, rz;
	public float px, py, pz;
	public float sx = 1, sy = 1, sz = 1;

	public BonePose copy() {
		BonePose b = new BonePose();
		b.rx = rx; b.ry = ry; b.rz = rz;
		b.px = px; b.py = py; b.pz = pz;
		b.sx = sx; b.sy = sy; b.sz = sz;
		return b;
	}

	public static BonePose lerp(BonePose a, BonePose b, float t) {
		BonePose o = new BonePose();
		o.rx = a.rx + (b.rx - a.rx) * t; o.ry = a.ry + (b.ry - a.ry) * t; o.rz = a.rz + (b.rz - a.rz) * t;
		o.px = a.px + (b.px - a.px) * t; o.py = a.py + (b.py - a.py) * t; o.pz = a.pz + (b.pz - a.pz) * t;
		o.sx = a.sx + (b.sx - a.sx) * t; o.sy = a.sy + (b.sy - a.sy) * t; o.sz = a.sz + (b.sz - a.sz) * t;
		return o;
	}
}
