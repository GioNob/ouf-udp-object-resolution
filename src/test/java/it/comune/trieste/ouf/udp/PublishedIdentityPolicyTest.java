package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class PublishedIdentityPolicyTest {
  private final ObjectMapper json=new ObjectMapper();

  @Test void decodesOnlyTheMappedPinnedScopedProposal() throws Exception {
    var resolution=proposal();
    var policy=PublishedIdentityPolicy.decode(json,resolution,"tenant-a","s","urn:T",Set.of("urn:key"));
    assertThat(policy.ref()).isEqualTo("identity://policy/1");
    assertThat(policy.signals()).singleElement().satisfies(signal ->
        assertThat(signal.assertionRef()).isEqualTo("assertion://key/1"));
    assertThatThrownBy(() -> PublishedIdentityPolicy.decode(json,resolution,"other","s","urn:T",Set.of("urn:key")))
        .hasMessage("UDP_GOVERNED_IDENTITY_PROFILE_INVALID");
    assertThatThrownBy(() -> PublishedIdentityPolicy.decode(json,resolution,"tenant-a","s","urn:T",Set.of("urn:other")))
        .hasMessage("UDP_GOVERNED_IDENTITY_PROFILE_INVALID");
    assertThatThrownBy(() -> PublishedIdentityPolicy.decode(json,resolution,"tenant-a","s","urn:T",Set.of("urn:key","urn:address")))
        .hasMessage("UDP_GOVERNED_IDENTITY_PROFILE_INVALID");
    resolution.put("matchProperty","urn:key");
    assertThatThrownBy(() -> PublishedIdentityPolicy.decode(json,resolution,"tenant-a","s","urn:T",Set.of("urn:key")))
        .hasMessage("UDP_GOVERNED_IDENTITY_PROFILE_INVALID");
  }

  @Test void aDeclaredUniqueSignalWithoutItsAssertionIsInvalid() throws Exception {
    var resolution=proposal();
    @SuppressWarnings("unchecked") var policy=(Map<String,Object>)resolution.get("governedIdentity");
    @SuppressWarnings("unchecked") var signals=(List<Map<String,Object>>)policy.get("signals");
    signals.getFirst().remove("assertionRef");
    assertThatThrownBy(() -> PublishedIdentityPolicy.decode(json,resolution,"tenant-a","s","urn:T",Set.of("urn:key")))
        .hasMessage("UDP_GOVERNED_IDENTITY_PROFILE_INVALID");
  }

  @SuppressWarnings("unchecked") private Map<String,Object> proposal() throws Exception {
    try(var input=getClass().getResourceAsStream("/identity-governed-proposal-v1.json")) {
      return json.readValue(input,Map.class);
    }
  }
}
