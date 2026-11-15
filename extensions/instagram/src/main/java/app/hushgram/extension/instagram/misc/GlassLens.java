/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.os.Build;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.Settings;

/**
 * The lens in the glass tab bar's raised bubble (#289), on Android 13 and newer: the blurred backdrop under
 * the selected tab's bubble drawn a little larger, bending more toward the bubble's rim, as glass would.
 * It is the backdrop the pill already blurs, run through a small shader into a render node of its own, so
 * nothing is read back and no view is touched. Before Android 13 there is no shader to run and the bubble
 * has its rim and gradient alone. A failure turns the lens off for this bar and the bubble carries on.
 *
 * <p>Home's see-through top bar borrows the rim for its bottom edge while the tab bar's bends (#513).
 */
public final class GlassLens {
    /** How much the centre of the bubble magnifies, and how much more its rim bends the backdrop. */
    static final float CENTRE_MAGNIFY = 0.10f;
    static final float RIM_BEND = 0.14f;

    private static final String SHADER = ""
            + "uniform shader content;\n"
            + "uniform float4 box;\n"
            + "uniform float radius;\n"
            + "uniform float reach;\n"
            + "uniform float energy;\n"
            + "uniform float dp;\n"
            + "uniform float dark;\n"
            + "uniform float3 bg;\n"
            + "half3 vivid(half3 c, float s) {\n"
            + "  float l = dot(c, half3(0.2126, 0.7152, 0.0722));\n"
            + "  return clamp(half3(l) + (c - half3(l)) * s, 0.0, 1.0);\n"
            + "}\n"
            + "half4 main(float2 p) {\n"
            + "  float2 mid = (box.xy + box.zw) * 0.5;\n"
            + "  float2 hs = (box.zw - box.xy) * 0.5;\n"
            + "  float2 r = p - mid;\n"
            + "  float2 q = abs(r) - (hs - float2(radius));\n"
            + "  float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "  float inside = clamp(0.5 - d, 0.0, 1.0);\n"
            + "  if (inside <= 0.0) return half4(0.0);\n"
            + "  float2 sg = float2(r.x < 0.0 ? -1.0 : 1.0, r.y < 0.0 ? -1.0 : 1.0);\n"
            + "  float2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) * sg\n"
            + "      : (q.x > q.y ? float2(sg.x, 0.0) : float2(0.0, sg.y));\n"
            // The glass's edge is a rounded bevel: its height, a squircle, rises from the edge over the bezel, and
            // light going down through it bends by Snell's law (air to glass, 1.5), more the steeper the surface.
            + "  float bezel = max(1.0, 0.5 * min(hs.x, hs.y));\n"
            + "  float t = clamp(-d / bezel, 0.0, 1.0);\n"
            + "  float u = 1.0 - t;\n"
            + "  float u3 = u * u * u;\n"
            + "  float body = max(1.0 - u3 * u, 0.0001);\n"
            + "  float slope = min(u3 * pow(body, -0.75), 8.0);\n"
            + "  float sinI = slope * inversesqrt(1.0 + slope * slope);\n"
            + "  float sinR = sinI / 1.5;\n"
            + "  float tanR = sinR * inversesqrt(max(1.0 - sinR * sinR, 0.0001));\n"
            + "  float f = pow(max(min(slope - tanR, 3.0), 0.0) / 3.0, 0.6);\n"
            // Which way it bends: in, towards the middle, so the edge shows what lies further in, drawn out.
            + "  float shift = reach * (1.0 + 0.35 * energy) * 0.7 * bezel * f;\n"
            + "  float split = 0.6 + 0.4 * energy;\n"
            + "  half4 sr = content.eval(p - n * (shift * (1.0 + 0.5 * split)));\n"
            + "  half4 sgr = content.eval(p - n * shift);\n"
            + "  half4 sb = content.eval(p - n * (shift * (1.0 - 0.5 * split)));\n"
            + "  half3 back = half3(bg);\n"
            + "  half3 col = half3(sr.r + back.r * (1.0 - sr.a), sgr.g + back.g * (1.0 - sgr.a), sb.b + back.b * (1.0 - sb.a));\n"
            + "  col = vivid(col, 1.25) * 0.96 + 0.04;\n"
            // Light: the edge lifts as glass does at a glancing angle, and a thin bright line runs round it, most where
            // it faces the light at the top left and a little where it faces away.
            + "  float edge = pow(1.0 - t, 3.0);\n"
            + "  col += half3(edge * mix(0.14, 0.10, dark));\n"
            + "  float facing = dot(n, float2(-0.55, -0.83));\n"
            + "  float ring = pow(clamp(1.0 + d / (2.4 * dp), 0.0, 1.0), 1.6);\n"
            + "  float spec = pow(clamp(facing, 0.0, 1.0), 1.3) * 0.95 + pow(clamp(-facing, 0.0, 1.0), 1.3) * 0.45;\n"
            + "  float broad = pow(clamp(1.0 + d / (bezel * 0.5), 0.0, 1.0), 2.0);\n"
            + "  col += half3(ring * spec * 0.85 + broad * pow(clamp(facing, 0.0, 1.0), 2.0) * 0.10);\n"
            + "  col *= half(1.0 - 0.12 * broad * clamp(-facing, 0.0, 1.0));\n"
            + "  col = clamp(col, 0.0, 1.0);\n"
            + "  return half4(col * inside, inside);\n"
            + "}\n";

