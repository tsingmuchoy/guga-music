package com.guga.music;

import android.view.View;

/**
 * 真液态玻璃视图的统一接口：所有透镜容器/控件都实现它，
 * GlassTheme.bindRoot 遍历视图树时靠它批量开启透镜。
 */
public interface GlassLensHost {
    /** 开关透镜；backdrop 为底衬来源视图（一般是 Activity 布局根视图） */
    void setLensEnabled(boolean on, View backdrop);

    boolean isLensOn();
}
