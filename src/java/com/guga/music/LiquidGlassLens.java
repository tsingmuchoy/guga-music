package com.guga.music;

import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * 真液态玻璃透镜渲染器（API 33+）。
 *
 * 光学模型与 AGSL 着色器移植自 QWEA0/Liquid-Glass-Android（MIT）的
 * GlassLensRenderer（Liquid Glass 2.0 核心），用法与参数语义保持一致：
 * 实时圆角矩形 SDF → 斜面厚度剖面 → 屏幕空间法线（SDF 数值梯度）
 * → 折射（沿法线向内采样，边缘出现背景压缩带）→ 三通道色散
 * → 双瓣边缘高光（迎光侧 + 背光侧内壁反射）。
 *
 * 管线：底衬录制（带外扩边距，录制时跳过玻璃自身）→ RenderEffect 模糊
 * → 本着色器 → 输出。零 Bitmap 分配、零像素回读，背景是“活”的。
 *
 * 本版只做向内折射（与 iOS 一致的默认模式），不做外层合成节点与双形状
 * 液态融合、按压凸起——这些统一留给后续版本。着色器里的相关 uniform
 * 仍按中性值设置（shape2 禁用、press/touch 为 0）。
 *
 * 失败策略与 QWEA0 一致：AGSL 编译失败或效果构建失败时永久标记不可用，
 * 调用方回退到主题级半透明样式（bg_bar_glass），绝不带病渲染。
 */
public class LiquidGlassLens {

