package com.guga.music;

import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 背景捕获：把背景来源视图画进给定画布，并把玻璃自身排除掉。
 *
 * 移植自 QWEA0/Liquid-Glass-Android（MIT）的 BackdropCapture，算法保持一致。
 * 关键点（直接 source.draw(canvas) 在玻璃是 source 的高层级后代时不安全）：
 * 1. 录制重入：路径上的中间容器此刻正在 beginRecording 里（玻璃的绘制就发生在
 *    它的录制过程中），硬件画布上 dispatchDraw → drawChild 会对同一个节点再次
 *    beginRecording，抛 IllegalStateException 直接崩溃。
 * 2. 引用成环：drawChild 记录的是中间容器 RenderNode 的引用而不是像素，而这个
 *    节点又引用着玻璃自己的节点，于是形成 玻璃 → 背景 → 中间容器 → 玻璃 的环，
 *    光栅化时无限递归。
 * 所以路径上的每一层都改用公开的 View.draw：这条路不碰该 View 自己的
 * RenderNode（既不重入也不成环），dispatchDraw 实时执行，被临时置为 INVISIBLE
 * 的下一层会被跳过。每层的定位取屏幕坐标差，各级滚动与平移自动带上。
 *
 * v1.47.0 相对 QWEA0 原版的两处改动（全量铺开后的必选项，原版只假设少量玻璃）：
 * 1. 每一层采样都藏起同级玻璃，而不只在嵌套采样时藏：原版顶层采样不藏同级，
 *    同级玻璃会在底衬录制里再触发各自的完整录制，卡片一多就平方级膨胀，
 *    首帧卡死、被系统杀掉，看起来就是“点进去又退回主页面”。同级玻璃本就不
 *    该进底衬（它们是前景、不是背景），藏掉既正确又把每帧录制压回线性。
 * 2. 藏起状态用跨实例共享集合记录：嵌套采样是另一个 BackdropCapture 实例，
 *    它按 getVisibility() 判断会把外层已藏起的玻璃重复登记、提前恢复，
 *    导致外层底衬里混进不该有的玻璃；共享集合保证只有藏起的那一次才恢复。
 */
public class BackdropCapture {

    /** 进行中的采样层数（只在主线程改）。> 0 说明当前绘制发生在某块玻璃的采样里 */
    private static int depth = 0;

    /** 当前正被某次采样藏起的视图（跨实例共享，避免嵌套采样提前恢复外层的隐藏） */
    private static final Set<View> hiddenViews =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    // 复用，避免每帧分配
    private final int[] hostLocation = new int[2];
    private final int[] childLocation = new int[2];

    /**
     * 把 source 的内容画进 canvas，跳过 glass。
     *
     * @param canvas 画布原点需已对齐 source 左上角
     */
    public void draw(Canvas canvas, View source, View glass) {
        drawLevel(canvas, source, glass);
    }

    private void drawLevel(Canvas canvas, View host, View glass) {
        // 这一层要跳过的直接子级：玻璃本身，或玻璃所在的那条分支。
        // host 不是玻璃的祖先（同级/跨层级/跨 window 的背景来源）时为 null，
        // 这种情况没有重入风险，整棵照常画。
        View branch = childOnPathTo(host, glass);
        drawContent(canvas, host, branch != null ? branch : glass);
        if (branch == null || branch == glass) return;

        // 分支刚才被跳过了，这里单独补画（坐标取屏幕差，自带滚动/平移）
        host.getLocationOnScreen(hostLocation);
        branch.getLocationOnScreen(childLocation);
        float dx = (float) (childLocation[0] - hostLocation[0]);
        float dy = (float) (childLocation[1] - hostLocation[1]);
        boolean clip = !(host instanceof ViewGroup) || ((ViewGroup) host).getClipChildren();

        int save = canvas.save();
        if (clip) canvas.clipRect(0f, 0f, (float) host.getWidth(), (float) host.getHeight());
        canvas.translate(dx, dy);
        if (clip) canvas.clipRect(0f, 0f, (float) branch.getWidth(), (float) branch.getHeight());
        drawLevel(canvas, branch, glass);
        canvas.restoreToCount(save);
    }

    /** 画 host 自身，期间把 hidden 与同级玻璃置为 INVISIBLE 让 dispatchDraw 跳过它们 */
    private void drawContent(Canvas canvas, View host, View hidden) {
        int save = canvas.save();
        // 公开的 View.draw(Canvas) 不带滚动偏移（框架是在 updateDisplayListIfDirty
        // 里补的），这里补上，否则滚动容器作为背景来源时内容会整体错位
        canvas.translate(-(float) host.getScrollX(), -(float) host.getScrollY());
        // 本次调用藏起的视图：只有本次藏起的才由本次恢复，避免嵌套采样提前恢复外层
        ArrayList<View> hiddenHere = new ArrayList<>();
        hideView(hidden, hiddenHere);
        // 同级玻璃一律藏起：它们是前景不是背景，且不藏会互相触发重录（见类注释）
        hideOtherGlass(host, hidden, hiddenHere);
        depth++;
        try {
            host.draw(canvas);
        } finally {
            depth--;
            for (View v : hiddenHere) {
                hiddenViews.remove(v);
                v.setTransitionVisibility(View.VISIBLE);
            }
            canvas.restoreToCount(save);
        }
    }

    /** 藏起单个视图；已在共享集合里的（外层已藏）不再重复登记 */
    private void hideView(View v, ArrayList<View> hiddenHere) {
        if (v == null) return;
        if (hiddenViews.add(v)) {
            v.setTransitionVisibility(View.INVISIBLE);
            hiddenHere.add(v);
        }
    }

    /** 把 host 的直接子级里（除 except 外）的玻璃临时藏起来；不往容器里钻 */
    private void hideOtherGlass(View host, View except, ArrayList<View> hiddenHere) {
        if (!(host instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) host;
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            if (child == except || !(child instanceof GlassLensHost)) continue;
            hideView(child, hiddenHere);
        }
    }

    /**
     * host 的直接子级里通往 descendant 的那一个；
     * host 不是 descendant 的祖先时返回 null
     */
    private View childOnPathTo(View host, View descendant) {
        View child = descendant;
        android.view.ViewParent p = descendant.getParent();
        while (p instanceof View) {
            if (p == host) return child;
            child = (View) p;
            p = p.getParent();
        }
        return null;
    }
}
