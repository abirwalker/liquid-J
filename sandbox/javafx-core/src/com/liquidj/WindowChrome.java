package com.liquidj;

import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Moving, resizing and maximizing for an undecorated window, which gets none of this from the OS.
 * Windows Snap (Win+Arrow, drag to a screen edge) is not available to undecorated JavaFX windows.
 */
final class WindowChrome {

    private static final double RESIZE_MARGIN = 6;

    private final Stage stage;
    private Rectangle2D restoreBounds;
    private boolean maximized;

    private double dragOffsetX;
    private double dragOffsetY;
    private boolean dragging;

    private Cursor resizeCursor = Cursor.DEFAULT;
    private boolean resizing;
    private double startScreenX;
    private double startScreenY;
    private Rectangle2D startBounds;

    WindowChrome(Stage stage) {
        this.stage = stage;
    }

    boolean isMaximized() {
        return maximized;
    }

    void minimize() {
        stage.setIconified(true);
    }

    /** Fills the visible area of the current screen (not the taskbar), or goes back to the old size. */
    void toggleMaximize() {
        if (maximized) {
            maximized = false;
            apply(restoreBounds);
            return;
        }
        restoreBounds = currentBounds();
        Screen screen = Screen.getScreensForRectangle(restoreBounds).stream().findFirst().orElse(Screen.getPrimary());
        maximized = true;
        apply(screen.getVisualBounds());
    }

    /** Dragging empty space in this node moves the window; double-clicking it maximizes. */
    void makeDragArea(Node area) {
        area.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY || isInteractive(event) || resizing) {
                return;
            }
            dragging = true;
            dragOffsetX = event.getScreenX() - stage.getX();
            dragOffsetY = event.getScreenY() - stage.getY();
        });
        area.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!dragging) {
                return;
            }
            if (maximized) {
                // Dragging a maximized window restores it under the pointer, as Windows does.
                double ratio = dragOffsetX / stage.getWidth();
                toggleMaximize();
                dragOffsetX = stage.getWidth() * ratio;
            }
            stage.setX(event.getScreenX() - dragOffsetX);
            stage.setY(Math.max(screenTop(event.getScreenX(), event.getScreenY()), event.getScreenY() - dragOffsetY));
        });
        area.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> dragging = false);
        area.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !isInteractive(event)) {
                toggleMaximize();
            }
        });
    }

    /** Edges and corners of the scene resize the window. Filters run before any control sees the press. */
    void enableResize(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            resizeCursor = maximized ? Cursor.DEFAULT : edgeCursor(event.getSceneX(), event.getSceneY(), scene);
            scene.setCursor(resizeCursor);
        });
        scene.addEventFilter(MouseEvent.MOUSE_EXITED_TARGET, event -> {
            if (!resizing) {
                scene.setCursor(Cursor.DEFAULT);
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (resizeCursor == Cursor.DEFAULT || event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            resizing = true;
            startScreenX = event.getScreenX();
            startScreenY = event.getScreenY();
            startBounds = currentBounds();
            event.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!resizing) {
                return;
            }
            resizeTo(event.getScreenX() - startScreenX, event.getScreenY() - startScreenY);
            event.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (resizing) {
                resizing = false;
                event.consume();
            }
        });
    }

    private void resizeTo(double dx, double dy) {
        double x = startBounds.getMinX();
        double y = startBounds.getMinY();
        double width = startBounds.getWidth();
        double height = startBounds.getHeight();
        boolean west = resizeCursor == Cursor.W_RESIZE || resizeCursor == Cursor.NW_RESIZE || resizeCursor == Cursor.SW_RESIZE;
        boolean east = resizeCursor == Cursor.E_RESIZE || resizeCursor == Cursor.NE_RESIZE || resizeCursor == Cursor.SE_RESIZE;
        boolean north = resizeCursor == Cursor.N_RESIZE || resizeCursor == Cursor.NW_RESIZE || resizeCursor == Cursor.NE_RESIZE;
        boolean south = resizeCursor == Cursor.S_RESIZE || resizeCursor == Cursor.SW_RESIZE || resizeCursor == Cursor.SE_RESIZE;

        if (east) {
            width = Math.max(stage.getMinWidth(), width + dx);
        }
        if (south) {
            height = Math.max(stage.getMinHeight(), height + dy);
        }
        if (west) {
            double newWidth = Math.max(stage.getMinWidth(), width - dx);
            x += width - newWidth;
            width = newWidth;
        }
        if (north) {
            double newHeight = Math.max(stage.getMinHeight(), height - dy);
            y += height - newHeight;
            height = newHeight;
        }
        apply(new Rectangle2D(x, y, width, height));
    }

    private static Cursor edgeCursor(double x, double y, Scene scene) {
        boolean west = x < RESIZE_MARGIN;
        boolean east = x > scene.getWidth() - RESIZE_MARGIN;
        boolean north = y < RESIZE_MARGIN;
        boolean south = y > scene.getHeight() - RESIZE_MARGIN;
        if (north && west) {
            return Cursor.NW_RESIZE;
        }
        if (north && east) {
            return Cursor.NE_RESIZE;
        }
        if (south && west) {
            return Cursor.SW_RESIZE;
        }
        if (south && east) {
            return Cursor.SE_RESIZE;
        }
        if (west) {
            return Cursor.W_RESIZE;
        }
        if (east) {
            return Cursor.E_RESIZE;
        }
        if (north) {
            return Cursor.N_RESIZE;
        }
        return south ? Cursor.S_RESIZE : Cursor.DEFAULT;
    }

    /** Presses on buttons and text fields belong to them, not to window dragging. */
    private static boolean isInteractive(MouseEvent event) {
        for (Node node = event.getTarget() instanceof Node n ? n : null; node != null; node = node.getParent()) {
            if (node instanceof ButtonBase || node instanceof TextInputControl) {
                return true;
            }
        }
        return false;
    }

    private static double screenTop(double screenX, double screenY) {
        return Screen.getScreensForRectangle(screenX, screenY, 1, 1).stream()
                .findFirst().orElse(Screen.getPrimary()).getVisualBounds().getMinY();
    }

    private Rectangle2D currentBounds() {
        return new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
    }

    private void apply(Rectangle2D bounds) {
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
    }
}