    /** 透镜 AGSL 着色器（与 QWEA0 的 LENS_AGSL 同源，MIT）。 */
    private static final String LENS_AGSL =
                        "            uniform shader content;\n" +
            "            uniform float  margin;\n" +
            "            uniform float2 viewSize;\n" +
            "            uniform float4 shape1;    // 真实主形状（触摸凸起的尺度用它）\n" +
            "            uniform float4 radii1;\n" +
            "            uniform float4 shape1L;   // 主形状：平边方向延伸到视图外，那条边就没有斜面（视图矩形外硬切）\n" +
            "            uniform float  rimSoft;   // 边缘柔化：折射带内沿法线方向的抹匀宽度（px，0 = 关）\n" +
            "            uniform float4 shape2;\n" +
            "            uniform float  radius2;\n" +
            "            uniform float  blendK;\n" +
            "            uniform float  bevel;\n" +
            "            uniform float  refractPx;\n" +
            "            uniform float  falloff;      // 折射剖面：> 0 为逆幂（引力透镜）衰减指数，0 = 平方斜面\n" +
            "            uniform float  refractDir;   // -1 向内采样（默认，与 iOS 一致：内侧压缩镜像）/ +1 向外（可选的凸透镜模式）\n" +
            "            uniform float2 sampleLo;     // 采样安全区（录制内容坐标）：区外没有内容，读到的是透明黑\n" +
            "            uniform float2 sampleHi;\n" +
            "            uniform float  dispersion;\n" +
            "            uniform float2 lightDir;\n" +
            "            uniform float  specStrength;\n" +
            "            uniform float  rimBandMax;   // 贴边高光带宽度上限（px，小控件按短边收）\n" +
            "            uniform float4 tintColor;\n" +
            "            uniform float  adaptiveTint;\n" +
            "            uniform float4 glassTint;\n" +
            "            uniform float  dimAmount;\n" +
            "            uniform float  satFactor;\n" +
            "            uniform float  press;\n" +
            "            uniform float2 touchPos;\n" +
            "            uniform float  touchAmp;\n" +
            "\n" +
            "            float sdRoundedBox(float2 p, float2 b, float r) {\n" +
            "                float2 q = abs(p) - b + r;\n" +
            "                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n" +
            "            }\n" +
            "\n" +
            "            // 逐角半径版：r = (左上, 右上, 右下, 左下)，按 p 所在象限选半径\n" +
            "            float sdRoundedBox4(float2 p, float2 b, float4 r) {\n" +
            "                float rx = (p.x > 0.0) ? ((p.y > 0.0) ? r.z : r.y) : ((p.y > 0.0) ? r.w : r.x);\n" +
            "                float2 q = abs(p) - b + rx;\n" +
            "                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rx;\n" +
            "            }\n" +
            "\n" +
            "            float sminPoly(float a, float b, float k) {\n" +
            "                float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);\n" +
            "                return mix(b, a, h) - k * h * (1.0 - h);\n" +
            "            }\n" +
            "\n" +
            "            // 斜面 / 法线 / 高光用透镜形状（平边已延伸出去，不产生边缘）\n" +
            "            float lensSDF(float2 p) {\n" +
            "                float d = sdRoundedBox4(p - shape1L.xy, shape1L.zw, radii1);\n" +
            "                if (shape2.z > 0.5) {\n" +
            "                    float d2 = sdRoundedBox(p - shape2.xy, shape2.zw, radius2);\n" +
            "                    if (blendK > 0.5) {\n" +
            "                        d = sminPoly(d, d2, blendK);\n" +
            "                    } else {\n" +
            "                        d = min(d, d2);\n" +
            "                    }\n" +
            "                }\n" +
            "                return d;\n" +
            "            }\n" +
            "\n" +
            "            half4 main(float2 coord) {\n" +
            "                float2 p = coord - float2(margin, margin);\n" +
            "                // 视图矩形外硬切：平边方向形状延伸到了视图外，靠这一刀收口，\n" +
            "                // 平边上不做羽化，两块玻璃贴边拼接时不会叠出一条发丝缝\n" +
            "                if (p.x < 0.0 || p.y < 0.0 || p.x > viewSize.x || p.y > viewSize.y) {\n" +
            "                    return half4(0.0);\n" +
            "                }\n" +
            "                float d = lensSDF(p);\n" +
            "\n" +
            "                // 覆盖率：1.5px 抗锯齿羽化，形状外完全透明\n" +
            "                float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);\n" +
            "                if (cov <= 0.004) {\n" +
            "                    return half4(0.0);\n" +
            "                }\n" +
            "\n" +
            "                // SDF 数值梯度 → 屏幕空间外法线\n" +
            "                float2 n = float2(\n" +
            "                    lensSDF(p + float2(1.0, 0.0)) - lensSDF(p - float2(1.0, 0.0)),\n" +
            "                    lensSDF(p + float2(0.0, 1.0)) - lensSDF(p - float2(0.0, 1.0))\n" +
            "                );\n" +
            "                float nLen = length(n);\n" +
            "                if (nLen > 0.0001) {\n" +
            "                    n = n / nLen;\n" +
            "                } else {\n" +
            "                    n = float2(0.0, -1.0);\n" +
            "                }\n" +
            "\n" +
            "                // 厚度剖面：t=1 平坦内部，t=0 边缘；slope 为斜面陡峭度（折射位移的比例）\n" +
            "                float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);\n" +
            "                float edge = 1.0 - t;\n" +
            "                float slope;\n" +
            "                if (falloff > 0.001) {\n" +
            "                    // 引力透镜式逆幂衰减：位移 ∝ (1 + x/k)^-p，核半径 k = 斜面宽度的 1/4，\n" +
            "                    // 减去带末端的值再归一化，贴边 = 1、带末端平滑落到 0。越贴边越剧烈：\n" +
            "                    // p = 2 时离边 k 处只剩 1/4，绝大部分弯折压在最外几个像素，\n" +
            "                    // 内侧只留一段缓慢回落的轻微放大尾巴\n" +
            "                    float gB = pow(5.0, -falloff);\n" +
            "                    slope = (pow(1.0 + 4.0 * t, -falloff) - gB) / (1.0 - gB);\n" +
            "                } else {\n" +
            "                    // 平方斜面：弯折沿整条斜面带均匀铺开\n" +
            "                    slope = edge * edge;\n" +
            "                }\n" +
            "\n" +
            "                // 折射：refractDir = -1 沿法线向内采样（默认，与 iOS 一致）——边缘是内侧背景的\n" +
            "                // 压缩镜像；+1 为可选的凸透镜模式，向外采样，形状外的背景被弯进边缘，\n" +
            "                // 靠近的内容还没进到玻璃下面就先出现在边缘，进来之后沿边缘延展。\n" +
            "                // RuntimeShader 子输入只保证\"输出裁剪区\"内可采样（Android 未暴露 Skia 的\n" +
            "                // childSampleRadius），向外采样必须让输出区覆盖整个外扩录制区——见 draw()\n" +
            "                // 里的外层合成节点；采样再由 sampleLo/Hi 钳在有内容的范围内\n" +
            "                float refr = refractPx * (1.0 + 0.6 * press);\n" +
            "                float2 offset = n * (refractDir * slope * refr);\n" +
            "\n" +
            "                // 触摸局部液态凸起（手指下方的局部放大：采样向触点收缩）\n" +
            "                if (touchAmp > 0.001) {\n" +
            "                    float2 tp = p - touchPos;\n" +
            "                    float tr = length(tp);\n" +
            "                    float sigma = max(max(shape1.z, shape1.w), 1.0);\n" +
            "                    float bump = touchAmp * exp(-(tr * tr) / (sigma * sigma * 0.30));\n" +
            "                    if (tr > 1.0) {\n" +
            "                        offset -= (tp / tr) * (bump * refr * 0.5);\n" +
            "                    }\n" +
            "                }\n" +
            "\n" +
            "                // 色散：三通道折射量不同（蓝光弯折最多），边缘出现光谱边纹\n" +
            "                float2 cR = coord + offset * (1.0 - dispersion * slope);\n" +
            "                float2 cG = coord + offset;\n" +
            "                float2 cB = coord + offset * (1.0 + dispersion * slope);\n" +
            "\n" +
            "                // 安全钳制到有内容的采样区，杜绝透明黑\n" +
            "                float2 lo = sampleLo;\n" +
            "                float2 hi = sampleHi;\n" +
            "                cR = clamp(cR, lo, hi);\n" +
            "                cG = clamp(cG, lo, hi);\n" +
            "                cB = clamp(cB, lo, hi);\n" +
            "                float3 col;\n" +
            "                if (rimSoft > 0.01 && slope > 0.001) {\n" +
            "                    // 边缘柔化：折射带内沿法线方向抹匀（宽度随斜面深度增长），\n" +
            "                    // 压缩带从一条硬线变成一段渐变，采样点仍钳在内容区内\n" +
            "                    float2 sm = n * (rimSoft * slope);\n" +
            "                    float2 cR1 = clamp(cR - sm, lo, hi);\n" +
            "                    float2 cR2 = clamp(cR + sm, lo, hi);\n" +
            "                    float2 cG1 = clamp(cG - sm, lo, hi);\n" +
            "                    float2 cG2 = clamp(cG + sm, lo, hi);\n" +
            "                    float2 cB1 = clamp(cB - sm, lo, hi);\n" +
            "                    float2 cB2 = clamp(cB + sm, lo, hi);\n" +
            "                    col = float3(\n" +
            "                        (content.eval(cR).r + content.eval(cR1).r + content.eval(cR2).r) / 3.0,\n" +
            "                        (content.eval(cG).g + content.eval(cG1).g + content.eval(cG2).g) / 3.0,\n" +
            "                        (content.eval(cB).b + content.eval(cB1).b + content.eval(cB2).b) / 3.0\n" +
            "                    );\n" +
            "                } else {\n" +
            "                    col = float3(\n" +
            "                        content.eval(cR).r,\n" +
            "                        content.eval(cG).g,\n" +
            "                        content.eval(cB).b\n" +
            "                    );\n" +
            "                }\n" +
            "\n" +
            "                // 饱和度（合并进同一 pass）；提饱和端走 vibrancy 曲线：低饱和\n" +
            "                // 像素多提、高饱和像素少提、极亮像素保护，避免线性提饱和把浓色\n" +
            "                // 推过曝（曲线与 GlassRuntimeEffects 的 API 36 滤镜一致）\n" +
            "                float lum = dot(col, float3(0.2126, 0.7152, 0.0722));\n" +
            "                if (satFactor <= 1.0) {\n" +
            "                    col = mix(float3(lum), col, satFactor);\n" +
            "                } else {\n" +
            "                    float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));\n" +
            "                    float room = 1.0 - smoothstep(0.2, 0.85, satNow);\n" +
            "                    float hl = 1.0 - smoothstep(0.75, 0.98, lum);\n" +
            "                    float amount = 1.0 + (satFactor - 1.0) * mix(0.3, 1.0, room * hl);\n" +
            "                    col = clamp(mix(float3(lum), col, amount), float3(0.0), float3(1.0));\n" +
            "                }\n" +
            "\n" +
            "                // 自适应染色（Regular）/ 压暗层（Clear）\n" +
            "                if (adaptiveTint > 0.5) {\n" +
            "                    // 逐像素自适应：按局部（模糊后）亮度在提亮/压暗之间平滑过渡，\n" +
            "                    // 玻璃跨明暗背景时不再整体翻转；曲线与全局版本\n" +
            "                    // （LiquidGlassView.onLuminanceSample）一致\n" +
            "                    float lumT = dot(col, float3(0.2126, 0.7152, 0.0722));\n" +
            "                    float e = smoothstep(0.35, 0.75, lumT);\n" +
            "                    col = mix(col, float3(1.0 - e), 0.14 + 0.08 * e);\n" +
            "                } else {\n" +
            "                    col = mix(col, tintColor.rgb, tintColor.a);\n" +
            "                }\n" +
            "                col = col * (1.0 - dimAmount);\n" +
            "\n" +
            "                // 使用方指定的玻璃本体色：按\"有色介质\"建模——吸收（保留背景明暗\n" +
            "                // 层次与折射细节）+ 少量散射（暗背景下也看得出色相）。\n" +
            "                // 位置在光照之前：染色属于透射，镜面高光属于表面反射，不该被染色\n" +
            "                if (glassTint.a > 0.002) {\n" +
            "                    float lumTint = dot(col, float3(0.2126, 0.7152, 0.0722));\n" +
            "                    float3 absorbed = col * mix(float3(1.0), glassTint.rgb, 0.85);\n" +
            "                    float3 scattered = glassTint.rgb * (0.38 * (1.0 - lumTint));\n" +
            "                    col = mix(col, clamp(absorbed + scattered, float3(0.0), float3(1.0)), glassTint.a);\n" +
            "                }\n" +
            "\n" +
            "                // 光照：同一法线场驱动。整圈亮边的明暗只由 dot(N, -L) 决定，没有与方向\n" +
            "                // 无关的常亮项——侧向（法线垂直于光线处）归零，不会留下一圈固定描边。\n" +
            "                // 两道对称的角度瓣（沿 iOS 26 控制中心截图里的控件一圈逐角量得）：迎光侧\n" +
            "                // 与背光侧峰值相等（背光侧是透明介质的内壁反射），瓣宽 pow 4.5（离轴 30°\n" +
            "                // 剩一半、45° 归零）；迎光侧另加一层向内的柔和辉光——iOS 左上角是亮线 +\n" +
            "                // 辉光，右下角只有亮线，两侧的边缘内侧都没有暗带\n" +
            "                float facing = dot(n, -lightDir);\n" +
            "                float lobeF = pow(max(facing, 0.0), 4.5);\n" +
            "                float lobeB = pow(max(-facing, 0.0), 4.5);\n" +
            "\n" +
            "                // 贴边亮线（中心在边内 1px，半宽 2px）+ 迎光侧辉光：从亮线内侧（边内 3px）起\n" +
            "                // 向内 1.5 次幂衰减，宽度与斜面弱相关、上限默认 6px、小控件按短边收。\n" +
            "                // 辉光不叠在亮线上，两侧亮线峰值相等\n" +
            "                float bandW = clamp(bevel * 0.3, 2.0, rimBandMax);\n" +
            "                float glowIn = clamp((-d - 1.0) / 2.0, 0.0, 1.0);\n" +
            "                float glow = glowIn * pow(clamp(1.0 - (-d - 3.0) / bandW, 0.0, 1.0), 1.5) * cov;\n" +
            "                float hair = clamp(1.0 - abs(d + 1.0) / 2.0, 0.0, 1.0) * cov;\n" +
            "                float spec = (hair * 0.70 * (lobeF + lobeB) + glow * 0.10 * lobeF)\n" +
            "                             * specStrength * (1.0 - 0.35 * press);\n" +
            "                col += float3(spec);\n" +
            "\n" +
            "                col = clamp(col, float3(0.0), float3(1.0));\n" +
            "                return half4(half3(col * cov), half(cov));\n" +
            "            }\n" +
            "        \n";

