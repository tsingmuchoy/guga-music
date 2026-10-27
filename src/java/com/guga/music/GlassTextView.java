package com.guga.music;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

/**
 * 真液态玻璃文本行：设置页行、主题行、芯片的透镜版。
 * 透镜在 onDraw 里先画、文字后画，文字压在折射之上可读。
 * 选中态芯片调用 setLensTint(accent) 保留底色（85% 染色 + 折射 + 高光），
 * 避免选中/未选中在透镜下长得一样。
 */
public class GlassTextView extends TextView implements GlassLensHost {

    private final GlassLensDelegate delegate = new GlassLensDelegate(24f);

    public GlassTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GlassTextView(Context context) {
        super(context);
    }

    public void setLensTint(int argb) {
        delegate.setTint(this, argb);
    }

    @Override
    public void setLensEnabled(boolean on, View backdrop) {
        delegate.setEnabled(this, on, backdrop);
    }

    @Override
    public boolean isLensOn() {
        return delegate.isOn();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (delegate.draw(canvas, this)) {
            super.onDraw(canvas);
            return;
        }
        super.onDraw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        delegate.release();
    }
}
