package com.guga.music;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 真液态玻璃底栏容器（API 33+ 且 glass 主题时启用透镜）。
 *
 * 启用时：背景的静态半透明 drawable 被清空，改由 dispatchDraw 先画
 * LiquidGlassLens 的实时折射，再画子内容；着色器自带边缘高光，
 * 因此布局里原有的 bg_glass_rim 覆盖层（按约定为最后一个子级）
 * 会被一并隐藏，关闭时恢复。
 *
 * 未启用时行为与普通 FrameLayout 完全一致，走 bg_bar_glass +
 * bg_glass_rim 的主题级样式，透镜引擎连实例都不创建。
 */
public class GlassBarLayout extends FrameLayout implements GlassLensHost {

    private final GlassLensDelegate delegate = new GlassLensDelegate(26f);

    public GlassBarLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // 约定：布局中最后一个子级为 bg_glass_rim 覆盖层（llMini / llTabs 均如此）
        if (getChildCount() >= 2) {
            delegate.setRimView(getChildAt(getChildCount() - 1));
        }
    }

    @Override
    public void setLensEnabled(boolean on, View backdrop) {
        delegate.setEnabled(this, on, backdrop);
    }

    @Override
    public boolean isLensOn() {
        return delegate.isOn();
    }

    /** 回退到主题级样式：恢复背景与覆盖层，透镜引擎释放 */
    public void revertLens() {
        delegate.revert(this);
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
