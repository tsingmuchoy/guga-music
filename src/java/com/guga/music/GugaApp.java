package com.guga.music;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * 全局前台跟踪：统计本 App 处于 started 状态的 Activity 数，
 * 供悬浮岛 / 状态栏歌词判断「App 自己在不在前台」。
 *
 * 必须在 Application 里注册（进程启动、任何 Activity 之前）：
 * 若等播放服务启动后再注册，会漏掉首个 Activity 的 start 事件，
 * 计数永久差一，悬浮岛就会在 App 内也显示、挡住自家按钮。
 */
public class GugaApp extends Application {

    public static int startedCount = 0;

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) {
                startedCount++;
                PlayerService s = PlayerService.get();
                if (s != null) s.refreshIsland();
            }
            @Override public void onActivityStopped(Activity a) {
                if (startedCount > 0) startedCount--;
                PlayerService s = PlayerService.get();
                if (s != null) {
                    s.refreshIsland();
                    s.refreshSbLyrics();
                }
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
}
