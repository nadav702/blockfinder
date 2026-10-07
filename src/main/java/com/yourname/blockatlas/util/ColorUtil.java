package com.yourname.blockatlas.util;

/** Small colour helpers. All "rgb" values are 0xRRGGBB, all "argb" values are 0xAARRGGBB. */
public final class ColorUtil {
    private ColorUtil() {}

    /** HSV (each 0..1) to 0xRRGGBB. */
    public static int hsv(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        int i = (int) (h * 6f);
        float f = h * 6f - i;
        float p = v * (1f - s);
        float q = v * (1f - f * s);
        float t = v * (1f - (1f - f) * s);
        float r, g, b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    /** Hue (0..1) of an 0xRRGGBB colour. Greys return 0. */
    public static float hue(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float d = max - min;
        if (d < 1e-4f) return 0f;
        float h;
        if (max == r) h = ((g - b) / d) % 6f;
        else if (max == g) h = (b - r) / d + 2f;
        else h = (r - g) / d + 4f;
        h /= 6f;
        return h < 0 ? h + 1f : h;
    }

    public static int argb(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    public static int opaque(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    /** Linear blend of two ARGB colours. */
    public static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 255, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24) & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24)
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }

    /** Sensible default colours for well-known blocks, hashed hue for everything else. */
    public static int defaultColor(String id) {
        String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (p.contains("diamond")) return 0x4DE8FF;
        if (p.contains("ancient_debris") || p.contains("netherite")) return 0xC0703F;
        if (p.contains("emerald")) return 0x3DFF7A;
        if (p.contains("redstone")) return 0xFF3B3B;
        if (p.contains("lapis")) return 0x4A72FF;
        if (p.contains("gold")) return 0xFFD84D;
        if (p.contains("iron")) return 0xE8B892;
        if (p.contains("copper")) return 0xFF8A4D;
        if (p.contains("coal")) return 0xA0A0A0;
        if (p.contains("quartz")) return 0xF3EFE6;
        if (p.contains("amethyst")) return 0xC77DFF;
        if (p.contains("spawner")) return 0xFF4DC3;
        if (p.contains("chest") || p.contains("barrel")) return 0xFFB454;
        float h = (id.hashCode() * 0.618034f);
        return hsv(h - (float) Math.floor(h), 0.72f, 1f);
    }
}
