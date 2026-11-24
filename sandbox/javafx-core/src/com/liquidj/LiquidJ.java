package com.liquidj;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.prefs.Preferences;

import javax.lang.model.SourceVersion;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

/** Window layout and the commands that connect the tree, editor, console and runner. */
public final class LiquidJ extends Application {

    private static final String VERSION = "0.1.0";
    private static final String LAST_FOLDER_KEY = "lastFolder";
    private static final String LIGHT_THEME_KEY = "lightTheme";

    private final Preferences prefs = Preferences.userNodeForPackage(LiquidJ.class);
    private final JavaRunner runner = new JavaRunner();
    private final BooleanProperty running = new SimpleBooleanProperty(false);
    private final ObjectProperty<Path> project = new SimpleObjectProperty<>();
    private final ObservableList<JavaRunner.Diagnostic> issues = FXCollections.observableArrayList();
    private final BooleanBinding cannotRun = project.isNull().or(running);
    private final Label status = new Label("Open a folder to start.");
    private final Theme theme = new Theme();

    private Stage stage;
    private EditorTabs editors;
    private ProjectTree tree;
    private ConsolePane console;
    private TabPane bottomTabs;
    private Tab issuesTab;
    private String lastMainClass;
    private boolean runRequested;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        theme.lightProperty().set(prefs.getBoolean(LIGHT_THEME_KEY, false));
        theme.lightProperty().addListener((obs, old, light) -> prefs.putBoolean(LIGHT_THEME_KEY, light));
        editors = new EditorTabs(theme);
        tree = new ProjectTree(editors::open);
        console = new ConsolePane(this::rerun, cannotRun, runner::sendInput);

        Tab consoleTab = new Tab("Console", console.node());
        issuesTab = new Tab("Issues", issueList());
        issuesTab.textProperty().bind(Bindings.when(Bindings.isEmpty(issues))
                .then("Issues")
                .otherwise(Bindings.concat("Issues (", Bindings.size(issues), ")")));
        bottomTabs = new TabPane(consoleTab, issuesTab);
        bottomTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        bottomTabs.getStyleClass().add("debug-area");

        SplitPane editorAndConsole = new SplitPane(editors.node(), bottomTabs);
        editorAndConsole.setOrientation(Orientation.VERTICAL);
        editorAndConsole.setDividerPositions(0.66);
        SplitPane workspace = new SplitPane(tree.node(), editorAndConsole);
        workspace.setDividerPositions(0.22);
        SplitPane.setResizableWithParent(tree.node(), false);

        BorderPane root = new BorderPane(workspace);
        root.setTop(new VBox(menuBar(), toolBar()));
        root.setBottom(statusBar());

        Scene scene = new Scene(root, 1200, 780);
        theme.attach(scene);
        stage.setScene(scene);
        stage.setTitle("Liquid-J");
        stage.setOnCloseRequest(event -> {
            if (!editors.closeAll()) {
                event.consume();
                return;
            }
            runner.stop();
        });
        stage.show();

