package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class SearchReceiptFixture {
  static final String KEY = "ab".repeat(32);
  static final String ISSUER = "https://auth.invalid";
  static final String RELATED_PATH = "/api/udp/v1/objects/related-search";
  static final String HEADER = "X-OUF-UDP-Search-Receipt";
  private static final ObjectMapper JSON = new ObjectMapper();

  private SearchReceiptFixture() {}

  static Map<String, Object> payload(String path, String capability, byte[] body, long issued) {
    var receipt = new LinkedHashMap<String, Object>();
    receipt.put("v", 1);
    receipt.put("purpose", "udp-object-search-owner");
    receipt.put("method", "POST");
    receipt.put("path", path);
    try {
      receipt.put("bodyHash", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
    receipt.put("capability", capability);
    receipt.put("iat", issued);
    receipt.put("exp", issued + 30);
    receipt.put("issuer", ISSUER);
    receipt.put("audience", "gateway");
    receipt.put("workload", "workload");
    receipt.put("subject", "human-a");
    receipt.put("tenant", "tenant-a");
    receipt.put("client", "client-test");
    receipt.put("acr", "1");
    receipt.put("roles", "role-test");
    receipt.put("scope", "mcp.connect urban.object.read " + capability);
    receipt.put("requestHash", "a".repeat(64));
    receipt.put("correlationId", "corr-related");
    return receipt;
  }

  static String signed(Map<String, Object> receipt) throws Exception {
    return signed(receipt, "ouf-udp-search-owner-v1.");
  }

  static String signed(Map<String, Object> receipt, String domain) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String payload = encoder.encodeToString(JSON.writeValueAsBytes(receipt));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
    return payload + "." + encoder.encodeToString(mac.doFinal((domain + payload).getBytes(StandardCharsets.US_ASCII)));
  }
}
