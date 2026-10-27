package com.guga.music;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

/**
 * glass 主题真折射的批量接线：
 * 遍历视图树，把所有 GlassLensHost 视图的透镜一次性打开，
 * 底衬来源统一为布局根视图。
 *
 * 门控与 v1.45.0 的底栏一致，只是范围从单个 glass 主题扩到全部模拟玻璃主题：
 * 全部主题共用同一套玻璃属性（只换色板），因此除扁平实色设计的 Material You 外，
 * 其余 8 套在 API 33+ 下都启用真透镜；Material You 保持扁平、透镜零成本。
 * 切主题时 Activity 重建，旧引擎随 onDetachedFromWindow 释放。
 */
public class GlassTheme {

    /** 透镜是否应启用（主题 + 系统版本双门控；扁平 Material You 除外） */
    public static boolean isLensActive(Context ctx) {
        return !ThemeUtil.currentId(ctx).equals("material")
                && Build.VERSION.SDK_INT >= 33;
    }

    /** 对 Activity：以布局根视图为底衬，绑定整棵树 */
    public static void bindActivity(Activity activity) {
        bindRoot(activityBackdrop(activity));
    }

    /** 取 Activity 的布局根视图（content 的第一个子级） */
    public static View activityBackdrop(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
            return ((ViewGroup) content).getChildAt(0);
        }
        return content;
    }

    /** 对任意已 inflate 的根视图：以该根视图为底衬绑定整棵树 */
    public static void bindRoot(View root) {
        if (root == null || !isLensActive(root.getContext())) return;
        bindView(root, root);
    }

    /** 对对话框根视图：底衬是宿主 Activity 的布局根（弹窗是独立窗口，底衬不能是自己） */
    public static void bindWithBackdrop(View dialogRoot, View backdrop) {
        if (dialogRoot == null || backdrop == null || !isLensActive(dialogRoot.getContext())) return;
        bindView(dialogRoot, backdrop);
    }

    private static void bindView(View v, View backdrop) {
        if (v instanceof GlassLensHost) {
            ((GlassLensHost) v).setLensEnabled(true, backdrop);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                bindView(g.getChildAt(i), backdrop);
            }
        }
    }

    /** 使某棵子树下所有已启用的透镜重录（滚动/切页后底衬变了时调用） */
    public static void invalidateGlass(View root) {
        if (root == null) return;
        if (root instanceof GlassLensHost && ((GlassLensHost) root).isLensOn()) {
            root.invalidate();
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                invalidateGlass(g.getChildAt(i));
            }
        }
    }

    /** 选中态芯片的透镜染色：accent 色 85% 混合，保留选中态可辨识 */
    public static int selectedTint(Context ctx) {
        int accent = ThemeUtil.color(ctx, R.attr.gAccent);
        return (accent & 0x00FFFFFF) | (0xD9 << 24);
    }
}
