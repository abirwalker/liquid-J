package com.liquidj;

import java.util.List;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.layout.Region;

/**
 * Dark or light stylesheet for the main window and every dialog.
 * Dialogs get their own Scene, so they do not inherit the window's stylesheets and must be styled here.
 */
final class Theme {

    private static final String BASE = stylesheet("liquidj.css");
    private static final String LIGHT = stylesheet("liquidj-light.css");

    private final BooleanProperty light = new SimpleBooleanProperty(false);

    BooleanProperty lightProperty() {
        return light;
    }

    void attach(Scene scene) {
        scene.getStylesheets().setAll(stylesheets());
        light.addListener((obs, old, now) -> scene.getStylesheets().setAll(stylesheets()));
    }

    <D extends Dialog<?>> D style(D dialog) {
        dialog.getDialogPane().getStylesheets().setAll(stylesheets());
        // Without this, long messages are cut off with "..." instead of growing the dialog.
        dialog.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        return dialog;
    }

    private List<String> stylesheets() {
        return light.get() ? List.of(BASE, LIGHT) : List.of(BASE);
    }

    private static String stylesheet(String name) {
        var url = Theme.class.getResource(name);
        if (url == null) {
            throw new IllegalStateException(name + " is missing from the build output");
        }
        return url.toExternalForm();
    }
}
