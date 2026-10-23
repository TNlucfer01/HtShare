package com.htshare.server;

/** Turns a browser's User-Agent into a short, friendly device label for the transfers panel. */
public final class DeviceNames {

  private DeviceNames() {}

  /** e.g. "Android · 192.168.1.23" */
  public static String label(String userAgent, String address) {
    return kind(userAgent) + " · " + address;
  }

  public static String kind(String ua) {
    if (ua == null || ua.isBlank()) return "Device";
    // order matters: iPad/iPhone and Android UAs also mention "Mac" / "Linux"
    if (ua.contains("iPhone")) return "iPhone";
    if (ua.contains("iPad")) return "iPad";
    if (ua.contains("Android")) return "Android";
    if (ua.contains("CrOS")) return "Chromebook";
    if (ua.contains("Macintosh") || ua.contains("Mac OS X")) return "Mac";
    if (ua.contains("Windows")) return "Windows";
    if (ua.contains("Linux")) return "Linux";
    if (ua.startsWith("curl/")) return "curl";
    if (ua.startsWith("Wget/")) return "wget";
    return "Browser";
  }
}
