package com.guga.music;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * 真液态玻璃卡片容器：bg_card / bg_sheet / bg_dialog 卡片的透镜版。
 * 行为与 GlassBarLayout 一致，只是父类是 LinearLayout（卡片布局
 * 依赖 orientation 纵向/横向排列子级，不能换成 FrameLayout）。
 * 默认圆角 24dp（与 bg_card / bg_sheet 一致）。
 */
public class GlassLinearLayout extends LinearLayout implements GlassLensHost {

    private final GlassLensDelegate delegate = new GlassLensDelegate(24f);

    public GlassLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GlassLinearLayout(Context context) {
        super(context);
    }

    public void setCornerDp(float dp) {
        delegate.setCornerDp(dp);
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
