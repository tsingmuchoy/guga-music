package com.guga.music;

import android.graphics.drawable.Drawable;
import android.widget.TextView;

/** 给文字行挂一个矢量小图标（替代 emoji 图标位），颜色跟随主题属性 */
public class IconUtil {

    public static void leading(TextView tv, int resId, int colorAttr) {
        leading(tv, resId, colorAttr, 8);
    }

    public static void leading(TextView tv, int resId, int colorAttr, int padDp) {
        Drawable d = tv.getContext().getDrawable(resId).mutate();
        d.setTint(ThemeUtil.color(tv.getContext(), colorAttr));
        tv.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);
        tv.setCompoundDrawablePadding((int) (padDp * tv.getResources().getDisplayMetrics().density));
    }
}
