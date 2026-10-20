package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class LinkedPropertyReconcilerTest {
  @Test void publishedXsdStringRetainsItsContractAndRejectsNonStringValues() throws Exception {
    String iri="http://www.w3.org/2001/XMLSchema#string";
    for(String normalizer:List.of("EXACT_TYPED","NFC_TRIM")){
      var r=new UdpPorts.PropertyRule("name","name",iri,"RESTRICTED",List.of(),new LinkedPropertyReconciler.Rule(LinkedPropertyReconciler.POLICY,"SINGLE",normalizer,1,"SOURCE_INTERVAL",null,null));
      var json=new com.fasterxml.jackson.databind.ObjectMapper();
      var restored=json.readValue(json.writeValueAsBytes(r),UdpPorts.PropertyRule.class);
      assertThat(restored.datatype()).isEqualTo(iri);
      var merged=result(restored,null,row("api","a","Name",restored),row("file","b","Name",restored));
      assertThat(merged.value()).isEqualTo("Name");assertThat(merged.members().getFirst().contributions()).hasSize(2);
      assertThat(result(restored,"old",row("source","a","A",restored),row("source","b","B",restored)).status()).isEqualTo("CONFLICT");
      assertThat(LinkedPropertyReconciler.normalize(" A ",restored)).isEqualTo(normalizer.equals("EXACT_TYPED")?" A ":"A");
      assertThatThrownBy(()->LinkedPropertyReconciler.normalize(42,restored)).hasMessage("UDP_PROPERTY_CONTRIBUTION_INVALID");
    }
    for(String invalid:List.of("xsd:string","https://www.w3.org/2001/XMLSchema#string","http://www.w3.org/2001/XMLSchema#dateTime"))
      assertThatThrownBy(()->new UdpPorts.PropertyRule("name","name",invalid,"OPEN",List.of(),new LinkedPropertyReconciler.Rule(LinkedPropertyReconciler.POLICY,"SINGLE","EXACT_TYPED",1,"UNBOUNDED",null,null))).hasMessage("UDP_PROPERTY_RECONCILIATION_PROFILE_UNSUPPORTED");
  }

  @Test void publishedDecimalComparesNumericValuesAndRetainsThePublishedDatatype() throws Exception {
    String iri="http://www.w3.org/2001/XMLSchema#decimal";
    var r=new UdpPorts.PropertyRule("name","name",iri,"RESTRICTED",List.of(),
      new LinkedPropertyReconciler.Rule(LinkedPropertyReconciler.POLICY,"SINGLE","EXACT_TYPED",1,"UNBOUNDED",null,null));
    var json=new com.fasterxml.jackson.databind.ObjectMapper();
    var restored=json.readValue(json.writeValueAsBytes(r),UdpPorts.PropertyRule.class);
    assertThat(restored.datatype()).isEqualTo(iri);
    assertThat(LinkedPropertyReconciler.normalize(new java.math.BigDecimal("12.3400"),restored)).isEqualTo(new java.math.BigDecimal("12.34"));
    var merged=result(restored,null,row("api","a",new java.math.BigDecimal("12.34"),restored),row("file","b",new java.math.BigDecimal("12.3400"),restored));
    assertThat(merged.members()).hasSize(1);assertThat(merged.members().getFirst().contributions()).hasSize(2);
    assertThatThrownBy(()->LinkedPropertyReconciler.normalize("12.34",restored)).hasMessage("UDP_PROPERTY_CONTRIBUTION_INVALID");
  }
  @Test void publishedDateRequiresCanonicalCalendarValueAndDoesNotNormalizeTimezones() {
    String iri="http://www.w3.org/2001/XMLSchema#date";
    var r=new UdpPorts.PropertyRule("name","name",iri,"RESTRICTED",List.of(),
      new LinkedPropertyReconciler.Rule(LinkedPropertyReconciler.POLICY,"SINGLE","EXACT_TYPED",1,"UNBOUNDED",null,null));
    assertThat(LinkedPropertyReconciler.normalize("2024-02-29",r)).isEqualTo("2024-02-29");
    assertThat(result(r,null,row("api","a","2024-02-29",r),row("file","b","2024-02-29",r)).members()).hasSize(1);
    for(String value:List.of("2023-02-29","2024-04-31","2024-2-29","2024-02-29Z","29/02/2024","0000-01-01"))
      assertThatThrownBy(()->LinkedPropertyReconciler.normalize(value,r)).hasMessage("UDP_PROPERTY_CONTRIBUTION_INVALID");
    assertThatThrownBy(()->new UdpPorts.PropertyRule("name","name",iri,"OPEN",List.of(),
      new LinkedPropertyReconciler.Rule(LinkedPropertyReconciler.POLICY,"SINGLE","NFC_TRIM",1,"UNBOUNDED",null,null)))
      .hasMessage("UDP_PROPERTY_RECONCILIATION_PROFILE_UNSUPPORTED");
  }

  static final OffsetDateTime NOW=OffsetDateTime.parse("2026-10-17T12:00:00Z");
  @Test @SuppressWarnings("unchecked") void expiredRevisionNeverResurrectsSupersededValueAndFutureRevisionWaits(){
    var r=rule("SINGLE","EXACT_TYPED",List.of(),"revision",null);
    var old=envelope("api","same","old");((Map<String,Object>)old.get("canonicalPayload")).put("revision",1);
    var next=envelope("api","same","next");((Map<String,Object>)next.get("canonicalPayload")).put("revision",2);
    var identity=(Map<String,Object>)next.get("sourceIdentity");identity.put("validTo","2026-10-16T00:00:00Z");
    assertThat(result(r,"old",LinkedPropertyReconciler.incoming("old",old,r),LinkedPropertyReconciler.incoming("next",next,r)).status()).isEqualTo("NO_VALID_CONTRIBUTION");
    identity.remove("validTo");identity.put("validFrom","2026-10-18T00:00:00Z");
    assertThat(result(r,null,LinkedPropertyReconciler.incoming("old",old,r),LinkedPropertyReconciler.incoming("next",next,r)).value()).isEqualTo("old");
  }
  @Test @SuppressWarnings("unchecked") void servingValidityUsesEveryMemberAndNeverTreatsMissingAsDeletion(){
    var r=rule("MULTI","EXACT_TYPED",List.of(),null,null);var a=envelope("api","a","A");var b=envelope("layer","b","B");
    ((Map<String,Object>)b.get("sourceIdentity")).put("validTo","2026-10-18T00:00:00Z");
    var value=result(r,null,LinkedPropertyReconciler.incoming("a",a,r),LinkedPropertyReconciler.incoming("b",b,r));
    String evidence=LinkedPropertyReconciler.write(Map.of("rule",r,"members",value.members()));
    assertThat(LinkedPropertyAccess.validAt(evidence,NOW)).isTrue();
    assertThat(LinkedPropertyAccess.validAt(evidence,NOW.plusDays(2))).isFalse();
    assertThat(value.members()).hasSize(2);
  }
  static UdpPorts.PropertyRule rule(String cardinality,String normalizer,List<String> order,String revision,String deletion){return new UdpPorts.PropertyRule("name","name","string","OPEN",order,new LinkedPropertyReconciler.Rule(LinkedPropertyReconciler.POLICY,cardinality,normalizer,1,"SOURCE_INTERVAL",revision,deletion));}
  static Map<String,Object> envelope(String source,String record,Object value){var payload=new LinkedHashMap<String,Object>();payload.put("name",value);return new LinkedHashMap<>(Map.of("sourceIdentity",new LinkedHashMap<>(Map.of("sourceId",source,"sourceObjectId",record,"typeCode","FEATURE")),"canonicalPayload",payload,"ingestionRunId","run","lineageId","lineage","contractRefs",Map.of("bundleRef","bundle:1")));}
  static Map<String,Object> row(String source,String record,Object value,UdpPorts.PropertyRule rule){return LinkedPropertyReconciler.incoming(source+record,envelope(source,record,value),rule);}
  static LinkedPropertyReconciler.Result result(UdpPorts.PropertyRule rule,Object current,Map<String,Object>... rows){return LinkedPropertyReconciler.reconcile(List.of(rows),rule,current,NOW,null);}
  @Test void absentNullAndBlankAreNotDeletion(){var r=rule("SINGLE","EXACT_TYPED",List.of(),null,null);for(Object value:Arrays.asList(null,""," \t"))assertThat(LinkedPropertyReconciler.incoming("h",envelope("api","id",value),r)).isNull();}
  @Test void differentRecordsFromSameApiOrFileDoNotHideEachOther(){var r=rule("SINGLE","EXACT_TYPED",List.of(),null,null);var result=result(r,"old",row("source","a","First",r),row("source","b","Second",r));assertThat(result.status()).isEqualTo("CONFLICT");assertThat(result.value()).isEqualTo("old");assertThat(result.candidates()).hasSize(2);}
  @Test void normalizedEqualityPreservesOriginalsAndEveryProvenance(){var r=rule("SINGLE","NFC_TRIM",List.of(),null,null);var result=result(r,null,row("api","a"," cafe\u0301 ",r),row("file","b","café",r));assertThat(result.value()).isEqualTo("café");assertThat(result.members()).hasSize(1);assertThat(result.members().getFirst().contributions()).extracting(x->x.get("originalValue")).containsExactlyInAnyOrder(" cafe\u0301 ","café");}
  @Test void exactDoesNotInventCaseOrWhitespaceEquivalence(){var r=rule("SINGLE","EXACT_TYPED",List.of(),null,null);assertThat(result(r,null,row("api","a","A",r),row("file","b"," A ",r)).status()).isEqualTo("CONFLICT");}
  @Test void sourcePriorityBeatsArrivalAndExpiredSourceFallsBack(){var r=rule("SINGLE","EXACT_TYPED",List.of("registry","survey"),null,null);var a=row("registry","a","official",r);var b=row("survey","b","later",r);assertThat(result(r,null,b,a).value()).isEqualTo("official");var e=envelope("registry","a","expired");((Map<String,Object>)e.get("sourceIdentity")).put("validTo","2020-01-01T00:00:00Z");assertThat(result(r,null,LinkedPropertyReconciler.incoming("expired",e,r),b).value()).isEqualTo("later");}
  @Test void multivalueUnionIsExplicitAndNeverSplitsText(){var r=rule("MULTI","NFC_TRIM",List.of(),null,null);var result=result(r,null,row("api","a",List.of(" A ","B"),r),row("geopackage","b",List.of("A","C,D"),r));assertThat((List<Object>)result.value()).containsExactlyInAnyOrderElementsOf(List.of("A","B","C,D"));assertThat(result.members().stream().filter(m->m.value().equals("A")).findFirst().orElseThrow().contributions()).hasSize(2);}
  @Test void revisionContractSupersedesOnlySameRecord(){var r=rule("SINGLE","EXACT_TYPED",List.of(),"revision",null);var a=envelope("layer","feature-1","old");((Map<String,Object>)a.get("canonicalPayload")).put("revision",1);var b=envelope("layer","feature-1","new");((Map<String,Object>)b.get("canonicalPayload")).put("revision",2);assertThat(result(r,null,LinkedPropertyReconciler.incoming("a",a,r),LinkedPropertyReconciler.incoming("b",b,r)).value()).isEqualTo("new");var c=envelope("layer","feature-2","other");((Map<String,Object>)c.get("canonicalPayload")).put("revision",99);assertThat(result(r,null,LinkedPropertyReconciler.incoming("b",b,r),LinkedPropertyReconciler.incoming("c",c,r)).status()).isEqualTo("CONFLICT");}
  @Test void deletionRequiresExplicitContractAuthorityAndRevision(){var r=rule("SINGLE","EXACT_TYPED",List.of("registry"),"revision","deleteName");var a=envelope("registry","a","old");((Map<String,Object>)a.get("canonicalPayload")).put("revision",1);var d=envelope("registry","a",null);((Map<String,Object>)d.get("canonicalPayload")).putAll(Map.of("revision",2,"deleteName",true));assertThat(result(r,"old",LinkedPropertyReconciler.incoming("a",a,r),LinkedPropertyReconciler.incoming("d",d,r)).deleted()).isTrue();var bad=envelope("other","a",null);((Map<String,Object>)bad.get("canonicalPayload")).putAll(Map.of("revision",2,"deleteName",true));assertThatThrownBy(()->LinkedPropertyReconciler.incoming("bad",bad,r)).hasMessage("UDP_PROPERTY_CONTRIBUTION_INVALID");}
  @Test void observationTimeAloneNeverSupersedes(){var r=rule("SINGLE","EXACT_TYPED",List.of(),null,null);var a=envelope("api","same","A");var b=envelope("api","same","B");((Map<String,Object>)a.get("sourceIdentity")).put("observedAt","2020-01-01T00:00:00Z");((Map<String,Object>)b.get("sourceIdentity")).put("observedAt","2026-01-01T00:00:00Z");assertThat(result(r,null,LinkedPropertyReconciler.incoming("a",a,r),LinkedPropertyReconciler.incoming("b",b,r)).status()).isEqualTo("CONFLICT");}
  @Test void unsupportedNormalizerOrGeometryCannotBeSilentlyActivated(){assertThatThrownBy(()->rule("SINGLE","SYNONYMS",List.of(),null,null)).isInstanceOf(IllegalArgumentException.class);var r=new UdpPorts.PropertyRule("shape","shape","object","OPEN",List.of(),new LinkedPropertyReconciler.Rule(LinkedPropertyReconciler.POLICY,"SINGLE","EXACT_TYPED",1,"UNBOUNDED",null,null));assertThatThrownBy(()->LinkedPropertyReconciler.normalize(Map.of("type","Point","coordinates",List.of(1,2)),r)).hasMessage("UDP_PROPERTY_CONTRIBUTION_INVALID");}
  @Test void coalescingRequiresAuthorizationForEverySupportingContribution(){var r=rule("MULTI","NFC_TRIM",List.of(),null,null);var a=row("public","a","A",r);var b=row("private","b","B",r);b.put("access_label","RESTRICTED");var result=result(r,null,a,b);String evidence=LinkedPropertyReconciler.write(Map.of("members",result.members()));var auth=new ServingAuthorizationContext("HUMAN","user","default",Set.of("urban.object.read"),Set.of("OPEN"),"auth","c");assertThat(LinkedPropertyAccess.permits(evidence,UUID.randomUUID(),UUID.randomUUID(),"name","urban.object.read",auth)).isFalse();}
}
