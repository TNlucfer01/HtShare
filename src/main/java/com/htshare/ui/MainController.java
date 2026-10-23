package com.htshare.ui;

import com.htshare.server.ConnectionMonitor;
import com.htshare.server.AsyncFileServer;
import com.htshare.server.TransferTracker;
import com.htshare.util.NetworkUtils;
import com.htshare.util.NetworkVerifier;
import com.htshare.util.QRCodeGenerator;
import com.htshare.util.SSLCertificateGenerator;
// for some itger thingd
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.prefs.Preferences;
// fxml thing
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.Node;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
// for the fonts ?
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainController {
  private static final Logger logger = LoggerFactory.getLogger(MainController.class);
  private static final int PREFERRED_PORT_HTTPS = 8443;
  private static final int PREFERRED_PORT_HTTP = 8080;
  // why xo  we nee the 5 minuted timout when i have inactive one at the ConnectionMonitor
  private static final int AUTO_SHUTDOWN_MINUTES = 5;
  private static final String GITHUB_URL = "https://github.com/TNlucfer01/htshare";
  // what are these @FXML dir even means
  // we are adding this wirht he fxml  with the id of the fx
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

  // Top-bar state pill ("Idle" / "Starting" / "Live") and the QR panel
  @FXML private HBox serverStatePill;
  @FXML private Label serverStateLabel;
  @FXML private StackPane qrFrame;
  @FXML private VBox qrPlaceholder;

  // Statistics then what fos
  @FXML private HBox statsSection;
  @FXML private Label dataSentLabel;
  @FXML private Label downloadsLabel;
  @FXML private Label activeConnectionsLabel;
  @FXML private Label timeRemainingLabel;

  // Live transfers panel, filled from the server's TransferTracker every second
  @FXML private VBox optionsCard;
  @FXML private VBox transfersCard;
  @FXML private VBox transfersList;
  @FXML private Label transfersEmpty;
  @FXML private Label transfersCountLabel;
  @FXML private Label httpsHintLabel;
  @FXML private FontIcon copyIcon;
  private final Map<Long, TransferRow> transferRows = new HashMap<>();
  private static final int MAX_TRANSFER_ROWS = 5;

  // Remembers folder, options and theme between launches (~/.java/.userPrefs on Linux)
  private final Preferences prefs = Preferences.userNodeForPackage(MainController.class);
  // understand the javafxml
  private Stage stage;
  private File selectedFolder;
  private AsyncFileServer fileServer;
  private boolean isDarkTheme = false;
  private int currentPort;
  private Timer statsUpdateTimer;
  private NetworkVerifier.NetworkInfo currentNetwork;
  private boolean useHttps = true;

  // for initialize but why do we need to add them at the front of the method
  @FXML
  public void initialize() {
    // Hidden nodes should not keep their space in the layout
    for (Node n :
        new Node[] {
          startServerButton,
          stopServerButton,
          serverProgress,
          urlContainer,
          portInfoLabel,
          statsSection
        }) {
      if (n != null) {
        n.managedProperty().bind(n.visibleProperty());
      }
    }

    // The QR frame shows a placeholder until a code exists
    if (qrPlaceholder != null) {
      qrPlaceholder.visibleProperty().bind(qrCodeImageView.visibleProperty().not());
    }
    if (qrFrame != null) {
      qrCodeImageView
          .visibleProperty()
          .addListener(
              (obs, was, showing) -> {
                qrFrame.getStyleClass().remove("qr-live");
                if (showing) {
                  qrFrame.getStyleClass().add("qr-live");
                }
              });
    }

    setRunningUi(false);
    serverProgress.setVisible(false);
    qrCodeImageView.setVisible(false);
    setServerState("Idle", false);

    for (Node n : new Node[] {optionsCard, transfersCard, transfersEmpty}) {
      if (n != null) {
        n.managedProperty().bind(n.visibleProperty());
      }
    }

    // Restore last session's choices (HTTPS on by default)
    httpsCheck.setSelected(prefs.getBoolean("https", true));
    autoShutdownCheck.setSelected(prefs.getBoolean("autoStop", true));
    httpsCheck.selectedProperty().addListener((o, a, on) -> prefs.putBoolean("https", on));
    autoShutdownCheck.selectedProperty().addListener((o, a, on) -> prefs.putBoolean("autoStop", on));

    // A Java runtime without jdk.crypto.ec can't do HTTPS that phones accept
    if (!SSLCertificateGenerator.httpsSupported()) {
      httpsCheck.setSelected(false);
      httpsCheck.setDisable(true);
      httpsCheck.setUserData("unsupported");
      if (httpsHintLabel != null) {
        httpsHintLabel.setText(
            "Not available in this build: its Java runtime is missing jdk.crypto.ec.");
      }
    }

    isDarkTheme = prefs.getBoolean("dark", true);
    updateThemeIcon();

    String last = prefs.get("folder", null);
    if (last != null && new File(last).isDirectory()) {
      setFolder(new File(last));
    } else {
      updateStatus("Choose a folder to start sharing", "info");
    }

    // Drop a folder anywhere on the window to share it
    rootContainer.setOnDragOver(this::handleDragOver);
    rootContainer.setOnDragExited(e -> rootContainer.getStyleClass().remove("drag-over"));
    rootContainer.setOnDragDropped(this::handleDragDropped);

    // Check network on startup
    checkNetwork();
  }

  public void setStage(Stage stage) {
    this.stage = stage;
  }

  private javafx.application.HostServices hostServices;

  public void setHostServices(javafx.application.HostServices hostServices) {
    this.hostServices = hostServices;
  }

  /** Theme to open with (remembered from last time). */
  public boolean isDarkTheme() {
    return isDarkTheme;
  }

  // the netwok section
  // these are called by the onaction attributes
  @FXML
  private void handleRefreshNetwork() {
    // what is the status of the netwok now
    checkNetwork();
    updateStatus("Network status refreshed", "info");
  }

  private void checkNetwork() {
    new Thread(
            () -> {
              // what does this do
              NetworkVerifier.NetworkValidationResult result = NetworkVerifier.validateNetwork();
              // what is this methoc and when does this run
              Platform.runLater(
                  () -> {
                    if (result.isValid()) {
                      // what is the connection now
                      currentNetwork = result.getNetworkInfo();
                      // why do we need the network type
                      String networkType = NetworkVerifier.getNetworkType(currentNetwork);
                      // where does this message even show
                      String message =
                          String.format(
                              "%s · %s", networkType, currentNetwork.getAddress().getHostAddress());
                      networkStatusLabel.setText(message);
                      networkStatusLabel.getStyleClass().removeAll("network-error");
                      networkStatusLabel.getStyleClass().add("network-success");

                      logger.info("Network validated: {}", currentNetwork);
                    } else {
                      networkStatusLabel.setText(result.getMessage());
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
    // can we add the drop and drag file
    DirectoryChooser directoryChooser = new DirectoryChooser();
    directoryChooser.setTitle("Select Folder to Share");

    if (selectedFolder != null && selectedFolder.exists()) {
      directoryChooser.setInitialDirectory(selectedFolder.getParentFile());
    }

    File folder = directoryChooser.showDialog(stage);

    if (folder != null) {
      setFolder(folder);
    }
  }

  private void setFolder(File folder) {
    selectedFolder = folder;
    folderPathLabel.setText(folder.getAbsolutePath());
    folderPathLabel.getStyleClass().remove("folder-empty");
    startServerButton.setDisable(fileServer != null);
    prefs.put("folder", folder.getAbsolutePath());
    updateStatus("Ready to share " + folder.getName(), "info");
    logger.info("Selected folder: {}", folder.getAbsolutePath());
  }

  private void handleDragOver(DragEvent e) {
    if (fileServer == null
        && e.getDragboard().hasFiles()
        && e.getDragboard().getFiles().get(0).isDirectory()) {
      e.acceptTransferModes(TransferMode.LINK, TransferMode.COPY);
      if (!rootContainer.getStyleClass().contains("drag-over")) {
        rootContainer.getStyleClass().add("drag-over");
      }
    }
    e.consume();
  }

  private void handleDragDropped(DragEvent e) {
    rootContainer.getStyleClass().remove("drag-over");
    boolean ok = false;
    if (fileServer == null && e.getDragboard().hasFiles()) {
      File f = e.getDragboard().getFiles().get(0);
      if (f.isDirectory()) {
        setFolder(f);
        ok = true;
      }
    }
    e.setDropCompleted(ok);
    e.consume();
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

    // (The certificate warning is explained inline next to the link instead of a popup.)

    try {
      // Disable buttons during startup
      startServerButton.setDisable(true);
      selectFolderButton.setDisable(true);
      autoShutdownCheck.setDisable(true);
      httpsCheck.setDisable(true);
      serverProgress.setVisible(true);
      setServerState("Starting", false);

      updateStatus("Starting " + (useHttps ? "HTTPS" : "HTTP") + " server…", "info");

      boolean autoShutdown = autoShutdownCheck.isSelected();

      // Start server in background thread
      new Thread(
              () -> {
                try {
                  // Create server with optional timeout and HTTPS
                  int timeout = autoShutdown ? AUTO_SHUTDOWN_MINUTES : 0;

                  fileServer =
                      new AsyncFileServer(
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
                                  "Using port "
                                      + currentPort
                                      + " because "
                                      + preferredPort
                                      + " was busy.";
                            }
                            if (useHttps) {
                              portMsg +=
                                  (portMsg.isEmpty() ? "" : " ")
                                      + "On the phone, tap Advanced → Proceed to accept the"
                                      + " self-signed certificate.";
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

                          setRunningUi(true);
                          serverProgress.setVisible(false);
                          setServerState("Live", true);

                          String securityBadge = useHttps ? "HTTPS" : "HTTP";
                          String statusMsg = "Sharing over " + securityBadge;
                          // then why do we need the inactive one
                          if (autoShutdown) {
                            statusMsg += " · stops after " + AUTO_SHUTDOWN_MINUTES + " min idle";
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

      setRunningUi(false);
      startServerButton.setDisable(false);
      selectFolderButton.setDisable(false);
      autoShutdownCheck.setDisable(false);
      httpsCheck.setDisable(httpsCheck.getUserData() != null);
      setServerState("Idle", false);
      transferRows.clear();
      transfersList.getChildren().clear();

      updateStatus("Stopped sharing", "info");
      logger.info("Server stopped manually");
    }
  }

  private void handleAutoShutdown() {
    Platform.runLater(
        () -> {
          logger.info("Auto-shutdown triggered due to inactivity");

          Alert alert = themed(new Alert(Alert.AlertType.INFORMATION));
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
    prefs.putBoolean("dark", isDarkTheme);
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

      updateStatus("Link copied", "success");
      if (copyIcon != null) {
        copyIcon.setIconLiteral("fth-check");
        javafx.animation.PauseTransition back =
            new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
        back.setOnFinished(e -> copyIcon.setIconLiteral("fth-copy"));
        back.play();
      }
      logger.info("URL copied to clipboard: {}", url);
    }
  }

  @FXML
  private void handleGithubLink() {
    try {
      // JavaFX's own browser launcher: works on Windows and Linux (incl. Wayland),
      // unlike java.awt.Desktop
      hostServices.showDocument(GITHUB_URL);
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
            AsyncFileServer server = fileServer;
            if (server != null) {
              ConnectionMonitor.ConnectionStats stats = server.getConnectionStats();
              ConnectionMonitor monitor = server.getConnectionMonitor();
              TransferTracker tracker = server.getTransfers();
              List<TransferTracker.Snapshot> rows = tracker.snapshot();
              long bytes = tracker.totalBytesSent();
              int files = tracker.filesSent();

              Platform.runLater(
                  () -> {
                    if (fileServer != server) {
                      return; // stopped in the meantime
                    }
                    downloadsLabel.setText(String.valueOf(files));
                    dataSentLabel.setText(formatBytes(bytes));
                    activeConnectionsLabel.setText(String.valueOf(stats.getActiveConnections()));
                    long remaining = monitor.getRemainingTime();
                    if (remaining >= 0) {
                      timeRemainingLabel.setText(
                          String.format("%02d:%02d", remaining / 60, remaining % 60));
                    } else {
                      timeRemainingLabel.setText("Off");
                    }
                    updateTransfers(rows);
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
    // base.css holds layout + components; the theme file only defines the -hs-* colors
    scene.getStylesheets().setAll(themeStylesheets(isDarkTheme));

    updateThemeIcon();
    logger.info("Switched to {} theme", isDarkTheme ? "dark" : "light");
  }

  /** Stylesheets for a theme, in load order. Also used by MainApp for the first scene. */
  public static java.util.List<String> themeStylesheets(boolean dark) {
    String theme = dark ? "/css/dark-theme.css" : "/css/light-theme.css";
    return java.util.List.of(
        MainController.class.getResource("/css/base.css").toExternalForm(),
        MainController.class.getResource(theme).toExternalForm());
  }

  private void updateThemeIcon() {
    if (themeIcon != null) {
      // Show the theme you'd switch to
      themeIcon.setIconLiteral(isDarkTheme ? "fth-sun" : "fth-moon");
    }
  }

  /** Swap the start / stop buttons and show or hide everything that only exists while live. */
  private void setRunningUi(boolean running) {
    startServerButton.setVisible(!running);
    stopServerButton.setVisible(running);
    stopServerButton.setDisable(!running);
    qrCodeImageView.setVisible(running);
    if (urlContainer != null) {
      urlContainer.setVisible(running);
    }
    if (statsSection != null) {
      statsSection.setVisible(running);
    }
    if (transfersCard != null) {
      transfersCard.setVisible(running);
    }
    if (optionsCard != null) {
      optionsCard.setVisible(!running);
    }
    if (portInfoLabel != null && !running) {
      portInfoLabel.setVisible(false);
    }
  }

  private void setServerState(String text, boolean live) {
    if (serverStateLabel != null) {
      serverStateLabel.setText(text);
    }
    if (serverStatePill != null) {
      serverStatePill.getStyleClass().remove("pill-live");
      if (live) {
        serverStatePill.getStyleClass().add("pill-live");
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

    setRunningUi(false);
    startServerButton.setDisable(false);
    selectFolderButton.setDisable(false);
    autoShutdownCheck.setDisable(false);
    httpsCheck.setDisable(httpsCheck.getUserData() != null);
    serverProgress.setVisible(false);
    setServerState("Idle", false);
  }

  private void updateStatus(String message, String type) {
    statusLabel.setText(message);
    statusLabel.getStyleClass().removeAll("status-info", "status-success", "status-error");
    statusLabel.getStyleClass().add("status-" + type);
  }

  /** Make a dialog use the app's own stylesheets, so it matches the light/dark theme. */
  private Alert themed(Alert alert) {
    if (rootContainer.getScene() != null) {
      alert.getDialogPane().getStylesheets().setAll(rootContainer.getScene().getStylesheets());
      alert.initOwner(rootContainer.getScene().getWindow());
    }
    alert.getDialogPane().getStyleClass().add("hs-dialog");
    alert.setGraphic(null);
    return alert;
  }

  private void showError(String message) {
    updateStatus(message, "error");

    Alert alert = themed(new Alert(Alert.AlertType.ERROR));
    alert.setTitle("Error");
    alert.setHeaderText(null);
    alert.setContentText(message);
    alert.showAndWait();
  }

  private void showWarning(String title, String message) {
    Alert alert = themed(new Alert(Alert.AlertType.WARNING));
    alert.setTitle(title);
    alert.setHeaderText(null);
    alert.setContentText(message);
    alert.showAndWait();
  }

  // ------------------------------------------------------------------ transfers panel

  /** Sync the rows with the latest snapshot: active first, then most recent. */
  private void updateTransfers(List<TransferTracker.Snapshot> all) {
    long active = all.stream().filter(t -> t.state() == TransferTracker.State.ACTIVE).count();
    transfersCountLabel.setText(active == 0 ? "" : active + " active");

    List<TransferTracker.Snapshot> shown =
        all.stream()
            .sorted(
                (a, b) ->
                    Boolean.compare(
                        b.state() == TransferTracker.State.ACTIVE,
                        a.state() == TransferTracker.State.ACTIVE))
            .limit(MAX_TRANSFER_ROWS)
            .toList();
    transfersEmpty.setVisible(shown.isEmpty());

    List<Node> nodes = new java.util.ArrayList<>();
    Map<Long, TransferRow> keep = new HashMap<>();
    for (TransferTracker.Snapshot t : shown) {
      TransferRow row = transferRows.computeIfAbsent(t.id(), id -> new TransferRow());
      row.update(t);
      keep.put(t.id(), row);
      nodes.add(row.root);
    }
    transferRows.clear();
    transferRows.putAll(keep);
    if (!transfersList.getChildren().equals(nodes)) {
      transfersList.getChildren().setAll(nodes);
    }
  }

  /** One file being sent: name, progress bar, and "device · speed · amount". */
  private static final class TransferRow {
    final VBox root = new VBox(6);
    final Label name = new Label();
    final Label percent = new Label();
    final ProgressBar bar = new ProgressBar(0);
    final Label meta = new Label();

    TransferRow() {
      name.getStyleClass().add("transfer-name");
      name.setMaxWidth(Double.MAX_VALUE);
      name.setMinWidth(0);
      HBox.setHgrow(name, Priority.ALWAYS);
      percent.getStyleClass().add("transfer-meta");
      percent.setMinWidth(Region.USE_PREF_SIZE);
      bar.getStyleClass().add("transfer-bar");
      bar.setMaxWidth(Double.MAX_VALUE);
      meta.getStyleClass().add("transfer-meta");
      HBox top = new HBox(8, name, percent);
      top.setAlignment(javafx.geometry.Pos.BASELINE_LEFT);
      root.getChildren().addAll(top, bar, meta);
    }

    void update(TransferTracker.Snapshot t) {
      name.setText(t.fileName());
      bar.setProgress(t.progress());
      bar.getStyleClass().removeAll("bar-done", "bar-stopped");
      String detail;
      switch (t.state()) {
        case ACTIVE:
          percent.setText(Math.round(t.progress() * 100) + "%");
          detail =
              formatBytes((long) t.bytesPerSecond())
                  + "/s · "
                  + formatBytes((long) (t.progress() * t.size()))
                  + " of "
                  + formatBytes(t.size());
          break;
        case DONE:
          percent.setText("Done");
          bar.getStyleClass().add("bar-done");
          detail = formatBytes(t.size());
          break;
        default:
          percent.setText("Stopped");
          bar.getStyleClass().add("bar-stopped");
          detail = "at " + Math.round(t.progress() * 100) + "% of " + formatBytes(t.size());
      }
      meta.setText(t.device() + " · " + detail);
    }
  }

  static String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    double v = bytes;
    String[] units = {"KB", "MB", "GB", "TB"};
    int i = -1;
    do {
      v /= 1024;
      i++;
    } while (v >= 1024 && i < units.length - 1);
    return (v >= 100 ? String.format("%.0f", v) : String.format("%.1f", v)) + " " + units[i];
  }

  public void cleanup() {
    stopStatsUpdater();
    if (fileServer != null) {
      fileServer.stop();
    }
  }
}
