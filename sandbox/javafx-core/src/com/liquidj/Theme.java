package com.liquidj;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;

/**
 * Dark or light stylesheet for the main window and every dialog, plus the bundled fonts.
 * Dialogs get their own Scene, so they do not inherit the window's stylesheets and must be styled here.
 */
final class Theme {

    // Inter (UI) and JetBrains Mono (code), SIL Open Font License; licenses sit next to the files.
    // JavaFX registers each Inter weight as its own family: "Inter", "Inter Medium", "Inter SemiBold".
    private static final List<String> FONT_FILES = List.of(
            "Inter-Regular.ttf", "Inter-Medium.ttf", "Inter-SemiBold.ttf", "JetBrainsMono-Regular.ttf");

    private static final String BASE = stylesheet("liquidj.css");
    private static final String LIGHT = stylesheet("liquidj-light.css");

    private final BooleanProperty light = new SimpleBooleanProperty(false);

    /** Must run before any UI is built. A missing font falls back to the system font, not an error. */
    static void loadFonts() {
        for (String file : FONT_FILES) {
            try (InputStream in = Theme.class.getResourceAsStream("fonts/" + file)) {
                if (in != null) {
                    Font.loadFont(in, 13);
                }
            } catch (IOException e) {
                // Falls back to the system font for this family.
            }
        }
    }

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