    private final RenderNode renderNode = new RenderNode("ApolloGlassLens");
    private final BackdropCapture backdropCapture = new BackdropCapture();

    // 透镜参数（px，由调用方按 density 换算后传入）
    private final float cornerPx;
    private final float bevelPx;
    private final float refractPx;
    private final float falloff;
    private final float dispersion;
    private final float blurPx;
    private final float spec;
    private final float lightX;
    private final float lightY;
    private final float rimBandMaxPx;
    private final float sat;

    private RuntimeShader shader;
    private boolean shaderBroken;

    private float sampleLoX;
    private float sampleLoY;
    private float sampleHiX;
    private float sampleHiY;
    private int lastWidth;
    private int lastHeight;
    private int lastMargin = -1;

    private final int[] location = new int[2];
    private final int[] parentLocation = new int[2];

    public LiquidGlassLens(float cornerPx, float bevelPx, float refractPx, float falloff,
                           float dispersion, float blurPx, float spec,
                           float lightX, float lightY, float rimBandMaxPx, float sat) {
        this.cornerPx = cornerPx;
        this.bevelPx = bevelPx;
        this.refractPx = refractPx;
        this.falloff = falloff;
        this.dispersion = dispersion;
        this.blurPx = blurPx;
        this.spec = spec;
        this.lightX = lightX;
        this.lightY = lightY;
        this.rimBandMaxPx = rimBandMaxPx;
        this.sat = sat;
    }

