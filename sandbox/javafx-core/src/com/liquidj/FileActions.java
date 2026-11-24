package com.liquidj;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.lang.model.SourceVersion;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;

/**
 * The right-click menu in Files: create, rename, delete and locate files.
 * Delete moves to the Recycle Bin (Trash on macOS) where the OS supports it, so a slip can be undone.
 */
final class FileActions {

    private static final String OS = System.getProperty("os.name", "");
    private static final boolean WINDOWS = OS.startsWith("Windows");
    private static final boolean MAC = OS.startsWith("Mac");
    // Windows refuses these in file names; refusing them everywhere keeps projects portable.
    private static final String FORBIDDEN = "\\/:*?\"<>|";

    private final Stage stage;
    private final Theme theme;
    private final EditorTabs editors;
    private final ProjectTree tree;
    private final Consumer<String> status;

    FileActions(Stage stage, Theme theme, EditorTabs editors, ProjectTree tree, Consumer<String> status) {
        this.stage = stage;
        this.theme = theme;
        this.editors = editors;
        this.tree = tree;
        this.status = status;
    }

    ContextMenu menuFor(Path target) {
        Path project = tree.folder().orElse(null);
        boolean isProject = target.equals(project);
        boolean isFolder = Files.isDirectory(target);
        Path folder = isFolder ? target : target.getParent();

        List<MenuItem> items = new ArrayList<>();
        if (!isFolder) {
            items.add(item("Open", null, () -> editors.open(target)));
            items.add(new SeparatorMenuItem());
        }
        items.add(item("New Java File...", null, () -> newJavaFile(folder)));
        items.add(item("New Folder...", null, () -> newFolder(folder)));
        if (!isProject) {
            items.add(new SeparatorMenuItem());
            items.add(item("Rename...", "F2", () -> rename(target)));
            items.add(item(canTrash() ? (MAC ? "Move to Trash" : "Move to Recycle Bin") : "Delete", "Delete",
                    () -> delete(target)));
        }
        items.add(new SeparatorMenuItem());
        items.add(item("Copy Path", null, () -> copy(target.toString(), "Copied path")));
        if (!isProject && project != null) {
            items.add(item("Copy Relative Path", null,
                    () -> copy(project.relativize(target).toString(), "Copied relative path")));
        }
        items.add(item(WINDOWS ? "Show in File Explorer" : MAC ? "Reveal in Finder" : "Open Containing Folder", null,
                () -> showOnDisk(target)));
        return new ContextMenu(items.toArray(MenuItem[]::new));
    }

    /** Creates Name.java in the folder, with a package line when the folder is a package under the source root. */
    void newJavaFile(Path folder) {
        Path project = tree.folder().orElse(null);
        if (project == null) {
            return;
        }
        String pkg = packageOf(folder, JavaRunner.sourceRoot(project));
        String prompt = "File name, without .java (saved in " + describe(folder, project) + ")";
        askName("New Java File", prompt, "", typed -> classNameProblem(baseName(typed), folder))
                .map(FileActions::baseName)
                .ifPresent(name -> {
                    Path file = folder.resolve(name + ".java");
                    String header = pkg == null ? "" : "package " + pkg + ";\n\n";
                    try {
                        Files.writeString(file, header + "public class " + name + " {\n\n}\n",
                                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    } catch (IOException e) {
                        showError("Could not create " + file.getFileName(), e);
                        return;
                    }
                    tree.refresh();
                    tree.reveal(file);
                    editors.open(file);
                    status.accept("Created " + project.relativize(file));
                });
    }

    void newFolder(Path parent) {
        Path project = tree.folder().orElse(null);
        if (project == null) {
            return;
        }
        String prompt = "Folder name (inside " + describe(parent, project) + ")";
        askName("New Folder", prompt, "", typed -> nameProblem(typed.strip(), parent, null)).ifPresent(name -> {
            Path folder = parent.resolve(name);
            try {
                Files.createDirectory(folder);
            } catch (IOException e) {
                showError("Could not create " + name, e);
                return;
            }
            tree.refresh();
            tree.reveal(folder);
            status.accept("Created " + project.relativize(folder));
        });
    }

    void rename(Path target) {
        String oldName = target.getFileName().toString();
        String prompt = "New name for " + oldName;
        askName("Rename", prompt, oldName, typed -> nameProblem(typed.strip(), target.getParent(), oldName))
                .ifPresent(name -> {
                    Path renamed = target.resolveSibling(name);
                    try {
                        Files.move(target, renamed);
                    } catch (IOException e) {
                        showError("Could not rename " + oldName, e);
                        return;
                    }
                    editors.moved(target, renamed);
                    tree.refresh();
                    tree.reveal(renamed);
                    status.accept("Renamed " + oldName + " to " + name);
                });
    }

    void delete(Path target) {
        String name = target.getFileName().toString();
        boolean trash = canTrash();
        List<String> details = new ArrayList<>();
        if (Files.isDirectory(target)) {
            details.add("Everything inside the folder goes with it.");
        }
        if (editors.hasUnsavedUnder(target)) {
            details.add("Unsaved changes in its open tabs will be lost.");
        }
        if (!trash) {
            details.add("This can't be undone.");
        }
        String bin = MAC ? "Trash" : "Recycle Bin";
        ButtonType confirm = new ButtonType(trash ? "Move to " + bin : "Delete", ButtonBar.ButtonData.OK_DONE);
        Alert alert = theme.style(new Alert(Alert.AlertType.CONFIRMATION, String.join("\n", details),
                confirm, ButtonType.CANCEL));
        alert.initOwner(stage);
        alert.setTitle(trash ? "Move to " + bin : "Delete");
        alert.setHeaderText(trash ? "Move " + name + " to the " + bin + "?" : "Delete " + name + " permanently?");
        // Enter must not delete by accident: Cancel is the default button.
        ((Button) alert.getDialogPane().lookupButton(confirm)).setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != confirm) {
            return;
        }
        try {
            if (trash) {
                if (!Desktop.getDesktop().moveToTrash(target.toFile())) {
                    throw new IOException("The " + bin + " refused the file.");
                }
            } else {
                deleteTree(target);
            }
        } catch (IOException | RuntimeException e) {
            showError("Could not delete " + name, e);
            return;
        }
        editors.closeUnder(target);
        tree.refresh();
        status.accept(trash ? "Moved " + name + " to the " + bin : "Deleted " + name);
    }

