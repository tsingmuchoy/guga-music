package com.guga.music;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;

/** 无第三方测试依赖；在真实 Android 图形栈上验证采样和生命周期。 */
public final class GlassRegressionTest extends Instrumentation {
    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            runOnMainSync(this::testCaptureAndLifecycle);
            for (String theme : new String[]{"glass", "material"}) captureTheme(theme);
            result.putString("stream", "PASS: visibility, exception cleanup, rebind, detach, shader, theme screenshots\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void testCaptureAndLifecycle() {
        Context context = new ContextThemeWrapper(getTargetContext(), R.style.ThemeGlass);
        FrameLayout root = new FrameLayout(context);
        GlassLinearLayout visible = new GlassLinearLayout(context);
        GlassLinearLayout gone = new GlassLinearLayout(context);
        GlassLinearLayout invisible = new GlassLinearLayout(context);
        root.addView(visible); root.addView(gone); root.addView(invisible);
        gone.setVisibility(View.GONE); invisible.setVisibility(View.INVISIBLE);
        root.layout(0, 0, 200, 200);
        Bitmap bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
        try {
            BackdropCapture capture = new BackdropCapture();
            capture.draw(new Canvas(bitmap), root, visible);
            check(visible.getVisibility() == View.VISIBLE, "visible glass was not restored");
            check(gone.getVisibility() == View.GONE, "capture revealed GONE glass");
            check(invisible.getVisibility() == View.INVISIBLE, "capture revealed INVISIBLE glass");

            FrameLayout throwing = new FrameLayout(context) {
                @Override public void draw(Canvas canvas) { throw new IllegalStateException("expected"); }
            };
            throwing.addView(visible = new GlassLinearLayout(context));
            try { capture.draw(new Canvas(bitmap), throwing, visible); }
            catch (IllegalStateException expected) { /* must unwind hidden views and depth */ }
            check(!BackdropCapture.isCapturing(), "capture depth leaked after exception");
            check(visible.getVisibility() == View.VISIBLE, "hidden view leaked after exception");

            if (Build.VERSION.SDK_INT >= 33) {
                GlassLensDelegate delegate = new GlassLensDelegate(24);
                View host = new View(context);
                ColorDrawable background = new ColorDrawable(Color.DKGRAY);
                host.setBackground(background);
                ViewOutlineProvider outline = host.getOutlineProvider();
                View rim = new View(context); rim.setVisibility(View.INVISIBLE);
                delegate.setRimView(rim);
                delegate.setEnabled(host, true, root);
                check(delegate.isOn(), "AGSL compilation failed");
                delegate.setEnabled(host, true, new FrameLayout(context));
                delegate.release(host);
                check(host.getBackground() == background, "rebind lost original background");
                check(host.getOutlineProvider() == outline, "outline was not restored");
                check(rim.getVisibility() == View.INVISIBLE, "rim visibility was not restored");
                delegate.setEnabled(host, true, root);
                delegate.draw(new Canvas(bitmap), host);
                check(!delegate.isOn() && host.getBackground() == background, "software fallback lost background");
            }
        } finally { bitmap.recycle(); }
    }

    private void captureTheme(String id) throws Exception {
        ThemeUtil.setTheme(getTargetContext(), id);
        Intent intent = new Intent(getTargetContext(), ThemeSettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = startActivitySync(intent);
        try {
            waitForIdleSync();
            Thread.sleep(700); // allow RenderThread to submit the first glass frame
            Bitmap screenshot = getUiAutomation().takeScreenshot();
            check(screenshot != null, "no screenshot for " + id);
            File directory = new File(getTargetContext().getExternalFilesDir(null), "glass-preview");
            check(directory.isDirectory() || directory.mkdirs(), "cannot create screenshot directory");
            try (FileOutputStream out = new FileOutputStream(new File(directory, id + ".png"))) {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally { screenshot.recycle(); }
        } finally { runOnMainSync(activity::finish); waitForIdleSync(); }
    }
}
