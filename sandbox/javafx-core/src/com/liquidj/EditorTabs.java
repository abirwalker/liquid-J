package com.liquidj;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional;

/** One tab per open file: loading, editing, syntax colours, unsaved-change tracking and saving. */
final class EditorTabs {

    // Geist Mono is bundled (Theme.loadFonts); the rest are fallbacks if it failed to load.
    static final String CODE_FONT_FAMILY = firstInstalled("Geist Mono", "Cascadia Mono", "Consolas",
            "Menlo", "DejaVu Sans Mono", "Monospaced");
    static final Font CODE_FONT = Font.font(CODE_FONT_FAMILY, 13);

    private static final PseudoClass CURRENT_LINE = PseudoClass.getPseudoClass("current");

    private static final String INDENT = "    ";
    // The editor gets slow on huge files; student sources are far below this.
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

    private final TabPane pane = new TabPane();
    private final StackPane root;
    private final ReadOnlyStringWrapper caret = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper lineEnding = new ReadOnlyStringWrapper("");
    private final Theme theme;
    private Path projectRoot;

    EditorTabs(Theme theme) {
        this.theme = theme;
        pane.setId("editor-tabs");
        pane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        pane.visibleProperty().bind(Bindings.isNotEmpty(pane.getTabs()));
        pane.getSelectionModel().selectedItemProperty().addListener((obs, old, tab) -> updateStatus());

        Node empty = emptyState();
        empty.visibleProperty().bind(pane.visibleProperty().not());
        root = new StackPane(empty, pane);
        root.getStyleClass().add("editor-area");
    }

    Node node() {
        return root;
    }

