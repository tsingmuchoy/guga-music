package com.guga.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/** 轻量色板示意：只绘制矢量，无位图、动画或额外背景采样。 */
public final class ThemePreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ThemeUtil.Def theme;
    private final boolean flat;
    private LinearGradient ambient;
    private LinearGradient sheen;

    public ThemePreviewView(Context context, ThemeUtil.Def theme) {
        super(context);
        this.theme = theme;
        flat = "material".equals(theme.id);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        ambient = new LinearGradient(0, 0, w, h, theme.grad, null, Shader.TileMode.CLAMP);
        sheen = new LinearGradient(0, 0, 0, h, 0x48FFFFFF, 0x10FFFFFF, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        canvas.scale(getWidth() / 64f, getHeight() / 56f);
        paint.setShader(null);
        paint.setColor(theme.bg);
        canvas.drawRoundRect(0, 0, 64, 56, 14, 14, paint);
        // 渐变需使用原始像素坐标，在缩放画布前通过本地矩阵归一化。
        canvas.restoreToCount(save);
        save = canvas.save();
        float sx = getWidth() / 64f, sy = getHeight() / 56f;
        paint.setShader(flat ? null : ambient);
        paint.setColor(theme.accent);
        canvas.drawRoundRect(5 * sx, 5 * sy, 59 * sx, 51 * sy, 11 * sx, 11 * sy, paint);
        paint.setShader(flat ? null : sheen);
        paint.setColor(flat ? theme.surface : 0xFFFFFFFF);
        canvas.drawRoundRect(10 * sx, 17 * sy, 54 * sx, 43 * sy, 9 * sx, 9 * sy, paint);
        paint.setShader(null);
        paint.setColor(0xEEFFFFFF);
        canvas.drawRoundRect(17 * sx, 24 * sy, 39 * sx, 27 * sy, sx, sy, paint);
        paint.setColor(0x99FFFFFF);
        canvas.drawRoundRect(17 * sx, 32 * sy, 31 * sx, 34 * sy, sx, sy, paint);
        canvas.restoreToCount(save);
    }
}
