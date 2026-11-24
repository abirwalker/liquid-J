package com.liquidj;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.shape.SVGPath;

/** Small vector icons drawn in a 14x14 box. Colours come from the stylesheet. */
final class Icons {

    private Icons() {
    }

    static Node play() {
        return filled("M3,1.5 L12.5,7 L3,12.5 Z");
    }

    static Node stop() {
        return filled("M2.5,2.5 H11.5 V11.5 H2.5 Z");
    }

    static Node sidebar() {
        return stroked("M1.5,2.5 H12.5 V11.5 H1.5 Z M5,2.5 V11.5");
    }

    static Node plus() {
        return stroked("M7,2 V12 M2,7 H12");
    }

    static Node folderOpen() {
        return stroked("M1.5,3 H5.5 L7,4.5 H12.5 V11.5 H1.5 Z");
    }

    static Node theme() {
        return stroked("M7,1.5 A5.5,5.5 0 1,0 7,12.5 A5.5,5.5 0 1,0 7,1.5 Z M7,1.5 V12.5");
    }

    static Node success() {
        SVGPath icon = stroked("M2,7.5 L5.5,11 L12,3");
        icon.getStyleClass().add("icon-ok");
        return icon;
    }

    static Node error() {
        SVGPath icon = stroked("M7,1.5 A5.5,5.5 0 1,0 7,12.5 A5.5,5.5 0 1,0 7,1.5 Z M4.8,4.8 L9.2,9.2 M9.2,4.8 L4.8,9.2");
        icon.getStyleClass().add("icon-error");
        return icon;
    }

    static Node warning() {
        SVGPath icon = stroked("M7,1.5 L12.8,12 H1.2 Z M7,5.5 V8.5 M7,10 V10.5");
        icon.getStyleClass().add("icon-warning");
        return icon;
    }

    /** Folder, Java badge or plain document, for the tree and the tabs. */
    static Node forFile(Path path) {
        if (Files.isDirectory(path)) {
            SVGPath folder = filled("M1,3 Q1,2 2,2 H5.2 L6.7,3.5 H12 Q13,3.5 13,4.5 V11 Q13,12 12,12 H2 Q1,12 1,11 Z");
            folder.getStyleClass().add("icon-folder");
            return folder;
        }
        if (path.getFileName().toString().endsWith(".java")) {
            Label badge = new Label("J");
            badge.getStyleClass().add("java-badge");
            return badge;
        }
        SVGPath document = stroked("M3,1.5 H8.5 L11,4 V12.5 H3 Z M8.5,1.5 V4 H11");
        document.getStyleClass().add("icon-file");
        return document;
    }

    private static SVGPath filled(String content) {
        SVGPath icon = new SVGPath();
        icon.setContent(content);
        icon.getStyleClass().addAll("icon", "icon-filled");
        return icon;
    }

    private static SVGPath stroked(String content) {
        SVGPath icon = new SVGPath();
        icon.setContent(content);
        icon.getStyleClass().addAll("icon", "icon-stroked");
        return icon;
    }
}
