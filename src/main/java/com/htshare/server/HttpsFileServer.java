package com.htshare.server;

import com.htshare.util.SSLCertificateGenerator;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HTTPS File Server using NanoHTTPD with SSL/TLS support */
public class HttpsFileServer extends NanoHTTPD {
  // gets the detaials about the HttpsFileServer
  private static final Logger logger = LoggerFactory.getLogger(HttpsFileServer.class);
  // max port we can use
  Helper helper = new Helper();
  private final File
      rootDirectory; // i dont want to change the  file or rootDirectory one declored or specified
  private final int actualPort; // final selected port
  private final ConnectionMonitor connectionMonitor; // don't know why we need this
  private final boolean useHttps; // what are we using

  private SSLContext sslContext; // somethign to do with the https

  /** Create HTTP file server (non-SSL) */
  public HttpsFileServer(int preferredPort, File rootDirectory) throws IOException {
    this(preferredPort, rootDirectory, 0, null, false);
  }

  /** Create file server with optional HTTPS and timeout */
  public HttpsFileServer(
      int preferredPort,
      File rootDirectory,
      int timeoutMinutes,
      Runnable onTimeout,
      boolean useHttps)
      throws IOException {
    super(preferredPort); // geting the port from the parent

    if (!rootDirectory.exists() || !rootDirectory.isDirectory()) {
      throw new IOException("Invalid root directory: " + rootDirectory);
    }
    // declaring
    this.rootDirectory = rootDirectory;
    this.actualPort = preferredPort; // Store the port we're using
    this.connectionMonitor = new ConnectionMonitor(timeoutMinutes, onTimeout);
    this.useHttps = useHttps;

    if (useHttps) {
      try {
        setupSSL();
        logger.info("HTTPS File Server initialized on port {} with SSL", actualPort);
      } catch (Exception e) {
        logger.error("Failed to setup SSL, falling back to HTTP", e);
        throw new IOException("SSL setup failed: " + e.getMessage());
      }

    } else {
      logger.info("HTTP File Server initialized on port {}", actualPort);
    }
    // doesn't this line show the absolute path in the log and the advesary can se it right ?
    logger.info("Serving from: {}", rootDirectory.getAbsolutePath());
  }

  // is it foe the response of the get or any other ones
  @Override
  public Response serve(IHTTPSession session) {
    String uri = session.getUri(); // ?
    String method = session.getMethod().toString(); // ?

    // Record activity
    connectionMonitor.recordRequest(uri, method);
    // don;t know why we ahave this logger
    logger.info("{} {} - {}", method, uri, session.getRemoteIpAddress());

    try {
      // Decode URI
      uri = URLDecoder.decode(uri, StandardCharsets.UTF_8);

      // Remove leading slash dont understand the woking of this condtion though
      if (uri.startsWith("/")) {
        uri = uri.substring(1);
      }
      // Handle API endpoints how ?
      if (uri.startsWith("api/")) {
        return handleApiRequest(uri, session);
      }

      // Handle root or directory listing
      if (uri.isEmpty()) {
        return serveDirectoryListing(rootDirectory, "");
      }

      // Resolve file path why ?
      File file = new File(rootDirectory, uri);

      // Security: prevent directory traversal so the user can't acces the parent folder or is it
      // differnt
      if (!file.getCanonicalPath().startsWith(rootDirectory.getCanonicalPath())) {
        logger.warn("Directory traversal attempt: {}", uri);
        return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Access denied");
      }
      // error
      if (!file.exists()) {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found");
      }
      // for showing the files
      if (file.isDirectory()) {
        return serveDirectoryListing(file, uri);
      }

      // Serve file give the file
      return serveFile(file);

    } catch (Exception e) {
      // why are we using the mime here
      logger.error("Error serving request", e);
      return newFixedLengthResponse(
          Response.Status.INTERNAL_ERROR,
          MIME_PLAINTEXT,
          "Internal server error: " + e.getMessage());
    }
  }

  // Utility methods
  public int getActualPort() {
    return actualPort;
  }

  public boolean isSecure() {
    return useHttps;
  }

  public ConnectionMonitor getConnectionMonitor() {
    return connectionMonitor;
  }

