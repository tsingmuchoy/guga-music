package com.guga.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** 点阵数字视图：数字 0-9 与字母 H/M/S 按 5x7 方点矩阵绘制（LED/像素字风格），其余字符
 *  仍按普通文字绘制。方点为 Canvas 矢量绘制、随尺寸任意缩放不糊，包内不引任何字体文件，
 *  符合 App「美术全部矢量、禁位图」口径。列宽不够时整体等比缩小、永不截断（统计页三列
 *  等宽，累计时长一格写 "3H47M" 这类长串也不被切掉）。统计页顶部三个大数字与榜单名次、
 *  计数用它替换普通 TextView。 */
public class PixelNumberView extends View {

    // 5x7 点阵字模：每行 5 位，1=亮点
    private static final String[][] GLYPHS = {
            {"01110", "10001", "10011", "10101", "11001", "10001", "01110"}, // 0
            {"00100", "01100", "00100", "00100", "00100", "00100", "01110"}, // 1
            {"01110", "10001", "00001", "00010", "00100", "01000", "11111"}, // 2
            {"01110", "10001", "00001", "00110", "00001", "10001", "01110"}, // 3
            {"00010", "00110", "01010", "10010", "11111", "00010", "00010"}, // 4
            {"11111", "10000", "11110", "00001", "00001", "10001", "01110"}, // 5
            {"00110", "01000", "10000", "11110", "10001", "10001", "01110"}, // 6
            {"11111", "00001", "00010", "00100", "01000", "01000", "01000"}, // 7
            {"01110", "10001", "10001", "01110", "10001", "10001", "01110"}, // 8
            {"01110", "10001", "10001", "01111", "00001", "00010", "01100"}, // 9
    };

    // 像素字母：H（小时）、M（分）、S（秒），与数字同 5x7 矩阵
    private static String[] glyph(char ch) {
        if (ch >= '0' && ch <= '9') return GLYPHS[ch - '0'];
        switch (ch) {
            case 'H': return new String[]{"10001", "10001", "10001", "11111", "10001", "10001", "10001"};
            case 'M': return new String[]{"10001", "11011", "10101", "10101", "10001", "10001", "10001"};
            case 'S': return new String[]{"01110", "10001", "10000", "01110", "00001", "10001", "01110"};
            default: return null;
        }
    }

    private String text = "";
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float textSizePx = 48;
    private float fitScale = 1f; // 列宽/行高不够时整体等比缩小的倍率（<=1），由 onMeasure 算出

    public PixelNumberView(Context context, AttributeSet attrs) {
        super(context, attrs);
        int color = 0xFFFFFFFF;
        if (attrs != null) {
            android.content.res.TypedArray a = context.obtainStyledAttributes(attrs,
                    new int[]{android.R.attr.textSize, android.R.attr.textColor, android.R.attr.text});
            textSizePx = a.getDimension(0, textSizePx);
            color = a.getColor(1, color);
            CharSequence t = a.getText(2);
            if (t != null) text = t.toString();
            a.recycle();
        }
        dotPaint.setColor(color);
        dotPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(color);
        textPaint.setTextSize(textSizePx);
    }

    public void setText(String s) {
        text = s == null ? "" : s;
        requestLayout();
        invalidate();
    }

    public void setTextColor(int color) {
        dotPaint.setColor(color);
        textPaint.setColor(color);
        invalidate();
    }

    public void setTextSize(float sp) {
        textSizePx = sp * getResources().getDisplayMetrics().scaledDensity;
        textPaint.setTextSize(textSizePx);
        requestLayout();
        invalidate();
    }

    private float cell() {
        return textSizePx / 7f;
    }

    private float digitWidth() {
        return cell() * 6; // 5 列 + 1 列字距
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float wNat = 0;
        float c = cell();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (glyph(ch) != null) wNat += digitWidth();
            else wNat += textPaint.measureText(text, i, i + 1);
        }
        if (text.length() > 0 && glyph(text.charAt(text.length() - 1)) != null) wNat -= c; // 末位像素字不留尾字距
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float hNat = Math.max(7 * c, fm.descent - fm.ascent);
        // 列宽/行高不够时整体缩小、永不截断（统计页三列等宽，长串如 123H45M 也能完整显示）
        fitScale = 1f;
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        if (wNat > 0) {
            if (widthMode != MeasureSpec.UNSPECIFIED) fitScale = Math.min(fitScale, widthSize / wNat);
            if (heightMode != MeasureSpec.UNSPECIFIED) fitScale = Math.min(fitScale, heightSize / hNat);
            fitScale = Math.min(1f, fitScale);
        }
        float w = wNat * fitScale;
        float h = hNat * fitScale;
        int measuredW;
        if (widthMode == MeasureSpec.EXACTLY) measuredW = widthSize;
        else if (widthMode == MeasureSpec.AT_MOST) measuredW = (int) Math.min(Math.ceil(w), widthSize);
        else measuredW = (int) Math.ceil(w);
        setMeasuredDimension(measuredW, resolveSize((int) Math.ceil(h), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float c = cell();
        float half = c * 0.42f; // 正方点边长 = 0.84 * cell，与之前圆点直径同视觉分量
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float hNat = Math.max(7 * c, fm.descent - fm.ascent);
        float wNat = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (glyph(ch) != null) wNat += digitWidth();
            else wNat += textPaint.measureText(text, i, i + 1);
        }
        if (text.length() > 0 && glyph(text.charAt(text.length() - 1)) != null) wNat -= c;
        float digitTop = (hNat - 7 * c) / 2f;
        float baseline = hNat / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.save();
        canvas.translate((getWidth() - wNat * fitScale) / 2f, (getHeight() - hNat * fitScale) / 2f);
        canvas.scale(fitScale, fitScale);
        float x = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String[] g = glyph(ch);
            if (g != null) {
                for (int row = 0; row < 7; row++) {
                    for (int col = 0; col < 5; col++) {
                        if (g[row].charAt(col) == '1') {
                            float cx = x + col * c + c / 2f;
                            float cy = digitTop + row * c + c / 2f;
                            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, dotPaint);
                        }
                    }
                }
                x += digitWidth();
            } else {
                canvas.drawText(text, i, i + 1, x, baseline, textPaint);
                x += textPaint.measureText(text, i, i + 1);
            }
        }
        canvas.restore();
    }
}
