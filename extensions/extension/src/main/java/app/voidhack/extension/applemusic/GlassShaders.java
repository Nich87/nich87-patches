package app.voidhack.extension.applemusic;

/** AGSL optical passes derived from the iOS 26 beta 2 QuartzCore SDR path. */
final class GlassShaders {
    private GlassShaders() {}

    // RenderEffect shades at the input size. Replicate each filtered sample across its
    // source cell so that drawing the node smaller preserves that sample at the new texel center.
    static final String DOWNSAMPLE = """
            uniform shader scene;
            half4 main(float2 px) {
                float2 p = floor(px * 0.25) * 4.0 + 2.0;
                return (scene.eval(p + float2(-1, -1)) + scene.eval(p + float2(1, -1))
                      + scene.eval(p + float2(-1, 1)) + scene.eval(p + float2(1, 1))) * 0.25;
            }
            """;

    static final String MIP = """
            uniform shader scene;
            half4 main(float2 px) {
                float2 p = floor(px * 0.5) * 2.0 + 1.0;
                const float a = 1.9600850343704224;
                const float b = 3.9206759929656982;
                float4 center = float4(scene.eval(p));
                float4 axial = float4(scene.eval(p + float2(-a, 0))) + float4(scene.eval(p + float2(a, 0)))
                             + float4(scene.eval(p + float2(0, -a))) + float4(scene.eval(p + float2(0, a)));
                float4 diagonal = float4(scene.eval(p + float2(-a, -a))) + float4(scene.eval(p + float2(a, -a)))
                                + float4(scene.eval(p + float2(-a, a))) + float4(scene.eval(p + float2(a, a)));
                float4 outer = float4(scene.eval(p + float2(-b, 0))) + float4(scene.eval(p + float2(b, 0)))
                             + float4(scene.eval(p + float2(0, -b))) + float4(scene.eval(p + float2(0, b)));
                return half4(center * 0.10549700260162354 + axial * 0.090185999870300293
                           + diagonal * 0.077097997069358826 + outer * 0.056340999901294708);
            }
            """;

    // All geometry is in physical pixels. The gradient points out of the shape.
    private static final String SHAPE = """
            float3 field(float2 p, float2 halfSize, float ovalization) {
                float radius = min(halfSize.x, halfSize.y);
                const float cornerScale = 1.5286649465560913;
                float corner = radius * cornerScale;
                float2 fromSide = abs(p) - halfSize + corner;
                float2 q = max(fromSide, 0.0) / corner;
                float ratio = min(q.x, q.y) / max(max(q.x, q.y), 0.00001);
                float correction = (((-0.926054 * ratio + 3.1560099124908447) * ratio - 3.64122)
                                    * ratio + 1.26803) * ratio + 0.268531;
                float len = length(q);
                float dist = corner * (len - 1.0 / (1.0 - ratio * ratio * min(len, 1.0) * correction))
                           + min(max(fromSide.x, fromSide.y), 0.0);
                float2 normal = fromSide.x > fromSide.y ? float2(1, 0) : float2(0, 1);
                if (dot(q, q) > 0.0) normal = normalize(q);
                normal *= mix(float2(-1), float2(1), step(float2(0), p));
                if (ovalization > 0.0) {
                    float2 oval = p * float2(1.0, halfSize.x / halfSize.y);
                    oval /= max(length(oval), 0.00001);
                    normal = normalize(mix(normal, oval, ovalization));
                }
                return float3(dist, normal);
            }

            float offset(float amount, float height, float depth) {
                float t = clamp(depth / max(height, 0.00001), 0.0, 1.0);
                return amount - amount * sqrt(t * (2.0 - t));
            }

            float lodForRadius(float radius) {
                return log2(radius < 2.0 ? 1.0 + 0.5 * radius : radius);
            }

            // The recovered compositor key/fill lobe with a unit curvature and
            // an analytic, pixel-wide antialias ramp. The caller supplies the light.
            float rim(float3 shape, float height, float2 direction, float threshold, float rolloff) {
                float depth = -shape.x;
                float profile = 1.0 - clamp(depth / height, 0.0, 1.0);
                float angular = clamp((dot(shape.yz, direction) - threshold) / (1.0 - threshold), 0.0, 1.0);
                float k = profile * angular * clamp(0.5 + height - depth, 0.0, 1.0);
                return k / (1.0 + (1.0 - k) * rolloff);
            }
            """;

