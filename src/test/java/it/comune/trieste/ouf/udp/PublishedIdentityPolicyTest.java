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

  @Test @SuppressWarnings("unchecked") void distinctRulesAreExplicitMultiSignalAndFingerprintBound() throws Exception {
    var resolution=proposal();var policy=(Map<String,Object>)resolution.get("governedIdentity");
    var signals=(List<Map<String,Object>>)policy.get("signals");
    signals.add(new LinkedHashMap<>(Map.of("id","urn:address","semanticRef","urn:address@set-1","comparator","TEXT_V1","excludesOnDisagreement",false,"uniqueWithinScope",false,"assertionRef","assertion://address")));
    var mapped=Set.of("urn:key","urn:address");
    var legacy=PublishedIdentityPolicy.decode(json,resolution,"tenant-a","s","urn:T",mapped);
    policy.put("distinctRules",List.of(Map.of("id","different-key-and-address","signalIds",List.of("urn:key","urn:address"),"assertionRef","assertion://distinct")));
    var changed=PublishedIdentityPolicy.decode(json,resolution,"tenant-a","s","urn:T",mapped);
    assertThat(changed.distinctRules()).hasSize(1);
    assertThat(ScopedIdentityCandidateRepository.fingerprint(changed)).isNotEqualTo(ScopedIdentityCandidateRepository.fingerprint(legacy));
    policy.put("distinctRules",List.of());
    assertThat(ScopedIdentityCandidateRepository.fingerprint(PublishedIdentityPolicy.decode(json,resolution,"tenant-a","s","urn:T",mapped))).isEqualTo(ScopedIdentityCandidateRepository.fingerprint(legacy));
    policy.put("distinctRules",List.of(Map.of("id","weak","signalIds",List.of("urn:key"),"assertionRef","assertion://weak")));
    assertThatThrownBy(()->PublishedIdentityPolicy.decode(json,resolution,"tenant-a","s","urn:T",mapped)).hasMessage("UDP_GOVERNED_IDENTITY_PROFILE_INVALID");
  }

  @Test @SuppressWarnings("unchecked") void categoryIsClosedAbsoluteAndConsistentAcrossDeclarations() throws Exception {
    var declaration=Map.of("schemeIri","urn:example:scheme","conceptIri","urn:example:kiosk");
    var target=Map.of("classIri","urn:T","category",declaration);
    var plain=Map.of("classIri","urn:T");
    var category=IdentityCategory.forClass(List.of(target,target),"urn:T");
    assertThat(category.conceptIri()).isEqualTo("urn:example:kiosk");
    assertThat(IdentityCategory.forClass(List.of(plain,Map.of("classIri","urn:other","category",declaration)),"urn:T")).isNull();
    for(var order:List.of(List.of(plain,target),List.of(target,plain)))
      assertThatThrownBy(()->IdentityCategory.forClass(order,"urn:T")).hasMessage("UDP_IDENTITY_CATEGORY_CONFLICT");
    Map<String,Object> invalidTarget=new HashMap<>(plain);
    for(Object invalid:Arrays.asList(null,"kiosk",Map.of("conceptIri","urn:example:kiosk"),
        Map.of("conceptIri","relative","schemeIri","urn:example:scheme"),
        Map.of("conceptIri","urn:example:kiosk","schemeIri","bad scheme"),
        Map.of("conceptIri","urn:example:kiosk","schemeIri","urn:example:scheme","extra",true))){
      invalidTarget.put("category",invalid);
      assertThatThrownBy(()->IdentityCategory.forClass(List.of(invalidTarget),"urn:T"))
          .hasMessage("UDP_IDENTITY_CATEGORY_INVALID");
    }
    var definition=proposal();
    var raw=(Map<String,Object>)definition.get("governedIdentity");
    var mapped=Set.of("urn:key");
    var inherited=PublishedIdentityPolicy.decode(json,definition,"tenant-a","s","urn:T",mapped,category);
    var legacy=PublishedIdentityPolicy.decode(json,definition,"tenant-a","s","urn:T",mapped);
    assertThat(ScopedIdentityCandidateRepository.fingerprint(legacy))
        .isEqualTo("5b7786d19aedbfba7caa7cf746659e8ffb7e0d0ca2cb9e624169c04b975204b9");
    assertThat(ScopedIdentityCandidateRepository.fingerprint(inherited))
        .isNotEqualTo(ScopedIdentityCandidateRepository.fingerprint(legacy));
    raw.put("category",declaration);
    assertThat(PublishedIdentityPolicy.decode(json,definition,"tenant-a","s","urn:T",mapped,category)).isEqualTo(inherited);
    for(var other:Arrays.asList(null,new IdentityCategory("urn:example:other",category.conceptIri()),
        new IdentityCategory(category.schemeIri(),"urn:example:pharmacy")))
      assertThatThrownBy(()->PublishedIdentityPolicy.decode(json,definition,"tenant-a","s","urn:T",mapped,other))
          .hasMessage("UDP_IDENTITY_CATEGORY_CONFLICT");
  }

  @Test void aDeclaredUniqueSignalWithoutItsAssertionIsInvalid() throws Exception {
    var resolution=proposal();
    @SuppressWarnings("unchecked") var policy=(Map<String,Object>)resolution.get("governedIdentity");
    @SuppressWarnings("unchecked") var signals=(List<Map<String,Object>>)policy.get("signals");
    signals.getFirst().remove("assertionRef");
    assertThatThrownBy(() -> PublishedIdentityPolicy.decode(json,resolution,"tenant-a","s","urn:T",Set.of("urn:key")))
        .hasMessage("UDP_GOVERNED_IDENTITY_PROFILE_INVALID");
  }

  @Test @SuppressWarnings("unchecked") void aRuleMayRequireAProperSubsetOfMappedSignals() throws Exception {
    var resolution=proposal();
    var policy=(Map<String,Object>)resolution.get("governedIdentity");
    var signals=(List<Map<String,Object>>)policy.get("signals");
    signals.add(new LinkedHashMap<>(Map.of("id","urn:address","semanticRef","urn:address@set-1",
        "comparator","TEXT_V1","excludesOnDisagreement",false,"uniqueWithinScope",false,
        "assertionRef","assertion://address/1")));
    var decoded=PublishedIdentityPolicy.decode(json,resolution,"tenant-a","s","urn:T",
        Set.of("urn:key","urn:address"));
    assertThat(decoded.sufficientRules()).singleElement().satisfies(rule->
        assertThat(rule.signalIds()).containsExactly("urn:key"));
    ((List<String>)((Map<String,Object>)((List<?>)policy.get("sufficientRules")).getFirst())
        .get("signalIds")).set(0,"urn:unknown");
    assertThatThrownBy(()->PublishedIdentityPolicy.decode(json,resolution,"tenant-a","s","urn:T",
        Set.of("urn:key","urn:address"))).hasMessage("UDP_GOVERNED_IDENTITY_PROFILE_INVALID");
  }

  @Test @SuppressWarnings("unchecked") void widePolicyKeepsAllSignalsAndResolvesOneLogicalObject() throws Exception {
    for(int count:List.of(300,400,1024,1025)){
      var resolution=proposal();var raw=(Map<String,Object>)resolution.get("governedIdentity");
      var signals=new ArrayList<Map<String,Object>>();var mapped=new HashSet<String>();
      var values=new HashMap<String,GovernedIdentityEngine.Value>();
      for(int n=0;n<count;n++){
        String id=n==0?"urn:key":"urn:property:"+n;String ref=id+"@set-1";mapped.add(id);
        signals.add(Map.of("id",id,"semanticRef",ref,"comparator","TEXT_V1","excludesOnDisagreement",false,"uniqueWithinScope",false,"assertionRef","assertion://wide/"+n));
        values.put(id,new GovernedIdentityEngine.Value(ref,"value-"+n,"fixture://wide"));
      }
      raw.put("signals",signals);
      if(count==1025){
        assertThatThrownBy(()->PublishedIdentityPolicy.decode(json,resolution,"tenant-a","s","urn:T",mapped)).hasMessage("UDP_GOVERNED_IDENTITY_PROFILE_INVALID");
        continue;
      }
      var policy=PublishedIdentityPolicy.decode(json,resolution,"tenant-a","s","urn:T",mapped);
      assertThat(policy.signals()).hasSize(count);
      UUID object=UUID.randomUUID();
      var candidate=new GovernedIdentityEngine.Candidate(object,"tenant-a","urn:T",values);
      var subject=new GovernedIdentityEngine.Subject("tenant-a","urn:T","s",values);
      var candidates=new GovernedIdentityEngine.Candidates(policy.ref(),policy.version(),"tenant-a","urn:T","fixture://coverage",true,List.of(candidate));
      var decision=new GovernedIdentityEngine().decide(policy,subject,candidates);
      assertThat(decision.outcome()).isEqualTo(GovernedIdentityEngine.Outcome.MATCH);
      assertThat(decision.objectId()).isEqualTo(object);
      assertThat(decision.assessments()).singleElement().satisfies(a->assertThat(a.evidence()).hasSize(count));
    }
  }

  @SuppressWarnings("unchecked") private Map<String,Object> proposal() throws Exception {
    try(var input=getClass().getResourceAsStream("/identity-governed-proposal-v1.json")) {
      return json.readValue(input,Map.class);
    }
  }
}

