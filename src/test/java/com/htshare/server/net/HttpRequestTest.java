package com.htshare.server.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HttpRequestTest {

  @Test
  void decodesPathsWithoutTurningPlusIntoSpace() throws Exception {
    assertEquals("/c++ notes (50%).txt", HttpRequest.decodePath("/c%2B%2B%20notes%20(50%25).txt"));
    assertEquals("/a+b", HttpRequest.decodePath("/a+b"));
    assertEquals("/café", HttpRequest.decodePath("/caf%C3%A9"));
  }

  @Test
  void rejectsBadEscapesAndNul() {
    assertThrows(HttpRequest.BadRequest.class, () -> HttpRequest.decodePath("/a%2"));
    assertThrows(HttpRequest.BadRequest.class, () -> HttpRequest.decodePath("/a%zz"));
    assertThrows(HttpRequest.BadRequest.class, () -> HttpRequest.decodePath("/a%00b"));
  }

  @Test
  void parsesHeadAndKeepAlive() throws Exception {
    HttpRequest r =
        HttpRequest.parse("GET /x%20y?download=1 HTTP/1.1\r\nHost: h\r\nRange: bytes=0-1");
    assertEquals("GET", r.method());
    assertEquals("/x y", r.path());
    assertEquals("1", r.param("download"));
    assertEquals("bytes=0-1", r.header("range"));
    assertTrue(r.wantsKeepAlive());
    assertFalse(r.hasBody());

    assertFalse(HttpRequest.parse("GET / HTTP/1.1\r\nConnection: close").wantsKeepAlive());
    assertFalse(HttpRequest.parse("GET / HTTP/1.0").wantsKeepAlive());
    assertThrows(HttpRequest.BadRequest.class, () -> HttpRequest.parse("GARBAGE"));
  }
}