    /** Used to show paths in the jump bar relative to the project. */
    void setProjectRoot(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /** "Ln 3, Col 9" for the active tab, empty when no file is open. */
    ReadOnlyStringProperty caretProperty() {
        return caret.getReadOnlyProperty();
    }

    /** "LF" or "CRLF" for the active tab. */
    ReadOnlyStringProperty lineEndingProperty() {
        return lineEnding.getReadOnlyProperty();
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
            CodeArea area = tab.area;
            int paragraph = Math.max(0, Math.min(line - 1, area.getParagraphs().size() - 1));
            int start = area.getAbsolutePosition(paragraph, 0);
            area.selectRange(start, start + area.getParagraphLength(paragraph));
            area.requestFollowCaret();
            area.requestFocus();
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

    private Node emptyState() {
        Label title = new Label("No file open");
        title.getStyleClass().add("empty-title");
        VBox hints = new VBox(6,
                hint("Open a file", "Double-click in the navigator"),
                hint("New Class", "Ctrl+N"),
                hint("Run", "F5"));
        hints.setAlignment(Pos.CENTER);
        VBox box = new VBox(14, title, hints);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("empty-state");
        return box;
    }

    private static Node hint(String action, String keys) {
        Label name = new Label(action);
        name.getStyleClass().add("hint-action");
        Label shortcut = new Label(keys);
        shortcut.getStyleClass().add("hint-keys");
        HBox row = new HBox(10, name, shortcut);
        row.setAlignment(Pos.CENTER);
        return row;
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

    private void updateStatus() {
        Optional<FileTab> active = activeTab();
        if (active.isEmpty()) {
            caret.set("");
            lineEnding.set("");
            return;
        }
        CodeArea area = active.get().area;
        caret.set("Ln " + (area.getCurrentParagraph() + 1) + ", Col " + (area.getCaretColumn() + 1));
        lineEnding.set(active.get().windowsLineEndings ? "CRLF" : "LF");
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
        final CodeArea area = new CodeArea();
        // The editor works with \n only; files that used \r\n are written back with \r\n.
        final boolean windowsLineEndings;
        final boolean java;
        final HBox jumpBar = new HBox();
        String savedText;

        FileTab(Path path, String diskText) {
            this.path = path;
            this.windowsLineEndings = diskText.contains("\r\n");
            this.java = path.getFileName().toString().endsWith(".java");
            this.savedText = diskText.replace("\r\n", "\n");

            area.setStyle("-fx-font-family: '" + CODE_FONT_FAMILY + "';");
            area.setParagraphGraphicFactory(this::lineNumber);
            area.setLineHighlighterOn(true);
            area.replaceText(savedText);
            area.getUndoManager().forgetHistory();
            area.moveTo(0);
            if (java) {
                area.setStyleSpans(0, JavaSyntax.highlight(savedText));
                // Recolour after typing pauses, not on every key.
                area.multiPlainChanges()
                        .successionEnds(Duration.ofMillis(60))
                        .subscribe(changes -> area.setStyleSpans(0, JavaSyntax.highlight(area.getText())));
            }
            area.textProperty().addListener((obs, old, now) -> updateTitle());
            area.caretPositionProperty().addListener((obs, old, now) -> {
                updateJumpBar();
                if (isSelected()) {
                    updateStatus();
                }
            });
            area.addEventFilter(KeyEvent.KEY_PRESSED, this::handleEditingKey);
            area.addEventFilter(KeyEvent.KEY_TYPED, this::handleTypedBrace);

            VirtualizedScrollPane<CodeArea> scroller = new VirtualizedScrollPane<>(area);
            jumpBar.getStyleClass().add("jump-bar");
            jumpBar.setAlignment(Pos.CENTER_LEFT);
            VBox content = new VBox(jumpBar, scroller);
            VBox.setVgrow(scroller, Priority.ALWAYS);
            setContent(content);
            setGraphic(Icons.forFile(path));
            setTooltip(new Tooltip(path.toString()));
            setOnCloseRequest(event -> {
                if (!confirmClose(this)) {
                    event.consume();
                }
            });
            updateTitle();
            updateJumpBar();
        }

        boolean isDirty() {
            return !area.getText().equals(savedText);
        }

        /** Right-aligned line number; the caret's line is brighter. */
        private Node lineNumber(int paragraph) {
            Label number = new Label(String.valueOf(paragraph + 1));
            number.getStyleClass().add("lineno");
            number.setAlignment(Pos.CENTER_RIGHT);
            number.setStyle("-fx-font-family: '" + CODE_FONT_FAMILY + "';");
            // Room for four digits, so the code does not shift sideways as the file grows.
            number.setMinWidth(Region.USE_PREF_SIZE);
            number.setPrefWidth(CODE_FONT.getSize() * 0.62 * 4 + 28);
            BooleanBinding current = Bindings.createBooleanBinding(
                    () -> area.getCurrentParagraph() == paragraph, area.currentParagraphProperty());
            number.pseudoClassStateChanged(CURRENT_LINE, current.get());
            current.addListener((obs, old, now) -> number.pseudoClassStateChanged(CURRENT_LINE, now));
            // The label must own the binding: the area only holds bindings weakly.
            number.getProperties().put(CURRENT_LINE, current);
            return number;
        }

        void updateTitle() {
            setText(path.getFileName() + (isDirty() ? "  ●" : ""));
        }

        /** project > src > Main.java > Main > main(), following the caret. */
        void updateJumpBar() {
            List<Node> crumbs = new ArrayList<>();
            Path shown = projectRoot != null && path.startsWith(projectRoot)
                    ? projectRoot.getFileName().resolve(projectRoot.relativize(path))
                    : path.getFileName();
            for (int i = 0; i < shown.getNameCount(); i++) {
                boolean last = i == shown.getNameCount() - 1;
                crumbs.add(crumb(shown.getName(i).toString(), last ? Icons.forFile(path) : null));
            }
            if (java) {
                for (JavaSyntax.Symbol symbol : JavaSyntax.symbolsAt(area.getText(), area.getCaretPosition())) {
                    boolean method = symbol.kind() == JavaSyntax.SymbolKind.METHOD;
                    Label kind = new Label(method ? "M" : "C");
                    kind.getStyleClass().addAll("symbol-badge", method ? "method" : "class");
                    crumbs.add(crumb(method ? symbol.name() + "()" : symbol.name(), kind));
                }
            }
            List<Node> withSeparators = new ArrayList<>();
            for (Node crumb : crumbs) {
                if (!withSeparators.isEmpty()) {
                    Label separator = new Label("›");
                    separator.getStyleClass().add("jump-separator");
                    withSeparators.add(separator);
                }
                withSeparators.add(crumb);
            }
            jumpBar.getChildren().setAll(withSeparators);
        }

        private Node crumb(String text, Node graphic) {
            Label label = new Label(text, graphic);
            label.getStyleClass().add("jump-crumb");
            return label;
        }

        private void handleEditingKey(KeyEvent event) {
            if (event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
                return;
            }
            if (event.getCode() == KeyCode.ENTER) {
                insertNewline();
                event.consume();
            } else if (event.getCode() == KeyCode.TAB) {
                if (event.isShiftDown()) {
                    outdentSelectedLines();
                } else if (area.getSelectedText().contains("\n")) {
                    indentSelectedLines();
                } else {
                    area.replaceSelection(INDENT);
                }
                event.consume();
            }
        }

        /** Keeps the indentation of the current line, one level deeper after "{", and splits "{}". */
        private void insertNewline() {
            String text = area.getText();
            int position = area.getSelection().getStart();
            int lineStart = text.lastIndexOf('\n', position - 1) + 1;
            String before = text.substring(lineStart, position);
            String indent = before.substring(0, before.length() - before.stripLeading().length());
            boolean opensBlock = before.stripTrailing().endsWith("{");
            int selectionEnd = area.getSelection().getEnd();
            boolean closesNext = selectionEnd < text.length() && text.charAt(selectionEnd) == '}';

            if (opensBlock && closesNext) {
                area.replaceSelection("\n" + indent + INDENT + "\n" + indent);
                area.moveTo(position + 1 + indent.length() + INDENT.length());
            } else {
                area.replaceSelection("\n" + indent + (opensBlock ? INDENT : ""));
            }
            area.requestFollowCaret();
        }

        /** Typing "}" on a blank line removes one indent level first. */
        private void handleTypedBrace(KeyEvent event) {
            if (!"}".equals(event.getCharacter()) || area.getSelection().getLength() > 0) {
                return;
            }
            int position = area.getCaretPosition();
            String text = area.getText();
            int lineStart = text.lastIndexOf('\n', position - 1) + 1;
            String before = text.substring(lineStart, position);
            if (before.isBlank() && before.endsWith(INDENT)) {
                area.deleteText(position - INDENT.length(), position);
            }
        }

        private void indentSelectedLines() {
            int first = area.offsetToPosition(area.getSelection().getStart(), TwoDimensional.Bias.Forward).getMajor();
            int last = area.offsetToPosition(area.getSelection().getEnd(), TwoDimensional.Bias.Forward).getMajor();
            for (int paragraph = first; paragraph <= last; paragraph++) {
                area.insertText(paragraph, 0, INDENT);
            }
            area.selectRange(area.getAbsolutePosition(first, 0),
                    area.getAbsolutePosition(last, area.getParagraphLength(last)));
        }

        private void outdentSelectedLines() {
            int first = area.offsetToPosition(area.getSelection().getStart(), TwoDimensional.Bias.Forward).getMajor();
            int last = area.offsetToPosition(area.getSelection().getEnd(), TwoDimensional.Bias.Forward).getMajor();
            for (int paragraph = first; paragraph <= last; paragraph++) {
                String line = area.getParagraph(paragraph).getText();
                int remove = 0;
                while (remove < INDENT.length() && remove < line.length() && line.charAt(remove) == ' ') {
                    remove++;
                }
                if (remove > 0) {
                    area.deleteText(paragraph, 0, paragraph, remove);
                }
            }
        }
    }
}
