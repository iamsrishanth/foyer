package com.kaera.foyer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

/**
 * Gives the phone remote reach outside Foyer: global back/home/recents, and tap-at-a-point.
 * The user must enable it in the TV's Accessibility settings - nothing is granted silently.
 */
public class FoyerAccessibility extends AccessibilityService {

    private static volatile FoyerAccessibility instance;

    /** Last package that opened a window, so the TV API can say what input is going to. */
    private static volatile String lastPkg;
    private static volatile long lastPkgAt;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Window changes are the only signal needed: which package last took the foreground.
        // No window content is retrieved (canRetrieveWindowContent stays false), so this keeps
        // working on the TV apps that publish no accessibility tree at all.
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        lastPkg = pkg.toString();
        lastPkgAt = SystemClock.uptimeMillis();
    }

    public static String lastActivePackage() {
        return lastPkg;
    }

    public static long lastActiveAt() {
        return lastPkgAt;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    public static boolean ready() {
        return instance != null;
    }

    public static boolean global(String action) {
        FoyerAccessibility s = instance;
        if (s == null || action == null) return false;
        int code;
        if ("back".equals(action)) code = GLOBAL_ACTION_BACK;
        else if ("home".equals(action)) code = GLOBAL_ACTION_HOME;
        else if ("recents".equals(action)) code = GLOBAL_ACTION_RECENTS;
        else if ("notifications".equals(action)) code = GLOBAL_ACTION_NOTIFICATIONS;
        else return false;
        try {
            return s.performGlobalAction(code);
        } catch (Exception e) {
            return false;
        }
    }

    /** nx/ny are fractions of the screen, so the phone never needs the TV's resolution. */
    public static boolean tap(float nx, float ny) {
        FoyerAccessibility s = instance;
        if (s == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        try {
            DisplayMetrics dm = s.getResources().getDisplayMetrics();
            float x = Math.max(0f, Math.min(1f, nx)) * dm.widthPixels;
            float y = Math.max(0f, Math.min(1f, ny)) * dm.heightPixels;
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                    .build();
            return s.dispatchGesture(gesture, null, null);
        } catch (Exception e) {
            return false;
        }
    }
}
