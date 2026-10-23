package com.htshare.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Adapter names as Java reports them on real Windows and Linux machines. */
class NetworkVerifierTest {

  @Test
  void windowsAdapters() {
    assertEquals("Wi-Fi", NetworkVerifier.typeOf("wlan2", "Intel(R) Wi-Fi 6 AX201 160MHz"));
    assertEquals("Ethernet", NetworkVerifier.typeOf("eth4", "Realtek PCIe GbE Family Controller"));
    // these are also "ethN" on Windows, but phones can't reach them
    assertEquals("Virtual", NetworkVerifier.typeOf("eth7", "Hyper-V Virtual Ethernet Adapter"));
    assertEquals("Virtual", NetworkVerifier.typeOf("eth9", "vEthernet (WSL)"));
    assertEquals("Virtual", NetworkVerifier.typeOf("eth3", "VirtualBox Host-Only Ethernet Adapter"));
    assertEquals("Virtual", NetworkVerifier.typeOf("net5", "TAP-Windows Adapter V9"));
    assertEquals("Virtual", NetworkVerifier.typeOf("wlan5", "Microsoft Wi-Fi Direct Virtual Adapter"));
    assertEquals("Virtual", NetworkVerifier.typeOf("eth6", "VMware Virtual Ethernet Adapter for VMnet8"));
  }

  @Test
  void linuxAdapters() {
    assertEquals("Wi-Fi", NetworkVerifier.typeOf("wlp2s0", "wlp2s0"));
    assertEquals("Wi-Fi", NetworkVerifier.typeOf("wlan0", "wlan0"));
    assertEquals("Ethernet", NetworkVerifier.typeOf("enp3s0", "enp3s0"));
    assertEquals("Ethernet", NetworkVerifier.typeOf("eth0", "eth0"));
    assertEquals("Virtual", NetworkVerifier.typeOf("docker0", "docker0"));
    assertEquals("Virtual", NetworkVerifier.typeOf("br-1a2b3c", "br-1a2b3c"));
    assertEquals("Virtual", NetworkVerifier.typeOf("virbr0", "virbr0"));
    assertEquals("Virtual", NetworkVerifier.typeOf("tun0", "tun0"));
    assertEquals("Virtual", NetworkVerifier.typeOf("wg0", "wg0"));
    assertEquals("Virtual", NetworkVerifier.typeOf("tailscale0", "tailscale0"));
    assertEquals("Loopback", NetworkVerifier.typeOf("lo", "lo"));
  }
}
