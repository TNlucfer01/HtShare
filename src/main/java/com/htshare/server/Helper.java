package com.htshare.server;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Helper {

  private static final Logger logger = LoggerFactory.getLogger(Helper.class);
  /** Page templates, read once from the classpath. */
  private static final java.util.Map<String, String> TEMPLATES =
      new java.util.concurrent.ConcurrentHashMap<>();

  public String generateDirectoryJson(File directory) {
    StringBuilder json = new StringBuilder();
    json.append("{\"path\":\"").append(escapeJson(directory.getAbsolutePath())).append("\",");
    json.append("\"name\":\"").append(escapeJson(directory.getName())).append("\",");
    json.append("\"files\":[");

    File[] files = directory.listFiles(f -> !f.isHidden() && !f.getName().startsWith("."));
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

  private static final java.util.Map<String, String> MIME =
      java.util.Map.ofEntries(
          java.util.Map.entry("html", "text/html; charset=utf-8"),
          java.util.Map.entry("htm", "text/html; charset=utf-8"),
          java.util.Map.entry("txt", "text/plain; charset=utf-8"),
          java.util.Map.entry("md", "text/plain; charset=utf-8"),
          java.util.Map.entry("log", "text/plain; charset=utf-8"),
          java.util.Map.entry("csv", "text/csv; charset=utf-8"),
          java.util.Map.entry("css", "text/css; charset=utf-8"),
          java.util.Map.entry("js", "text/javascript; charset=utf-8"),
          java.util.Map.entry("json", "application/json"),
          java.util.Map.entry("xml", "application/xml"),
          java.util.Map.entry("pdf", "application/pdf"),
          java.util.Map.entry("jpg", "image/jpeg"),
          java.util.Map.entry("jpeg", "image/jpeg"),
          java.util.Map.entry("png", "image/png"),
          java.util.Map.entry("gif", "image/gif"),
          java.util.Map.entry("webp", "image/webp"),
          java.util.Map.entry("avif", "image/avif"),
          java.util.Map.entry("heic", "image/heic"),
          java.util.Map.entry("svg", "image/svg+xml"),
          java.util.Map.entry("bmp", "image/bmp"),
          java.util.Map.entry("mp4", "video/mp4"),
          java.util.Map.entry("m4v", "video/mp4"),
          java.util.Map.entry("webm", "video/webm"),
          java.util.Map.entry("mov", "video/quicktime"),
          java.util.Map.entry("mkv", "video/x-matroska"),
          java.util.Map.entry("mp3", "audio/mpeg"),
          java.util.Map.entry("m4a", "audio/mp4"),
          java.util.Map.entry("aac", "audio/aac"),
          java.util.Map.entry("wav", "audio/wav"),
          java.util.Map.entry("ogg", "audio/ogg"),
          java.util.Map.entry("flac", "audio/flac"),
          java.util.Map.entry("zip", "application/zip"),
          java.util.Map.entry("gz", "application/gzip"),
          java.util.Map.entry("apk", "application/vnd.android.package-archive"));

  public String getMimeType(String filename) {
    int dot = filename.lastIndexOf('.');
    String ext = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    return MIME.getOrDefault(ext, "application/octet-stream");
  }

  /** Files a phone browser can show by itself, so the row opens them instead of downloading. */
  public boolean isPreviewable(String filename) {
    String mime = getMimeType(filename);
    return mime.startsWith("image/") && !mime.equals("image/heic")
        || mime.equals("video/mp4")
        || mime.equals("video/webm")
        || mime.startsWith("audio/")
        || mime.equals("application/pdf")
        || mime.startsWith("text/plain");
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
    return TEMPLATES.computeIfAbsent(name, Helper::readTemplate);
  }

  private static String readTemplate(String name) {
    try (InputStream in = Helper.class.getResourceAsStream("/template/" + name)) {
      if (in == null) {
        throw new IOException("not on classpath");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to load template: " + name, e);
    }
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
    // isHidden() means "starts with a dot" on Linux but "has the Hidden attribute" on Windows,
    // so check both: dotfiles are never served, so they shouldn't be listed either
    File[] files = directory.listFiles(f -> !f.isHidden() && !f.getName().startsWith("."));
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

    StringBuilder html = new StringBuilder();
    if (files.length > 8) {
      html.append("<div class=\"filter\"><input id=\"q\" type=\"search\" autocomplete=\"off\"")
          .append(" placeholder=\"Filter ").append(files.length).append(" items\"")
          .append(" aria-label=\"Filter files\"></div>")
          .append("<p class=\"nomatch\" hidden>No matches</p>");
    }
    html.append("<ul class=\"list\">");
    for (File f : files) {
      String path = relativePath.isEmpty() ? f.getName() : relativePath + "/" + f.getName();
      String href = escapeHtml(encodePath(path));
      String name = escapeHtml(f.getName());

      if (f.isDirectory()) {
        String[] children = f.list();
        int count = children == null ? 0 : children.length;
        html.append("<li data-name=\"").append(escapeHtml(f.getName().toLowerCase())).append("\">")
            .append("<a class=\"row\" href=\"").append(href).append("\">")
            .append("<span class=\"ico ico-folder\">").append(ICON_FOLDER).append("</span>")
            .append("<span class=\"text\"><span class=\"name\">").append(name).append("</span>")
            .append("<span class=\"meta\">").append(count).append(count == 1 ? " item" : " items")
            .append("</span></span>")
            .append(ICON_CHEVRON)
            .append("</a></li>");
      } else {
        // Photos, videos, music, PDFs and text open in the phone's browser (tap the row);
        // the arrow button always downloads. Anything else downloads straight away.
        boolean preview = isPreviewable(f.getName());
        String dlHref = href + "?download=1";
        String ico =
            "<span class=\"ico ico-" + fileKind(f.getName()) + "\">"
                + escapeHtml(fileBadge(f.getName())) + "</span>";
        String text =
            "<span class=\"text\"><span class=\"name\">" + name + "</span>"
                + "<span class=\"meta\">" + formatFileSize(f.length())
                + "</span></span>";
        html.append("<li data-name=\"").append(escapeHtml(f.getName().toLowerCase())).append("\">");
        if (preview) {
          html.append("<a class=\"row\" href=\"").append(href).append("\">")
              .append(ico).append(text).append("</a>")
              .append("<a class=\"dl\" href=\"").append(dlHref).append("\" download")
              .append(" aria-label=\"Download ").append(name).append("\">")
              .append(ICON_DOWNLOAD).append("</a>");
        } else {
          html.append("<a class=\"row\" href=\"").append(dlHref).append("\" download>")
              .append(ico).append(text).append(ICON_DOWNLOAD).append("</a>");
        }
        html.append("</li>");
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
