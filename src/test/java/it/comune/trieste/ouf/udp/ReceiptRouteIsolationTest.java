package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.assertThat;

import it.comune.trieste.ouf.authorization.TestAuthorization;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ReceiptRouteIsolationTest {
  private enum Endpoint {
    SEARCH("/api/udp/v1/objects/search", "urban.object.search", SearchReceiptFixture.HEADER),
    RELATED(SearchReceiptFixture.RELATED_PATH, "urban.object.related_search", SearchReceiptFixture.HEADER),
    ACQUISITION("/api/udp/v1/operations/acquisition", "urban.acquisition.read", "X-OUF-UDP-Acquisition-Receipt");

    final String path, capability, header;

    Endpoint(String path, String capability, String header) {
      this.path = path;
      this.capability = capability;
      this.header = header;
    }
  }

  private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);
  // Sia gli scope firmati sia la policy owner consentono tutte e tre le operazioni.
  private static final Set<String> GRANTS = Set.of("urban.object.search", "urban.object.related_search",
      "urban.object.read", "lineage.object.read", "lineage.property.read");
  private ObjectSearchReceiptFilter filter;

  @BeforeEach
  void configureFilter(@TempDir Path directory) throws Exception {
    Path key = Files.writeString(directory.resolve("owner-key"), SearchReceiptFixture.KEY);
    var env = new MockEnvironment()
        .withProperty("ouf.udp.search.tenant-id", "tenant-a")
        .withProperty("ouf.udp.search.issuer", SearchReceiptFixture.ISSUER)
        .withProperty("ouf.udp.search.audience", "gateway")
        .withProperty("ouf.udp.search.workload", "workload")
        .withProperty("ouf.udp.search.owner-key-file", key.toString());
    filter = new ObjectSearchReceiptFilter(env, Clock.fixed(Instant.ofEpochSecond(1001), ZoneOffset.UTC));
  }

  @ParameterizedTest(name = "{0} {1} → {2}")
  @CsvSource({
      "UDP-60m-12, SEARCH, RELATED",
      "UDP-60m-13, RELATED, SEARCH",
      "UDP-60m-14, SEARCH, ACQUISITION",
      "UDP-60m-15, ACQUISITION, SEARCH",
      "UDP-60m-16, RELATED, ACQUISITION",
      "UDP-60m-17, ACQUISITION, RELATED"
  })
  void receiptIsBoundToSourceRoute(String petId, Endpoint source, Endpoint destination) throws Exception {
    var claims = SearchReceiptFixture.payload(source.path, source.capability, BODY, 1000);
    claims.put("scope", String.join(" ", GRANTS));
    String receipt;
    if (source == Endpoint.ACQUISITION) {
      claims.put("purpose", "udp-acquisition-owner");
      receipt = SearchReceiptFixture.signed(claims, "ouf-udp-acquisition-owner-v1.");
    } else {
      receipt = SearchReceiptFixture.signed(claims);
    }

    var accepted = new MockFilterChain();
    assertThat(send(source, source.header, receipt, accepted).getStatus()).isEqualTo(200);
    assertThat(accepted.getRequest().getAttribute("ouf.correlationId")).isEqualTo(claims.get("correlationId"));
    assertDenied(destination, source.header, receipt);
    if (!destination.header.equals(source.header)) assertDenied(destination, destination.header, receipt);
  }

  private void assertDenied(Endpoint destination, String header, String receipt) throws Exception {
    var chain = new MockFilterChain();
    var response = send(destination, header, receipt, chain);
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getErrorMessage()).isEqualTo("INVALID_UDP_SEARCH_RECEIPT_OR_POLICY");
    assertThat(chain.getRequest()).isNull();
  }

  private MockHttpServletResponse send(Endpoint target, String header, String receipt, MockFilterChain chain)
      throws Exception {
    var request = new MockHttpServletRequest("POST", target.path);
    request.setContentType("application/json");
    request.setContent(BODY);
    request.addHeader(header, receipt);
    TestAuthorization.bind(request, "human-a", "HUMAN", GRANTS);
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }
}
