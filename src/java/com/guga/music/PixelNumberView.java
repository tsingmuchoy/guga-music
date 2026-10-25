package com.guga.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** 点阵数字视图：数字按 5x7 方点矩阵绘制（LED/像素字风格），非数字字符（小时/分/秒、空格）
 *  仍按普通文字绘制。方点为 Canvas 矢量绘制、随尺寸任意缩放不糊，包内不引任何字体文件，
 *  符合 App「美术全部矢量、禁位图」口径。统计页顶部三个大数字用它替换普通 TextView。 */
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

    private String text = "";
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float textSizePx = 48;
    private float xOffset = 0; // 定宽（EXACTLY）时内容水平居中的偏移

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
        float w = 0;
        float c = cell();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '0' && ch <= '9') w += digitWidth();
            else w += textPaint.measureText(text, i, i + 1);
        }
        if (text.length() > 0 && text.charAt(text.length() - 1) >= '0'
                && text.charAt(text.length() - 1) <= '9') w -= c; // 末位数字不留尾字距
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        int h = (int) Math.ceil(Math.max(7 * c, fm.descent - fm.ascent));
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int measuredW;
        if (widthMode == MeasureSpec.EXACTLY) {
            measuredW = widthSize;
            xOffset = Math.max(0, (widthSize - w) / 2f);
        } else if (widthMode == MeasureSpec.AT_MOST) {
            measuredW = (int) Math.min(Math.ceil(w), widthSize);
            xOffset = 0;
        } else {
            measuredW = (int) Math.ceil(w);
            xOffset = 0;
        }
        setMeasuredDimension(measuredW, resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float c = cell();
        float half = c * 0.42f; // 正方点边长 = 0.84 * cell，与之前圆点直径同视觉分量
        float digitTop = (getHeight() - 7 * c) / 2f;
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        float x = xOffset;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '0' && ch <= '9') {
                String[] g = GLYPHS[ch - '0'];
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
    }
}
