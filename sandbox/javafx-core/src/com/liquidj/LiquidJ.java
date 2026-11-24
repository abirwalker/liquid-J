package com.liquidj;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

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
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import org.fxmisc.richtext.GenericStyledArea;

/**
 * Window layout and the commands that connect the tree, editor, console and runner.
 * Layout follows Zed on Windows: one title bar with the menus, build status and window buttons;
 * a sidebar with the navigators; the editor pane with tabs, path bar, code and console; and a
 * status bar with panel toggles. The window draws its own title bar, so WindowChrome moves and
 * resizes it.
 */
public final class LiquidJ extends Application {

    private static final String VERSION = "0.4.0";
    private static final String LAST_FOLDER_KEY = "lastFolder";
    private static final String RECENT_FOLDERS_KEY = "recentFolders";
    private static final String LIGHT_THEME_KEY = "lightTheme";
    private static final int MAX_RECENT = 6;
    private static final double SIDEBAR_WIDTH = 0.2;
    private static final double CONSOLE_DIVIDER = 0.68;

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
    private final ToggleGroup navigatorTabs = new ToggleGroup();
    private final ToggleButton filesTab = new ToggleButton("Files");
    private final ToggleButton issuesTab = new ToggleButton("Issues");

    private Stage stage;
    private WindowChrome chrome;
    private EditorTabs editors;
    private ProjectTree tree;
    private FileActions fileActions;
    private ConsolePane console;
    private WelcomePane welcome;
    private SplitPane split;
    private SplitPane editorSplit;
    private Node sidebar;
    private Node consoleArea;
    private double sidebarDivider = SIDEBAR_WIDTH;
    private double consoleDivider = CONSOLE_DIVIDER;
    private String lastMainClass;
    private boolean runRequested;

