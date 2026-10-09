package com.guga.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** 逐字歌词视图（全屏歌词页当前行专用）：按字/词的起止时间逐字扫光，
 *  已唱部分用主题渐变着色，正在唱的字按进度部分填充；长行按词自动折行居中。 */
public class SyllableView extends View {

private List<Lyrics.Word> words;
private long posMs;
private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
private final Paint sungPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
private int[] grad;
private float textPx;
private float lineHeightPx;
private int layoutWidth = -1;
private final List<List<Lyrics.Word>> rows = new ArrayList<>();
private final List<Float> rowWidths = new ArrayList<>();

public SyllableView(Context c) { super(c); }

public void setWords(List<Lyrics.Word> ws, float textSizeSp, int baseColor, int[] gradColors) {
words = ws;
grad = gradColors;
float density = getResources().getDisplayMetrics().density;
textPx = textSizeSp * density;
basePaint.setTextSize(textPx);
sungPaint.setTextSize(textPx);
basePaint.setTypeface(Typeface.DEFAULT_BOLD);
sungPaint.setTypeface(Typeface.DEFAULT_BOLD);
basePaint.setColor(baseColor);
lineHeightPx = textPx * 1.42f;
layoutWidth = -1;
requestLayout();
invalidate();
}

public void setPosition(long pos) {
if (Math.abs(pos - posMs) >= 16) { posMs = pos; invalidate(); }
}

private void ensureLayout(int w) {
if (w == layoutWidth || words == null) return;
layoutWidth = w;
rows.clear(); rowWidths.clear();
List<Lyrics.Word> row = new ArrayList<>();
float rw = 0;
for (Lyrics.Word word : words) {
float ww = basePaint.measureText(word.text);
if (!row.isEmpty() && rw + ww > w) {
rows.add(row); rowWidths.add(rw);
row = new ArrayList<>(); rw = 0;
}
row.add(word); rw += ww;
}
if (!row.isEmpty()) { rows.add(row); rowWidths.add(rw); }
if (rows.isEmpty()) { rows.add(new ArrayList<>()); rowWidths.add(0f); }
}

@Override protected void onMeasure(int widthSpec, int heightSpec) {
int w = MeasureSpec.getSize(widthSpec);
ensureLayout(Math.max(0, w));
int h = (int) Math.ceil(rows.size() * lineHeightPx);
setMeasuredDimension(w, h);
}

@Override protected void onDraw(Canvas canvas) {
if (words == null || words.isEmpty()) return;
int w = getWidth();
ensureLayout(w);
if (grad != null && grad.length > 0) {
sungPaint.setShader(new LinearGradient(0, 0, Math.max(1, w), 0, grad, null, Shader.TileMode.CLAMP));
}
float baseline0 = textPx * 1.08f;
for (int r = 0; r < rows.size(); r++) {
float x = Math.max(0, (w - rowWidths.get(r)) / 2f);
float baseline = baseline0 + r * lineHeightPx;
for (Lyrics.Word word : rows.get(r)) {
float ww = basePaint.measureText(word.text);
long wEnd = word.startMs + Math.max(1, word.durMs);
if (posMs >= wEnd) {
canvas.drawText(word.text, x, baseline, sungPaint);
} else if (posMs <= word.startMs) {
canvas.drawText(word.text, x, baseline, basePaint);
} else {
canvas.drawText(word.text, x, baseline, basePaint);
float frac = (posMs - word.startMs) / (float) (wEnd - word.startMs);
canvas.save();
canvas.clipRect(x, baseline - textPx * 1.2f, x + ww * frac, baseline + textPx * 0.4f);
canvas.drawText(word.text, x, baseline, sungPaint);
canvas.restore();
}
x += ww;
}
}
}
}
