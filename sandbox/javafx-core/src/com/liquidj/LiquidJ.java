package com.liquidj;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.prefs.Preferences;

import javax.lang.model.SourceVersion;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.IntegerBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
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
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

/** Window layout and the commands that connect the tree, editor, console and runner. */
public final class LiquidJ extends Application {

    private static final String VERSION = "0.2.0";
    private static final String LAST_FOLDER_KEY = "lastFolder";
    private static final String RECENT_FOLDERS_KEY = "recentFolders";
    private static final String LIGHT_THEME_KEY = "lightTheme";
    private static final int MAX_RECENT = 6;

    private final Preferences prefs = Preferences.userNodeForPackage(LiquidJ.class);
    private final JavaRunner runner = new JavaRunner();
    private final BooleanProperty running = new SimpleBooleanProperty(false);
    private final ObjectProperty<Path> project = new SimpleObjectProperty<>();
    private final ObservableList<JavaRunner.Diagnostic> issues = FXCollections.observableArrayList();
    private final IntegerBinding errorCount = countIssues(true);
    private final IntegerBinding warningCount = countIssues(false);
    private final BooleanBinding cannotRun = project.isNull().or(running);
    private final Theme theme = new Theme();

    private final Label status = new Label();
    private final StackPane statusIcon = new StackPane();
    private final Label scheme = new Label();

    private Stage stage;
    private EditorTabs editors;
    private ProjectTree tree;
    private ConsolePane console;
    private WelcomePane welcome;
    private SplitPane workspace;
    private TabPane bottomTabs;
    private Tab issuesTab;
    private double navigatorDivider = 0.2;
    private String lastMainClass;
    private boolean runRequested;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        theme.lightProperty().set(prefs.getBoolean(LIGHT_THEME_KEY, false));
        theme.lightProperty().addListener((obs, old, light) -> prefs.putBoolean(LIGHT_THEME_KEY, light));
        editors = new EditorTabs(theme);
        tree = new ProjectTree(editors::open);
        console = new ConsolePane(runner::sendInput);
        welcome = new WelcomePane(this::chooseFolder, this::openProject);
        welcome.showRecent(recentFolders());

        SplitPane editorAndConsole = new SplitPane(editors.node(), debugArea());
        editorAndConsole.setOrientation(Orientation.VERTICAL);
        editorAndConsole.setDividerPositions(0.7);
        editorAndConsole.getStyleClass().add("content-panel");
        clipRounded(editorAndConsole, 12);
        workspace = new SplitPane(tree.node(), editorAndConsole);
        workspace.getStyleClass().add("workspace");
        workspace.setDividerPositions(navigatorDivider);
        SplitPane.setResizableWithParent(tree.node(), false);
        workspace.visibleProperty().bind(project.isNotNull());

        StackPane welcomeLayer = new StackPane(welcome.node());
        welcomeLayer.getStyleClass().add("welcome-layer");
        welcomeLayer.visibleProperty().bind(project.isNull());

        BorderPane root = new BorderPane(new StackPane(workspace, welcomeLayer));
        root.setTop(new VBox(menuBar(), toolBar()));
        root.setBottom(statusBar());

        Scene scene = new Scene(root, 1280, 820);
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
        updateScheme();
        setStatus("Ready", null);
        stage.show();

