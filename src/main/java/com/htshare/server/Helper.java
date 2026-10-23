package com.htshare.server;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Helper {

  private static final Logger logger = LoggerFactory.getLogger(HttpFileServer.class);
  private static final int MIN_PORT = 8080;
  private static final int MAX_PORT = 8180;

  public String generateDirectoryJson(File directory) {
    StringBuilder json = new StringBuilder();
    json.append("{\"path\":\"").append(escapeJson(directory.getAbsolutePath())).append("\",");
    json.append("\"name\":\"").append(escapeJson(directory.getName())).append("\",");
    json.append("\"files\":[");

    File[] files = directory.listFiles();
    if (files != null) {
      for (int i = 0; i < files.length; i++) {
        File file = files[i];
        json.append("{");
        json.append("\"name\":\"").append(escapeJson(file.getName())).append("\",");
        json.append("\"type\":\"").append(file.isDirectory() ? "directory" : "file").append("\",");
        json.append("\"size\":").append(file.length()).append(",");
        json.append("\"modified\":").append(file.lastModified());
        json.append("}");

        if (i < files.length - 1) {
          json.append(",");
        }
      }
    }

    json.append("]}");
    return json.toString();
  }

  public String getMimeType(String filename) {
    String lower = filename.toLowerCase();
    if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
    if (lower.endsWith(".txt")) return "text/plain";
    if (lower.endsWith(".css")) return "text/css";
    if (lower.endsWith(".js")) return "application/javascript";
    if (lower.endsWith(".json")) return "application/json";
    if (lower.endsWith(".xml")) return "application/xml";
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
    if (lower.endsWith(".png")) return "image/png";
    if (lower.endsWith(".gif")) return "image/gif";
    if (lower.endsWith(".svg")) return "image/svg+xml";
    if (lower.endsWith(".pdf")) return "application/pdf";
    if (lower.endsWith(".zip")) return "application/zip";
    if (lower.endsWith(".mp4")) return "video/mp4";
    if (lower.endsWith(".mp3")) return "audio/mpeg";
    return "application/octet-stream";
  }

  public String getFileIcon(String filename) {
    String lower = filename.toLowerCase();
    if (lower.matches(".*\\.(jpg|jpeg|png|gif|svg|bmp|webp)$")) return "🖼️";
    if (lower.matches(".*\\.(mp4|avi|mov|mkv|wmv|flv)$")) return "🎬";
    if (lower.matches(".*\\.(mp3|wav|flac|aac|ogg|wma)$")) return "🎵";
    if (lower.endsWith(".pdf")) return "📄";
    if (lower.matches(".*\\.(zip|rar|7z|tar|gz)$")) return "📦";
    if (lower.matches(".*\\.(doc|docx)$")) return "📝";
    if (lower.matches(".*\\.(xls|xlsx|csv)$")) return "📊";
    if (lower.matches(".*\\.(ppt|pptx)$")) return "📽️";
    if (lower.endsWith(".txt")) return "📃";
    if (lower.matches(".*\\.(html|css|js|java|py|cpp|c|h)$")) return "💻";
    return "📄";
  }

  public String formatFileSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
    return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
  }

  public String escapeHtml(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
        // so a file named "{{fileList}}" can't be picked up as a template placeholder
        .replace("{", "&#123;");
  }

  public String escapeJson(String text) {
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  /** Load a page template from the classpath, so it also works from the jar / AppImage. */
  public String loadTemplate(String name) {
    try (InputStream in = Helper.class.getResourceAsStream("/template/" + name)) {
      if (in == null) {
        throw new IOException("not on classpath");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to load template: " + name, e);
    }
  }

  /** Get embedded CSS for styling */
  // repeated
  /** Find an available port starting from preferred port */
  public int findAvailablePort(int preferredPort) {
    for (int port = preferredPort; port <= MAX_PORT; port++) {
      try {
        // Try to create a server socket on this port
        HttpServer testServer = HttpServer.create(new InetSocketAddress(port), 0);
        testServer.stop(0);
        logger.info("Found available port: {}", port);
        return port;
      } catch (IOException e) {
        logger.debug("Port {} is not available, trying next...", port);
      }
    }

    logger.warn("No available port found in range {}-{}, using preferred port", MIN_PORT, MAX_PORT);
    return preferredPort;
  }

  /** Percent-encode a relative path for use in an href, segment by segment. */
  public String encodePath(String relativePath) {
    StringBuilder out = new StringBuilder();
    for (String part : relativePath.split("/")) {
      if (part.isEmpty()) continue;
      out.append('/').append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
    }
    return out.length() == 0 ? "/" : out.toString();
  }

  /** Short label for the file-type badge on the phone page, e.g. "PDF", "MP4". */
  public String fileBadge(String filename) {
    int dot = filename.lastIndexOf('.');
    if (dot <= 0 || dot == filename.length() - 1) return "FILE";
    String ext = filename.substring(dot + 1).toUpperCase();
    return ext.length() > 4 ? ext.substring(0, 4) : ext;
  }

  /** Broad file kind, used to tint the badge: image, video, audio, archive, doc, code, other. */
  public String fileKind(String filename) {
    String lower = filename.toLowerCase();
    if (lower.matches(".*\\.(jpg|jpeg|png|gif|svg|bmp|webp|heic)$")) return "image";
    if (lower.matches(".*\\.(mp4|avi|mov|mkv|wmv|flv|webm)$")) return "video";
    if (lower.matches(".*\\.(mp3|wav|flac|aac|ogg|wma|m4a)$")) return "audio";
    if (lower.matches(".*\\.(zip|rar|7z|tar|gz|xz|bz2|iso)$")) return "archive";
    if (lower.matches(".*\\.(pdf|doc|docx|xls|xlsx|csv|ppt|pptx|txt|md|odt|ods)$")) return "doc";
    if (lower.matches(".*\\.(html|css|js|ts|java|py|cpp|c|h|rs|go|sh|json|xml)$")) return "code";
    return "other";
  }

  private static final String ICON_FOLDER =
      "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z\"/></svg>";
  private static final String ICON_CHEVRON =
      "<svg class=\"end\" viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M9 6l6 6-6 6\"/></svg>";
  private static final String ICON_DOWNLOAD =
      "<svg class=\"end\" viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M12 4v11M7 10l5 5 5-5M5 20h14\"/></svg>";

  /** Breadcrumb links back to the shared root; empty at the root itself. */
  public String Breadcrumb(String rootName, String relativePath) {
    if (relativePath.isEmpty()) {
      return "";
    }
    StringBuilder html = new StringBuilder("<nav class=\"crumbs\" aria-label=\"Path\">");
    html.append("<a href=\"/\">").append(escapeHtml(rootName)).append("</a>");

    String[] parts = relativePath.split("/");
    String currentPath = "";
    for (int i = 0; i < parts.length - 1; i++) {
      if (parts[i].isEmpty()) continue;
      currentPath += (currentPath.isEmpty() ? "" : "/") + parts[i];
      html.append("<span class=\"sep\">/</span><a href=\"")
          .append(escapeHtml(encodePath(currentPath)))
          .append("\">")
          .append(escapeHtml(parts[i]))
          .append("</a>");
    }
    html.append("</nav>");
    return html.toString();
  }

  /** Folders first, then files, both alphabetical. */
  private File[] sortedEntries(File directory) {
    File[] files = directory.listFiles(f -> !f.isHidden());
    if (files == null) return new File[0];
    java.util.Arrays.sort(
        files,
        (a, b) -> {
          if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
          return a.getName().compareToIgnoreCase(b.getName());
        });
    return files;
  }

  public String ListFiles(File directory, String relativePath) {
    File[] files = sortedEntries(directory);
    if (files.length == 0) {
      return "<div class=\"empty\">"
          + ICON_FOLDER
          + "<p>This folder is empty</p></div>";
    }

    StringBuilder html = new StringBuilder("<ul class=\"list\">");
    for (File f : files) {
      String path = relativePath.isEmpty() ? f.getName() : relativePath + "/" + f.getName();
      String href = escapeHtml(encodePath(path));
      String name = escapeHtml(f.getName());

      if (f.isDirectory()) {
        String[] children = f.list();
        int count = children == null ? 0 : children.length;
        html.append("<li><a class=\"row\" href=\"").append(href).append("\">")
            .append("<span class=\"ico ico-folder\">").append(ICON_FOLDER).append("</span>")
            .append("<span class=\"text\"><span class=\"name\">").append(name).append("</span>")
            .append("<span class=\"meta\">").append(count).append(count == 1 ? " item" : " items")
            .append("</span></span>")
            .append(ICON_CHEVRON)
            .append("</a></li>");
      } else {
        html.append("<li><a class=\"row\" href=\"").append(href).append("\" download>")
            .append("<span class=\"ico ico-").append(fileKind(f.getName())).append("\">")
            .append(escapeHtml(fileBadge(f.getName()))).append("</span>")
            .append("<span class=\"text\"><span class=\"name\">").append(name).append("</span>")
            .append("<span class=\"meta\">").append(formatFileSize(f.length()))
            .append("</span></span>")
            .append(ICON_DOWNLOAD)
            .append("</a></li>");
      }
    }
    html.append("</ul>");
    return html.toString();
  }

  /** Summary under the folder title, e.g. "3 folders · 5 files". */
  public String itemSummary(File directory) {
    int dirs = 0, files = 0;
    for (File f : sortedEntries(directory)) {
      if (f.isDirectory()) dirs++;
      else files++;
    }
    if (dirs == 0 && files == 0) return "Empty";
    StringBuilder s = new StringBuilder();
    if (dirs > 0) s.append(dirs).append(dirs == 1 ? " folder" : " folders");
    if (files > 0) {
      if (s.length() > 0) s.append(" · ");
      s.append(files).append(files == 1 ? " file" : " files");
    }
    return s.toString();
  }

  public String generateDirectoryHtml(File directory, String relativePath) {
    return generateDirectoryHtml(directory, relativePath, directory.getName());
  }

  /** Render the phone page. rootName labels the first breadcrumb link. */
  public String generateDirectoryHtml(File directory, String relativePath, String rootName) {
    String html = loadTemplate("home.html");
    html = html.replace("{{title}}", escapeHtml(directory.getName()));
    html = html.replace("{{folderName}}", escapeHtml(directory.getName()));
    html = html.replace("{{itemSummary}}", escapeHtml(itemSummary(directory)));
    html = html.replace("{{breadcrumb}}", Breadcrumb(rootName, relativePath));
    html = html.replace("{{fileList}}", ListFiles(directory, relativePath));
    return html;
  }
}