    @Override
    public void start(Stage primaryStage) {
        Theme.loadFonts();
        stage = primaryStage;
        stage.initStyle(StageStyle.UNDECORATED);
        chrome = new WindowChrome(stage);
        theme.lightProperty().set(prefs.getBoolean(LIGHT_THEME_KEY, false));
        theme.lightProperty().addListener((obs, old, light) -> prefs.putBoolean(LIGHT_THEME_KEY, light));
        editors = new EditorTabs(theme);
        tree = new ProjectTree(editors::open);
        fileActions = new FileActions(stage, theme, editors, tree, text -> setStatus(text, null));
        tree.setFileActions(fileActions);
        console = new ConsolePane(runner::sendInput);
        welcome = new WelcomePane(this::chooseFolder, this::openProject);
        welcome.showRecent(recentFolders());

        sidebar = sidebar();
        split = new SplitPane(sidebar, editorPane());
        split.getStyleClass().add("window-split");
        split.setDividerPositions(SIDEBAR_WIDTH);
        SplitPane.setResizableWithParent(sidebar, false);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox root = new VBox(titleBar(), split, statusBar());
        root.getStyleClass().add("window-root");
        Scene scene = new Scene(root, 1280, 820);
        theme.attach(scene);
        chrome.enableResize(scene);
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(560);
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

    // ---------- Title bar ----------

    private Node titleBar() {
        HBox left = new HBox(menuBar());
        left.setAlignment(Pos.CENTER_LEFT);

        Button runButton = toolButton(Icons.play(), "Run (F5)", this::run);
        runButton.setId("run-button");
        runButton.getStyleClass().add("run");
        runButton.disableProperty().bind(cannotRun);
        Button stop = toolButton(Icons.stop(), "Stop (Shift+F5)", runner::stop);
        stop.disableProperty().bind(running.not());

        Button minimize = captionButton(Icons.windowMinimize(), "Minimize", chrome::minimize);
        Button maximize = captionButton(Icons.windowMaximize(), "Maximize", chrome::toggleMaximize);
        maximize.getStyleClass().add("maximize");
        chrome.maximizedProperty().addListener((obs, old, isMax) -> {
            maximize.setGraphic(isMax ? Icons.windowRestore() : Icons.windowMaximize());
            maximize.getTooltip().setText(isMax ? "Restore" : "Maximize");
        });
        Button close = captionButton(Icons.windowClose(), "Close", this::exit);
        close.getStyleClass().add("close");
        HBox captions = new HBox(minimize, maximize, close);
        captions.getStyleClass().add("caption-buttons");

        HBox right = new HBox(6, capsule(runButton, stop), captions);
        right.setAlignment(Pos.CENTER_RIGHT);
        // Only the caption buttons span the bar's full height; the rest stay their own size.
        right.setFillHeight(false);
        captions.setMinHeight(Region.USE_PREF_SIZE);

        BorderPane bar = new BorderPane(activityView(), null, right, null, left);
        bar.getStyleClass().add("title-bar");
        BorderPane.setAlignment(left, Pos.CENTER_LEFT);
        BorderPane.setAlignment(right, Pos.CENTER_RIGHT);
        chrome.makeDragArea(bar);
        return bar;
    }

    private MenuBar menuBar() {
        Menu file = new Menu("File", null,
                item("New Java File...", "Shortcut+N", this::newJavaFile, project.isNull()),
                item("Open Folder...", "Shortcut+O", this::chooseFolder, null),
                item("Close Folder", null, this::closeProject, project.isNull()),
                new SeparatorMenuItem(),
                item("Save", "Shortcut+S", editors::saveActive, editors.noFileOpen()),
                item("Save All", "Shortcut+Shift+S", editors::saveAll, editors.noFileOpen()),
                item("Close Tab", "Shortcut+W", editors::closeActive, editors.noFileOpen()),
                new SeparatorMenuItem(),
                item("Exit", null, this::exit, null));
        Menu edit = new Menu("Edit", null,
                item("Undo", "Shortcut+Z", () -> onFocusedText(GenericStyledArea::undo, TextInputControl::undo), null),
                item("Redo", "Shortcut+Y", () -> onFocusedText(GenericStyledArea::redo, TextInputControl::redo), null),
                new SeparatorMenuItem(),
                item("Cut", "Shortcut+X", () -> onFocusedText(GenericStyledArea::cut, TextInputControl::cut), null),
                item("Copy", "Shortcut+C", () -> onFocusedText(GenericStyledArea::copy, TextInputControl::copy), null),
                item("Paste", "Shortcut+V", () -> onFocusedText(GenericStyledArea::paste, TextInputControl::paste), null),
                new SeparatorMenuItem(),
                item("Select All", "Shortcut+A",
                        () -> onFocusedText(GenericStyledArea::selectAll, TextInputControl::selectAll), null));
        CheckMenuItem lightTheme = new CheckMenuItem("Light Theme");
        lightTheme.selectedProperty().bindBidirectional(theme.lightProperty());
        Menu view = new Menu("View", null,
                item("Files", "Shortcut+1", () -> showNavigator(filesTab), project.isNull()),
                item("Issues", "Shortcut+5", () -> showNavigator(issuesTab), project.isNull()),
                new SeparatorMenuItem(),
                item("Toggle Sidebar", "Shortcut+B", this::toggleSidebar, project.isNull()),
                item("Toggle Console", "Shortcut+J", this::toggleConsole, project.isNull()),
                new SeparatorMenuItem(),
                lightTheme);
        Menu run = new Menu("Run", null,
                item("Run", "F5", this::run, cannotRun),
                item("Build", "Shortcut+F9", () -> startBuild(null, false), cannotRun),
                item("Stop", "Shift+F5", runner::stop, running.not()),
                new SeparatorMenuItem(),
                item("Clear Console", null, console::clear, null));
        Menu help = new Menu("Help", null, item("About Liquid-J", null, this::showAbout, null));
        MenuBar bar = new MenuBar(file, edit, view, run, help);
        bar.setId("menu-bar");
        return bar;
    }

    /** Edit menu commands go to whichever text field or code area has focus. */
    private void onFocusedText(Consumer<GenericStyledArea<?, ?, ?>> onArea, Consumer<TextInputControl> onField) {
        Node focused = stage.getScene().getFocusOwner();
        if (focused instanceof GenericStyledArea<?, ?, ?> area) {
            onArea.accept(area);
        } else if (focused instanceof TextInputControl field) {
            onField.accept(field);
        }
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
        activity.setMaxWidth(440);
        activity.setMinWidth(240);
        BorderPane.setAlignment(activity, Pos.CENTER);
        return activity;
    }

    // ---------- Sidebar ----------

    private Node sidebar() {
        for (ToggleButton tab : List.of(filesTab, issuesTab)) {
            tab.setToggleGroup(navigatorTabs);
            tab.getStyleClass().add("navigator-tab");
            tab.disableProperty().bind(project.isNull());
        }
        filesTab.setId("files-tab");
        issuesTab.setId("issues-tab");
        Label issueCount = new Label();
        issueCount.getStyleClass().add("count-badge");
        issueCount.textProperty().bind(Bindings.size(issues).asString());
        issueCount.visibleProperty().bind(Bindings.isNotEmpty(issues));
        issueCount.managedProperty().bind(issueCount.visibleProperty());
        issuesTab.setGraphic(issueCount);
        issuesTab.setContentDisplay(ContentDisplay.RIGHT);
        navigatorTabs.selectToggle(filesTab);
        // A navigator is always showing; clicking the selected tab again keeps it selected.
        navigatorTabs.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                navigatorTabs.selectToggle(old);
            }
        });

        Button add = toolButton(Icons.plus(), "New Java File (Ctrl+N)", this::newJavaFile);
        add.getStyleClass().add("plain");
        add.disableProperty().bind(project.isNull());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(2, filesTab, issuesTab, spacer, add);
        header.getStyleClass().add("sidebar-header");
        header.setAlignment(Pos.CENTER_LEFT);

        Node files = tree.node();
        files.visibleProperty().bind(navigatorTabs.selectedToggleProperty().isEqualTo(filesTab));
        Node issueList = issueList();
        issueList.visibleProperty().bind(navigatorTabs.selectedToggleProperty().isEqualTo(issuesTab));
        StackPane content = new StackPane(files, issueList);
        VBox.setVgrow(content, Priority.ALWAYS);

        TextField filter = new TextField();
        filter.setId("file-filter");
        filter.setPromptText("Filter files");
        filter.getStyleClass().add("filter-field");
        filter.textProperty().addListener((obs, old, text) -> tree.setFilter(text));
        filter.disableProperty().bind(project.isNull());
        project.addListener((obs, old, now) -> filter.clear());
        HBox footer = new HBox(filter);
        HBox.setHgrow(filter, Priority.ALWAYS);
        footer.getStyleClass().add("sidebar-footer");

        VBox box = new VBox(header, content, footer);
        box.getStyleClass().add("sidebar");
        return box;
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
                HBox row = new HBox(8, issue.error() ? Icons.error() : Icons.warning(), new VBox(2, message, where));
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

    // ---------- Editor pane ----------

    private Node editorPane() {
        consoleArea = consoleArea();
        editorSplit = new SplitPane(editors.node(), consoleArea);
        editorSplit.setOrientation(Orientation.VERTICAL);
        editorSplit.setDividerPositions(CONSOLE_DIVIDER);
        editorSplit.getStyleClass().add("editor-split");
        editorSplit.visibleProperty().bind(project.isNotNull());

        StackPane welcomeLayer = new StackPane(welcome.node());
        welcomeLayer.getStyleClass().add("welcome-layer");
        welcomeLayer.visibleProperty().bind(project.isNull());

        StackPane pane = new StackPane(editorSplit, welcomeLayer);
        pane.getStyleClass().add("editor-pane");
        return pane;
    }

    private Node consoleArea() {
        Label heading = new Label("Console");
        heading.getStyleClass().add("console-title");
        Button rerun = new Button("Rerun");
        rerun.disableProperty().bind(cannotRun);
        rerun.setOnAction(event -> rerun());
        Button clear = new Button("Clear");
        clear.setOnAction(event -> console.clear());
        Button hide = toolButton(Icons.windowClose(), "Hide console (Ctrl+J)", this::toggleConsole);
        hide.getStyleClass().add("plain");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(4, heading, spacer, rerun, clear, hide);
        header.getStyleClass().add("console-header");
        header.setAlignment(Pos.CENTER_LEFT);

        Node output = console.node();
        VBox.setVgrow(output, Priority.ALWAYS);
        VBox area = new VBox(header, output);
        area.getStyleClass().add("console-area");
        return area;
    }

    // ---------- Status bar ----------

    private Node statusBar() {
        Button sidebarToggle = toolButton(Icons.sidebar(), "Toggle sidebar (Ctrl+B)", this::toggleSidebar);
        Button consoleToggle = toolButton(Icons.console(), "Toggle console (Ctrl+J)", this::toggleConsole);
        Button errors = issueBadge(Icons.error(), errorCount);
        Button warnings = issueBadge(Icons.warning(), warningCount);
        for (Button button : List.of(sidebarToggle, consoleToggle)) {
            button.getStyleClass().add("plain");
            button.disableProperty().bind(project.isNull());
        }
        errors.visibleProperty().bind(project.isNotNull());
        warnings.visibleProperty().bind(project.isNotNull());

        Label caret = new Label();
        caret.textProperty().bind(editors.caretProperty());
        Label lineEnding = new Label();
        lineEnding.textProperty().bind(editors.lineEndingProperty());
        HBox fileInfo = new HBox(18, caret, new Label("Spaces: 4"), new Label("UTF-8"), lineEnding, new Label("Java"));
        fileInfo.visibleProperty().bind(editors.noFileOpen().not());
        fileInfo.setAlignment(Pos.CENTER_RIGHT);
        Label jdk = new Label("JDK " + Runtime.version().feature());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(4, sidebarToggle, consoleToggle, errors, warnings, spacer, fileInfo, jdk);
        HBox.setMargin(jdk, new Insets(0, 0, 0, 18));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    // ---------- Small builders ----------

    /** Icon and count; clicking shows the Issues navigator. */
    private Button issueBadge(Node icon, IntegerBinding count) {
        Button badge = new Button();
        badge.setGraphic(icon);
        badge.textProperty().bind(count.asString());
        badge.getStyleClass().add("issue-badge");
        badge.setOnAction(event -> showNavigator(issuesTab));
        return badge;
    }

    private static Button captionButton(Node icon, String tooltip, Runnable action) {
        Button button = new Button(null, icon);
        button.getStyleClass().add("caption-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static HBox capsule(Button... buttons) {
        HBox capsule = new HBox(2, buttons);
        capsule.getStyleClass().add("capsule");
        capsule.setMaxHeight(Region.USE_PREF_SIZE);
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

    // ---------- Panels ----------

    private void toggleSidebar() {
        if (split.getItems().contains(sidebar)) {
            sidebarDivider = split.getDividerPositions()[0];
            split.getItems().remove(sidebar);
        } else {
            split.getItems().add(0, sidebar);
            split.setDividerPositions(sidebarDivider);
        }
    }

    private void toggleConsole() {
        if (editorSplit.getItems().contains(consoleArea)) {
            consoleDivider = editorSplit.getDividerPositions()[0];
            editorSplit.getItems().remove(consoleArea);
        } else {
            showConsole();
        }
    }

    private void showConsole() {
        if (!editorSplit.getItems().contains(consoleArea)) {
            editorSplit.getItems().add(consoleArea);
            editorSplit.setDividerPositions(consoleDivider);
        }
    }

    private void showNavigator(ToggleButton tab) {
        if (!split.getItems().contains(sidebar)) {
            toggleSidebar();
        }
        navigatorTabs.selectToggle(tab);
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

    /** Window title (taskbar) and the project and run target in the activity view. */
    private void updateTitle() {
        Path folder = project.get();
        if (folder == null) {
            stage.setTitle("Liquid-J");
            scheme.setText("No project");
            return;
        }
        String name = folder.getFileName().toString();
        stage.setTitle(name + " - Liquid-J");
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
        showConsole();
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
                    showNavigator(issuesTab);
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

    /** Ctrl+N and the + buttons: a new file in the source root (src/ when the project has one). */
    private void newJavaFile() {
        Path folder = project.get();
        if (folder != null) {
            fileActions.newJavaFile(JavaRunner.sourceRoot(folder));
        }
    }

    private void showAbout() {
        Alert about = theme.style(new Alert(Alert.AlertType.INFORMATION,
                "A small Java IDE for CSE 342 (Advanced Programming Lab).\n\n"
                        + "Java " + Runtime.version() + "\nJavaFX " + System.getProperty("javafx.version")
                        + "\n\nFonts: Geist and Geist Mono, SIL Open Font License 1.1."));
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