    /** 预编译着色器：失败则本透镜永久不可用，调用方应走回退样式。 */
    public boolean prepare() {
        if (shaderBroken) return false;
        if (shader != null) return true;
        try {
            shader = new RuntimeShader(LENS_AGSL);
            return true;
        } catch (Exception e) {
            shaderBroken = true;
            return false;
        }
    }

    /** AGSL 编译失败（个别设备驱动问题）时为 false，调用方应回退旧管线 */
    public boolean isAvailable() {
        return !shaderBroken;
    }

    /**
     * 绘制透镜玻璃到目标画布。
     *
     * @param canvas 目标画布（必须硬件加速）
     * @param glassView 玻璃视图（定位 + 录制时跳过自身）
     * @param backdropSource 背景来源视图（Apollo 里为 activity 根布局）
     * @param marginPx 背景录制外扩边距（px，覆盖模糊/折射采样越界）
     * @return 是否成功绘制；false 时调用方应回退
     */
    public boolean draw(Canvas canvas, View glassView, View backdropSource, int marginPx) {
        if (shaderBroken) return false;
        if (Build.VERSION.SDK_INT < 33) return false;
        if (!canvas.isHardwareAccelerated()) return false;
        if (backdropSource == null) return false;
        int width = glassView.getWidth();
        int height = glassView.getHeight();
        if (width <= 0 || height <= 0) return false;

        RuntimeShader sh = shader;
        if (sh == null) {
            if (!prepare()) return false;
            sh = shader;
        }

        // 计算相对背景视图的偏移（屏幕坐标差：兼容滚动容器）
        glassView.getLocationOnScreen(location);
        backdropSource.getLocationOnScreen(parentLocation);
        float offsetX = (float) (location[0] - parentLocation[0]);
        float offsetY = (float) (location[1] - parentLocation[1]);

        int recW = width + 2 * marginPx;
        int recH = height + 2 * marginPx;

        // 向内采样时安全区恒为视图矩形（无需 4px 量化）
        float loX = marginPx + 1f;
        float loY = marginPx + 1f;
        float hiX = marginPx + width - 1f;
        float hiY = marginPx + height - 1f;
        boolean boundsChanged = loX != sampleLoX || loY != sampleLoY
                || hiX != sampleHiX || hiY != sampleHiY;

        if (boundsChanged || width != lastWidth || height != lastHeight || marginPx != lastMargin) {
            try {
                renderNode.setRenderEffect(buildEffect(sh, marginPx, width, height));
            } catch (Exception e) {
                shaderBroken = true;
                return false;
            }
            sampleLoX = loX;
            sampleLoY = loY;
            sampleHiX = hiX;
            sampleHiY = hiY;
            lastWidth = width;
            lastHeight = height;
            lastMargin = marginPx;
        }

        // 录制区域向四周外扩 margin：模糊在边缘处能采到真实内容而非透明黑
        renderNode.setPosition(-marginPx, -marginPx, width + marginPx, height + marginPx);
        recordBackdrop(backdropSource, glassView, offsetX, offsetY, marginPx, recW, recH);
        // 形状覆盖率由着色器输出（形状外 alpha=0），无需 clipPath
        canvas.drawRenderNode(renderNode);
        return true;
    }

