package it.comune.trieste.ouf.udp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;

class ValueTypesContractTest {
  private static final String SET = "00000000-0000-0000-0000-000000000001";
  private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private static final String PRECISE = "1234567890123456789012345678901234567890.00000000000000000001";
  private static void validate(CanonicalValueType type,Object value) {
    type.validate(value,(crs,geo)->{}); // PostGIS gate is exercised by the serving runtime test.
  }
  private static void rejected(CanonicalValueType type,Object value) {
    assertEquals("UDP_DATA_VALUE_NONCANONICAL",
        assertThrows(IllegalArgumentException.class,()->validate(type,value)).getMessage());
  }
  @Test void d084ContractDigestAndComparators() throws Exception {
    byte[] bytes=Files.readAllBytes(Path.of("contracts/ouf-value-types-v1.json"));
    assertEquals("c046646c3030f1bcf4460741bbd09351f9bb501cb054d8ed45ce2e7d98dd4247",digest(bytes));
    var contract=JSON.readTree(bytes);
    assertEquals("ouf.value-types.v1",contract.get("schema").asText());
    assertEquals(CanonicalValueType.BASE.size(),contract.get("baseTypes").size());
    for(var entry:contract.get("baseTypes")){
      String type=entry.get("id").asText();
      assertEquals(type,CanonicalValueType.BASE.get(entry.get("datatype").asText()));
      assertEquals(contract.get("identityComparators").get(type).asText(),
          CanonicalValueType.base(type,false).comparator().name());
    }
  }
  @Test void d084BaseRepresentationsAndLists() {
    record Sample(String type,Object canonical,List<?> invalid){}
    var point=Map.of("crs","EPSG:4326","geoJson",Map.of("type","Point","coordinates",
        List.of(new BigDecimal("13.75000000000000001"),new BigDecimal("45.6495"))));
    var samples=List.of(
        new Sample("string","",List.of(42,true)),
        new Sample("integer",PRECISE.split("\\.")[0],List.of(2,"+1","-0","01","1e2","1.0")),
        new Sample("decimal",PRECISE,List.of(1,"1.0","1.20","1e3","+1","-0","00.1")),
        new Sample("boolean",false,List.of("false",0)),
        new Sample("date","2000-02-29",List.of("2023-02-29","22/11/2026","2026-2-01")),
        new Sample("datetime","2026-11-22T01:02:03.123456789Z",List.of("2026-11-22T02:02:03+01:00","2026-11-22T01:02:03","2026-11-31T01:02:03Z")),
        new Sample("concept","https://example.org/città/uno",List.of("relative/path","broken space:1")),
        new Sample("geometry",point,List.of(Map.of("type","Point","coordinates",List.of(13,45)),
            Map.of("crs","EPSG:4326","geoJson",Map.of("type","Point","coordinates",List.of("13",45))),
            Map.of("crs","EPSG:4326","geoJson",Map.of("type","Point","coordinates",List.of(13,new BigDecimal("90.000000000000000001")))))));
    for(var sample:samples){
      var scalar=CanonicalValueType.base(sample.type(),false);
      validate(scalar,sample.canonical());
      rejected(scalar,null);
      rejected(scalar,List.of(sample.canonical()));
      for(Object invalid:sample.invalid())rejected(scalar,invalid);
      var list=CanonicalValueType.base(sample.type(),true);
      validate(list,List.of(sample.canonical(),sample.canonical()));
      rejected(list,List.of(sample.canonical(),sample.invalid().getFirst()));
      rejected(list,List.of());
      rejected(list,sample.canonical());
    }
    var split=new PublishedValueTypes().composition(Map.of("kind","CONSTRUCTOR","constructorId","LIST_SPLIT_V1",
        "params",Map.of("itemReader","DECIMAL_COMMA_V1","separator",";")),"list");
    validate(split,List.of(PRECISE,"0"));
    assertEquals("JSON_V1",split.comparator().name());
  }
  @Test void d084PublishedNestedValuesAndConstraints() {
    var registry=new PublishedValueTypes();
    bind(registry,"urn:detail",List.of(field("amount","decimal","ONE",true,Map.of("minInclusive","1","maxInclusive",PRECISE))));
    bind(registry,"urn:record",List.of(field("items","urn:detail@"+SET,"MANY",true,Map.of()),
        field("title","string","ONE",false,Map.of("maxLength",1))));
    var shape=structured(registry,"urn:record");
    assertEquals(shape,registry.pinned("urn:record"));
    var items=List.of(Map.of("amount",PRECISE));
    validate(shape,Map.of("items",items,"title","🌿"));
    validate(shape,Map.of("items",items));
    assertEquals("JSON_V1",shape.comparator().name());
    for(Object invalid:List.of(Map.of(),Map.of("title","x"),Map.of("items",List.of()),Map.of("items",Map.of("amount","1")),
        Map.of("items",items,"unknown",true),Map.of("items",items,"title","ab"),
        Map.of("items",List.of(Map.of("amount",2))),Map.of("items",List.of(Map.of("amount","0"))),
        Map.of("items",List.of(Map.of("amount",PRECISE+"1")))))rejected(shape,invalid);
  }
  @Test void d084PublishedVocabularyAndTemporalBounds(){
    var registry=new PublishedValueTypes();
    registry.bind(Map.of("semanticId","urn:colour:red","publicationSetId",SET),
        Map.of("artifact_type","CONCEPT","definition",Map.of("vocabularyRef","urn:colour")),ValueTypesContractTest::hash);
    bind(registry,"urn:reading",List.of(
        field("code","concept","ONE",true,Map.of("vocabularyRef","urn:colour")),
        field("day","date","ONE",false,Map.of("minInclusive","2024-02-29")),
        field("time","datetime","MANY",false,Map.of("maxInclusive","2024-02-29T00:00:00.1Z"))));
    var shape=structured(registry,"urn:reading");
    assertEquals(false,shape.hasGeometry(Map.of("code","urn:colour:red")));
    validate(shape,Map.of("code","urn:colour:red","day","2024-02-29","time",List.of("2024-02-29T00:00:00Z")));
    assertEquals("UDP_CONCEPT_MEMBERSHIP_UNVERIFIED",assertThrows(IllegalArgumentException.class,
        ()->validate(shape,Map.of("code","urn:colour:blue"))).getMessage());
    rejected(shape,Map.of("code","urn:colour:red","day","2024-02-28"));
    rejected(shape,Map.of("code","urn:colour:red","time",List.of("2024-02-29T00:00:00.100000001Z")));
  }
  @Test void d084RegistryIntegrityAndDefinitionLimits() {
    var registry=new PublishedValueTypes();
    var definition=Map.of("components",List.of(field("name","string","ONE",true,Map.of())));
    var pin=pin("urn:root",definition,"0".repeat(64));
    var actual=Map.<String,Object>of("artifact_type","DATATYPE","status","ACTIVE","definition",definition,"definitionSha256","0".repeat(64));
    assertThrows(IllegalArgumentException.class,()->registry.bind(pin,actual,ValueTypesContractTest::hash));
    var mismatched=new HashMap<>(actual);
    mismatched.put("definition",Map.of());
    assertThrows(IllegalArgumentException.class,()->registry.bind(pin,mismatched,ValueTypesContractTest::hash));
    bind(registry,"urn:cycle",List.of(field("child","urn:cycle@"+SET,"ONE",false,Map.of())));
    assertThrows(IllegalArgumentException.class,()->structured(registry,"urn:cycle"));
    for(int i=5;i>=1;i--)bind(registry,"urn:depth:"+i,List.of(field("child",i==5?"integer":"urn:depth:"+(i+1)+"@"+SET,"ONE",false,Map.of())));
    structured(registry,"urn:depth:2");
    assertThrows(IllegalArgumentException.class,()->structured(registry,"urn:depth:1"));
    var fields=new ArrayList<Map<String,Object>>();
    for(int i=0;i<32;i++)fields.add(field("k"+i,"integer","ONE",false,Map.of()));
    bind(registry,"urn:wide",fields);
    var children=new ArrayList<Map<String,Object>>();
    for(int i=0;i<9;i++)children.add(field("k"+i,"urn:wide@"+SET,"ONE",false,Map.of()));
    bind(registry,"urn:many",children);
    assertThrows(IllegalArgumentException.class,()->structured(registry,"urn:many"));
    fields.add(field("extra","integer","ONE",false,Map.of()));
    bind(registry,"urn:tooWide",fields);
    assertThrows(IllegalArgumentException.class,()->structured(registry,"urn:tooWide"));
    assertThrows(IllegalArgumentException.class,()->structured(registry,"urn:missing"));
  }
  @Test void d084IdentityPreservesPrecisionAndArrayOrder() {
    var json=GovernedIdentityEngine.ComparatorKind.JSON_V1;
    String a="{\"z\":[1,2],\"a\":"+PRECISE+"}";
    String b="{\"a\":"+PRECISE+",\"z\":[1,2]}";
    assertEquals(GovernedIdentityEngine.normalize(json,a),GovernedIdentityEngine.normalize(json,b));
    assertNotEquals(GovernedIdentityEngine.normalize(json,a),GovernedIdentityEngine.normalize(json,b.replace("[1,2]","[2,1]")));
    assertNotEquals(GovernedIdentityEngine.normalize(json,a),GovernedIdentityEngine.normalize(json,b.replace(PRECISE,PRECISE+"1")));
    assertEquals(PRECISE,GovernedIdentityEngine.normalize(GovernedIdentityEngine.ComparatorKind.DECIMAL_V1,PRECISE));
  }
  private static CanonicalValueType structured(PublishedValueTypes types,String iri){
    return types.composition(Map.of("kind","STRUCTURED","typeRef",iri+"@"+SET),iri);
  }
  private static Map<String,Object> field(String name,String type,String cardinality,boolean required,Map<String,Object> constraints){
    return Map.of("name",name,"type",type,"cardinality",cardinality,"required",required,"constraints",constraints);
  }
  private static void bind(PublishedValueTypes types,String iri,List<?> fields){
    var def=Map.of("components",List.copyOf(fields));
    String digest=hash(def).substring(7);
    types.bind(pin(iri,def,digest),Map.of("artifact_type","DATATYPE","status","ACTIVE","definition",def,"definitionSha256",digest),
        ValueTypesContractTest::hash);
  }
  private static Map<String,Object> pin(String iri,Map<?,?> def,String digest){
    return Map.of("semanticId",iri,"publicationSetId",SET,"definition",def,"definitionSha256",digest);
  }
  private static String digest(byte[] bytes)throws Exception{
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
  private static String hash(Object value){
    try{return "sha256:"+digest(JSON.writeValueAsBytes(value));}
    catch(Exception failure){throw new AssertionError(failure);}
  }
}
