package com.htshare.ui;

import com.htshare.server.ConnectionMonitor;
import com.htshare.server.HttpsFileServer;
import com.htshare.util.NetworkUtils;
import com.htshare.util.NetworkVerifier;
import com.htshare.util.QRCodeGenerator;
import java.io.File;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainController {
  private static final Logger logger = LoggerFactory.getLogger(MainController.class);
  private static final int PREFERRED_PORT_HTTPS = 8443;
  private static final int PREFERRED_PORT_HTTP = 8080;
  private static final int AUTO_SHUTDOWN_MINUTES = 5;
  private static final String GITHUB_URL = "https://github.com/TNLucifer";

  @FXML private VBox rootContainer;
  @FXML private Label networkStatusLabel;
  @FXML private Button refreshNetworkButton;
  @FXML private Label folderPathLabel;
  @FXML private Button selectFolderButton;
  @FXML private Button startServerButton;
  @FXML private Button stopServerButton;
  @FXML private CheckBox autoShutdownCheck;
  @FXML private CheckBox httpsCheck;
  @FXML private ImageView qrCodeImageView;
  @FXML private HBox urlContainer;
  @FXML private Label serverUrlLabel;
  @FXML private Button copyUrlButton;
  @FXML private Label portInfoLabel;
  @FXML private Label statusLabel;
  @FXML private ToggleButton themeToggle;
  @FXML private FontIcon themeIcon;
  @FXML private ProgressIndicator serverProgress;
  @FXML private Hyperlink githubLink;

  // Statistics
  @FXML private HBox statsSection;
  @FXML private Label requestsLabel;
  @FXML private Label downloadsLabel;
  @FXML private Label activeConnectionsLabel;
  @FXML private Label timeRemainingLabel;

  private Stage stage;
  private File selectedFolder;
  private HttpsFileServer fileServer;
  private boolean isDarkTheme = false;
  private int currentPort;
  private Timer statsUpdateTimer;
  private NetworkVerifier.NetworkInfo currentNetwork;
  private boolean useHttps = true;

  @FXML
  public void initialize() {
    stopServerButton.setDisable(true);
    serverProgress.setVisible(false);
    qrCodeImageView.setVisible(false);

    if (urlContainer != null) {
      urlContainer.setVisible(false);
    }

    if (portInfoLabel != null) {
      portInfoLabel.setVisible(false);
    }

    if (statsSection != null) {
      statsSection.setVisible(false);
    }

    // Set HTTPS as default
    if (httpsCheck != null) {
      httpsCheck.setSelected(true);
    }

    // Set default theme (change isDarkTheme to true for dark mode default)
    isDarkTheme = true; // false = light (default), true = dark
    updateThemeIcon();

    updateStatus("Ready. Select a folder to share.", "info");

    // Check network on startup
    checkNetwork();
  }

  public void setStage(Stage stage) {
    this.stage = stage;
  }

  @FXML
  private void handleRefreshNetwork() {
    checkNetwork();
    updateStatus("Network status refreshed", "info");
  }

  private void checkNetwork() {
    new Thread(
            () -> {
              NetworkVerifier.NetworkValidationResult result = NetworkVerifier.validateNetwork();

              Platform.runLater(
                  () -> {
                    if (result.isValid()) {
                      currentNetwork = result.getNetworkInfo();
                      String networkType = NetworkVerifier.getNetworkType(currentNetwork);
                      String message =
                          String.format(
                              "✓ Connected to %s: %s",
                              networkType, currentNetwork.getAddress().getHostAddress());
                      networkStatusLabel.setText(message);
                      networkStatusLabel.getStyleClass().removeAll("network-error");
                      networkStatusLabel.getStyleClass().add("network-success");

                      logger.info("Network validated: {}", currentNetwork);
                    } else {
                      networkStatusLabel.setText("⚠ " + result.getMessage());
                      networkStatusLabel.getStyleClass().removeAll("network-success");
                      networkStatusLabel.getStyleClass().add("network-error");

                      logger.warn("Network validation failed: {}", result.getMessage());
                    }
                  });
            })
        .start();
  }

  @FXML
  private void handleSelectFolder() {
    DirectoryChooser directoryChooser = new DirectoryChooser();
    directoryChooser.setTitle("Select Folder to Share");

    if (selectedFolder != null && selectedFolder.exists()) {
      directoryChooser.setInitialDirectory(selectedFolder.getParentFile());
    }

    File folder = directoryChooser.showDialog(stage);

    if (folder != null) {
      selectedFolder = folder;
      folderPathLabel.setText(folder.getAbsolutePath());
      startServerButton.setDisable(false);
      updateStatus("Folder selected: " + folder.getName(), "success");
      logger.info("Selected folder: {}", folder.getAbsolutePath());
    }
  }

  @FXML
  private void handleStartServer() {
    if (selectedFolder == null) {
      showError("Please select a folder first");
      return;
    }

    // Verify network before starting
    if (currentNetwork == null) {
      showWarning(
          "Network Warning",
          "Could not verify network connection. Server will start but may not be accessible.");
    }

    // Check HTTPS preference
    useHttps = httpsCheck != null && httpsCheck.isSelected();
    int preferredPort = useHttps ? PREFERRED_PORT_HTTPS : PREFERRED_PORT_HTTP;

    // Show HTTPS certificate warning
    if (useHttps) {
      Alert alert = new Alert(Alert.AlertType.INFORMATION);
      alert.setTitle("HTTPS Security");
      alert.setHeaderText("Self-Signed Certificate");
      alert.setContentText(
          "The server will use a self-signed SSL certificate.\n\n"
              + "Mobile browsers will show a security warning.\n"
              + "Click 'Advanced' → 'Proceed' to continue.\n\n"
              + "This is normal for local HTTPS servers.");
      alert.showAndWait();
    }

    try {
      // Disable buttons during startup
      startServerButton.setDisable(true);
      selectFolderButton.setDisable(true);
      autoShutdownCheck.setDisable(true);
      if (httpsCheck != null) {
        httpsCheck.setDisable(false);
      }
      serverProgress.setVisible(true);

      updateStatus("Starting " + (useHttps ? "HTTPS" : "HTTP") + " server...", "info");

      boolean autoShutdown = autoShutdownCheck.isSelected();

      // Start server in background thread
      new Thread(
              () -> {
                try {
                  // Create server with optional timeout and HTTPS
                  int timeout = autoShutdown ? AUTO_SHUTDOWN_MINUTES : 0;

                  fileServer =
                      new HttpsFileServer(
                          preferredPort,
                          selectedFolder,
                          timeout,
                          this::handleAutoShutdown,
                          useHttps);
                  fileServer.start();

                  // Get actual port used
                  currentPort = fileServer.getActualPort();

                  String localIP = NetworkUtils.getLocalIPAddress();
                  String protocol = useHttps ? "https" : "http";
                  String serverUrl = protocol + "://" + localIP + ":" + currentPort;

                  Platform.runLater(
                      () -> {
                        try {
                          // Generate QR Code
                          Image qrImage = QRCodeGenerator.generateQRCode(serverUrl, 300, 300);
                          qrCodeImageView.setImage(qrImage);
                          qrCodeImageView.setVisible(true);

                          serverUrlLabel.setText(serverUrl);
                          if (urlContainer != null) {
                            urlContainer.setVisible(true);
                          }

                          // Show port info if different from preferred
                          if (portInfoLabel != null) {
                            String portMsg = "";
                            if (currentPort != preferredPort) {
                              portMsg =
                                  "ℹ Using port "
                                      + currentPort
                                      + " (Port "
                                      + preferredPort
                                      + " was unavailable)";
                            }
                            if (useHttps) {
                              portMsg +=
                                  (portMsg.isEmpty() ? "" : " • ")
                                      + "🔒 HTTPS enabled with self-signed certificate";
                            }
                            if (!portMsg.isEmpty()) {
                              portInfoLabel.setText(portMsg);
                              portInfoLabel.setVisible(true);
                            }
                          }

                          // Show statistics section
                          if (statsSection != null) {
                            statsSection.setVisible(true);
                            startStatsUpdater();
                          }

                          stopServerButton.setDisable(false);
                          serverProgress.setVisible(false);

                          String securityBadge = useHttps ? "🔒 HTTPS" : "HTTP";
                          String statusMsg = securityBadge + " server running on " + serverUrl;

                          if (autoShutdown) {
                            statusMsg += " • Auto-shutdown: " + AUTO_SHUTDOWN_MINUTES + " min idle";
                          }

                          updateStatus(statusMsg, "success");

                          logger.info(
                              "Server started successfully on {} (HTTPS: {})", serverUrl, useHttps);

                        } catch (Exception e) {
                          handleServerError(e);
                        }
                      });

                } catch (IOException e) {
                  Platform.runLater(() -> handleServerError(e));
                }
              })
          .start();

    } catch (Exception e) {
      handleServerError(e);
    }
  }

  @FXML
  private void handleStopServer() {
    stopStatsUpdater();

    if (fileServer != null) {
      fileServer.stop();
      fileServer = null;

      qrCodeImageView.setVisible(false);
      if (urlContainer != null) {
        urlContainer.setVisible(false);
      }
      if (portInfoLabel != null) {
        portInfoLabel.setVisible(false);
      }
      if (statsSection != null) {
        statsSection.setVisible(false);
      }

      stopServerButton.setDisable(true);
      startServerButton.setDisable(false);
      selectFolderButton.setDisable(false);
      autoShutdownCheck.setDisable(false);
      if (httpsCheck != null) {
        httpsCheck.setDisable(false);
      }

      updateStatus("Server stopped", "info");
      logger.info("Server stopped manually");
    }
  }

  private void handleAutoShutdown() {
    Platform.runLater(
        () -> {
          logger.info("Auto-shutdown triggered due to inactivity");

          Alert alert = new Alert(Alert.AlertType.INFORMATION);
          alert.setTitle("Server Auto-Shutdown");
          alert.setHeaderText("Inactivity Timeout");
          alert.setContentText(
              String.format(
                  "Server stopped automatically after %d minutes of inactivity.",
                  AUTO_SHUTDOWN_MINUTES));
          alert.show();

          handleStopServer();
          updateStatus(
              "Server stopped (auto-shutdown after " + AUTO_SHUTDOWN_MINUTES + " min idle)",
              "info");
        });
  }

  @FXML
  private void handleThemeToggle() {
    isDarkTheme = !isDarkTheme;
    switchTheme();
  }

  @FXML
  private void handleCopyUrl() {
    if (serverUrlLabel != null && serverUrlLabel.getText() != null) {
      String url = serverUrlLabel.getText();

      // Copy to clipboard
      javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
      javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
      content.putString(url);
      clipboard.setContent(content);

      updateStatus("✓ URL copied to clipboard!", "success");
      logger.info("URL copied to clipboard: {}", url);
    }
  }

  @FXML
  private void handleGithubLink() {
    try {
      java.awt.Desktop.getDesktop().browse(new java.net.URI(GITHUB_URL));
      logger.info("Opened GitHub link");
    } catch (Exception e) {
      logger.error("Failed to open GitHub link", e);
      updateStatus("Failed to open GitHub link", "error");
    }
  }

  private void startStatsUpdater() {
    if (statsUpdateTimer != null) {
      statsUpdateTimer.cancel();
    }

    statsUpdateTimer = new Timer(true);
    statsUpdateTimer.scheduleAtFixedRate(
        new TimerTask() {
          @Override
          public void run() {
            if (fileServer != null) {
              ConnectionMonitor.ConnectionStats stats = fileServer.getConnectionStats();
              ConnectionMonitor monitor = fileServer.getConnectionMonitor();

              Platform.runLater(
                  () -> {
                    if (requestsLabel != null) {
                      requestsLabel.setText(String.valueOf(stats.getTotalRequests()));
                    }
                    if (downloadsLabel != null) {
                      downloadsLabel.setText(String.valueOf(stats.getTotalDownloads()));
                    }
                    if (activeConnectionsLabel != null) {
                      activeConnectionsLabel.setText(String.valueOf(stats.getActiveConnections()));
                    }
                    if (timeRemainingLabel != null && monitor != null) {
                      long remaining = monitor.getRemainingTime();
                      if (remaining >= 0) {
                        long minutes = remaining / 60;
                        long seconds = remaining % 60;
                        timeRemainingLabel.setText(String.format("%02d:%02d", minutes, seconds));
                      } else {
                        timeRemainingLabel.setText("--");
                      }
                    }
                  });
            }
          }
        },
        0,
        1000); // Update every second
  }

  private void stopStatsUpdater() {
    if (statsUpdateTimer != null) {
      statsUpdateTimer.cancel();
      statsUpdateTimer = null;
    }
  }

  private void switchTheme() {
    Scene scene = rootContainer.getScene();
    scene.getStylesheets().clear();

    if (isDarkTheme) {
      scene.getStylesheets().add(getClass().getResource("/css/dark-theme.css").toExternalForm());
    } else {
      scene.getStylesheets().add(getClass().getResource("/css/light-theme.css").toExternalForm());
    }

    updateThemeIcon();
    logger.info("Switched to {} theme", isDarkTheme ? "dark" : "light");
  }

  private void updateThemeIcon() {
    if (themeIcon != null) {
      if (isDarkTheme) {
        // Dark mode active - show sun icon (click to go light)
        themeIcon.setIconLiteral("mdi2w-white-balance-sunny");
      } else {
        // Light mode active - show moon icon (click to go dark)
        themeIcon.setIconLiteral("mdi2m-moon-waning-crescent");
      }
    }
  }

  private void handleServerError(Exception e) {
    logger.error("Server error", e);

    String errorMsg = "Failed to start server";
    if (e.getMessage() != null) {
      if (e.getMessage().contains("Address already in use")) {
        errorMsg = "Port is already in use. Try closing other applications.";
      } else if (e.getMessage().contains("Permission denied")) {
        errorMsg = "Permission denied. Try using a port above 1024.";
      } else {
        errorMsg = "Failed to start server: " + e.getMessage();
      }
    }

    showError(errorMsg);

    startServerButton.setDisable(false);
    selectFolderButton.setDisable(false);
    autoShutdownCheck.setDisable(false);
    stopServerButton.setDisable(true);
    serverProgress.setVisible(false);
  }

  private void updateStatus(String message, String type) {
    statusLabel.setText(message);
    statusLabel.getStyleClass().removeAll("status-info", "status-success", "status-error");
    statusLabel.getStyleClass().add("status-" + type);
  }

  private void showError(String message) {
    updateStatus(message, "error");

    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setTitle("Error");
    alert.setHeaderText(null);
    alert.setContentText(message);
    alert.showAndWait();
  }

  private void showWarning(String title, String message) {
    Alert alert = new Alert(Alert.AlertType.WARNING);
    alert.setTitle(title);
    alert.setHeaderText(null);
    alert.setContentText(message);
    alert.showAndWait();
  }

  public void cleanup() {
    stopStatsUpdater();
    if (fileServer != null) {
      fileServer.stop();
    }
  }
}
