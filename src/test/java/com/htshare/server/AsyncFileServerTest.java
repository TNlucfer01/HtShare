package com.htshare.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

/** End-to-end tests: a real server on a temp folder, real HTTP(S) requests. */
class AsyncFileServerTest {

  // ------------------------------------------------------------------ pure helpers

  @Test
  void rangeParsing() {
    assertArrayEquals(new long[] {0, 99}, FileResponder.parseRange("bytes=0-99", 1000));
    assertArrayEquals(new long[] {900, 999}, FileResponder.parseRange("bytes=-100", 1000));
    assertArrayEquals(new long[] {500, 999}, FileResponder.parseRange("bytes=500-", 1000));
    assertArrayEquals(new long[] {990, 999}, FileResponder.parseRange("bytes=990-5000", 1000));
    assertEquals(0, FileResponder.parseRange("bytes=1000-", 1000).length); // unsatisfiable
    assertNull(FileResponder.parseRange("bytes=0-1,5-6", 1000)); // multi-range: send all
    assertNull(FileResponder.parseRange("bytes=9-3", 1000)); // malformed: ignore
    assertNull(FileResponder.parseRange("items=0-1", 1000));
  }

  @Test
  void contentDispositionKeepsUnicodeNames() {
    String h = FileResponder.contentDisposition("attachment", "résumé \"final\".pdf");
    assertTrue(h.startsWith("attachment; filename=\"r_sum_ _final_.pdf\""), h);
    assertTrue(h.endsWith("filename*=UTF-8''r%C3%A9sum%C3%A9%20%22final%22.pdf"), h);
  }

  @Test
  void unsafePathsAreRejected() {
    assertEquals("a/b.txt", AsyncFileServer.safeRelativePath("/a//b.txt"));
    assertEquals("", AsyncFileServer.safeRelativePath("/"));
    assertNull(AsyncFileServer.safeRelativePath("/../etc/passwd"));
    assertNull(AsyncFileServer.safeRelativePath("/a/./b"));
    assertNull(AsyncFileServer.safeRelativePath("/.ssh/id_rsa"));
    assertNull(AsyncFileServer.safeRelativePath("/a\\..\\b"));
    assertNull(AsyncFileServer.safeRelativePath("/C:/Windows/win.ini"));
    assertNull(AsyncFileServer.safeRelativePath("/notes.txt::$DATA"));
  }

  // ------------------------------------------------------------------ over the network

  @Test
  void servesFilesRangesAndListings() throws Exception {
    Path dir = Files.createTempDirectory("htshare-test");
    byte[] data = randomBytes(3_000_000);
    Files.write(dir.resolve("big file.bin"), data);
    Files.writeString(dir.resolve("c++ notes.txt"), "hello");
    Files.writeString(dir.resolve(".env"), "secret");
    Files.createDirectory(dir.resolve("sub"));

    AsyncFileServer server = new AsyncFileServer(19180, dir.toFile(), 0, null, false);
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getActualPort();

      assertArrayEquals(data, get(base + "/big%20file.bin").body);
      assertEquals("hello", new String(get(base + "/c%2B%2B%20notes.txt").body));

      Response part = get(base + "/big%20file.bin", "Range", "bytes=100-199");
      assertEquals(206, part.status);
      assertEquals("bytes 100-199/3000000", part.header("Content-Range"));
      assertArrayEquals(java.util.Arrays.copyOfRange(data, 100, 200), part.body);

      Response page = get(base + "/");
      assertEquals(200, page.status);
      String html = new String(page.body, "UTF-8");
      assertTrue(html.contains("big file.bin"), "listing shows files");
      assertTrue(!html.contains(".env"), "listing hides dotfiles");

      String json = new String(get(base + "/api/list").body, "UTF-8");
      assertTrue(json.contains("big file.bin") && !json.contains(".env"), "JSON list hides dotfiles");
      assertEquals(404, get(base + "/.env").status);
      assertEquals(404, get(base + "/../../etc/passwd").status);
      assertEquals(404, get(base + "/missing.txt").status);
      assertEquals(200, get(base + "/sub").status);
    } finally {
      server.stop();
    }
  }

  @Test
  void manyParallelDownloadsOnTwoIoThreads() throws Exception {
    Path dir = Files.createTempDirectory("htshare-test");
    byte[] data = randomBytes(8_000_000);
    Files.write(dir.resolve("f.bin"), data);
    String expected = sha256(data);

    AsyncFileServer server = new AsyncFileServer(19280, dir.toFile(), 0, null, false);
    server.start();
    ExecutorService clients = Executors.newFixedThreadPool(24);
    try {
      String url = "http://127.0.0.1:" + server.getActualPort() + "/f.bin";
      List<Future<String>> results = new ArrayList<>();
      for (int i = 0; i < 24; i++) {
        results.add(clients.submit(() -> sha256(get(url).body)));
      }
      for (Future<String> r : results) {
        assertEquals(expected, r.get());
      }
      assertEquals(24L * data.length, server.getTransfers().totalBytesSent());
    } finally {
      clients.shutdownNow();
      server.stop();
    }
  }

  @Test
  void httpsWorks() throws Exception {
    if (!com.htshare.util.SSLCertificateGenerator.httpsSupported()) {
      return; // runtime without jdk.crypto.ec
    }
    Path dir = Files.createTempDirectory("htshare-test");
    byte[] data = randomBytes(2_000_000);
    Files.write(dir.resolve("s.bin"), data);

    AsyncFileServer server = new AsyncFileServer(19443, dir.toFile(), 0, null, true);
    server.start();
    try {
      URL url = new URL("https://127.0.0.1:" + server.getActualPort() + "/s.bin");
      HttpsURLConnection c = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
      c.setSSLSocketFactory(trustAll().getSocketFactory());
      c.setHostnameVerifier((h, s) -> true);
      assertEquals(200, c.getResponseCode());
      try (InputStream in = c.getInputStream()) {
        assertArrayEquals(data, in.readAllBytes());
      }
    } finally {
      server.stop();
    }
  }

  // ------------------------------------------------------------------ helpers

  record Response(int status, byte[] body, HttpURLConnection conn) {
    String header(String name) {
      return conn.getHeaderField(name);
    }
  }

  static Response get(String url, String... headers) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
    for (int i = 0; i + 1 < headers.length; i += 2) {
      c.setRequestProperty(headers[i], headers[i + 1]);
    }
    int status = c.getResponseCode();
    InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
    byte[] body = in == null ? new byte[0] : in.readAllBytes();
    return new Response(status, body, c);
  }

  static byte[] randomBytes(int n) {
    byte[] b = new byte[n];
    new Random(42).nextBytes(b);
    return b;
  }

  static String sha256(byte[] b) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
  }

  static SSLContext trustAll() throws Exception {
    TrustManager tm =
        new X509TrustManager() {
          public void checkClientTrusted(X509Certificate[] c, String a) {}

          public void checkServerTrusted(X509Certificate[] c, String a) {}

          public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
          }
        };
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(null, new TrustManager[] {tm}, null);
    return ctx;
  }
}