    private void copy(String text, String message) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        status.accept(message);
    }

    private void showOnDisk(Path target) {
        try {
            if (WINDOWS) {
                // Explorer needs "/select," and the path as separate arguments to handle spaces.
                new ProcessBuilder("explorer.exe", "/select,", target.toString()).start();
            } else if (MAC) {
                new ProcessBuilder("open", "-R", target.toString()).start();
            } else {
                Desktop.getDesktop().open((Files.isDirectory(target) ? target : target.getParent()).toFile());
            }
        } catch (IOException | RuntimeException e) {
            showError("Could not open the folder", e);
        }
    }

    /** A name dialog whose OK button stays disabled while the name has a problem, which shows in the header. */
    private Optional<String> askName(String title, String prompt, String initial, Function<String, String> problemOf) {
        TextInputDialog dialog = theme.style(new TextInputDialog(initial));
        dialog.initOwner(stage);
        dialog.setTitle(title);
        dialog.setHeaderText(prompt);
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        Runnable check = () -> {
            String problem = problemOf.apply(dialog.getEditor().getText());
            ok.setDisable(problem != null);
            dialog.setHeaderText(problem == null || problem.isEmpty() ? prompt : problem);
        };
        dialog.getEditor().textProperty().addListener((obs, old, text) -> check.run());
        check.run();
        // Select the name but not the extension, so typing replaces only the name.
        int dot = initial.lastIndexOf('.');
        int end = dot > 0 ? dot : initial.length();
        dialog.setOnShown(event -> Platform.runLater(() -> dialog.getEditor().selectRange(0, end)));
        return dialog.showAndWait().map(String::strip);
    }

    /** Null when usable, empty when blank or unchanged, otherwise the reason. */
    private static String nameProblem(String name, Path folder, String currentName) {
        if (name.isEmpty() || name.equals(currentName)) {
            return "";
        }
        if (name.equals(".") || name.equals("..") || name.endsWith(".") || name.chars().anyMatch(c -> c < 32
                || FORBIDDEN.indexOf(c) >= 0)) {
            return "\"" + name + "\" can't be used: names can't contain " + FORBIDDEN + " or end with a dot.";
        }
        // A change of letter case only is a rename of the same file, not a clash.
        boolean sameFile = name.equalsIgnoreCase(currentName == null ? "" : currentName);
        if (!sameFile && Files.exists(folder.resolve(name))) {
            return name + " already exists here.";
        }
        return null;
    }

    /** Null when the name is usable, empty when nothing is typed yet, otherwise the reason. */
    private static String classNameProblem(String name, Path folder) {
        if (name.isEmpty()) {
            return "";
        }
        // The file holds a public class of the same name, so the name must be a valid class name.
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
            return "\"" + name + "\" can't be used: a Java file name must be a valid class name"
                    + " (letters, digits, _ and $, not starting with a digit).";
        }
        if (Files.exists(folder.resolve(name + ".java"))) {
            return name + ".java already exists.";
        }
        return null;
    }

    /** "Shape.java" and "Shape" both name the file Shape.java. */
    private static String baseName(String typed) {
        String name = typed.strip();
        return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : name;
    }

    /** "shapes.round" for src/shapes/round; null in the source root, outside it, or if a folder isn't a valid name. */
    private static String packageOf(Path folder, Path sourceRoot) {
        if (!folder.startsWith(sourceRoot) || folder.equals(sourceRoot)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Path part : sourceRoot.relativize(folder)) {
            String name = part.toString();
            if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
                return null;
            }
            parts.add(name);
        }
        return String.join(".", parts);
    }

    private static String describe(Path folder, Path project) {
        return folder.equals(project) ? "the project folder" : project.relativize(folder).toString();
    }

    private static boolean canTrash() {
        return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
    }

    private static void deleteTree(Path target) throws IOException {
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void showError(String header, Exception e) {
        Alert alert = theme.style(new Alert(Alert.AlertType.ERROR, String.valueOf(e.getMessage())));
        alert.initOwner(stage);
        alert.setHeaderText(header);
        alert.showAndWait();
    }

    private static MenuItem item(String text, String accelerator, Runnable action) {
        MenuItem item = new MenuItem(text);
        if (accelerator != null) {
            // Shown as a hint only: menus shown with show() don't register accelerators; ProjectTree handles the keys.
            item.setAccelerator(KeyCombination.keyCombination(accelerator));
        }
        item.setOnAction(event -> action.run());
        return item;
    }
}