    /** 把背景来源画进 renderNode（节点局部坐标，原点在外扩后的录制区左上角） */
    private void recordBackdrop(View parent, View glassView,
                                float offsetX, float offsetY,
                                int marginPx, int recW, int recH) {
        Canvas recordingCanvas = renderNode.beginRecording(recW, recH);
        try {
            recordingCanvas.translate(marginPx - offsetX, marginPx - offsetY);
            backdropCapture.draw(recordingCanvas, parent, glassView);
        } finally {
            renderNode.endRecording();
        }
    }

    /** 构建效果链：模糊（内层）→ 透镜着色器（外层） */
    private RenderEffect buildEffect(RuntimeShader sh, int marginPx, int width, int height) {
        float cx = width / 2f;
        float cy = height / 2f;
        float hw = width / 2f;
        float hh = height / 2f;
        sh.setFloatUniform("margin", (float) marginPx);
        sh.setFloatUniform("viewSize", (float) width, (float) height);
        sh.setFloatUniform("shape1", cx, cy, hw, hh);
        sh.setFloatUniform("radii1", cornerPx, cornerPx, cornerPx, cornerPx);
        // 无平边：透镜形状与主形状相同（悬浮 pill 四角均为圆角）
        sh.setFloatUniform("shape1L", cx, cy, hw, hh);
        sh.setFloatUniform("rimSoft", 0f);
        // 副形状禁用（shape2.z <= 0.5 时着色器跳过融合分支）
        sh.setFloatUniform("shape2", 0f, 0f, 0f, 0f);
        sh.setFloatUniform("radius2", 0f);
        sh.setFloatUniform("blendK", 0f);
        sh.setFloatUniform("bevel", bevelPx);
        sh.setFloatUniform("refractPx", refractPx);
        sh.setFloatUniform("falloff", falloff);
        sh.setFloatUniform("refractDir", -1f);
        sh.setFloatUniform("sampleLo", sampleLoX, sampleLoY);
        sh.setFloatUniform("sampleHi", sampleHiX, sampleHiY);
        sh.setFloatUniform("dispersion", dispersion);
        sh.setFloatUniform("lightDir", lightX, lightY);
        sh.setFloatUniform("specStrength", spec);
        sh.setFloatUniform("rimBandMax", rimBandMaxPx);
        // 本体染色关闭：纯折射 + 高光，不额外着色
        sh.setFloatUniform("tintColor", 0f, 0f, 0f, 0f);
        sh.setFloatUniform("adaptiveTint", 0f);
        sh.setFloatUniform("glassTint", 0f, 0f, 0f, 0f);
        sh.setFloatUniform("dimAmount", 0f);
        sh.setFloatUniform("satFactor", sat);
        sh.setFloatUniform("press", 0f);
        sh.setFloatUniform("touchPos", 0f, 0f);
        sh.setFloatUniform("touchAmp", 0f);

        RenderEffect lens = RenderEffect.createRuntimeShaderEffect(sh, "content");
        if (blurPx > 0.01f) {
            return RenderEffect.createChainEffect(
                    lens,
                    RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP));
        }
        return lens;
    }

    public void release() {
        renderNode.discardDisplayList();
        shader = null;
    }
}
