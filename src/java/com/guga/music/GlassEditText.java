package com.guga.music;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;

/**
 * 真液态玻璃输入框：搜索框与各表单输入框的透镜版。
 * 透镜在 onDraw 里先画，文字与光标后画。默认圆角 20dp
 *（bg_input 16dp 与 bg_card 输入框 24dp 的折中，着色器还会
 * 钳制到半高以内，视觉差异可忽略）。
 */
public class GlassEditText extends EditText implements GlassLensHost {

    private final GlassLensDelegate delegate = new GlassLensDelegate(20f);

    public GlassEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GlassEditText(Context context) {
        super(context);
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
