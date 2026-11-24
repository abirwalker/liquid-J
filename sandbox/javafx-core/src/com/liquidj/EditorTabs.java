package com.liquidj;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;

/** One tab per open file: loading, editing, unsaved-change tracking and saving. */
final class EditorTabs {

    static final Font CODE_FONT = Font.font(firstInstalled("Cascadia Mono", "Consolas", "Menlo",
            "DejaVu Sans Mono", "Monospaced"), 13);

    // TextArea gets very slow on huge files; student sources are far below this.
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

    private final TabPane pane = new TabPane();
    private final StackPane root;
    private final ReadOnlyStringWrapper caret = new ReadOnlyStringWrapper("");
    private final Theme theme;

    EditorTabs(Theme theme) {
        this.theme = theme;
        pane.setId("editor-tabs");
        pane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        pane.visibleProperty().bind(Bindings.isNotEmpty(pane.getTabs()));
        pane.getSelectionModel().selectedItemProperty().addListener((obs, old, tab) -> updateCaret());
        Label empty = new Label("No file open. Double-click a file in the project tree.");
        empty.getStyleClass().add("empty-state");
        empty.visibleProperty().bind(pane.visibleProperty().not());
        root = new StackPane(empty, pane);
        root.getStyleClass().add("editor-area");
    }

    Node node() {
        return root;
    }

    /** "Ln 3, Col 9" for the active tab, empty when no file is open. */
    ReadOnlyStringProperty caretProperty() {
        return caret.getReadOnlyProperty();
    }

    BooleanBinding noFileOpen() {
        return Bindings.isEmpty(pane.getTabs());
    }

    Optional<Path> activeFile() {
        return activeTab().map(tab -> tab.path);
    }

    void open(Path file) {
        openTab(file).ifPresent(tab -> tab.area.requestFocus());
    }

    /** Opens the file and selects the given 1-based line. */
    void goTo(Path file, int line) {
        openTab(file).ifPresent(tab -> {
            String text = tab.area.getText();
            int start = 0;
            for (int current = 1; current < line; current++) {
                int newline = text.indexOf('\n', start);
                if (newline < 0) {
                    break;
                }
                start = newline + 1;
            }
            int end = text.indexOf('\n', start);
            tab.area.selectRange(end < 0 ? text.length() : end, start);
            tab.area.requestFocus();
        });
    }

    boolean saveActive() {
        return activeTab().map(this::save).orElse(true);
    }

    /** Saves every changed tab. Returns false if any file could not be written. */
    boolean saveAll() {
        boolean allSaved = true;
        for (FileTab tab : fileTabs()) {
            if (tab.isDirty() && !save(tab)) {
                allSaved = false;
            }
        }
        return allSaved;
    }

    void closeActive() {
        activeTab().ifPresent(tab -> {
            if (confirmClose(tab)) {
                pane.getTabs().remove(tab);
            }
        });
    }

    /** Asks about every unsaved tab, then closes them all. Returns false if the user cancelled. */
    boolean closeAll() {
        for (FileTab tab : fileTabs()) {
            if (!confirmClose(tab)) {
                return false;
            }
        }
        pane.getTabs().clear();
        return true;
    }

    private Optional<FileTab> openTab(Path file) {
        Path path = file.toAbsolutePath().normalize();
        for (FileTab tab : fileTabs()) {
            if (tab.path.equals(path)) {
                pane.getSelectionModel().select(tab);
                return Optional.of(tab);
            }
        }
        String name = path.getFileName().toString();
        String text;
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                showError("Can't open " + name, "The file is larger than 2 MB.");
                return Optional.empty();
            }
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            showError("Can't open " + name, "The file is not UTF-8 text.");
            return Optional.empty();
        } catch (IOException e) {
            showError("Can't open " + name, e.getMessage());
            return Optional.empty();
        }
        FileTab tab = new FileTab(path, text);
        pane.getTabs().add(tab);
        pane.getSelectionModel().select(tab);
        return Optional.of(tab);
    }

    private boolean save(FileTab tab) {
        String text = tab.area.getText();
        String onDisk = tab.windowsLineEndings ? text.replace("\n", "\r\n") : text;
        try {
            Files.writeString(tab.path, onDisk, StandardCharsets.UTF_8);
        } catch (IOException e) {
            showError("Could not save " + tab.path.getFileName(), e.getMessage());
            return false;
        }
        tab.savedText = text;
        tab.updateTitle();
        return true;
    }

    private boolean confirmClose(FileTab tab) {
        if (!tab.isDirty()) {
            return true;
        }
        pane.getSelectionModel().select(tab);
        ButtonType saveButton = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discardButton = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Your changes will be lost if you don't save them.",
                saveButton, discardButton, ButtonType.CANCEL);
        alert.setHeaderText("Save changes to " + tab.path.getFileName() + "?");
        ownAlert(alert);
        ButtonType choice = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == saveButton) {
            return save(tab);
        }
        return choice == discardButton;
    }

    private void updateCaret() {
        Optional<FileTab> active = activeTab();
        if (active.isEmpty()) {
            caret.set("");
            return;
        }
        TextArea area = active.get().area;
        String before = area.getText(0, area.getCaretPosition());
        int line = 1 + (int) before.chars().filter(ch -> ch == '\n').count();
        int column = before.length() - before.lastIndexOf('\n');
        caret.set("Ln " + line + ", Col " + column);
    }

    private Optional<FileTab> activeTab() {
        return pane.getSelectionModel().getSelectedItem() instanceof FileTab tab ? Optional.of(tab) : Optional.empty();
    }

    private List<FileTab> fileTabs() {
        List<FileTab> tabs = new ArrayList<>();
        for (Tab tab : pane.getTabs()) {
            if (tab instanceof FileTab fileTab) {
                tabs.add(fileTab);
            }
        }
        return tabs;
    }

    private void showError(String header, String detail) {
        Alert alert = new Alert(Alert.AlertType.ERROR, detail);
        alert.setHeaderText(header);
        ownAlert(alert);
        alert.showAndWait();
    }

    private void ownAlert(Alert alert) {
        theme.style(alert);
        Scene scene = pane.getScene();
        if (scene != null) {
            alert.initOwner(scene.getWindow());
        }
    }

    private static String firstInstalled(String... families) {
        List<String> installed = Font.getFamilies();
        return Stream.of(families).filter(installed::contains).findFirst().orElse("Monospaced");
    }

    private final class FileTab extends Tab {
        final Path path;
        final TextArea area;
        // The editor works with \n only; files that used \r\n are written back with \r\n.
        final boolean windowsLineEndings;
        String savedText;

        FileTab(Path path, String diskText) {
            this.path = path;
            this.windowsLineEndings = diskText.contains("\r\n");
            this.savedText = diskText.replace("\r\n", "\n");
            this.area = new TextArea(savedText);
            area.setFont(CODE_FONT);
            area.textProperty().addListener((obs, old, now) -> updateTitle());
            area.caretPositionProperty().addListener((obs, old, now) -> updateCaret());
            setContent(area);
            setTooltip(new Tooltip(path.toString()));
            setOnCloseRequest(event -> {
                if (!confirmClose(this)) {
                    event.consume();
                }
            });
            updateTitle();
        }

        boolean isDirty() {
            return !area.getText().equals(savedText);
        }

        void updateTitle() {
            setText(path.getFileName() + (isDirty() ? "  ●" : ""));
        }
    }
}