    /**
     * The liquid pill's rim (#369): within {@link #RIM_BAND} of the edge the backdrop is mirrored across the
     * band's inner line, as {@link #rimSource} works out, so what runs under the edge shows upside down and
     * moves the other way, as it does through the edge of real glass. Inside the band the backdrop is left as is.
     */
    private static final String RIM_SHADER = ""
            + "uniform shader content;\n"
            + "uniform float4 box;\n"
            + "uniform float radius;\n"
            + "uniform float band;\n"
            + "half4 main(float2 p) {\n"
            + "  float2 mid = (box.xy + box.zw) * 0.5;\n"
            + "  float2 hs = (box.zw - box.xy) * 0.5;\n"
            + "  float2 q = abs(p - mid) - (hs - float2(radius));\n"
            + "  float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "  float inside = clamp(0.5 - d, 0.0, 1.0);\n"
            + "  if (inside <= 0.0) return half4(0.0);\n"
            + "  float2 side = sign(p - mid);\n"
            + "  float2 normal = q.x > 0.0 && q.y > 0.0 ? normalize(q) * side\n"
            + "      : (q.x > q.y ? float2(side.x, 0.0) : float2(0.0, side.y));\n"
            + "  float depth = max(0.0, -d);\n"
            + "  float2 source = depth < band ? p - normal * (2.0 * (band - depth)) : p;\n"
            + "  return content.eval(source) * inside;\n"
            + "}\n";

