package com.htshare;

// for the ui
import com.htshare.ui.MainController;
// for the Application
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainApp extends Application {
  private static final Logger logger = LoggerFactory.getLogger(MainApp.class);
  private static final int WINDOW_WIDTH = 800;
  private static final int WINDOW_HEIGHT = 700;

  @Override
  public void start(Stage primaryStage) {
    try {
      FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
      Parent root = loader.load();
      // what is this line  do i mean i created the MainController then how can the builtin
      // controller uses them
      MainController controller = loader.getController();
      // what is stage again ?
      controller.setStage(primaryStage);

      // scene decide what is showing on the window
      Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);

      // Load default theme (light)
      // for the css
      scene.getStylesheets().add(getClass().getResource("/css/dark-theme.css").toExternalForm());
      // meta data
      primaryStage.setTitle("HtShare -Desktop");
      primaryStage.setScene(scene);
      // why is it  even there i mean  the size of the Scene had intialized
      primaryStage.setMinWidth(700);

      primaryStage.setMinHeight(600);
      primaryStage.show();

      // Cleanup on close
      primaryStage.setOnCloseRequest(
          even -> {
            controller.cleanup();
            logger.info("Application closed");
            javafx.application.Platform.exit();
            System.exit(0);
          });

    } catch (Exception e) {
      logger.error("Failed to start application", e);
      System.exit(1);
    }
  }

  public static void main(String[] args) {
    launch(args);
  }
}
