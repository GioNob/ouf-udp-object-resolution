package it.comune.trieste.ouf.udp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ouf.udp.reference-batch")
public record ReferenceBatchSettings(int requestBytes, int items, int references, int bundleBytes,
    int verificationAttempts, int fallbackIntervalMs, int gatewayIntervalMs) {
  public ReferenceBatchSettings {
    if (requestBytes < 1 || requestBytes > 256 * 1024 || items < 1 || items > 1000 ||
        references < 1 || bundleBytes < 1 || verificationAttempts < 1 || verificationAttempts > 128 ||
        fallbackIntervalMs < 1 || gatewayIntervalMs < 1) throw new IllegalArgumentException("UDP_REFERENCE_BATCH_CONFIG_INVALID");
  }
}