    /**
     * What sits over the glass seen through the lens. At rest it is as it is, drawn in a little at the very edge;
     * the more the lens moves ([energy]) the more it is drawn in towards the edge, bulged at the middle, smeared
     * along the way it moves, and split into its colours, the red and blue sides pulled apart, as light through
     * moving glass is.
     */
    private static final String EDGE_SHADER = ""
            + "uniform shader content;\n"
            + "uniform float4 box;\n"
            + "uniform float radius;\n"
            + "uniform float amount;\n"
            + "uniform float energy;\n"
            + "half4 main(float2 p) {\n"
            + "  float2 mid = (box.xy + box.zw) * 0.5;\n"
            + "  float2 hs = (box.zw - box.xy) * 0.5;\n"
            + "  float2 q = abs(p - mid) - (hs - float2(radius));\n"
            + "  float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "  float inside = clamp(0.5 - d, 0.0, 1.0);\n"
            + "  if (inside <= 0.0) return half4(0.0);\n"
            + "  float depth = clamp(-d / max(1.0, min(hs.x, hs.y)), 0.0, 1.0);\n"
            + "  float near = (1.0 - depth) * (1.0 - depth);\n"
            + "  near = near * near;\n"
            + "  float2 rel = (p - mid) / hs;\n"
            + "  float r2 = clamp(dot(rel, rel), 0.0, 1.0);\n"
            + "  float k = 1.0 + amount * near * (0.6 + 0.4 * energy) - energy * 0.10 * (1.0 - r2);\n"
            + "  float2 centre = mid + (p - mid) * k;\n"
            + "  if (energy < 0.02) return content.eval(centre) * inside;\n"
            + "  float reach = 0.07 * energy * hs.x;\n"
            + "  half4 sum = half4(0.0);\n"
            + "  for (int i = 0; i < 5; i++) {\n"
            + "    float off = (float(i) / 4.0 * 2.0 - 1.0) * reach;\n"
            + "    half4 cr = content.eval(centre + float2(off * (1.0 + 0.9 * energy), 0.0));\n"
            + "    half4 cg = content.eval(centre + float2(off, 0.0));\n"
            + "    half4 cb = content.eval(centre + float2(off * (1.0 - 0.9 * energy), 0.0));\n"
            + "    sum += half4(cr.r, cg.g, cb.b, max(cr.a, max(cg.a, cb.a)));\n"
            + "  }\n"
            + "  return sum * 0.2 * inside;\n"
            + "}\n";

    /** How deep the rim's mirrored band reaches into the pill, as a share of the pill's half height. */
    static final float RIM_BAND = 0.42f;

    /**
     * How far in from the edge the rim samples the backdrop for a point [depth] in from it, in a band [band]
     * deep: past the band, the point itself, and within it, the mirror image across the band's inner line, so
     * the edge shows what's 2 x [band] in and the order runs backwards. The same sum as {@link #RIM_SHADER}.
     */
    static float rimSource(float depth, float band) {
        return depth < band ? 2f * band - depth : depth;
    }

    /** The deepest the rim's band may reach, as a share of the pill's half height, whatever the strength. */
    static final float MAX_RIM_BAND = 0.95f;

    /** [percent] as the multiplier the bend is scaled by, kept inside the slider's range. */
    static float scale(int percent) {
        return Math.max(0, Math.min(200, percent)) / 100f;
    }

    /** The rim's band at [percent] of its standard depth, as a share of the pill's half height. */
    static float bandShare(int percent) {
        return Math.min(MAX_RIM_BAND, RIM_BAND * scale(percent));
    }

    /** Whether this Android can run the lens at all. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /** Whether this is the pill's rim (#369) rather than the bubble's lens (#289). */
    private final boolean rim;
    /** A strength to draw at instead of the saved one, in percent, while a slider is being dragged. -1 for none. */
    int strengthNow = -1;
    @Nullable private RenderNode node;
    @Nullable private RuntimeShader shader;
    private boolean broken;

    /** The bubble's lens. */
    GlassLens() {
        this(false);
    }

    /** The bubble's lens, or with [rim] the pill's rim. */
    public GlassLens(boolean rim) {
        this.rim = rim;
    }

    /**
     * A lens for what sits over the glass, such as the tabs: they keep their size and place across the lens, and
     * only close to its edge are they drawn in, as the glass's edge draws in what is past it. [amount] is
     * how much at the very edge.
     */
    static GlassLens edge(float amount) {
        GlassLens made = new GlassLens(false);
        made.edge = true;
        made.edgeAmount = amount;
        return made;
    }

    private boolean edge;
    private float edgeAmount;
    private float edgeEnergy;
    private float lookDp = 3f;
    private float lookDark = 1f;
    private float lookRed = 0.1f, lookGreen = 0.1f, lookBlue = 0.1f;

