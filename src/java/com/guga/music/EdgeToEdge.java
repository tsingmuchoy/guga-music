package com.guga.music;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

/**
 * 顶部沉浸（v1.52.9）：状态栏透明 + 内容延伸到状态栏后方，渐变底衬直通顶边。
 * 之前主题里 statusBarColor 是纯色深色块，顶上像一条黑边，不好看。
 *
 * 只动顶部，不管底部：不改导航栏颜色、不设 LAYOUT_HIDE_NAVIGATION，
 * 下面导航键栏原样不动、内容也不会顶到它（窗口本身仍在导航栏上方）。
 * 布局根视图设 fitsSystemWindows 后自动吃掉状态栏高度做上边距，
 * 卡片、搜索栏位置与原来一致，只有底衬背景会画到状态栏后面。
 * 图标仍是浅色（全部 9 套主题顶底都是深色），不用改亮暗。
 */
public class EdgeToEdge {

    public static void apply(Activity activity) {
        Window w = activity.getWindow();
        if (w == null) return;
        w.setStatusBarColor(Color.TRANSPARENT);
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        View content = activity.findViewById(android.R.id.content);
        if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
            View root = ((ViewGroup) content).getChildAt(0);
            root.setFitsSystemWindows(true);
            root.requestApplyInsets();
        }
    }
}
