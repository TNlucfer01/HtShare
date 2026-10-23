package com.htshare.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Network verification utility to ensure devices are on the same network
 */
public class NetworkVerifier {
    private static final Logger logger = LoggerFactory.getLogger(NetworkVerifier.class);

    /**
     * Information about a network interface
     */
    public static class NetworkInfo {
        private final String interfaceName;
        private final String displayName;
        private final InetAddress address;
        private final InetAddress subnet;
        private final short prefixLength;
        
        public NetworkInfo(String interfaceName, String displayName, 
                          InetAddress address, InetAddress subnet, short prefixLength) {
            this.interfaceName = interfaceName;
            this.displayName = displayName;
            this.address = address;
            this.subnet = subnet;
            this.prefixLength = prefixLength;
        }
        
        public String getInterfaceName() { return interfaceName; }
        public String getDisplayName() { return displayName; }
        public InetAddress getAddress() { return address; }
        public InetAddress getSubnet() { return subnet; }
        public short getPrefixLength() { return prefixLength; }
        
        public String getNetworkRange() {
            return subnet.getHostAddress() + "/" + prefixLength;
        }
        
        @Override
        public String toString() {
            return String.format("%s (%s): %s - Network: %s/%d", 
                displayName, interfaceName, address.getHostAddress(), 
                subnet.getHostAddress(), prefixLength);
        }
    }

    /**
     * Get all active network interfaces with IPv4 addresses
     */
    public static List<NetworkInfo> getActiveNetworks() throws SocketException {
        List<NetworkInfo> networks = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        
        while (interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();
            
            // Skip loopback and inactive interfaces
            if (iface.isLoopback() || !iface.isUp()) {
                continue;
            }
            
            // Get all addresses for this interface
            List<InterfaceAddress> interfaceAddresses = iface.getInterfaceAddresses();
            
            for (InterfaceAddress ifAddr : interfaceAddresses) {
                InetAddress addr = ifAddr.getAddress();
                
                // Only process IPv4 addresses
                if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                    InetAddress subnet = calculateNetworkAddress(addr, ifAddr.getNetworkPrefixLength());
                    
                    NetworkInfo info = new NetworkInfo(
                        iface.getName(),
                        iface.getDisplayName(),
                        addr,
                        subnet,
                        ifAddr.getNetworkPrefixLength()
                    );
                    
                    networks.add(info);
                    logger.debug("Found network: {}", info);
                }
            }
        }
        
