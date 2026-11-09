import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class liquidJ extends Application {

    @Override
    public void start(Stage stage) {
        TextArea textArea = new TextArea();

        StackPane root = new StackPane(textArea);
        root.setPadding(new Insets(16));

        Scene scene = new Scene(root, 600, 400);

        stage.setTitle("Liquid-J - Text Field");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
