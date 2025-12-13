package com.htshare.server;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class Helper {
  
  private static final Logger logger = LoggerFactory.getLogger(HttpFileServer.class);
  private static final int MIN_PORT = 8080;
  private static final int MAX_PORT = 8180;
  public  String generateDirectoryJson(File directory) {
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
        .replace("'", "&#39;");
  }

  public String escapeJson(String text) {
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  /** Get embedded CSS for styling */
  public String getEmbeddedCss() {
    return """
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        min-height: 100vh; padding: 20px;
    }
    .container {
        max-width: 900px; margin: 0 auto; background: white;
        border-radius: 16px; box-shadow: 0 20px 60px rgba(0,0,0,0.15);
        overflow: hidden;
    }
    .header {
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        color: white; padding: 40px 30px; text-align: center;
    }
    .header h1 { font-size: 32px; margin-bottom: 8px; font-weight: 600; }
    .subtitle { opacity: 0.95; font-size: 15px; }
    .breadcrumb {
        padding: 16px 25px; background: #f8f9fa;
        border-bottom: 1px solid #e9ecef; font-size: 14px;
    }
    .breadcrumb a { color: #667eea; text-decoration: none; font-weight: 500; }
    .breadcrumb a:hover { text-decoration: underline; }
    .file-list { list-style: none; }
    .file-item {
        border-bottom: 1px solid #e9ecef;
        transition: all 0.2s ease;
    }
    .file-item:hover { background: #f8f9fa; transform: translateX(4px); }
    .file-item a {
        display: flex; align-items: center;
        padding: 18px 25px; text-decoration: none;
        color: #2d3748;
    }
    .icon { font-size: 28px; margin-right: 16px; min-width: 32px; }
    .name { flex: 1; font-weight: 500; font-size: 15px; }
    .size {
        color: #718096; font-size: 13px;
        margin-left: 12px; min-width: 70px;
        text-align: right;
    }
    .empty {
        text-align: center; padding: 80px 20px;
        color: #a0aec0; font-size: 18px;
    }
    @media (max-width: 600px) {
        .container { border-radius: 0; }
        .header h1 { font-size: 24px; }
        .size { display: none; }
    }
    """;
  }

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

  public String generateDirectoryHtml(File directory, String relativePath) {
    StringBuilder html = new StringBuilder();

    html.append("<!DOCTYPE html><html lang=\"en\"><head>");
    html.append("<meta charset=\"UTF-8\">");
    html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
    html.append("<title>").append(escapeHtml(directory.getName())).append(" - HtShare</title>");
    html.append("<style>");
    html.append(getEmbeddedCss());
    html.append("</style></head><body>");

    // Header
    html.append("<div class=\"container\">");
    html.append("<div class=\"header\">");
    html.append("<h1>📁 ").append(escapeHtml(directory.getName())).append("</h1>");
    html.append("<p class=\"subtitle\">Shared Folder</p>");
    html.append("</div>");

    // Breadcrumb navigation
    if (!relativePath.isEmpty()) {
      html.append("<div class=\"breadcrumb\">");
      html.append("<a href=\"/\">🏠 Home</a>");

      String[] parts = relativePath.split("/");
      String currentPath = "";
      for (String part : parts) {
        if (!part.isEmpty()) {
          currentPath += "/" + part;
          html.append(" / <a href=\"")
              .append(currentPath)
              .append("\">")
              .append(escapeHtml(part))
              .append("</a>");
        }
      }
      html.append("</div>");
    }

    // File listing
    File[] files = directory.listFiles();

    if (files == null || files.length == 0) {
      html.append("<div class=\"empty\">📭 This folder is empty</div>");
    } else {
      // Sort files: directories first, then alphabetically
      List<File> dirs = new ArrayList<>();
      List<File> regularFiles = new ArrayList<>();

      for (File f : files) {
        if (f.isDirectory()) {
          dirs.add(f);
        } else {
          regularFiles.add(f);
        }
      }

      dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
      regularFiles.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

      html.append("<ul class=\"file-list\">");

      // Parent directory link
      if (!relativePath.isEmpty()) {
        String parentPath =
            relativePath.contains("/")
                ? relativePath.substring(0, relativePath.lastIndexOf("/"))
                : "";
        html.append("<li class=\"file-item\">");
        html.append("<a href=\"/").append(parentPath).append("\">");
        html.append("<span class=\"icon\">⬆️</span>");
        html.append("<span class=\"name\">..</span>");
        html.append("</a></li>");
      }

      // Directories
      for (File dir : dirs) {
        String path = relativePath.isEmpty() ? dir.getName() : relativePath + "/" + dir.getName();
        html.append("<li class=\"file-item\">");
        html.append("<a href=\"/").append(path).append("\">");
        html.append("<span class=\"icon\">📁</span>");
        html.append("<span class=\"name\">").append(escapeHtml(dir.getName())).append("</span>");
        html.append("</a></li>");
      }

      // Files
      for (File file : regularFiles) {
        String path = relativePath.isEmpty() ? file.getName() : relativePath + "/" + file.getName();
        html.append("<li class=\"file-item\">");
        html.append("<a href=\"/").append(path).append("\" download>");
        html.append("<span class=\"icon\">").append(getFileIcon(file.getName())).append("</span>");
        html.append("<span class=\"name\">").append(escapeHtml(file.getName())).append("</span>");
        html.append("<span class=\"size\">")
            .append(formatFileSize(file.length()))
            .append("</span>");
        html.append("</a></li>");
      }

      html.append("</ul>");
    }

    html.append("</div></body></html>");
    return html.toString();
  }
}
