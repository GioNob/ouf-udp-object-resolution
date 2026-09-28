package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class GovernedIdentitySubjectMapperTest {
  private final GovernedIdentitySubjectMapper mapper=new GovernedIdentitySubjectMapper();
  private final UdpPorts.MaterializationProfile mapping=new UdpPorts.MaterializationProfile("authority://1",
      List.of(new UdpPorts.PropertyRule("identity","urn:key","string","OPEN",List.of())));

  @Test void projectsCanonicalHandoffThroughPublishedMappingWithoutFileOrVerticalFields()throws Exception{
    var policy=policy();
    var subject=mapper.map("h-1",handoff("set-1","A-123"),mapping,policy);
    assertThat(subject.tenantId()).isEqualTo("tenant-a");
    assertThat(subject.values()).containsOnlyKeys("urn:key");
    assertThat(subject.values().get("urn:key"))
        .isEqualTo(new GovernedIdentityEngine.Value("urn:key@set-1","A-123",
            "handoff://h-1#canonicalPayload/identity"));
    assertThat(mapper.map("h-2",handoff("set-1",null),mapping,policy).values()).isEmpty();
    var unsupported=new HashMap<String,Object>(handoff("set-1",null));
    unsupported.put("canonicalPayload",Map.of("identity",List.of("A-123")));
    assertThatThrownBy(()->mapper.map("h-3",unsupported,mapping,policy))
        .hasMessage("UDP_IDENTITY_SUBJECT_MAPPING_INVALID");
  }

  @Test void rejectsMismatchedPublicationOrUnmappedIdentitySignal()throws Exception{
    var policy=policy();
    assertThatThrownBy(()->mapper.map("h-1",handoff("set-2","A-123"),mapping,policy))
        .hasMessage("UDP_IDENTITY_SUBJECT_MAPPING_INVALID");
    var wrong=new UdpPorts.MaterializationProfile("authority://1",List.of(
        new UdpPorts.PropertyRule("identity","urn:other","string","OPEN",List.of())));
    assertThatThrownBy(()->mapper.map("h-1",handoff("set-1","A-123"),wrong,policy))
        .hasMessage("UDP_IDENTITY_SUBJECT_MAPPING_INVALID");
  }

  @SuppressWarnings("unchecked") private GovernedIdentityEngine.Policy policy()throws Exception{
    var json=new ObjectMapper();
    try(var input=getClass().getResourceAsStream("/identity-governed-proposal-v1.json")){
      Map<String,Object> proposal=json.readValue(input,Map.class);
      return PublishedIdentityPolicy.decode(json,proposal,"tenant-a","s","urn:T",Set.of("urn:key"));
    }
  }
  private Map<String,Object> handoff(String publication,String value){
    Map<String,Object> canonical=new HashMap<>();if(value!=null)canonical.put("identity",value);
    return Map.of("sourceIdentity",Map.of("sourceId","s"),"canonicalPayload",canonical,
        "contractRefs",Map.of("semanticPublicationSetRef",publication));
  }
}
