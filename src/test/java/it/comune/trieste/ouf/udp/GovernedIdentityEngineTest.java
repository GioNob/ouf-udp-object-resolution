package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.udp.GovernedIdentityEngine.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GovernedIdentityEngineTest {
  private final GovernedIdentityEngine engine=new GovernedIdentityEngine();
  private static final String NAME="urn:name@set-1", ADDRESS="urn:address@set-1",
      CATEGORY="urn:category@set-1", GEO="urn:geo@set-1";
  private static final List<Signal> SIGNALS=List.of(
      new Signal("name",NAME,ComparatorKind.TEXT_V1,false,false,"policy://comparison/name" ),
      new Signal("address",ADDRESS,ComparatorKind.TEXT_V1,false,false,"policy://comparison/address" ),
      new Signal("category",CATEGORY,ComparatorKind.CONCEPT,false,false,"policy://comparison/category" ),
      new Signal("geo",GEO,ComparatorKind.TEXT_V1,false,false,"policy://comparison/geo" ));
  private static final Policy POLICY=new Policy("policy://objects","1","tenant","Place","source",10,true,
      SIGNALS,List.of(new SufficientRule("whole-record",Set.of("name","address","category","geo"),"policy://whole-record/1")));

  @Test void completeFourFieldEqualityMatchesWithoutStableIdentifier() {
    UUID id=UUID.randomUUID();
    var subject=subject("Aurora","Via Roma 1","cinema","45.1,13.1");
    var exact=candidate(id," aurora ","Via Roma 1","cinema","45.1,13.1");
    var partial=candidate(UUID.randomUUID(),"Aurora","Via Roma 1","cinema","45.1,13.2");
    var result=engine.decide(POLICY,subject,rows(exact,partial));
    assertThat(result.outcome()).isEqualTo(Outcome.MATCH);
    assertThat(result.objectId()).isEqualTo(id);
    assertThat(result.reason()).isEqualTo("COMPLETE_CANONICAL_EQUALITY");
    assertThat(engine.decide(POLICY,subject,rows(partial,exact)).objectId()).isEqualTo(id);
  }

  @Test void changedGeoreferenceOrAddressRemainsAnUncertainCase() {
    var subject=subject("Aurora","Via Roma 1","cinema","entrance");
    var different=candidate(UUID.randomUUID(),"Aurora","Via Roma 1","cinema","building-centre");
    var result=engine.decide(POLICY,subject,rows(different));
    assertThat(result.outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
    assertThat(result.assessments()).singleElement().satisfies(a -> {
      assertThat(a.evidence()).filteredOn(e->e.kind()==EvidenceKind.AGREE).hasSize(3);
      assertThat(a.excluded()).isFalse();
    });
  }

  @Test void missingFieldOrSemanticVersionCannotCompleteEquality() {
    var values=new HashMap<>(subject("Aurora","Via Roma 1","cinema","entrance").values());
    values.remove("geo");
    var candidate=candidate(UUID.randomUUID(),"Aurora","Via Roma 1","cinema","entrance");
    assertThat(engine.decide(POLICY,new Subject("tenant","Place","source",values),rows(candidate)).outcome())
        .isEqualTo(Outcome.REVIEW_REQUIRED);
    values.put("geo",new Value("urn:geo@set-2","entrance","fixture://subject"));
    assertThat(engine.decide(POLICY,new Subject("tenant","Place","source",values),rows(candidate)).outcome())
        .isEqualTo(Outcome.REVIEW_REQUIRED);
  }

  @Test void duplicateExactRecordsRequireHumanAndEmptyScopeCanCreate() {
    var subject=subject("Aurora","Via Roma 1","cinema","entrance");
    assertThat(engine.decide(POLICY,subject,rows()).outcome()).isEqualTo(Outcome.NEW_OBJECT);
    assertThat(engine.decide(POLICY,subject,rows(candidate(UUID.randomUUID(),"Aurora","Via Roma 1","cinema","entrance"),
        candidate(UUID.randomUUID(),"Aurora","Via Roma 1","cinema","entrance"))).outcome())
        .isEqualTo(Outcome.REVIEW_REQUIRED);
    var incomplete=new Candidates(POLICY.ref(),POLICY.version(),"tenant","Place",null,false,List.of());
    assertThat(engine.decide(POLICY,subject,incomplete).outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
  }

  @Test void subsetAndUniqueAssertionsCannotAuthorizeAutomaticMatch() {
    assertThatThrownBy(()->new Policy("p","1","tenant","Place","source",10,true,SIGNALS,
        List.of(new SufficientRule("subset",Set.of("name"),"assertion://subset"))))
        .hasMessage("UDP_IDENTITY_POLICY_INVALID");
    var unique=List.of(new Signal("name",NAME,ComparatorKind.TEXT_V1,false,true,"assertion://unique"));
    assertThatThrownBy(()->new Policy("p","1","tenant","Place","source",10,true,unique,
        List.of(new SufficientRule("unique",Set.of("name"),"assertion://unique"))))
        .hasMessage("UDP_IDENTITY_POLICY_INVALID");
  }

  private static Subject subject(String n,String a,String c,String g){return new Subject("tenant","Place","source",values(n,a,c,g));}
  private static Candidate candidate(UUID id,String n,String a,String c,String g){return new Candidate(id,"tenant","Place",values(n,a,c,g));}
  private static Map<String,Value> values(String n,String a,String c,String g){return Map.of("name",new Value(NAME,n,"fixture://subject"),
      "address",new Value(ADDRESS,a,"fixture://subject"),"category",new Value(CATEGORY,c,"fixture://subject"),
      "geo",new Value(GEO,g,"fixture://subject"));}
  private static Candidates rows(Candidate... candidates){return new Candidates(POLICY.ref(),POLICY.version(),"tenant","Place",
      "index://snapshot",true,List.of(candidates));}
}