        initialFolder().ifPresent(this::openProject);
    }

    private MenuBar menuBar() {
        Menu file = new Menu("File", null,
                item("Open Folder...", "Shortcut+O", this::chooseFolder, null),
                item("New Class...", "Shortcut+N", this::newClass, project.isNull()),
                new SeparatorMenuItem(),
                item("Save", "Shortcut+S", editors::saveActive, editors.noFileOpen()),
                item("Save All", "Shortcut+Shift+S", editors::saveAll, editors.noFileOpen()),
                item("Close Tab", "Shortcut+W", editors::closeActive, editors.noFileOpen()),
                new SeparatorMenuItem(),
                item("Exit", null, this::exit, null));
        Menu run = new Menu("Run", null,
                item("Run", "F5", this::run, cannotRun),
                item("Build", "Shortcut+B", () -> startBuild(null, false), cannotRun),
                item("Stop", "Shift+F5", runner::stop, running.not()),
                new SeparatorMenuItem(),
                item("Clear Console", null, console::clear, null));
        CheckMenuItem lightTheme = new CheckMenuItem("Light Theme");
        lightTheme.selectedProperty().bindBidirectional(theme.lightProperty());
        Menu view = new Menu("View", null, lightTheme);
        Menu help = new Menu("Help", null, item("About Liquid-J", null, this::showAbout, null));
        return new MenuBar(file, view, run, help);
    }

    private ToolBar toolBar() {
        Button runButton = button("Run", this::run);
        runButton.getStyleClass().add("primary");
        runButton.disableProperty().bind(cannotRun);
        Button stop = button("Stop", runner::stop);
        stop.disableProperty().bind(running.not());
        Button newClass = button("New Class", this::newClass);
        newClass.disableProperty().bind(project.isNull());
        Button open = button("Open Folder", this::chooseFolder);
        return new ToolBar(runButton, stop, new Separator(), newClass, open);
    }

    private HBox statusBar() {
        status.setId("status");
        Label caret = new Label();
        caret.textProperty().bind(editors.caretProperty());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(12, status, spacer, caret);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private ListView<JavaRunner.Diagnostic> issueList() {
        ListView<JavaRunner.Diagnostic> list = new ListView<>(issues);
        list.setId("issues");
        list.setPlaceholder(new Label("No issues."));
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(JavaRunner.Diagnostic issue, boolean empty) {
                super.updateItem(issue, empty);
                if (empty || issue == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label kind = new Label(issue.error() ? "error" : "warning");
                kind.getStyleClass().addAll("issue-kind", issue.error() ? "error" : "warning");
                setGraphic(kind);
                setText(issue.file().getFileName() + ":" + issue.line() + "  " + issue.message());
            }
        });
        list.setOnMouseClicked(event -> openIssue(list.getSelectionModel().getSelectedItem()));
        list.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                openIssue(list.getSelectionModel().getSelectedItem());
            }
        });
        return list;
    }

    private void openIssue(JavaRunner.Diagnostic issue) {
        if (issue != null) {
            editors.goTo(issue.file(), issue.line());
        }
    }

    private Optional<Path> initialFolder() {
        List<String> args = getParameters().getRaw();
        String candidate = args.isEmpty() ? prefs.get(LAST_FOLDER_KEY, null) : args.get(0);
        if (candidate == null) {
            return Optional.empty();
        }
        Path folder = Path.of(candidate).toAbsolutePath().normalize();
        return Files.isDirectory(folder) ? Optional.of(folder) : Optional.empty();
    }

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Java Project Folder");
        Path parent = project.get() == null ? null : project.get().getParent();
        if (parent != null && Files.isDirectory(parent)) {
            chooser.setInitialDirectory(parent.toFile());
        }
        File chosen = chooser.showDialog(stage);
        if (chosen != null) {
            openProject(chosen.toPath());
        }
    }

    private void openProject(Path folder) {
        if (running.get()) {
            setStatus("Stop the running program before opening another folder.", null);
            return;
        }
        if (!editors.closeAll()) {
            return;
        }
        project.set(folder);
        tree.show(folder);
        issues.clear();
        console.clear();
        lastMainClass = null;
        stage.setTitle(folder.getFileName() + " - Liquid-J");
        prefs.put(LAST_FOLDER_KEY, folder.toString());
        setStatus("Opened " + folder, null);
    }

    private void run() {
        if (cannotRun.get()) {
            return;
        }
        if (!editors.saveAll()) {
            setStatus("Run cancelled: a file could not be saved.", null);
            return;
        }
        // Run the open file when it has a main method, like most IDEs do.
        Optional<String> fromActiveFile = editors.activeFile().flatMap(JavaRunner::mainClassOf);
        if (fromActiveFile.isPresent()) {
            startBuild(fromActiveFile.get(), true);
            return;
        }
        List<String> mains = JavaRunner.mainClasses(project.get());
        if (mains.size() <= 1) {
            // With no main method we still compile, so the student sees their errors.
            startBuild(mains.isEmpty() ? null : mains.get(0), true);
            return;
        }
        String preselected = mains.contains(lastMainClass) ? lastMainClass : mains.get(0);
        ChoiceDialog<String> dialog = theme.style(new ChoiceDialog<>(preselected, mains));
        dialog.initOwner(stage);
        dialog.setTitle("Run");
        dialog.setHeaderText("Several classes have a main method. Which one should run?");
        dialog.showAndWait().ifPresent(mainClass -> startBuild(mainClass, true));
    }

    private void rerun() {
        if (lastMainClass == null) {
            run();
        } else {
            startBuild(lastMainClass, true);
        }
    }

    /** Saves, compiles and, when mainClass is not null, runs it. runWanted marks a Run with no main class. */
    private void startBuild(String mainClass, boolean runWanted) {
        if (cannotRun.get()) {
            return;
        }
        if (!editors.saveAll()) {
            setStatus("Build cancelled: a file could not be saved.", null);
            return;
        }
        runRequested = runWanted;
        if (mainClass != null) {
            lastMainClass = mainClass;
        }
        console.clear();
        issues.clear();
        bottomTabs.getSelectionModel().select(0);
        running.set(true);
        console.setInputEnabled(mainClass != null);
        setStatus(mainClass == null ? "Building..." : "Building and running " + mainClass + "...", null);

        runner.start(project.get(), mainClass, new JavaRunner.Listener() {
            @Override
            public void output(JavaRunner.Channel channel, String text) {
                console.append(text);
            }

            @Override
            public void finished(JavaRunner.Result result) {
                Platform.runLater(() -> showResult(result));
            }
        });
    }

    private void showResult(JavaRunner.Result result) {
        running.set(false);
        console.setInputEnabled(false);
        issues.setAll(result.diagnostics());
        String buildTime = String.format("%.1f s", result.compileMillis() / 1000.0);
        switch (result.status()) {
            case COMPILED -> {
                String note = runRequested ? " No class has a main method, so nothing was run." : "";
                console.append("\nBuild succeeded in " + buildTime + "." + note + "\n");
                setStatus("Build succeeded in " + buildTime + "." + note, "ok");
            }
            case RAN -> {
                console.append("\nProcess finished with exit code " + result.exitCode() + ".\n");
                setStatus("Build succeeded in " + buildTime + ". Program exited with code "
                        + result.exitCode() + ".", result.exitCode() == 0 ? "ok" : "error");
            }
            case COMPILE_FAILED -> {
                long errors = result.diagnostics().stream().filter(JavaRunner.Diagnostic::error).count();
                setStatus("Build failed: " + JavaRunner.plural(errors, "error") + ".", "error");
                if (!issues.isEmpty()) {
                    bottomTabs.getSelectionModel().select(issuesTab);
                }
            }
            case STOPPED -> {
                console.append("\nStopped.\n");
                setStatus("Stopped.", null);
            }
            case FAILED_TO_START -> {
                console.append(result.message() + "\n");
                setStatus(result.message(), "error");
            }
        }
        runRequested = false;
    }

    /** kind is "ok", "error" or null for neutral; it maps to a style class on the status label. */
    private void setStatus(String text, String kind) {
        status.setText(text);
        status.getStyleClass().removeAll("ok", "error");
        if (kind != null) {
            status.getStyleClass().add(kind);
        }
    }

    private void newClass() {
        Path folder = project.get();
        if (folder == null) {
            return;
        }
        Path sourceRoot = JavaRunner.sourceRoot(folder);
        String prompt = "Name of the new class (created in " + folder.relativize(sourceRoot) + ")";
        TextInputDialog dialog = theme.style(new TextInputDialog());
        dialog.initOwner(stage);
        dialog.setTitle("New Class");
        dialog.setHeaderText(prompt);
        Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        dialog.getEditor().textProperty().addListener((obs, old, text) -> {
            String problem = classNameProblem(text.strip(), sourceRoot);
            okButton.setDisable(problem != null);
            dialog.setHeaderText(problem == null || problem.isEmpty() ? prompt : problem);
        });

        dialog.showAndWait().map(String::strip).ifPresent(name -> {
            Path file = sourceRoot.resolve(name + ".java");
            try {
                Files.writeString(file, "public class " + name + " {\n\n}\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            } catch (IOException e) {
                Alert alert = theme.style(new Alert(Alert.AlertType.ERROR, e.getMessage()));
                alert.initOwner(stage);
                alert.setHeaderText("Could not create " + file.getFileName());
                alert.showAndWait();
                return;
            }
            tree.refresh();
            editors.open(file);
            setStatus("Created " + folder.relativize(file), null);
        });
    }

    /** Null when the name is usable, empty when nothing is typed yet, otherwise the reason. */
    private static String classNameProblem(String name, Path sourceRoot) {
        if (name.isEmpty()) {
            return "";
        }
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
            return "\"" + name + "\" is not a valid Java class name.";
        }
        if (Files.exists(sourceRoot.resolve(name + ".java"))) {
            return name + ".java already exists.";
        }
        return null;
    }

    private void showAbout() {
        Alert about = theme.style(new Alert(Alert.AlertType.INFORMATION,
                "A small Java IDE for CSE 342 (Advanced Programming Lab).\n\n"
                        + "Java " + Runtime.version() + "\nJavaFX " + System.getProperty("javafx.version")));
        about.initOwner(stage);
        about.setTitle("About Liquid-J");
        about.setHeaderText("Liquid-J " + VERSION);
        about.showAndWait();
    }

    private void exit() {
        if (editors.closeAll()) {
            runner.stop();
            stage.close();
        }
    }

    private static MenuItem item(String text, String accelerator, Runnable action, BooleanBinding disabled) {
        MenuItem item = new MenuItem(text);
        if (accelerator != null) {
            item.setAccelerator(KeyCombination.keyCombination(accelerator));
        }
        if (disabled != null) {
            item.disableProperty().bind(disabled);
        }
        item.setOnAction(event -> action.run());
        return item;
    }

    private static Button button(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("glass");
        button.setOnAction(event -> action.run());
        return button;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
