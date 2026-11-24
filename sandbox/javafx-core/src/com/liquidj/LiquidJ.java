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
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Window layout and the commands that connect the tree, editor, console and runner.
 * The layout follows Xcode: a full-height sidebar (window controls, run controls, navigators)
 * beside the main column (toolbar, editor, console, status line). The window draws its own
 * title bar, so WindowChrome handles moving and resizing.
 */
public final class LiquidJ extends Application {

    private static final String VERSION = "0.3.0";
    private static final String LAST_FOLDER_KEY = "lastFolder";
    private static final String RECENT_FOLDERS_KEY = "recentFolders";
    private static final String LIGHT_THEME_KEY = "lightTheme";
    private static final int MAX_RECENT = 6;
    private static final double SIDEBAR_WIDTH = 0.22;

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
    private final Label title = new Label();
    private final ToggleGroup navigatorTabs = new ToggleGroup();
    private final ToggleButton filesTab = new ToggleButton();
    private final ToggleButton issuesTab = new ToggleButton();

    private Stage stage;
    private WindowChrome chrome;
    private EditorTabs editors;
    private ProjectTree tree;
    private ConsolePane console;
    private WelcomePane welcome;
    private SplitPane split;
    private VBox sidebarBody;
    private double sidebarDivider = SIDEBAR_WIDTH;
    private String lastMainClass;
    private boolean runRequested;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.initStyle(StageStyle.UNDECORATED);
        chrome = new WindowChrome(stage);
        theme.lightProperty().set(prefs.getBoolean(LIGHT_THEME_KEY, false));
        theme.lightProperty().addListener((obs, old, light) -> prefs.putBoolean(LIGHT_THEME_KEY, light));
        editors = new EditorTabs(theme);
        tree = new ProjectTree(editors::open);
        console = new ConsolePane(runner::sendInput);
        welcome = new WelcomePane(this::chooseFolder, this::openProject);
        welcome.showRecent(recentFolders());

        split = new SplitPane(sidebar(), mainColumn());
        split.getStyleClass().add("window-split");
        split.setDividerPositions(SIDEBAR_WIDTH);
        SplitPane.setResizableWithParent(split.getItems().get(0), false);

        StackPane root = new StackPane(split);
        root.getStyleClass().add("window-root");
        Scene scene = new Scene(root, 1280, 820);
        theme.attach(scene);
        chrome.enableResize(scene);
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        stage.setTitle("Liquid-J");
        stage.setOnCloseRequest(event -> {
            if (!editors.closeAll()) {
                event.consume();
                return;
            }
            runner.stop();
        });
        updateTitle();
        setStatus("Ready", null);
        stage.show();