        initialFolder().ifPresent(this::openProject);
    }

    // ---------- Window parts ----------

    private MenuBar menuBar() {
        Menu file = new Menu("File", null,
                item("Open Folder...", "Shortcut+O", this::chooseFolder, null),
                item("Close Folder", null, this::closeProject, project.isNull()),
                new SeparatorMenuItem(),
                item("New Class...", "Shortcut+N", this::newClass, project.isNull()),
                item("Save", "Shortcut+S", editors::saveActive, editors.noFileOpen()),
                item("Save All", "Shortcut+Shift+S", editors::saveAll, editors.noFileOpen()),
                item("Close Tab", "Shortcut+W", editors::closeActive, editors.noFileOpen()),
                new SeparatorMenuItem(),
                item("Exit", null, this::exit, null));
        CheckMenuItem lightTheme = new CheckMenuItem("Light Theme");
        lightTheme.selectedProperty().bindBidirectional(theme.lightProperty());
        Menu view = new Menu("View", null,
                item("Show or Hide Navigator", "Shortcut+0", this::toggleNavigator, project.isNull()),
                lightTheme);
        Menu run = new Menu("Run", null,
                item("Run", "F5", this::run, cannotRun),
                item("Build", "Shortcut+B", () -> startBuild(null, false), cannotRun),
                item("Stop", "Shift+F5", runner::stop, running.not()),
                new SeparatorMenuItem(),
                item("Clear Console", null, console::clear, null));
        Menu help = new Menu("Help", null, item("About Liquid-J", null, this::showAbout, null));
        return new MenuBar(file, view, run, help);
    }

    private Node toolBar() {
        Button navigator = toolButton(Icons.sidebar(), "Show or hide the navigator (Ctrl+0)", this::toggleNavigator);
        navigator.disableProperty().bind(project.isNull());
        Button runButton = toolButton(Icons.play(), "Run (F5)", this::run);
        runButton.setId("run-button");
        runButton.getStyleClass().add("run");
        runButton.disableProperty().bind(cannotRun);
        Button stop = toolButton(Icons.stop(), "Stop (Shift+F5)", runner::stop);
        stop.disableProperty().bind(running.not());
        HBox left = new HBox(8, capsule(navigator), capsule(runButton, stop));
        left.setAlignment(Pos.CENTER_LEFT);

        Button newClass = toolButton(Icons.plus(), "New Class (Ctrl+N)", this::newClass);
        newClass.disableProperty().bind(project.isNull());
        Button open = toolButton(Icons.folderOpen(), "Open Folder (Ctrl+O)", this::chooseFolder);
        Button themeToggle = toolButton(Icons.theme(), "Switch light or dark theme",
                () -> theme.lightProperty().set(!theme.lightProperty().get()));
        HBox right = new HBox(8, capsule(newClass, open), capsule(themeToggle));
        right.setAlignment(Pos.CENTER_RIGHT);

        BorderPane bar = new BorderPane(activityView(), null, right, null, left);
        bar.getStyleClass().add("window-toolbar");
        return bar;
    }

    /** The centre capsule: project and run target, then what the last build did. */
    private Node activityView() {
        scheme.getStyleClass().add("scheme");
        status.setId("status");
        status.getStyleClass().add("activity-status");
        statusIcon.getStyleClass().add("activity-icon");

        Button errors = issueBadge(Icons.error(), errorCount, true);
        Button warnings = issueBadge(Icons.warning(), warningCount, true);
        Region divider = new Region();
        divider.getStyleClass().add("activity-divider");

        HBox content = new HBox(8, scheme, divider, statusIcon, status, errors, warnings);
        content.setAlignment(Pos.CENTER_LEFT);
        content.getStyleClass().add("activity-content");

        ProgressBar progress = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        progress.setId("build-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.visibleProperty().bind(running);
        StackPane.setAlignment(progress, Pos.BOTTOM_CENTER);

        StackPane activity = new StackPane(content, progress);
        activity.getStyleClass().add("activity");
        activity.setMaxWidth(560);
        activity.setMinWidth(320);
        BorderPane.setAlignment(activity, Pos.CENTER);
        return activity;
    }

    private Node debugArea() {
        Tab consoleTab = new Tab("Console", console.node());
        issuesTab = new Tab("Issues", issueList());
        Label count = new Label();
        count.getStyleClass().add("count-badge");
        count.textProperty().bind(Bindings.size(issues).asString());
        count.visibleProperty().bind(Bindings.isNotEmpty(issues));
        count.managedProperty().bind(count.visibleProperty());
        issuesTab.setGraphic(count);
        bottomTabs = new TabPane(consoleTab, issuesTab);
        bottomTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        bottomTabs.getStyleClass().add("debug-area");

        Button rerun = new Button("Rerun");
        rerun.disableProperty().bind(cannotRun);
        rerun.setOnAction(event -> rerun());
        Button clear = new Button("Clear");
        clear.setOnAction(event -> console.clear());
        HBox actions = new HBox(2, rerun, clear);
        actions.getStyleClass().add("debug-actions");
        actions.setPickOnBounds(false);
        actions.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(actions, Pos.TOP_RIGHT);
        return new StackPane(bottomTabs, actions);
    }

    private Node statusBar() {
        Button errors = issueBadge(Icons.error(), errorCount, false);
        Button warnings = issueBadge(Icons.warning(), warningCount, false);
        errors.visibleProperty().bind(project.isNotNull());
        warnings.visibleProperty().bind(project.isNotNull());

        Label caret = new Label();
        caret.textProperty().bind(editors.caretProperty());
        Label lineEnding = new Label();
        lineEnding.textProperty().bind(editors.lineEndingProperty());
        HBox fileInfo = new HBox(16, caret, new Label("Spaces: 4"), new Label("UTF-8"), lineEnding);
        fileInfo.visibleProperty().bind(editors.noFileOpen().not());
        fileInfo.setAlignment(Pos.CENTER_RIGHT);
        Label java = new Label("Java " + Runtime.version().feature());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(16, errors, warnings, spacer, fileInfo, java);
        bar.setAlignment(Pos.CENTER_LEFT);
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
                setGraphic(issue.error() ? Icons.error() : Icons.warning());
                setText(issue.file().getFileName() + ":" + issue.line() + "   " + issue.message());
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

    /** Icon and count; clicking shows the Issues tab. */
    private Button issueBadge(Node icon, IntegerBinding count, boolean hideWhenZero) {
        Button badge = new Button();
        badge.setGraphic(icon);
        badge.textProperty().bind(count.asString());
        badge.getStyleClass().add("issue-badge");
        if (hideWhenZero) {
            badge.visibleProperty().bind(count.greaterThan(0));
            badge.managedProperty().bind(badge.visibleProperty());
        }
        badge.setOnAction(event -> bottomTabs.getSelectionModel().select(issuesTab));
        return badge;
    }

    private static HBox capsule(Button... buttons) {
        HBox capsule = new HBox(2, buttons);
        capsule.getStyleClass().add("capsule");
        capsule.setAlignment(Pos.CENTER);
        return capsule;
    }

    private static Button toolButton(Node icon, String tooltip, Runnable action) {
        Button button = new Button(null, icon);
        button.getStyleClass().add("tool-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(event -> action.run());
        return button;
    }

    /** Children of a rounded panel paint over its corners unless the panel clips them. */
    private static void clipRounded(Region region, double radius) {
        Rectangle clip = new Rectangle();
        clip.setArcWidth(radius * 2);
        clip.setArcHeight(radius * 2);
        clip.widthProperty().bind(region.widthProperty());
        clip.heightProperty().bind(region.heightProperty());
        region.setClip(clip);
    }

    private IntegerBinding countIssues(boolean errors) {
        return Bindings.createIntegerBinding(
                () -> (int) issues.stream().filter(issue -> issue.error() == errors).count(), issues);
    }

    private void toggleNavigator() {
        Node navigator = tree.node();
        if (workspace.getItems().contains(navigator)) {
            navigatorDivider = workspace.getDividerPositions()[0];
            workspace.getItems().remove(navigator);
        } else {
            workspace.getItems().add(0, navigator);
            workspace.setDividerPositions(navigatorDivider);
        }
    }

    // ---------- Projects ----------

    private Optional<Path> initialFolder() {
        List<String> args = getParameters().getRaw();
        String candidate = args.isEmpty() ? prefs.get(LAST_FOLDER_KEY, null) : args.get(0);
        return candidate == null ? Optional.empty() : toFolder(candidate);
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
        if (!Files.isDirectory(folder)) {
            setStatus("Folder not found: " + folder, "error");
            welcome.showRecent(recentFolders());
            return;
        }
        if (!editors.closeAll()) {
            return;
        }
        Path normalized = folder.toAbsolutePath().normalize();
        project.set(normalized);
        editors.setProjectRoot(normalized);
        tree.show(normalized);
        issues.clear();
        console.clear();
        lastMainClass = null;
        stage.setTitle(normalized.getFileName() + " - Liquid-J");
        prefs.put(LAST_FOLDER_KEY, normalized.toString());
        rememberRecent(normalized);
        updateScheme();
        setStatus("Ready", null);
    }

    private void closeProject() {
        if (running.get()) {
            setStatus("Stop the running program before closing the folder.", null);
            return;
        }
        if (!editors.closeAll()) {
            return;
        }
        project.set(null);
        issues.clear();
        console.clear();
        lastMainClass = null;
        stage.setTitle("Liquid-J");
        prefs.remove(LAST_FOLDER_KEY);
        welcome.showRecent(recentFolders());
        updateScheme();
        setStatus("Ready", null);
    }

    private List<Path> recentFolders() {
        return prefs.get(RECENT_FOLDERS_KEY, "").lines()
                .map(this::toFolder)
                .flatMap(Optional::stream)
                .distinct()
                .limit(MAX_RECENT)
                .toList();
    }

    private void rememberRecent(Path folder) {
        List<Path> recent = new ArrayList<>(recentFolders());
        recent.remove(folder);
        recent.add(0, folder);
        List<String> kept = new ArrayList<>();
        int length = 0;
        for (Path path : recent.subList(0, Math.min(MAX_RECENT, recent.size()))) {
            length += path.toString().length() + 1;
            if (length > Preferences.MAX_VALUE_LENGTH) {
                break;
            }
            kept.add(path.toString());
        }
        prefs.put(RECENT_FOLDERS_KEY, String.join("\n", kept));
        welcome.showRecent(recentFolders());
    }

    private Optional<Path> toFolder(String text) {
        try {
            Path folder = Path.of(text.strip()).toAbsolutePath().normalize();
            return Files.isDirectory(folder) ? Optional.of(folder) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private void updateScheme() {
        Path folder = project.get();
        if (folder == null) {
            scheme.setText("No project");
            return;
        }
        scheme.setText(folder.getFileName() + (lastMainClass == null ? "" : "  ›  " + lastMainClass));
    }

    // ---------- Build and run ----------

    private void openIssue(JavaRunner.Diagnostic issue) {
        if (issue != null) {
            editors.goTo(issue.file(), issue.line());
        }
    }

    private void run() {
        if (cannotRun.get()) {
            return;
        }
        if (!editors.saveAll()) {
            setStatus("Run cancelled: a file could not be saved.", "error");
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
            setStatus("Build cancelled: a file could not be saved.", "error");
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
        updateScheme();
        setStatus(mainClass == null ? "Building..." : "Running " + mainClass + "...", null);

        runner.start(project.get(), mainClass, new JavaRunner.Listener() {
            @Override
            public void output(JavaRunner.Channel channel, String text) {
                console.append(text, switch (channel) {
                    case OUT -> ConsolePane.Kind.OUT;
                    case ERR -> ConsolePane.Kind.ERR;
                    case INFO -> ConsolePane.Kind.INFO;
                });
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
                console.append("\nBuild succeeded in " + buildTime + "." + note + "\n", ConsolePane.Kind.INFO);
                setStatus("Build Succeeded  ·  " + buildTime, "ok");
            }
            case RAN -> {
                console.append("\nProcess finished with exit code " + result.exitCode() + ".\n",
                        ConsolePane.Kind.INFO);
                boolean clean = result.exitCode() == 0;
                setStatus((clean ? "Build Succeeded  ·  " + buildTime : "Exited with code " + result.exitCode()),
                        clean ? "ok" : "error");
            }
            case COMPILE_FAILED -> {
                long errors = result.diagnostics().stream().filter(JavaRunner.Diagnostic::error).count();
                setStatus("Build Failed  ·  " + JavaRunner.plural(errors, "error"), "error");
                if (!issues.isEmpty()) {
                    bottomTabs.getSelectionModel().select(issuesTab);
                }
            }
            case STOPPED -> {
                console.append("\nStopped.\n", ConsolePane.Kind.INFO);
                setStatus("Stopped", null);
            }
            case FAILED_TO_START -> {
                console.append(result.message() + "\n", ConsolePane.Kind.ERR);
                setStatus(result.message(), "error");
            }
        }
        runRequested = false;
    }

    /** kind is "ok", "error" or null for neutral; it picks the icon and a style class on the status text. */
    private void setStatus(String text, String kind) {
        status.setText(text);
        status.getStyleClass().removeAll("ok", "error");
        statusIcon.getChildren().clear();
        if (kind != null) {
            status.getStyleClass().add(kind);
            statusIcon.getChildren().add(kind.equals("ok") ? Icons.success() : Icons.error());
        }
    }

    // ---------- Dialogs ----------

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

    public static void main(String[] args) {
        launch(args);
    }
}
