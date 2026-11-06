package com.kaera.foyer;

import android.content.ComponentName;

/** One launchable app shown as a tile. */
public class AppEntry {
    public final String pkg;
    public final String label;
    public final ComponentName component;

    public AppEntry(String pkg, String label, ComponentName component) {
        this.pkg = pkg;
        this.label = label;
        this.component = component;
    }

    public String id() {
        return component.flattenToShortString();
    }
}
