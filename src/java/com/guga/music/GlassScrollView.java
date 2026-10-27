package com.guga.music;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;

/**
 * 真液态玻璃滚动容器：诊断日志面板（bg_card 的 ScrollView）的透镜版。
 * 透镜作为静态背景先画，滚动内容后画，滚动时框架重绘会带上新底衬。
 */
public class GlassScrollView extends ScrollView implements GlassLensHost {

    private final GlassLensDelegate delegate = new GlassLensDelegate(24f);

    public GlassScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GlassScrollView(Context context) {
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
    protected void dispatchDraw(Canvas canvas) {
        if (delegate.draw(canvas, this)) {
            super.dispatchDraw(canvas);
            return;
        }
        super.dispatchDraw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        delegate.release();
    }
}
