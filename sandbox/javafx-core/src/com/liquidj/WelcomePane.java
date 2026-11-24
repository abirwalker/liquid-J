package com.liquidj;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Shown in place of the editor until a project folder is open: open a folder or pick a recent one. */
final class WelcomePane {

    private final VBox root;
    private final VBox recentList = new VBox(2);
    private final Consumer<Path> openFolder;

    WelcomePane(Runnable chooseFolder, Consumer<Path> openFolder) {
        this.openFolder = openFolder;

        Label title = new Label("Liquid-J");
        title.getStyleClass().add("welcome-title");
        Label subtitle = new Label("A Java IDE for CSE 342. Open a project folder to start.");
        subtitle.getStyleClass().add("welcome-subtitle");

        Button open = new Button("Open Folder...", Icons.folderOpen());
        open.getStyleClass().addAll("glass", "primary", "welcome-open");
        open.setOnAction(event -> chooseFolder.run());
        Label shortcut = new Label("Ctrl+O");
        shortcut.getStyleClass().add("hint-keys");
        HBox actions = new HBox(10, open, shortcut);
        actions.setAlignment(Pos.CENTER_LEFT);

        Label recentTitle = new Label("Recent");
        recentTitle.getStyleClass().add("welcome-section");

        root = new VBox(18, new VBox(4, title, subtitle), actions, new VBox(6, recentTitle, recentList));
        root.getStyleClass().add("welcome");
        root.setAlignment(Pos.CENTER_LEFT);
        root.setMaxWidth(520);
        root.setMaxHeight(Double.NEGATIVE_INFINITY);
    }

    Node node() {
        return root;
    }

    void showRecent(List<Path> folders) {
        if (folders.isEmpty()) {
            Label none = new Label("No recent projects yet.");
            none.getStyleClass().add("welcome-empty");
            recentList.getChildren().setAll(none);
            return;
        }
        recentList.getChildren().setAll(folders.stream().map(this::recentRow).toList());
    }

    private Node recentRow(Path folder) {
        Label name = new Label(folder.getFileName() == null ? folder.toString() : folder.getFileName().toString());
        name.getStyleClass().add("recent-name");
        Label location = new Label(folder.toString());
        location.getStyleClass().add("recent-path");
        Button row = new Button(null, new VBox(1, name, location));
        row.getStyleClass().add("recent-row");
        row.setMaxWidth(Double.MAX_VALUE);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnAction(event -> openFolder.accept(folder));
        return row;
    }
}
