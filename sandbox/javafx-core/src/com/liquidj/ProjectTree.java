package com.liquidj;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;

/** The project folder as a tree. Folders load their children the first time they are expanded. */
final class ProjectTree {

    private final TreeView<Path> view = new TreeView<>();
    private final StackPane root;

    ProjectTree(Consumer<Path> openFile) {
        view.setId("project-tree");
        view.setCellFactory(tree -> new TreeCell<>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                boolean blank = empty || item == null;
                setText(blank ? null : displayName(item));
                setGraphic(blank ? null : Icons.forFile(item));
            }
        });
        view.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openSelected(openFile);
            }
        });
        view.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                openSelected(openFile);
            }
        });
        view.setVisible(false);
        Label empty = new Label("No folder open");
        empty.getStyleClass().add("empty-state");
        // The tree panel is translucent, so the hint must be hidden, not just covered.
        empty.visibleProperty().bind(view.visibleProperty().not());
        root = new StackPane(empty, view);
        root.getStyleClass().add("navigator");
    }

    Node node() {
        return root;
    }

    void show(Path folder) {
        PathItem top = new PathItem(folder);
        top.setExpanded(true);
        for (TreeItem<Path> child : top.getChildren()) {
            if (displayName(child.getValue()).equals("src")) {
                child.setExpanded(true);
            }
        }
        view.setRoot(top);
        view.setVisible(true);
    }

    /** Reloads from disk, keeping expanded folders and the selection. */
    void refresh() {
        TreeItem<Path> oldRoot = view.getRoot();
        if (oldRoot == null) {
            return;
        }
        Set<Path> expanded = new HashSet<>();
        collectExpanded(oldRoot, expanded);
        TreeItem<Path> selected = view.getSelectionModel().getSelectedItem();
        Path selectedPath = selected == null ? null : selected.getValue();

        PathItem newRoot = new PathItem(oldRoot.getValue());
        view.setRoot(newRoot);
        restore(newRoot, expanded, selectedPath);
    }

    private void restore(TreeItem<Path> item, Set<Path> expanded, Path selectedPath) {
        if (item.getValue().equals(selectedPath)) {
            view.getSelectionModel().select(item);
        }
        if (!expanded.contains(item.getValue())) {
            return;
        }
        item.setExpanded(true);
        for (TreeItem<Path> child : item.getChildren()) {
            restore(child, expanded, selectedPath);
        }
    }

    private static void collectExpanded(TreeItem<Path> item, Set<Path> expanded) {
        if (!item.isExpanded()) {
            return;
        }
        expanded.add(item.getValue());
        for (TreeItem<Path> child : item.getChildren()) {
            collectExpanded(child, expanded);
        }
    }

    private void openSelected(Consumer<Path> openFile) {
        TreeItem<Path> selected = view.getSelectionModel().getSelectedItem();
        if (selected != null && Files.isRegularFile(selected.getValue())) {
            openFile.accept(selected.getValue());
        }
    }

    private static String displayName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    /** Hides dot-files and out/, which holds the IDE's own compiled classes. */
    private static boolean isShown(Path path) {
        String name = displayName(path);
        return !name.startsWith(".") && !(name.equals("out") && Files.isDirectory(path));
    }

    private static final class PathItem extends TreeItem<Path> {
        private boolean loaded;

        PathItem(Path path) {
            super(path);
        }

        @Override
        public boolean isLeaf() {
            return !Files.isDirectory(getValue());
        }

        @Override
        public ObservableList<TreeItem<Path>> getChildren() {
            if (!loaded) {
                loaded = true;
                super.getChildren().setAll(listChildren(getValue()));
            }
            return super.getChildren();
        }

        private static List<PathItem> listChildren(Path folder) {
            if (!Files.isDirectory(folder)) {
                return List.of();
            }
            try (Stream<Path> entries = Files.list(folder)) {
                return entries.filter(ProjectTree::isShown)
                        .sorted(Comparator.comparing((Path path) -> !Files.isDirectory(path))
                                .thenComparing(path -> displayName(path).toLowerCase()))
                        .map(PathItem::new)
                        .toList();
            } catch (IOException e) {
                return List.of();
            }
        }
    }
}
