package com.liquidj;

import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Program output plus a line for typing input to the running program. */
final class ConsolePane {

    // A program stuck in a print loop would otherwise grow the TextArea until the UI freezes.
    private static final int MAX_CHARS = 200_000;

    private final TextArea output = new TextArea();
    private final TextField input = new TextField();
    private final VBox root;
    private final StringBuilder pending = new StringBuilder();

    ConsolePane(Runnable onRerun, ObservableBooleanValue rerunDisabled, Predicate<String> onInput) {
        output.setId("console-output");
        output.setEditable(false);
        output.setFont(EditorTabs.CODE_FONT);

        input.setId("console-input");
        input.setFont(EditorTabs.CODE_FONT);
        input.setPromptText("Input for the running program: type a line and press Enter");
        input.setDisable(true);
        input.setOnAction(event -> {
            String line = input.getText();
            if (onInput.test(line)) {
                append(line + "\n");
                input.clear();
            }
        });

        Button clear = new Button("Clear");
        clear.setOnAction(event -> clear());
        Button rerun = new Button("Rerun");
        rerun.disableProperty().bind(rerunDisabled);
        rerun.setOnAction(event -> onRerun.run());

        HBox actions = new HBox(4, clear, rerun);
        actions.getStyleClass().add("console-actions");
        actions.setPadding(new Insets(4, 6, 4, 6));
        root = new VBox(actions, output, input);
        root.getStyleClass().add("console");
        VBox.setVgrow(output, Priority.ALWAYS);
    }

    Node node() {
        return root;
    }

    /** Safe to call from any thread. Chunks are batched into one UI update per frame. */
    void append(String text) {
        boolean scheduleFlush;
        synchronized (pending) {
            scheduleFlush = pending.isEmpty();
            pending.append(text);
        }
        if (scheduleFlush) {
            Platform.runLater(this::flush);
        }
    }

    void clear() {
        synchronized (pending) {
            pending.setLength(0);
        }
        output.clear();
    }

    void setInputEnabled(boolean enabled) {
        input.setDisable(!enabled);
    }

    private void flush() {
        String text;
        synchronized (pending) {
            text = pending.toString();
            pending.setLength(0);
        }
        if (text.isEmpty()) {
            return;
        }
        output.appendText(text);
        int excess = output.getLength() - MAX_CHARS;
        if (excess > 0) {
            output.deleteText(0, excess);
        }
        output.end();
    }
}
