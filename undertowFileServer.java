import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.Headers;
import java.io.File;
import java.nio.file.Files;
import javax.net.ssl.SSLContext;

public class UndertowFileServer {

  private final File rootDirectory;
  private final ConnectionMonitor monitor;

  public UndertowFileServer(
      int port, File rootDirectory, SSLContext sslContext, ConnectionMonitor monitor) {

    this.rootDirectory = rootDirectory;
    this.monitor = monitor;

    PathHandler routes =
        new PathHandler()

            // ---- API: /api/stats ----
            .addExactPath(
                "/api/stats",
                exchange -> {
                  monitor.recordRequest("/api/stats", "GET");

                  var stats = monitor.getStats();
                  String json =
                      String.format(
                          "{\"totalRequests\":%d,\"totalDownloads\":%d,\"activeConnections\":%d,"
                              + "\"secondsSinceLastActivity\":%d,\"hasActivity\":%b}",
                          stats.getTotalRequests(),
                          stats.getTotalDownloads(),
                          stats.getActiveConnections(),
                          stats.getSecondsSinceLastActivity(),
                          stats.hasHadActivity());

                  exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                  exchange.getResponseHeaders().put(Headers.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

                  exchange.getResponseSender().send(json);
                })

            // ---- API: /api/list ----
            .addExactPath(
                "/api/list",
                exchange -> {
                  monitor.recordRequest("/api/list", "GET");

                  String path =
                      exchange.getQueryParameters().getOrDefault("path", null) != null
                          ? exchange.getQueryParameters().get("path").getFirst()
                          : "";

                  File dir = new File(rootDirectory, path);

                  if (!dir.getCanonicalPath().startsWith(rootDirectory.getCanonicalPath())
                      || !dir.isDirectory()) {
                    exchange.setStatusCode(403);
                    exchange.getResponseSender().send("{\"error\":\"Access denied\"}");
                    return;
                  }
                  String json = new Helper().generateDirectoryJson(dir);

                  exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                  exchange.getResponseHeaders().put(Headers.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

                  exchange.getResponseSender().send(json);
                })

            // ---- Files: everything else ----
            .addPrefixPath("/", exchange -> serveFile(exchange));

    Undertow.builder()
        // what is this line do why is it listens to the router addess
        .addHttpsListener(port, "0.0.0.0", sslContext)
        .setHandler(routes)
        .build()
        .start();
  }

  private void serveFile(HttpServerExchange exchange) throws Exception {
    String path = exchange.getRelativePath();
    if (path.startsWith("/")) path = path.substring(1);

    File file = new File(rootDirectory, path);

    if (!file.getCanonicalPath().startsWith(rootDirectory.getCanonicalPath()) || !file.exists()) {
      exchange.setStatusCode(404);
      exchange.getResponseSender().send("Not found");
      return;
    }

    if (file.isDirectory()) {
      String html = new Helper().generateDirectoryHtml(file, path);
      exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html");
      exchange.getResponseSender().send(html);
      return;
    }

    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, Files.probeContentType(file.toPath()));
    exchange
        .getResponseHeaders()
        .put(Headers.CONTENT_DISPOSITION, "inline; filename=\"" + file.getName() + "\"");

    exchange.startBlocking();
    Files.copy(file.toPath(), exchange.getOutputStream());

    monitor.recordDownload(file.getName(), file.length());
  }
}
