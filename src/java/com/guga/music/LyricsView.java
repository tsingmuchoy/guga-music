package com.guga.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** 播放页歌词窗：显示当前行前后共 5 行，当前行主题色加粗，远处行渐隐 */
public class LyricsView extends View {

    private List<Lyrics.Line> lines = new ArrayList<>();
    private String placeholder = "♪ ♪ ♪";
    private int cur = -1;
    private final Paint pCur = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LyricsView(Context c, AttributeSet a) {
        super(c, a);
        float sp = getResources().getDisplayMetrics().scaledDensity;
        pCur.setTextSize(17 * sp);
        pCur.setFakeBoldText(true);
        pCur.setColor(ThemeUtil.color(c, R.attr.gAccent));
        pCur.setTextAlign(Paint.Align.CENTER);
        pNear.setTextSize(14.5f * sp);
        pNear.setColor(ThemeUtil.color(c, R.attr.gTextSec));
        pNear.setTextAlign(Paint.Align.CENTER);
        pFar.setTextSize(14 * sp);
        pFar.setColor(ThemeUtil.color(c, R.attr.gTextFaint));
        pFar.setTextAlign(Paint.Align.CENTER);
        pHint.setTextSize(14 * sp);
        pHint.setColor(ThemeUtil.color(c, R.attr.gTextFaint));
        pHint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0) pCur.setShader(new android.graphics.LinearGradient(0, 0, w, 0,
                ThemeUtil.gradColors(getContext()), null, android.graphics.Shader.TileMode.CLAMP));
    }

    public void setLines(List<Lyrics.Line> l, String src) {
        lines = l == null ? new ArrayList<>() : l;
        cur = -1;
        invalidate();
    }

    public void setPlaceholder(String s) {
        placeholder = s;
        lines = new ArrayList<>();
        cur = -1;
        invalidate();
    }

    public boolean hasLines() { return !lines.isEmpty(); }

    public void setPosition(long posMs) {
        if (lines.isEmpty()) return;
        int idx = Lyrics.indexAt(lines, posMs);
        if (idx != cur) {
            cur = idx;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (lines.isEmpty()) {
            Paint.FontMetrics fm = pHint.getFontMetrics();
            canvas.drawText(placeholder, w / 2, h / 2 - (fm.ascent + fm.descent) / 2, pHint);
            return;
        }
        int center = cur < 0 ? 0 : cur;
        float rowH = h / 5f;
        for (int off = -2; off <= 2; off++) {
            int i = center + off;
            if (i < 0 || i >= lines.size()) continue;
            Paint p = off == 0 ? pCur : Math.abs(off) == 1 ? pNear : pFar;
            Paint.FontMetrics fm = p.getFontMetrics();
            float y = h / 2 + off * rowH - (fm.ascent + fm.descent) / 2;
            String text = lines.get(i).text;
            // 过长截断防溢出
            float maxW = w - 24;
            if (p.measureText(text) > maxW) {
                while (text.length() > 1 && p.measureText(text + "…") > maxW) text = text.substring(0, text.length() - 1);
                text = text + "…";
            }
            canvas.drawText(text, w / 2, y, p);
        }
    }
}