        return networks;
    }

    /**
     * Get the network a phone on the same Wi-Fi can reach us on.
     *
     * <p>1. Ask the OS which local address it would use for outgoing traffic (a "connected" UDP
     * socket picks a route but sends nothing). 2. If that's a virtual/VPN adapter or there is no
     * route, score the candidates instead. Works the same on Windows, Linux and macOS, where
     * interface names differ (Windows calls every Ethernet-type adapter "ethN", including
     * Hyper-V/WSL and VirtualBox ones; Linux uses enp3s0 / wlp2s0).
     */
    public static NetworkInfo getPrimaryNetwork() throws SocketException, UnknownHostException {
        List<NetworkInfo> networks = getActiveNetworks();

        if (networks.isEmpty()) {
            throw new SocketException("No active network interfaces found");
        }

        InetAddress routed = defaultRouteAddress();
        if (routed != null) {
            for (NetworkInfo network : networks) {
                if (network.getAddress().equals(routed) && !isVirtual(network)) {
                    logger.info("Selected primary network (default route): {}", network);
                    return network;
                }
            }
        }

        NetworkInfo best = networks.get(0);
        for (NetworkInfo network : networks) {
            if (score(network) > score(best)) {
                best = network;
            }
        }
        logger.info("Selected primary network (best match): {}", best);
        return best;
    }

    /** Local address the OS would use for traffic to the internet, or null if there's no route. */
    static InetAddress defaultRouteAddress() {
        try (DatagramSocket probe = new DatagramSocket()) {
            // UDP connect() only chooses a route; no packet is sent.
            probe.connect(InetAddress.getByAddress(new byte[] {8, 8, 8, 8}), 53);
            InetAddress local = probe.getLocalAddress();
            return local == null || local.isAnyLocalAddress() ? null : local;
        } catch (Exception e) {
            return null;
        }
    }

    private static final String[] VIRTUAL_HINTS = {
        "virtual", "vmware", "vmnet", "vbox", "hyper-v", "vethernet", "docker", "veth", "br-",
        "virbr", "lxc", "lxd", "tun", "tap", "wg", "wireguard", "tailscale", "zerotier", "zt",
        "utun", "vpn", "npcap", "loopback", "teredo", "isatap", "anyconnect", "nordlynx",
        "tap-windows", "wintun", "wi-fi direct"
    };

    /** VM, container, VPN and tunnel adapters: phones on the Wi-Fi can't reach these. */
    static boolean isVirtual(NetworkInfo n) {
        return looksVirtual(n.getInterfaceName(), n.getDisplayName());
    }

    static boolean looksVirtual(String name, String display) {
        String a = name == null ? "" : name.toLowerCase();
        String b = display == null ? "" : display.toLowerCase();
        for (String hint : VIRTUAL_HINTS) {
            // short hints must match the start of the name (e.g. "tun0", "wg0"), not any substring
            boolean shortHint = hint.length() <= 3;
            if (shortHint ? a.startsWith(hint) : (a.contains(hint) || b.contains(hint))) {
                return true;
            }
        }
        return false;
    }

    private static int score(NetworkInfo n) {
        if (isVirtual(n)) {
            return -100;
        }
        int s = 0;
        byte[] ip = n.getAddress().getAddress();
        int a = ip[0] & 0xff, b = ip[1] & 0xff;
        if (a == 192 && b == 168) s += 30;               // typical home Wi-Fi
        else if (a == 10) s += 25;
        else if (a == 172 && b >= 16 && b <= 31) s += 15; // also common for Docker, so lower
        else if (a == 169 && b == 254) s -= 50;           // link-local: no DHCP, unusable
        String type = getNetworkType(n);
        if (type.equals("Wi-Fi")) s += 10;
        else if (type.equals("Ethernet")) s += 8;
        return s;
    }

    /**
     * Check if a client IP is on the same network as the server
     */
    public static boolean isSameNetwork(InetAddress serverAddr, InetAddress clientAddr, 
                                       short prefixLength) {
        try {
            byte[] serverBytes = serverAddr.getAddress();
            byte[] clientBytes = clientAddr.getAddress();
            
            // Both must be IPv4
            if (serverBytes.length != 4 || clientBytes.length != 4) {
                return false;
            }
            
            // Calculate subnet mask
            int mask = 0xffffffff << (32 - prefixLength);
            
            // Convert IP addresses to integers
            int serverInt = byteArrayToInt(serverBytes);
            int clientInt = byteArrayToInt(clientBytes);
            
            // Compare network portions
            return (serverInt & mask) == (clientInt & mask);
            
        } catch (Exception e) {
            logger.error("Error checking network match", e);
            return false;
        }
    }

    /**
     * Calculate network address from IP and prefix length
     */
    public static InetAddress calculateNetworkAddress(InetAddress address, short prefixLength) {
        try {
            byte[] addrBytes = address.getAddress();
            int mask = 0xffffffff << (32 - prefixLength);
            int addrInt = byteArrayToInt(addrBytes);
            int networkInt = addrInt & mask;
            
            return InetAddress.getByAddress(intToByteArray(networkInt));
        } catch (Exception e) {
            logger.error("Error calculating network address", e);
            return address;
        }
    }

    /**
     * Calculate broadcast address from IP and prefix length
     */
    public static InetAddress calculateBroadcastAddress(InetAddress address, short prefixLength) {
        try {
            byte[] addrBytes = address.getAddress();
            int mask = 0xffffffff << (32 - prefixLength);
            int addrInt = byteArrayToInt(addrBytes);
            int broadcastInt = addrInt | ~mask;
            
            return InetAddress.getByAddress(intToByteArray(broadcastInt));
        } catch (Exception e) {
            logger.error("Error calculating broadcast address", e);
            return address;
        }
    }

    /**
     * Get network type description
     */
    public static String getNetworkType(NetworkInfo network) {
        return typeOf(network.getInterfaceName(), network.getDisplayName());
    }

    static String typeOf(String interfaceName, String displayName) {
        String name = interfaceName == null ? "" : interfaceName.toLowerCase();
        String display = displayName == null ? "" : displayName.toLowerCase();

        if (name.startsWith("lo") && !name.startsWith("lowpan")) {
            return "Loopback";
        }
        if (looksVirtual(interfaceName, displayName)) {
            return "Virtual";
        }
        // Wi-Fi: wlan0 (Windows/old Linux), wlp2s0 (Linux), "Wi-Fi"/"Wireless" display names
        if (name.startsWith("wl") || name.contains("wi-fi") || display.contains("wireless")
                || display.contains("wi-fi") || display.contains("wlan") || display.contains("802.11")) {
            return "Wi-Fi";
        }
        // Ethernet: eth0 (Windows/old Linux), enp3s0 / eno1 / ens33 / enx… (Linux), en0 (macOS)
        if (name.startsWith("eth") || name.startsWith("en") || display.contains("ethernet")) {
            return "Ethernet";
        }
        return "Other";
    }

    /**
     * Validate network connectivity
     */
    public static NetworkValidationResult validateNetwork() {
        try {
            List<NetworkInfo> networks = getActiveNetworks();
            
            if (networks.isEmpty()) {
                return new NetworkValidationResult(false, 
                    "No active network connections found. Please connect to a network.", 
                    null);
            }
            
            NetworkInfo primary = getPrimaryNetwork();
            String networkType = getNetworkType(primary);
            
            // Check if it's a usable network (not virtual or loopback)
            if (networkType.equals("Virtual") || networkType.equals("Loopback")) {
                return new NetworkValidationResult(false,
                    "Connected to virtual/loopback network. Please use a real network connection.",
                    primary);
            }
            
            // Check if private IP range (local network)
            if (!isPrivateIP(primary.getAddress())) {
                logger.warn("Public IP detected: {}", primary.getAddress().getHostAddress());
            }
            
            String message = String.format("Connected to %s network: %s", 
                networkType, primary.getAddress().getHostAddress());
            
            return new NetworkValidationResult(true, message, primary);
            
        } catch (Exception e) {
            logger.error("Network validation failed", e);
            return new NetworkValidationResult(false, 
                "Network validation failed: " + e.getMessage(), 
                null);
        }
    }

    /**
     * Check if IP is in private range
     */
    public static boolean isPrivateIP(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) return false;
        
        int b1 = bytes[0] & 0xFF;
        int b2 = bytes[1] & 0xFF;
        
        // 10.0.0.0/8
        if (b1 == 10) return true;
        
        // 172.16.0.0/12
        if (b1 == 172 && (b2 >= 16 && b2 <= 31)) return true;
        
        // 192.168.0.0/16
        if (b1 == 192 && b2 == 168) return true;
        
        return false;
    }

    /**
     * Result of network validation
     */
    public static class NetworkValidationResult {
        private final boolean valid;
        private final String message;
        private final NetworkInfo networkInfo;
        
        public NetworkValidationResult(boolean valid, String message, NetworkInfo networkInfo) {
            this.valid = valid;
            this.message = message;
            this.networkInfo = networkInfo;
        }
        
        public boolean isValid() { return valid; }
        public String getMessage() { return message; }
        public NetworkInfo getNetworkInfo() { return networkInfo; }
    }

    // Helper methods
    private static int byteArrayToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) |
               ((bytes[1] & 0xFF) << 16) |
               ((bytes[2] & 0xFF) << 8) |
               (bytes[3] & 0xFF);
    }

    private static byte[] intToByteArray(int value) {
        return new byte[] {
            (byte) (value >>> 24),
            (byte) (value >>> 16),
            (byte) (value >>> 8),
            (byte) value
        };
    }
}