    static final String REGULAR = SHAPE + """
            uniform shader atlas;
            uniform float4 levels[6];
            uniform float lastLevel;
            uniform float2 captureSize;
            uniform float2 extent;
            uniform float2 center;
            uniform float2 light;
            uniform float density;
            uniform float dark;
            uniform float interaction;
            uniform float parallax;

            float3 levelAt(float2 p, int i) {
                float4 level = levels[0];
                if (i == 1) level = levels[1];
                else if (i == 2) level = levels[2];
                else if (i == 3) level = levels[3];
                else if (i == 4) level = levels[4];
                else if (i == 5) level = levels[5];
                float2 texel = clamp(p * level.zw / captureSize, float2(0.5), level.zw - 0.5);
                return float3(atlas.eval(level.xy + texel).rgb);
            }

            float3 backdrop(float2 p, float lod) {
                float level = clamp(lod, 0.0, lastLevel);
                int lower = int(floor(level));
                if (level == float(lower)) return levelAt(p, lower);
                return mix(levelAt(p, lower), levelAt(p, lower + 1), fract(level));
            }

            float3 faceSample(float2 p, float lod) {
                float shift = lod; // 0.25 * LOD in quarter-resolution texels
                return (backdrop(p + float2(-shift, -shift), lod) + backdrop(p + float2(shift, -shift), lod)
                      + backdrop(p + float2(-shift, shift), lod) + backdrop(p + float2(shift, shift), lod)) * 0.25;
            }

            float blurLod(float d, float size, float radius) {
                float depthRamp = size > 2.0 ? smoothstep(-0.5 * size, -1.0, d) : step(-1.0, d);
                float profile = 1.0 - 0.5 * depthRamp
                              + 0.5 * step(0.0, d);
                return lodForRadius(radius * profile);
            }

            float luminance(float3 rgb) {
                return dot(rgb, float3(0.2126, 0.7152, 0.0722));
            }

            half4 main(float2 px) {
                float2 p = px - center;
                if (abs(p.x) > extent.x * 0.5 + 48.0 * density
                        || abs(p.y) > extent.y * 0.5 + 56.0 * density) return half4(0);
                float3 shape = field(p, extent * 0.5, 0.0);
                float d = shape.x / density;
                float2 sampleAt = px - float2(parallax, 0.0);
                float coverage = clamp(0.5 - shape.x, 0.0, 1.0);
                float size = min(extent.x, extent.y) / density;
                float t = clamp((size - 48.0) / 112.0, 0.0, 1.0);

                float shadowOpacity = 0.0;
                float shadowAlpha = 0.0;
                float3 shadowColor = float3(0);
                if (coverage < 1.0 && (t > 0.0 || dark < 1.0)) {
                    float shadowD = field(p - float2(0, 8) * density, extent * 0.5, 0.0).x / density;
                    float s = clamp(0.5 + shadowD / 96.0, 0.0, 1.0) * 4.0 - 2.0;
                    float s2 = s * s;
                    float shadow = clamp(0.5 + s * (((0.002954483 * s2 - 0.034454346) * s2
                                 + 0.16821289) * s2 - 0.56054688), 0.0, 1.0);
                    shadowOpacity = shadow * mix(mix(0.5, 0.4, dark), 0.25, t);
                    shadowAlpha = shadowOpacity * mix(mix(0.12, 0.0, dark), 1.0, t);
                    if (t > 0.0 && shadowOpacity > 0.0) {
                        float amount = offset(min(0.625 * size, 75.0), 0.4 * size, -d);
                        float3 rgb = backdrop(sampleAt + shape.yz * (amount * density), lodForRadius(8.0 * density));
                        float y = luminance(rgb);
                        shadowColor = t * mix(0.88 * (y + 1.8 * (rgb - y)), float3(0.5 * y), dark);
                    }
                }
                if (coverage == 0.0) return half4(shadowColor * shadowOpacity, shadowAlpha);

                float inner = offset(max(-0.8 * size, -60.0), min(0.25 * size, 20.0), -d);
                float radius = (1.5 + 4.5 * t) * density * 0.4;
                float3 rgb = faceSample(sampleAt + shape.yz * (inner * density), blurLod(d + inner, size, radius));
                float edge = clamp((d + 1.0) * density, 0.0, 1.0) * 0.3;
                if (edge > 0.0) {
                    float outer = offset(0.2 * size, 0.125 * size, -d);
                    float3 lip = backdrop(sampleAt + shape.yz * (outer * density), blurLod(d + outer, size, radius));
                    rgb = mix(rgb, lip, edge);
                }
                float y = luminance(rgb);
                // Saturation is 1 in both Regular recipes, so RGB minus luma retains chroma.
                float3 face = 0.6 * (rgb - y) + mix(0.7 + 0.318 * y, 0.12 + 0.24 * y, dark);
                if (t > 0.0) {
                    float bleed = offset(0.35 * size, 0.35 * size, -d);
                    rgb = backdrop(sampleAt + shape.yz * (bleed * density),
                                   lodForRadius(min(0.7 * size, 160.0) * density * 0.2));
                    y = luminance(rgb);
                    float3 ambient = mix(0.9 + 0.1 * y + 1.2 * (rgb - y), float3(0.5 * y), dark);
                    float luma = mix(luminance(face), 1.0 - luminance(face), dark);
                    float weight = luma * luma * clamp(1.0 - d, 0.0, 1.0);
                    face = mix(face, ambient, weight * weight * t * mix(0.5, 0.8, dark));
                }
                float2 key = normalize(light);
                float specular = mix(0.26, 0.44, dark) * rim(shape, 2.1 * density, key, 0.05, 0.6)
                               + 0.08 * rim(shape, 1.5 * density, -key, 0.2, 0.5);
                face = clamp(face + specular + interaction * mix(-0.035, 0.07, dark), 0.0, 1.0);
                return half4(face * coverage + shadowColor * shadowOpacity * (1.0 - coverage),
                             coverage + shadowAlpha * (1.0 - coverage));
            }
            """;
}
