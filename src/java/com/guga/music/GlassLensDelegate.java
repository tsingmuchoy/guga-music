package com.guga.music;

import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * 透镜生命周期委托：给不同父类（FrameLayout / LinearLayout / TextView /
 * EditText / ScrollView）的玻璃视图共用同一套开启、绘制、回退逻辑，
 * 避免每个子类各写一份透镜代码。
 *
 * 启用时：保存原背景并清空，改由 dispatchDraw/onDraw 先画透镜再画内容；
 * 着色器自带边缘高光，因此可选的 rim 覆盖层（如底栏的 bg_glass_rim）
 * 会被一并隐藏，关闭时恢复。未启用时行为与原视图完全一致，
 * 透镜引擎连实例都不创建。
 */
public class GlassLensDelegate {

    private LiquidGlassLens lens;
    private View backdropSource;
    private boolean on;
    private Drawable savedBackground;
    private float cornerDp;
    private int tintArgb;
    private View rimView;
    private int savedRimVisibility;
    private ViewOutlineProvider savedOutlineProvider;

    public GlassLensDelegate(float cornerDp) {
        this.cornerDp = cornerDp;
    }

    public void setCornerDp(float dp) {
        cornerDp = dp;
    }

    /** 仅底栏使用：透镜自带高光时隐藏原 bg_glass_rim 覆盖层 */
    public void setRimView(View v) {
        rimView = v;
    }

    public boolean isOn() {
        return on;
    }

    /**
     * 选中态芯片等需要保留底色时用的本体染色（straight-alpha ARGB，
     * a=0 关闭）。着色器内按 tint alpha 向染色混合，折射与高光仍在。
     */
    public void setTint(View host, int argb) {
        tintArgb = argb;
        if (lens != null) lens.setTint(argb);
        if (on) host.invalidate();
    }

    public void setEnabled(View host, boolean enable, View backdrop) {
        if (enable && Build.VERSION.SDK_INT >= 33 && backdrop != null) {
            if (on && backdropSource == backdrop) return; // 幂等：重复绑定不再重建
            float d = host.getResources().getDisplayMetrics().density;
            LiquidGlassLens l = new LiquidGlassLens(
                    cornerDp * d, // 圆角（着色器内还会钳制到半高以内，药丸芯片自动成全圆角）
                    8f * d,       // 斜面厚度
                    4f * d,       // 折射量：边缘保留弯折，减少文字重影
                    2.0f,         // 逆幂衰减：弯折压在最外几个像素
                    0.16f,        // 克制的色散，避免彩色硬边
                    4f * d,       // 更清透的底衬
                    0.72f,        // 高光强度
                    -0.5f, -0.866f, // 光源方向（左上）
                    6f * d,       // 辉光带宽上限
                    1.08f);       // 保留主题色，避免光晕过饱和
            l.setTint(tintArgb);
            l.setSurfaceColor(ThemeUtil.color(host.getContext(), R.attr.gSurface));
            if (!l.prepare()) {
                revert(host);
                return;
            }
            if (lens != null) lens.release();
            lens = l;
            backdropSource = backdrop;
            if (!on) {
                savedBackground = host.getBackground();
                savedOutlineProvider = host.getOutlineProvider();
                if (rimView != null) savedRimVisibility = rimView.getVisibility();
            }
            host.setBackground(null);
            final float radius = cornerDp * d;
            host.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 圆角不超出半高，避免小芯片的阴影轮廓异常
                    float r = Math.min(radius, Math.min(view.getWidth(), view.getHeight()) / 2f);
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            if (rimView != null) rimView.setVisibility(View.GONE);
            on = true;
            host.invalidate();
        } else {
            revert(host);
        }
    }

    /** 绘制透镜；返回 true 表示已画，调用方继续画内容；false 则走回退 */
    public boolean draw(Canvas canvas, View host) {
        if (!on || lens == null || BackdropCapture.isCapturing()) return false;
        if (!canvas.isHardwareAccelerated()) {
            revert(host);
            return false;
        }
        float d = host.getResources().getDisplayMetrics().density;
        try {
            if (lens.draw(canvas, host, backdropSource, (int) (16f * d + 0.5f))) return true;
        } catch (RuntimeException e) {
            // 录制重入等运行时异常绝不能炸掉整个 Activity：回退到主题级样式
            //（旧问题里“点进去又退回主页面”就是绘制异常一路崩到进程被杀）
        }
        revert(host);
        return false;
    }

    /** 回退到主题级样式：恢复背景与覆盖层，透镜引擎释放 */
    public void revert(View host) {
        if (!on && lens == null) return;
        on = false;
        if (lens != null) {
            lens.release();
            lens = null;
        }
        backdropSource = null;
        host.setBackground(savedBackground);
        savedBackground = null;
        host.setOutlineProvider(savedOutlineProvider);
        savedOutlineProvider = null;
        if (rimView != null) rimView.setVisibility(savedRimVisibility);
        host.invalidate();
    }

    public void release(View host) {
        // detach 后 View 可能被复用；恢复背景，避免重绑定保存到 null 背景。
        revert(host);
    }
}
