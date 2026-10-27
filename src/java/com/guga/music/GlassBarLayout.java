package com.guga.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * 真液态玻璃底栏容器（API 33+ 才启用透镜，见 setLensEnabled）。
 *
 * 启用时：背景的静态半透明 drawable 被清空，改由 dispatchDraw 先画
 * LiquidGlassLens 的实时折射（底衬来自 activity 根布局，录制时跳过本栏
 * 自身），再画子内容；着色器自带边缘高光，因此布局里原有的 bg_glass_rim
 * 覆盖层（按约定为最后一个子级）会被一并隐藏，关闭时恢复。
 *
 * 未启用时（非 glass 主题、API < 33、着色器编译失败）：行为与普通
 * FrameLayout 完全一致，走 bg_bar_glass + bg_glass_rim 的主题级样式，
 * 透镜引擎连实例都不创建——未选中的主题不占用任何运行时资源。
 */
public class GlassBarLayout extends FrameLayout {

    private LiquidGlassLens lens;
    private View backdropSource;
    private boolean lensOn;
    private Drawable savedBackground;
    private View rimView;

    public GlassBarLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // 约定：布局中最后一个子级为 bg_glass_rim 覆盖层（llMini / llTabs 均如此）
        if (getChildCount() >= 2) {
            rimView = getChildAt(getChildCount() - 1);
        }
    }

    /**
     * 开关真液态玻璃。只应在 glass 主题且 API 33+ 时由宿主 Activity 调用；
     * 着色器预编译失败时静默保持关闭，调用方无需处理。
     */
    public void setLensEnabled(boolean on, View backdropSource) {
        if (on && Build.VERSION.SDK_INT >= 33 && backdropSource != null) {
            float d = getResources().getDisplayMetrics().density;
            LiquidGlassLens l = new LiquidGlassLens(
                    26f * d,   // 圆角：与 bg_bar_glass 的 26dp 一致
                    8f * d,    // 斜面厚度
                    5f * d,    // 折射量
                    2.0f,      // 逆幂衰减：弯折压在最外几个像素
                    0.5f,      // 色散
                    6f * d,    // 底衬模糊
                    1.0f,      // 高光强度
                    -0.5f, -0.866f, // 光源方向（左上）
                    6f * d,    // 辉光带宽上限
                    1.15f);    // 饱和度（轻微提 vibrancy）
            if (!l.prepare()) {
                lensOn = false;
                return;
            }
            lens = l;
            this.backdropSource = backdropSource;
            savedBackground = getBackground();
            setBackground(null);
            final float radius = 26f * d;
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            if (rimView != null) rimView.setVisibility(View.GONE);
            lensOn = true;
        } else {
            revertLens();
        }
        invalidate();
    }

    public boolean isLensOn() {
        return lensOn;
    }

    /** 回退到主题级样式：恢复背景与覆盖层，透镜引擎释放。 */
    public void revertLens() {
        if (!lensOn && lens == null) return;
        lensOn = false;
        if (lens != null) {
            lens.release();
            lens = null;
        }
        backdropSource = null;
        if (savedBackground != null) {
            setBackground(savedBackground);
            savedBackground = null;
        }
        setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        if (rimView != null) rimView.setVisibility(View.VISIBLE);
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (lensOn && lens != null && canvas.isHardwareAccelerated()) {
            float d = getResources().getDisplayMetrics().density;
            if (lens.draw(canvas, this, backdropSource, (int) (16f * d + 0.5f))) {
                super.dispatchDraw(canvas);
                return;
            }
            // 绘制失败（驱动问题等）：回退主题级样式，下一帧起恢复背景
            revertLens();
        }
        super.dispatchDraw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (lens != null) {
            lens.release();
            lens = null;
        }
        lensOn = false;
    }
}
