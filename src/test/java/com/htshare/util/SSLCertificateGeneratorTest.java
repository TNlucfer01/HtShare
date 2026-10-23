package com.htshare.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;
import org.junit.jupiter.api.Test;

class SSLCertificateGeneratorTest {

  @Test
  void createsAValidSelfSignedCertificate() throws Exception {
    if (!SSLCertificateGenerator.httpsSupported()) {
      return; // runtime without jdk.crypto.ec
    }
    KeyStore ks = SSLCertificateGenerator.generateKeyStore();
    X509Certificate cert = (X509Certificate) ks.getCertificate(ks.aliases().nextElement());

    cert.checkValidity(new Date());
    cert.verify(cert.getPublicKey()); // signed by its own key
    assertTrue(cert.getSubjectX500Principal().getName().contains("CN=HtShare"));
    assertTrue(
        cert.getSubjectAlternativeNames().stream().anyMatch(n -> n.get(1).equals("localhost")),
        "SAN includes localhost");
  }
}
