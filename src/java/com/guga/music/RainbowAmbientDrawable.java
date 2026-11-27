package com.guga.music;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** 彩虹桥的连续七色环境光；着色器仅在尺寸变化时创建，无逐帧分配或动画。 */
public final class RainbowAmbientDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] washColors;
    private Shader spectrum;
    private Shader coolLight;
    private Shader warmLight;
    private int alpha = 255;

    public RainbowAmbientDrawable(int[] colors) {
        washColors = colors.clone();
        float[] hsv = new float[3];
        for (int i = 0; i < washColors.length; i++) {
            // 背景透过深色底衬会降饱和；环境光比按钮色带更饱满，避免混成灰。
            Color.colorToHSV(washColors[i], hsv);
            hsv[1] = Math.max(0.62f, hsv[1]);
            washColors[i] = Color.HSVToColor(0x55, hsv);
        }
    }

    @Override protected void onBoundsChange(Rect bounds) {
        float w = bounds.width(), h = bounds.height();
        if (w <= 0 || h <= 0) return;
        spectrum = new LinearGradient(bounds.left, bounds.top,
                bounds.left + w * 0.35f, bounds.bottom, washColors, null, Shader.TileMode.CLAMP);
        float radius = Math.max(w, h) * 0.7f;
        coolLight = new RadialGradient(bounds.left + w * 0.9f, bounds.top + h * 0.18f,
                radius, 0x087ADCEB, 0x007ADCEB, Shader.TileMode.CLAMP);
        warmLight = new RadialGradient(bounds.left + w * 0.08f, bounds.top + h * 0.82f,
                radius, 0x08C4A0F4, 0x00C4A0F4, Shader.TileMode.CLAMP);
    }

    @Override public void draw(Canvas canvas) {
        paint.setShader(null);
        paint.setColor(0xFF101421);
        paint.setAlpha(alpha);
        canvas.drawRect(getBounds(), paint);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(alpha);
        drawLayer(canvas, spectrum);
        drawLayer(canvas, coolLight);
        drawLayer(canvas, warmLight);
    }

    private void drawLayer(Canvas canvas, Shader shader) {
        if (shader == null) return;
        paint.setShader(shader);
        canvas.drawRect(getBounds(), paint);
    }

    @Override public void setAlpha(int alpha) {
        this.alpha = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