    /** What the glass lens shows itself on: the screen's [dp], whether it is [dark], and the [base] colour behind it. */
    void look(float dp, boolean dark, int base) {
        lookDp = Math.max(1f, dp);
        lookDark = dark ? 1f : 0f;
        lookRed = ((base >> 16) & 0xff) / 255f;
        lookGreen = ((base >> 8) & 0xff) / 255f;
        lookBlue = (base & 0xff) / 255f;
    }

    /** How much the lens is moving, from 0 at rest to 1 at speed, which the edge lens bends and smears by. */
    void energy(float moving) {
        edgeEnergy = Math.max(0f, Math.min(1f, moving));
    }

    /** The bend's strength in percent: the one being dragged, else the slider's, else the standard for a bar with no bend switch on. */
    int percent() {
        if (strengthNow >= 0) return strengthNow;
        return Settings.GLASS_TAB_BAR_REFRACT.get() ? Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.get() : 100;
    }

    /** How many times the lens was asked to paint, and how many it painted. Read by tests. */
    int asked;
    int painted;

    /**
     * Draws the lens over [box] ([radius] corners), in the bar's coordinates, into [canvas]. [backdrop] is
     * the pill's blurred backdrop node, which the pill draws at [nodeLeft], [nodeTop] and [downsample] times
     * its size. [width] and [height] are the bar's. Says whether the lens drew.
     */
    boolean paint(Canvas canvas, @Nullable RenderNode backdrop, float nodeLeft, float nodeTop, float downsample,
            int width, int height, RectF box, float radius) {
        return paint(canvas, backdrop, nodeLeft, nodeTop, downsample, width, height, box, radius,
                Math.min(box.width(), box.height()));
    }

    /**
     * {@link #paint(Canvas, RenderNode, float, float, float, int, int, RectF, float)} with the rim's band a share
     * of half of [across] rather than of the box's shorter side, for a box that runs on past the glass.
     */
    public boolean paint(Canvas canvas, @Nullable RenderNode backdrop, float nodeLeft, float nodeTop, float downsample,
            int width, int height, RectF box, float radius, float across) {
        asked++;
        if (broken || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || backdrop == null || width <= 0 || height <= 0 || !canvas.isHardwareAccelerated()) {
            return false;
        }
        try {
            RuntimeShader lens = shader;
            if (lens == null) {
                lens = new RuntimeShader(edge ? EDGE_SHADER : rim ? RIM_SHADER : SHADER);
                shader = lens;
            }
            RenderNode layer = node;
            if (layer == null) {
                layer = new RenderNode(rim ? "hushgram-glass-rim" : "hushgram-glass-lens");
                node = layer;
            }
            lens.setFloatUniform("box", box.left, box.top, box.right, box.bottom);
            lens.setFloatUniform("radius", Math.max(0f, radius));
            if (edge) {
                lens.setFloatUniform("amount", edgeAmount * scale(percent()));
                lens.setFloatUniform("energy", edgeEnergy);
            } else if (rim) {
                lens.setFloatUniform("band", bandShare(percent()) * across / 2f);
            } else {
                lens.setFloatUniform("reach", scale(percent()));
                lens.setFloatUniform("energy", edgeEnergy);
                lens.setFloatUniform("dp", lookDp);
                lens.setFloatUniform("dark", lookDark);
                lens.setFloatUniform("bg", lookRed, lookGreen, lookBlue);
            }
            layer.setPosition(0, 0, width, height);
            layer.setRenderEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"));
            Canvas recording = layer.beginRecording(width, height);
            try {
                recording.translate(nodeLeft, nodeTop);
                recording.scale(downsample, downsample);
                recording.drawRenderNode(backdrop);
            } finally {
                layer.endRecording();
            }
            canvas.drawRenderNode(layer);
            painted++;
            return true;
        } catch (Throwable failure) {
            broken = true;
            GlassTabBar.diagnose("lens failed", failure);
            return false;
        }
    }

    /** Lets go of the lens's render node. */
    public void discard() {
        RenderNode layer = node;
        node = null;
        shader = null;
        if (layer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) layer.discardDisplayList();
    }
}
