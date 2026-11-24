package com.liquidj;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;

/** Program output, coloured by stream, plus a line for typing input to the running program. */
final class ConsolePane {

    /** Style classes for output: program stdout, program stderr, and lines the IDE writes itself. */
    enum Kind {
        OUT("console-out"), ERR("console-err"), INFO("console-info");

        final String styleClass;

        Kind(String styleClass) {
            this.styleClass = styleClass;
        }
    }

    private record Chunk(String text, Kind kind) {}

    // A program stuck in a print loop would otherwise grow the console until the UI freezes.
    private static final int MAX_CHARS = 200_000;

    private final StyleClassedTextArea output = new StyleClassedTextArea();
    private final TextField input = new TextField();
    private final VBox root;
    private final List<Chunk> pending = new ArrayList<>();

    ConsolePane(Predicate<String> onInput) {
        output.setId("console-output");
        output.setEditable(false);
        output.setWrapText(true);
        output.setStyle("-fx-font-family: '" + EditorTabs.CODE_FONT_FAMILY + "';");

        input.setId("console-input");
        input.setFont(EditorTabs.CODE_FONT);
        input.setPromptText("Input for the running program: type a line and press Enter");
        input.setDisable(true);
        input.setOnAction(event -> {
            String line = input.getText();
            if (onInput.test(line)) {
                append(line + "\n", Kind.OUT);
                input.clear();
            }
        });

        VirtualizedScrollPane<StyleClassedTextArea> scroller = new VirtualizedScrollPane<>(output);
        root = new VBox(scroller, input);
        root.getStyleClass().add("console");
        VBox.setVgrow(scroller, Priority.ALWAYS);
    }

    Node node() {
        return root;
    }

    String text() {
        return output.getText();
    }

    /** Safe to call from any thread. Chunks are batched into one UI update per frame. */
    void append(String text, Kind kind) {
        boolean scheduleFlush;
        synchronized (pending) {
            scheduleFlush = pending.isEmpty();
            pending.add(new Chunk(text, kind));
        }
        if (scheduleFlush) {
            Platform.runLater(this::flush);
        }
    }

    void clear() {
        synchronized (pending) {
            pending.clear();
        }
        output.clear();
    }

    void setInputEnabled(boolean enabled) {
        input.setDisable(!enabled);
    }

    private void flush() {
        List<Chunk> chunks;
        synchronized (pending) {
            chunks = new ArrayList<>(pending);
            pending.clear();
        }
        if (chunks.isEmpty()) {
            return;
        }
        for (Chunk chunk : chunks) {
            output.append(chunk.text(), chunk.kind().styleClass);
        }
        int excess = output.getLength() - MAX_CHARS;
        if (excess > 0) {
            output.deleteText(0, excess);
        }
        output.moveTo(output.getLength());
        output.requestFollowCaret();
    }
}