  public ConnectionMonitor.ConnectionStats getConnectionStats() {
    return connectionMonitor.getStats();
  }

  @Override
  public void stop() {
    super.stop();
    if (connectionMonitor != null) {
      connectionMonitor.shutdown();
    }
    logger.info("Server stopped");
  }

  /** Setup SSL/TLS with self-signed certificate */
  // why do we need this >? also problem in this code
  private void setupSSL() throws Exception {
    // Create self-signed certificate in-memory
    KeyStore keyStore = SSLCertificateGenerator.generateKeyStore();

    // Setup SSL context
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, SSLCertificateGenerator.KEYSTORE_PASSWORD.toCharArray());

    sslContext = SSLContext.getInstance("TLS");
    sslContext.init(kmf.getKeyManagers(), null, null);

    // Make NanoHTTPD use SSL
    makeSecure(sslContext.getServerSocketFactory(), null);

    logger.info("SSL/TLS configured successfully with self-signed certificate");
  }

  /** Handle API requests */
  private Response handleApiRequest(String uri, IHTTPSession session) {
    // these things happens at the mobile
    if (uri.equals("api/stats")) {
      return handleStatsApi();
    } else if (uri.startsWith("api/list")) {
      return handleListApi(session);
    }
    return newFixedLengthResponse(
        Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Endpoint not found\"}");
  }

  /** Stats API endpoint */
  private Response handleStatsApi() {
    ConnectionMonitor.ConnectionStats stats = connectionMonitor.getStats();

    String json =
        String.format(
            "{\"totalRequests\":%d,\"totalDownloads\":%d,\"activeConnections\":%d,"
                + "\"secondsSinceLastActivity\":%d,\"hasActivity\":%b}",
            stats.getTotalRequests(),
            stats.getTotalDownloads(),
            stats.getActiveConnections(),
            stats.getSecondsSinceLastActivity(),
            stats.hasHadActivity());

    Response response = newFixedLengthResponse(Response.Status.OK, "application/json", json);
    // why are we adding this in the header
    response.addHeader("Access-Control-Allow-Origin", "*");
    return response;
  }

  /** List API endpoint */
  private Response handleListApi(IHTTPSession session) {

    try {
      // path of the root dir
      String path = session.getParms().getOrDefault("path", "");

      File directory = new File(rootDirectory, path);
      // what is getCanonicalPath ?
      if (!directory.getCanonicalPath().startsWith(rootDirectory.getCanonicalPath())) {
        return newFixedLengthResponse(
            Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Access denied\"}");
      }

      if (!directory.exists() || !directory.isDirectory()) {
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Directory not found\"}");
      }
      // dir json but from where
      String json = helper.generateDirectoryJson(directory);
      Response response = newFixedLengthResponse(Response.Status.OK, "application/json", json);
      // why are we addinf this header
      response.addHeader("Access-Control-Allow-Origin", "*");
      return response;

    } catch (Exception e) {
      logger.error("Error in list API", e);
      return newFixedLengthResponse(
          Response.Status.INTERNAL_ERROR,
          "application/json",
          "{\"error\":\"" + e.getMessage() + "\"}");
    }
  }

  /** Serve a file */
  private Response serveFile(File file) throws IOException {
    // why mime in https
    String mimeType = helper.getMimeType(file.getName());
    FileInputStream fis = new FileInputStream(file);

    Response response = newChunkedResponse(Response.Status.OK, mimeType, fis);
    // wat are these header are doing
    response.addHeader("Content-Disposition", "inline; filename=\"" + file.getName() + "\"");
    response.addHeader("Accept-Ranges", "bytes");
    response.addHeader("Content-Length", String.valueOf(file.length()));

    // Record download ?
    connectionMonitor.recordDownload(file.getName(), file.length());
    // where are we logging them
    logger.info("Serving file: {} ({} bytes)", file.getName(), file.length());
    return response;
  }

  /** Serve directory listing */
  private Response serveDirectoryListing(File directory, String relativePath) {
    String html = helper.generateDirectoryHtml(directory, relativePath);

    Response response = newFixedLengthResponse(Response.Status.OK, "text/html", html);
    // why these headers
    response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
    return response;
  }
}