        initialFolder().ifPresent(this::openProject);
    }

    // ---------- Sidebar ----------

    private Node sidebar() {
        HBox windowButtons = new HBox(8,
                windowButton("close", "Close", this::exit),
                windowButton("minimize", "Minimize", () -> chrome.minimize()),
                windowButton("maximize", "Maximize or restore", () -> chrome.toggleMaximize()));
        windowButtons.getStyleClass().add("window-buttons");
        windowButtons.setAlignment(Pos.CENTER_LEFT);

        Button runButton = toolButton(Icons.play(), "Run (F5)", this::run);
        runButton.setId("run-button");
        runButton.getStyleClass().add("run");
        runButton.disableProperty().bind(cannotRun);
        Button stop = toolButton(Icons.stop(), "Stop (Shift+F5)", runner::stop);
        stop.disableProperty().bind(running.not());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox topRow = new HBox(10, windowButtons, appMenu(), spacer, capsule(runButton, stop));
        topRow.getStyleClass().add("sidebar-top");
        topRow.setAlignment(Pos.CENTER_LEFT);
        chrome.makeDragArea(topRow);

        sidebarBody = new VBox(navigatorTabStrip(), navigatorContent(), sidebarFooter());
        VBox.setVgrow(sidebarBody.getChildren().get(1), Priority.ALWAYS);
        VBox.setVgrow(sidebarBody, Priority.ALWAYS);

        VBox sidebar = new VBox(topRow, sidebarBody);
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    /** File, View, Run and Help in one menu; MenuButton installs the item shortcuts on the scene. */
    private Node appMenu() {
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
                item("Show or Hide Navigator", "Shortcut+0", this::toggleSidebar, project.isNull()),
                item("Files", "Shortcut+1", () -> navigatorTabs.selectToggle(filesTab), project.isNull()),
                item("Issues", "Shortcut+5", () -> navigatorTabs.selectToggle(issuesTab), project.isNull()),
                new SeparatorMenuItem(),
                lightTheme);
        Menu run = new Menu("Run", null,
                item("Run", "F5", this::run, cannotRun),
                item("Build", "Shortcut+B", () -> startBuild(null, false), cannotRun),
                item("Stop", "Shift+F5", runner::stop, running.not()),
                new SeparatorMenuItem(),
                item("Clear Console", null, console::clear, null));
        Menu help = new Menu("Help", null, item("About Liquid-J", null, this::showAbout, null));

        MenuButton menu = new MenuButton(null, Icons.menu(), file, view, run, help);
        menu.setId("app-menu");
        menu.getStyleClass().add("app-menu");
        menu.setTooltip(new Tooltip("Menu"));
        return menu;
    }

    private Node navigatorTabStrip() {
        filesTab.setGraphic(Icons.folderOpen());
        filesTab.setTooltip(new Tooltip("Files (Ctrl+1)"));
        filesTab.setId("files-tab");
        issuesTab.setGraphic(Icons.warning());
        issuesTab.setTooltip(new Tooltip("Issues (Ctrl+5)"));
        issuesTab.setId("issues-tab");
        for (ToggleButton tab : List.of(filesTab, issuesTab)) {
            tab.setToggleGroup(navigatorTabs);
            tab.getStyleClass().add("navigator-tab");
            tab.disableProperty().bind(project.isNull());
        }
        navigatorTabs.selectToggle(filesTab);
        // A navigator is always showing; clicking the selected tab again keeps it selected.
        navigatorTabs.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                navigatorTabs.selectToggle(old);
            }
        });
        HBox strip = new HBox(2, filesTab, issuesTab);
        strip.getStyleClass().add("navigator-tabs");
        return strip;
    }

    private Node navigatorContent() {
        Node files = tree.node();
        files.visibleProperty().bind(navigatorTabs.selectedToggleProperty().isEqualTo(filesTab));
        Node issueList = issueList();
        issueList.visibleProperty().bind(navigatorTabs.selectedToggleProperty().isEqualTo(issuesTab));
        return new StackPane(files, issueList);
    }

    private Node sidebarFooter() {
        Button add = toolButton(Icons.plus(), "New Class (Ctrl+N)", this::newClass);
        add.disableProperty().bind(project.isNull());
        TextField filter = new TextField();
        filter.setId("file-filter");
        filter.setPromptText("Filter");
        filter.getStyleClass().add("filter-field");
        filter.textProperty().addListener((obs, old, text) -> tree.setFilter(text));
        filter.disableProperty().bind(project.isNull());
        project.addListener((obs, old, now) -> filter.clear());
        HBox.setHgrow(filter, Priority.ALWAYS);
        HBox footer = new HBox(6, add, filter);
        footer.getStyleClass().add("sidebar-footer");
        footer.setAlignment(Pos.CENTER_LEFT);
        return footer;
    }

    private ListView<JavaRunner.Diagnostic> issueList() {
        ListView<JavaRunner.Diagnostic> list = new ListView<>(issues);
        list.setId("issues");
        Label placeholder = new Label("No issues");
        placeholder.getStyleClass().add("empty-state");
        list.setPlaceholder(placeholder);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(JavaRunner.Diagnostic issue, boolean empty) {
                super.updateItem(issue, empty);
                if (empty || issue == null) {
                    setGraphic(null);
                    return;
                }
                Label message = new Label(issue.message());
                message.getStyleClass().add("issue-message");
                message.setWrapText(true);
                Label where = new Label(issue.file().getFileName() + ":" + issue.line());
                where.getStyleClass().add("issue-location");
                HBox row = new HBox(8, issue.error() ? Icons.error() : Icons.warning(), new VBox(1, message, where));
                setGraphic(row);
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

    // ---------- Main column ----------

    private Node mainColumn() {
        SplitPane editorAndConsole = new SplitPane(editors.node(), consoleArea());
        editorAndConsole.setOrientation(Orientation.VERTICAL);
        editorAndConsole.setDividerPositions(0.7);
        editorAndConsole.getStyleClass().add("editor-split");
        editorAndConsole.visibleProperty().bind(project.isNotNull());

        StackPane welcomeLayer = new StackPane(welcome.node());
        welcomeLayer.getStyleClass().add("welcome-layer");
        welcomeLayer.visibleProperty().bind(project.isNull());

        StackPane body = new StackPane(editorAndConsole, welcomeLayer);
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox column = new VBox(mainToolbar(), body, statusLine());
        column.getStyleClass().add("main-column");
        return column;
    }

    private Node mainToolbar() {
        Button sidebarToggle = toolButton(Icons.sidebar(), "Show or hide the navigator (Ctrl+0)", this::toggleSidebar);
        sidebarToggle.getStyleClass().add("plain");
        sidebarToggle.disableProperty().bind(project.isNull());
        title.getStyleClass().add("window-title");

        Button open = toolButton(Icons.folderOpen(), "Open Folder (Ctrl+O)", this::chooseFolder);
        Button themeToggle = toolButton(Icons.theme(), "Switch light or dark theme",
                () -> theme.lightProperty().set(!theme.lightProperty().get()));

        Region left = new Region();
        Region right = new Region();
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox bar = new HBox(10, sidebarToggle, title, left, activityView(), right, capsule(open, themeToggle));
        bar.getStyleClass().add("main-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        chrome.makeDragArea(bar);
        return bar;
    }

    /** The centre capsule: project and run target, then what the last build did. */
    private Node activityView() {
        scheme.getStyleClass().add("scheme");
        status.setId("status");
        status.getStyleClass().add("activity-status");
        statusIcon.getStyleClass().add("activity-icon");

        Button errors = issueBadge(Icons.error(), errorCount);
        Button warnings = issueBadge(Icons.warning(), warningCount);
        errors.visibleProperty().bind(errorCount.greaterThan(0));
        errors.managedProperty().bind(errors.visibleProperty());
        warnings.visibleProperty().bind(warningCount.greaterThan(0));
        warnings.managedProperty().bind(warnings.visibleProperty());
        Region divider = new Region();
        divider.getStyleClass().add("activity-divider");

        HBox content = new HBox(8, scheme, divider, statusIcon, status, errors, warnings);
        content.setAlignment(Pos.CENTER_LEFT);

        ProgressBar progress = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        progress.setId("build-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.visibleProperty().bind(running);
        StackPane.setAlignment(progress, Pos.BOTTOM_CENTER);

        StackPane activity = new StackPane(content, progress);
        activity.getStyleClass().add("activity");
        activity.setMaxWidth(480);
        activity.setMinWidth(260);
        HBox.setHgrow(activity, Priority.SOMETIMES);
        return activity;
    }

    private Node consoleArea() {
        Label heading = new Label("Console");
        heading.getStyleClass().add("console-title");
        Button rerun = new Button("Rerun");
        rerun.disableProperty().bind(cannotRun);
        rerun.setOnAction(event -> rerun());
        Button clear = new Button("Clear");
        clear.setOnAction(event -> console.clear());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(4, heading, spacer, rerun, clear);
        header.getStyleClass().add("console-header");
        header.setAlignment(Pos.CENTER_LEFT);

        Node output = console.node();
        VBox.setVgrow(output, Priority.ALWAYS);
        VBox area = new VBox(header, output);
        area.getStyleClass().add("console-area");
        return area;
    }

    private Node statusLine() {
        Button errors = issueBadge(Icons.error(), errorCount);
        Button warnings = issueBadge(Icons.warning(), warningCount);
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
        HBox line = new HBox(16, errors, warnings, spacer, fileInfo, java);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("status-line");
        return line;
    }

    // ---------- Small builders ----------

    /** Icon and count; clicking shows the Issues navigator. */
    private Button issueBadge(Node icon, IntegerBinding count) {
        Button badge = new Button();
        badge.setGraphic(icon);
        badge.textProperty().bind(count.asString());
        badge.getStyleClass().add("issue-badge");
        badge.setOnAction(event -> showIssues());
        return badge;
    }

    private static Button windowButton(String kind, String tooltip, Runnable action) {
        Button button = new Button();
        button.getStyleClass().addAll("window-button", kind);
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false);
        button.setOnAction(event -> action.run());
        return button;
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

    private IntegerBinding countIssues(boolean errors) {
        return Bindings.createIntegerBinding(
                () -> (int) issues.stream().filter(issue -> issue.error() == errors).count(), issues);
    }

    /** Collapses the sidebar to its top row (window and run controls), or opens it again. */
    private void toggleSidebar() {
        boolean open = sidebarBody.isVisible();
        sidebarBody.setVisible(!open);
        sidebarBody.setManaged(!open);
        if (open) {
            sidebarDivider = split.getDividerPositions()[0];
            Node sidebar = split.getItems().get(0);
            double collapsed = sidebar.minWidth(-1) / split.getWidth();
            split.setDividerPositions(collapsed);
        } else {
            split.setDividerPositions(sidebarDivider);
        }
    }

    private void showIssues() {
        if (!sidebarBody.isVisible()) {
            toggleSidebar();
        }
        navigatorTabs.selectToggle(issuesTab);
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
        navigatorTabs.selectToggle(filesTab);
        issues.clear();
        console.clear();
        lastMainClass = null;
        prefs.put(LAST_FOLDER_KEY, normalized.toString());
        rememberRecent(normalized);
        updateTitle();
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
        tree.hide();
        issues.clear();
        console.clear();
        lastMainClass = null;
        prefs.remove(LAST_FOLDER_KEY);
        welcome.showRecent(recentFolders());
        updateTitle();
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

    /** Window title, toolbar title and the run target in the activity view. */
    private void updateTitle() {
        Path folder = project.get();
        if (folder == null) {
            stage.setTitle("Liquid-J");
            title.setText("Liquid-J");
            scheme.setText("No project");
            return;
        }
        String name = folder.getFileName().toString();
        stage.setTitle(name + " - Liquid-J");
        title.setText(name);
        scheme.setText(name + (lastMainClass == null ? "" : "  ›  " + lastMainClass));
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
        running.set(true);
        console.setInputEnabled(mainClass != null);
        updateTitle();
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
                setStatus(clean ? "Build Succeeded  ·  " + buildTime : "Exited with code " + result.exitCode(),
                        clean ? "ok" : "error");
            }
            case COMPILE_FAILED -> {
                long errors = result.diagnostics().stream().filter(JavaRunner.Diagnostic::error).count();
                setStatus("Build Failed  ·  " + JavaRunner.plural(errors, "error"), "error");
                if (!issues.isEmpty()) {
                    showIssues();
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

    public static void main(String[] args) {
        launch(args);
    }
}
