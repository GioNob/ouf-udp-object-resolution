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

  @Test void exactCandidateWithAnotherPlausibleObjectRequiresReview() {
    UUID id=UUID.randomUUID();
    var subject=subject("Aurora","Via Roma 1","cinema","45.1,13.1");
    var exact=candidate(id," aurora ","Via Roma 1","cinema","45.1,13.1");
    var partial=candidate(UUID.randomUUID(),"Aurora","Via Roma 1","cinema","45.1,13.2");
    var result=engine.decide(POLICY,subject,rows(exact,partial));
    assertThat(result.outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
    assertThat(result.assessments()).extracting(Assessment::objectId).containsExactlyInAnyOrder(id,partial.objectId());
    assertThat(engine.decide(POLICY,subject,rows(partial,exact)).outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
    assertThat(engine.decide(POLICY,subject,rows(exact)).objectId()).isEqualTo(id);
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

  @Test void allExposedFieldsMatchEvenWhenStoredObjectHasAdditionalFields() {
    var values=new HashMap<>(subject("Aurora","Via Roma 1","cinema","entrance").values());
    values.remove("geo");
    values.remove("category");
    var candidateValues=new HashMap<>(candidate(UUID.randomUUID(),"Aurora","Via Roma 1","cinema","building-centre").values());
    candidateValues.put("unmapped",new Value("urn:unmapped@set-1","additional","fixture://candidate"));
    var candidate=new Candidate(UUID.randomUUID(),"tenant","Place",candidateValues);
    var incoming=new Subject("tenant","Place","source",values);
    var matched=engine.decide(POLICY,incoming,rows(candidate));
    assertThat(matched.outcome()).isEqualTo(Outcome.MATCH);
    assertThat(matched.objectId()).isEqualTo(candidate.objectId());
    assertThat(matched.assessments()).singleElement().satisfies(a -> {
      assertThat(a.evidence()).extracting(Evidence::signalId).containsExactly("address","category","geo","name","unmapped");
      assertThat(a.evidence()).filteredOn(e->e.kind()==EvidenceKind.AGREE).hasSize(2);
    });
    var conflicting=new Candidate(UUID.randomUUID(),"tenant","Place",Map.of(
        "name",values.get("name"),
        "address",new Value(ADDRESS,"Via Milano 9","fixture://candidate")));
    assertThat(engine.decide(POLICY,incoming,rows(conflicting)).outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
    assertThat(engine.decide(POLICY,incoming,rows(candidate,
        candidate(UUID.randomUUID(),"Aurora","Via Roma 1","shop","elsewhere"))).outcome())
        .isEqualTo(Outcome.REVIEW_REQUIRED);
  }

  @Test void incomingObjectMayHaveMoreFieldsThanTheExistingObject() {
    var incoming=subject("Aurora","Via Roma 1","cinema","entrance");
    UUID id=UUID.randomUUID();
    var existing=new Candidate(id,"tenant","Place",Map.of(
        "name",new Value(NAME," aurora ","fixture://candidate"),
        "address",new Value(ADDRESS,"Via Roma 1","fixture://candidate")));
    assertThat(engine.decide(POLICY,incoming,rows(existing)).objectId()).isEqualTo(id);
    var conflicting=new Candidate(UUID.randomUUID(),"tenant","Place",Map.of(
        "name",new Value(NAME,"Aurora","fixture://candidate"),
        "address",new Value(ADDRESS,"Via Milano 9","fixture://candidate")));
    assertThat(engine.decide(POLICY,incoming,rows(conflicting)).outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
    var crossed=new Candidate(UUID.randomUUID(),"tenant","Place",Map.of(
        "name",new Value(NAME,"Aurora","fixture://candidate"),
        "category",new Value(CATEGORY,"cinema","fixture://candidate"),
        "unmapped",new Value("urn:unmapped@set-1","something","fixture://candidate")));
    assertThat(engine.decide(POLICY,incoming,rows(crossed)).outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
  }

  @Test void wrongSemanticPublicationAndEmptyObservationCannotMatchOrCreate() {
    var values=new HashMap<>(subject("Aurora","Via Roma 1","cinema","entrance").values());
    values.put("geo",new Value("urn:geo@set-2","entrance","fixture://subject"));
    assertThatThrownBy(()->engine.decide(POLICY,new Subject("tenant","Place","source",values),rows()))
        .hasMessage("UDP_IDENTITY_POLICY_INVALID");
    var empty=new Subject("tenant","Place","source",Map.of());
    assertThat(engine.decide(POLICY,empty,rows()).outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
    assertThat(engine.decide(POLICY,empty,rows()).reason()).isEqualTo("NO_EXPOSED_CANONICAL_FIELDS");
  }

  @Test void unparseableCanonicalValueStaysUncertain() {
    var signal=new Signal("amount","urn:amount@set-1",ComparatorKind.DECIMAL_V1,false,false,"policy://amount");
    var policy=new Policy("policy://decimal","1","tenant","Place","source",10,true,List.of(signal),
        List.of(new SufficientRule("whole-record",Set.of("amount"),"policy://whole-record/decimal")));
    var incoming=new Subject("tenant","Place","source",Map.of("amount",
        new Value("urn:amount@set-1","10","fixture://subject")));
    var existing=new Candidate(UUID.randomUUID(),"tenant","Place",Map.of("amount",
        new Value("urn:amount@set-1","invalid-decimal","fixture://candidate")));
    var retrieved=new Candidates(policy.ref(),policy.version(),"tenant","Place","index://snapshot",true,List.of(existing));
    assertThat(engine.decide(policy,incoming,retrieved).outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
  }

  @Test void entirelyDifferentComparableFieldsEstablishDistinctObjects() {
    var incoming=subject("Aurora","Via Roma 1","cinema","entrance");
    var other=candidate(UUID.randomUUID(),"Boreale","Via Milano 9","shop","other-place");
    var decision=engine.decide(POLICY,incoming,rows(other));
    assertThat(decision.outcome()).isEqualTo(Outcome.NEW_OBJECT);
    assertThat(decision.reason()).isEqualTo("ALL_CANDIDATES_DISTINCT");
    assertThat(decision.assessments()).singleElement().satisfies(a->assertThat(a.excluded()).isTrue());
    var partlySimilar=candidate(UUID.randomUUID(),"Aurora","Via Milano 9","shop","other-place");
    assertThat(engine.decide(POLICY,incoming,rows(other,partlySimilar)).outcome())
        .isEqualTo(Outcome.REVIEW_REQUIRED);
    var exact=candidate(UUID.randomUUID(),"Aurora","Via Roma 1","cinema","entrance");
    assertThat(engine.decide(POLICY,incoming,rows(exact,partlySimilar)).outcome())
        .isEqualTo(Outcome.REVIEW_REQUIRED);
    var noCreate=new Policy(POLICY.ref(),POLICY.version(),"tenant","Place","source",10,false,
        SIGNALS,POLICY.sufficientRules());
    assertThat(engine.decide(noCreate,incoming,rows(other)).outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
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
