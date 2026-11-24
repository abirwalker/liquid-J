package com.liquidj;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

/**
 * The project folder as a tree. Folders load their children the first time they are expanded.
 * With a filter, the tree shows only matching files and the folders that lead to them.
 */
final class ProjectTree {

    // Stops a filter on a huge folder from walking the whole disk.
    private static final int MAX_MATCHES = 500;

    private final TreeView<Path> view = new TreeView<>();
    private final StackPane root;
    private Path folder;
    private String filter = "";

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
        // The sidebar is translucent, so the hint must be hidden, not just covered.
        empty.visibleProperty().bind(view.visibleProperty().not());
        root = new StackPane(empty, view);
        root.getStyleClass().add("file-navigator");
    }

    Node node() {
        return root;
    }

    void show(Path folder) {
        this.folder = folder;
        this.filter = "";
        showAll();
        view.setVisible(true);
    }

    void hide() {
        folder = null;
        view.setRoot(null);
        view.setVisible(false);
    }

    /** Case-insensitive part of a file name; blank shows the whole tree again. */
    void setFilter(String text) {
        filter = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (folder == null) {
            return;
        }
        if (filter.isEmpty()) {
            showAll();
        } else {
            view.setRoot(filteredTree());
        }
    }

    private void showAll() {
        PathItem top = new PathItem(folder);
        top.setExpanded(true);
        for (TreeItem<Path> child : top.getChildren()) {
            if (displayName(child.getValue()).equals("src")) {
                child.setExpanded(true);
            }
        }
        view.setRoot(top);
    }

    /** Reloads from disk, keeping expanded folders and the selection. */
    void refresh() {
        TreeItem<Path> oldRoot = view.getRoot();
        if (oldRoot == null) {
            return;
        }
        if (!filter.isEmpty()) {
            view.setRoot(filteredTree());
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

    private TreeItem<Path> filteredTree() {
        TreeItem<Path> top = new TreeItem<>(folder);
        top.setExpanded(true);
        Map<Path, TreeItem<Path>> items = new HashMap<>();
        items.put(folder, top);
        for (Path match : matchingFiles()) {
            attach(match, items);
        }
        return top;
    }

    /** Adds the path under its parent, creating the parent folders first. */
    private static void attach(Path path, Map<Path, TreeItem<Path>> items) {
        if (items.containsKey(path)) {
            return;
        }
        attach(path.getParent(), items);
        TreeItem<Path> item = new TreeItem<>(path);
        item.setExpanded(true);
        items.get(path.getParent()).getChildren().add(item);
        items.put(path, item);
    }

    private List<Path> matchingFiles() {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    return dir.equals(folder) || isShown(dir) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (isShown(file) && displayName(file).toLowerCase(Locale.ROOT).contains(filter)) {
                        found.add(file);
                    }
                    return found.size() >= MAX_MATCHES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Show whatever was found before the walk failed.
        }
        found.sort(Comparator.naturalOrder());
        return found;
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
