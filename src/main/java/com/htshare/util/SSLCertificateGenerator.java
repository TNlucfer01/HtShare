package com.htshare.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the self-signed certificate for HTTPS mode.
 *
 * <p>Builds the X.509 certificate by hand (DER encoding) using only public Java APIs. The earlier
 * version relied on JDK-internal {@code sun.security.x509} classes, which need special JVM flags
 * and failed in the AppImage — so HTTPS, the default, didn't work there.
 *
 * <p>Uses an EC P-256 key (fast to generate, supported by every current phone) and lists the
 * computer's local IP addresses as subject alternative names.
 */
public class SSLCertificateGenerator {
  private static final Logger logger = LoggerFactory.getLogger(SSLCertificateGenerator.class);

  public static final String KEYSTORE_PASSWORD = "fileshare123";
  private static final String ALIAS = "htshare";
  private static final int VALIDITY_DAYS = 365;

  // object identifiers used below
  private static final String OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2";
  private static final String OID_COMMON_NAME = "2.5.4.3";
  private static final String OID_ORGANIZATION = "2.5.4.10";
  private static final String OID_SUBJECT_ALT_NAME = "2.5.29.17";
  private static final String OID_BASIC_CONSTRAINTS = "2.5.29.19";
  private static final String OID_EXT_KEY_USAGE = "2.5.29.37";
  private static final String OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";

  /**
   * Whether this Java runtime can do HTTPS that phones accept. Modern browsers require ECDHE key
   * exchange, which lives in the {@code jdk.crypto.ec} module; a trimmed (jlink) runtime built
   * without it can't serve HTTPS at all. packaging/build-appimage.sh includes it.
   */
  public static boolean httpsSupported() {
    try {
      KeyPairGenerator.getInstance("EC");
      return true;
    } catch (java.security.NoSuchAlgorithmException e) {
      return false;
    }
  }

  /** A fresh key pair and certificate in an in-memory KeyStore. */
  public static KeyStore generateKeyStore() throws Exception {
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
    kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
    KeyPair keyPair = kpg.generateKeyPair();

    X509Certificate cert = selfSigned(keyPair, localAddresses());

    KeyStore ks = KeyStore.getInstance("PKCS12");
    ks.load(null, null);
    ks.setKeyEntry(
        ALIAS, keyPair.getPrivate(), KEYSTORE_PASSWORD.toCharArray(), new Certificate[] {cert});
    logger.info(
        "Self-signed certificate ready ({}), valid until {}",
        cert.getSubjectX500Principal().getName(),
        cert.getNotAfter());
    return ks;
  }

  static X509Certificate selfSigned(KeyPair keyPair, List<InetAddress> ips) throws Exception {
    byte[] name =
        seq(
            set(seq(oid(OID_COMMON_NAME), utf8("HtShare"))),
            set(seq(oid(OID_ORGANIZATION), utf8("HtShare local share"))));
    byte[] sigAlg = seq(oid(OID_ECDSA_SHA256));

    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
    byte[] validity = seq(utcTime(now.minusDays(1)), utcTime(now.plusDays(VALIDITY_DAYS)));

    byte[] serialBytes = new byte[16];
    new SecureRandom().nextBytes(serialBytes);
    BigInteger serial = new BigInteger(1, serialBytes);

    // Subject alternative names: "localhost" + every local IPv4 address
    List<byte[]> names = new ArrayList<>();
    names.add(tlv(0x82, "localhost".getBytes(StandardCharsets.US_ASCII))); // [2] dNSName
    for (InetAddress ip : ips) {
      names.add(tlv(0x87, ip.getAddress())); // [7] iPAddress
    }
    byte[] extensions =
        seq(
            seq(oid(OID_SUBJECT_ALT_NAME), octets(seq(names.toArray(new byte[0][])))),
            seq(oid(OID_BASIC_CONSTRAINTS), octets(seq())), // CA: false
            seq(oid(OID_EXT_KEY_USAGE), octets(seq(oid(OID_SERVER_AUTH)))));

    byte[] tbs =
        seq(
            tlv(0xa0, integer(BigInteger.TWO)), // [0] version: v3
            integer(serial),
            sigAlg,
            name, // issuer
            validity,
            name, // subject (same: self-signed)
            keyPair.getPublic().getEncoded(), // SubjectPublicKeyInfo, already DER
            tlv(0xa3, extensions)); // [3] extensions

    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(keyPair.getPrivate());
    signer.update(tbs);
    byte[] cert = seq(tbs, sigAlg, bitString(signer.sign()));

    return (X509Certificate)
        CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
  }

  private static List<InetAddress> localAddresses() {
    List<InetAddress> out = new ArrayList<>();
    try {
      for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!ni.isUp()) continue;
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
          if (a instanceof Inet4Address) out.add(a);
        }
      }
    } catch (Exception e) {
      logger.debug("Could not list interfaces for the certificate: {}", e.toString());
    }
    return out;
  }

  // ------------------------------------------------------------------ minimal DER encoder

  private static byte[] tlv(int tag, byte[] value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 6);
    out.write(tag);
    int len = value.length;
    if (len < 0x80) {
      out.write(len);
    } else {
      byte[] l = BigInteger.valueOf(len).toByteArray();
      int skip = l[0] == 0 ? 1 : 0;
      out.write(0x80 | (l.length - skip));
      out.write(l, skip, l.length - skip);
    }
    out.writeBytes(value);
    return out.toByteArray();
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] p : parts) out.writeBytes(p);
    return out.toByteArray();
  }

  private static byte[] seq(byte[]... parts) {
    return tlv(0x30, concat(parts));
  }

  private static byte[] set(byte[]... parts) {
    return tlv(0x31, concat(parts));
  }

  private static byte[] integer(BigInteger v) {
    return tlv(0x02, v.toByteArray());
  }

  private static byte[] octets(byte[] v) {
    return tlv(0x04, v);
  }

  private static byte[] bitString(byte[] v) {
    return tlv(0x03, concat(new byte[] {0}, v)); // 0 unused bits
  }

  private static byte[] utf8(String s) {
    return tlv(0x0c, s.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] utcTime(ZonedDateTime t) {
    String s = t.format(DateTimeFormatter.ofPattern("yyMMddHHmmss")) + "Z";
    return tlv(0x17, s.getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] oid(String dotted) {
    String[] parts = dotted.split("\\.");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
    for (int i = 2; i < parts.length; i++) {
      long v = Long.parseLong(parts[i]);
      // base-128, high bit set on all but the last byte
      byte[] tmp = new byte[10];
      int n = 0;
      do {
        tmp[n++] = (byte) (v & 0x7f);
        v >>= 7;
      } while (v > 0);
      for (int j = n - 1; j >= 0; j--) {
        out.write(tmp[j] | (j > 0 ? 0x80 : 0));
      }
    }
    return tlv(0x06, out.toByteArray());
  }
}
