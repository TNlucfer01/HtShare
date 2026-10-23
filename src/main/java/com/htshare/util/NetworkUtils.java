package com.htshare.util;

import java.io.IOException;
import java.net.*;
import java.util.Enumeration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NetworkUtils {
  private static final Logger logger = LoggerFactory.getLogger(NetworkUtils.class);

  /** Get the local IP address of this machine on the network */
  public static String getLocalIPAddress() throws SocketException, UnknownHostException {
    // Same choice as the status bar, so the QR code never points at a VM/VPN adapter
    try {
      return NetworkVerifier.getPrimaryNetwork().getAddress().getHostAddress();
    } catch (SocketException e) {
      logger.warn("No primary network found: {}", e.getMessage());
    }
    // Last resort: first non-loopback IPv4 address
    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

    while (interfaces.hasMoreElements()) {
      NetworkInterface iface = interfaces.nextElement();

      // Skip loopback and inactive interfaces
      if (iface.isLoopback() || !iface.isUp()) {
        continue;
      }

      Enumeration<InetAddress> addresses = iface.getInetAddresses();
      while (addresses.hasMoreElements()) {
        InetAddress addr = addresses.nextElement();

        // We want IPv4 addresses only
        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
          String ip = addr.getHostAddress();
          logger.info("Found local IP address: {} on interface: {}", ip, iface.getName());
          return ip;
        }
      }
    }

    // Fallback: use localhost resolution
    logger.warn("Could not find network IP, using localhost resolution");
    return InetAddress.getLocalHost().getHostAddress();
  }

  /** Check if a specific port is available */
  public static boolean isPortAvailable(int port) {
    try (ServerSocket serverSocket = new ServerSocket(port)) {
      serverSocket.setReuseAddress(true);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** Check if network is available */
  public static boolean isNetworkAvailable() {
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface iface = interfaces.nextElement();
        if (iface.isUp() && !iface.isLoopback()) {
          return true;
        }
      }
      return false;
    } catch (SocketException e) {
      logger.error("Error checking network availability", e);
      return false;
    }
  }

  /** Get the network interface name for the primary connection */
  public static String getActiveNetworkInterface() throws SocketException {
    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

    while (interfaces.hasMoreElements()) {
      NetworkInterface iface = interfaces.nextElement();

      if (iface.isLoopback() || !iface.isUp()) {
        continue;
      }

      Enumeration<InetAddress> addresses = iface.getInetAddresses();
      while (addresses.hasMoreElements()) {
        InetAddress addr = addresses.nextElement();
        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
          return iface.getDisplayName();
        }
      }
    }

    return "Unknown";
  }
}
