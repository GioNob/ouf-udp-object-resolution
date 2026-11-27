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
    assertEquals("3b85052e253b985f1082527eb595d2f02e5336047203deb3503a9766c1324295",digest(bytes));
    var contract=JSON.readTree(bytes);
    assertEquals("ouf.value-types.v1",contract.get("schema").asText());
    assertEquals(2,contract.get("revision").intValue());
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
            Map.of("crs","EPSG:4326","geoJson",Map.of("type","Point","coordinates",List.of(13,new BigDecimal("90.00000000000001")))))));
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
  @Test void d084PinnedValueMapMembershipIsScopedToTheBuilder() throws Exception {
    var registry=new PublishedValueTypes();
    String map="urn:map@"+SET, other="urn:another-map@"+SET;
    valueMap(registry,"urn:map",SET,"urn:colours");
    valueMap(registry,"urn:another-map",SET,"urn:other-vocabulary");
    registry.bind(Map.of("semanticId","urn:pinned","publicationSetId",SET,"definition",Map.of("vocabularyRef","urn:colours")),
        Map.of("artifact_type","CONCEPT","definition",Map.of("vocabularyRef","urn:colours")),ValueTypesContractTest::hash);
    bind(registry,"urn:palette",List.of(
        field("colours","concept","MANY",true,Map.of("vocabularyRef","urn:colours")),
        field("accent","concept","ONE",false,Map.of("vocabularyRef","urn:colours"))));
    var direct=Map.of("fieldName","colour","reader","VALUE_MAP_V1","transformParams",Map.of("valueMapRef",map));
    var split=Map.of("constructorId","LIST_SPLIT_V1","inputs",List.of(Map.of("role","value","fieldName","codes","reader","IDENTITY")),
        "params",Map.of("itemReader","VALUE_MAP_V1","itemReaderParams",Map.of("valueMapRef",map),"separator",","));
    bind(registry,"urn:collection",List.of(field("palettes","urn:palette@"+SET,"MANY",true,Map.of())));
    for(var colours:List.of(Map.of("component","colours","items",List.of(direct,direct)),Map.of("component","colours","constructor",split))){
      var components=List.of(colours,
          Map.of("component","accent","reader","VALUE_MAP_V1","fieldName","g","transformParams",Map.of("valueMapRef",other)));
      var shape=registry.composition(Map.of("kind","STRUCTURED","typeRef","urn:palette@"+SET,"components",components),"urn:palette");
      shape=JSON.readValue(JSON.writeValueAsBytes(shape),CanonicalValueType.class);
      var collection=registry.composition(Map.of("kind","STRUCTURED","typeRef","urn:collection@"+SET,"components",List.of(
          Map.of("component","palettes","items",List.of(Map.of("structured",Map.of("typeRef","urn:palette@"+SET,"components",components)))))),"urn:collection");
      validate(collection,Map.of("palettes",List.of(Map.of("colours",List.of("urn:red")))));
      validate(shape,Map.of("colours",List.of("urn:red","urn:blue")));
      validate(shape,Map.of("colours",List.of("urn:red"),"accent","urn:pinned"));
      rejected(shape,Map.of("colours",List.of("relative")));
      for(var invalid:List.of(Map.of("colours",List.of("urn:red"),"accent","urn:green"),
          Map.of("colours",List.of("urn:red"),"accent","urn:red"))) {
        unverified(shape,invalid);
      }
    }
    var mixed=registry.composition(Map.of("kind","STRUCTURED","typeRef","urn:palette@"+SET,"components",List.of(
        Map.of("component","colours","items",List.of(direct,Map.of("fieldName","g","reader","VALUE_MAP_V1",
            "transformParams",Map.of("valueMapRef",other)))))),"urn:palette");
    validate(mixed,Map.of("colours",List.of("urn:red","urn:pinned")));
    unverified(mixed,Map.of("colours",List.of("urn:pinned","urn:red")));
    unverified(mixed,Map.of("colours",List.of("urn:red")));
    validate(mixed,Map.of("colours",List.of("urn:pinned")));
    valueMap(registry,"urn:map",new UUID(0,3).toString(),"urn:colours");
    for (String ref:List.of("urn:map","urn:map@"+new UUID(0,2))) {
      var shape=registry.composition(Map.of("kind","STRUCTURED","typeRef","urn:palette@"+SET,"components",List.of(
          Map.of("component","colours","items",List.of(Map.of("fieldName","c","reader","VALUE_MAP_V1",
              "transformParams",Map.of("valueMapRef",ref)))))),"urn:palette");
      unverified(shape,Map.of("colours",List.of("urn:red")));
    }
  }
  private static void valueMap(PublishedValueTypes registry,String id,String set,String vocabulary) {
    var pin=valueMapPin(id,set,vocabulary+"@"+SET);
    registry.bind(pin,resolvedValueMap(pin),ValueTypesContractTest::hash);
  }
  private static Map<String,Object> valueMapPin(String id,String set,String target) {
    var definition=Map.of("targetVocabulary",target,"entries",List.of(Map.of("code","R","concept","urn:red")));
    return new HashMap<>(Map.of("semanticId",id,"semanticVersion","1","revisionId",new UUID(0,42).toString(),
        "publicationSetId",set,"valueMapRef",id+"@"+set,"targetVocabulary",target,
        "definition",definition,"definitionSha256",hash(definition).substring(7)));
  }
  private static Map<String,Object> resolvedValueMap(Map<String,Object> pin) {
    return Map.of("semantic_id",pin.get("semanticId"),"semantic_version",pin.get("semanticVersion"),
        "revision_id",pin.get("revisionId"),"publication_set_id",pin.get("publicationSetId"),
        "artifact_type","VALUE_MAP","status","ACTIVE","definition",pin.get("definition"),
        "definitionSha256",pin.get("definitionSha256"));
  }
  @Test void d084CompactedManyKeepsOnlyCommonProducerEvidence() {
    var registry=new PublishedValueTypes();
    valueMap(registry,"urn:map",SET,"urn:v");
    valueMap(registry,"urn:other",SET,"urn:other-vocabulary");
    registry.bind(Map.of("semanticId","urn:pinned","publicationSetId",SET),
        Map.of("artifact_type","CONCEPT","definition",Map.of("vocabularyRef","urn:v")),ValueTypesContractTest::hash);
    var concept=Map.<String,Object>of("vocabularyRef","urn:v");
    bind(registry,"urn:row",List.of(field("a","concept","ONE",true,concept),field("b","concept","ONE",true,concept)));
    bind(registry,"urn:rows",List.of(field("rows","urn:row@"+SET,"MANY",true,Map.of())));
    var items=new ArrayList<Map<String,Object>>();
    for(String mapId:List.of("urn:map","urn:map","urn:other","urn:other")){
      var a=Map.of("component","a","reader","VALUE_MAP_V1","fieldName","a","transformParams",Map.of("valueMapRef","urn:map@"+SET));
      var b=Map.of("component","b","reader","VALUE_MAP_V1","fieldName","b","transformParams",Map.of("valueMapRef",mapId+"@"+SET));
      items.add(Map.of("structured",Map.of("typeRef","urn:row@"+SET,"components",List.of(a,b))));
    }
    var shape=registry.composition(Map.of("kind","STRUCTURED","typeRef","urn:rows@"+SET,"components",List.of(
        Map.of("component","rows","items",items))),"urn:rows");
    var mapped=Map.of("a","urn:blue","b","urn:blue");
    var pinned=Map.of("a","urn:blue","b","urn:pinned");
    validate(shape,Map.of("rows",List.of(mapped,pinned,pinned)));
    unverified(shape,Map.of("rows",List.of(mapped,mapped,pinned)));
    unverified(shape,Map.of("rows",List.of(mapped,pinned,mapped)));
    validate(shape,Map.of("rows",List.of(pinned)));
  }
  @Test void d084HistoricalShapeWithoutMapProofRoundTrips() throws Exception {
    var registry=new PublishedValueTypes();
    bind(registry,"urn:part",List.of(field("n","integer","ONE",true,Map.of())));
    var shape=structured(registry,"urn:part");
    var json=JSON.valueToTree(shape);
    assertEquals(false,json.has("valueMapProof"));
    var retained=JSON.treeToValue(json,CanonicalValueType.class);
    assertEquals(shape,retained);
    validate(retained,Map.of("n","9007199254740993"));
  }
  @Test void d084ValueMapPinMustMatchThePublishedCoordinate() {
    var good=valueMapPin("urn:map",SET,"urn:v@"+SET);
    for (var change:List.of(Map.of("valueMapRef","urn:other@"+SET),Map.of("targetVocabulary","urn:other@"+SET),
        Map.of("vocabularyRef","urn:other"))) {
      var pin=new HashMap<>(good);
      pin.putAll(change);
      assertEquals("UDP_PINNED_DATATYPE_INVALID",assertThrows(IllegalArgumentException.class,
          ()->new PublishedValueTypes().bind(pin,resolvedValueMap(good),ValueTypesContractTest::hash)).getMessage());
    }
    var missing=new HashMap<>(good);
    missing.remove("targetVocabulary");
    assertEquals("UDP_PINNED_DATATYPE_INVALID",assertThrows(IllegalArgumentException.class,
        ()->new PublishedValueTypes().bind(missing,resolvedValueMap(good),ValueTypesContractTest::hash)).getMessage());
  }
  @Test void d084EmbeddedValueMapBuildsTheSameProofAsTheResolvedPin() {
    String vocabulary="https://example.org/vocab@colours";
    var pin=valueMapPin("urn:map",SET,vocabulary+"@"+new UUID(0,43));
    pin.put("vocabularyRef",vocabulary); // A matching historical alias is allowed.
    var resolved=new PublishedValueTypes();
    resolved.bind(pin,resolvedValueMap(pin),ValueTypesContractTest::hash);
    var embedded=new PublishedValueTypes();
    embedded.embedded(pin,ValueTypesContractTest::hash);
    var definition=Map.of("components",List.of(field("colour","concept","ONE",true,Map.of("vocabularyRef",vocabulary))));
    var typePin=pin("urn:record",definition,hash(definition).substring(7));
    var expression=Map.<String,Object>of("kind","STRUCTURED","typeRef","urn:record@"+SET,
        "components",List.of(Map.of("component","colour","fieldName","code","reader","VALUE_MAP_V1",
            "transformParams",Map.of("valueMapRef","urn:map"))));
    for(var types:List.of(resolved,embedded)){
      types.embedded(typePin,ValueTypesContractTest::hash);
      validate(types.composition(expression,"urn:record"),Map.of("colour","urn:red"));
      assertEquals("UDP_PINNED_DATATYPE_INVALID",assertThrows(IllegalArgumentException.class,
          ()->structured(types,"urn:map")).getMessage());
      assertEquals("UDP_PINNED_DATATYPE_INVALID",assertThrows(IllegalArgumentException.class,
          ()->types.pinned("urn:map")).getMessage());
    }
    assertEquals(resolved.composition(expression,"urn:record"),embedded.composition(expression,"urn:record"));
    var otherPin=Map.<String,Object>of("semanticId","urn:map","publicationSetId",new UUID(0,44).toString());
    resolved.bind(otherPin,Map.of("artifact_type","PROPERTY"),ValueTypesContractTest::hash);
    embedded.recordReference(otherPin);
    for(var types:List.of(resolved,embedded)){
      unverified(types.composition(expression,"urn:record"),Map.of("colour","urn:red"));
    }
  }
  private static void unverified(CanonicalValueType shape,Object value) {
    assertEquals("UDP_CONCEPT_MEMBERSHIP_UNVERIFIED",assertThrows(IllegalArgumentException.class,
        ()->validate(shape,value)).getMessage());
  }
  @Test void d084CanonicalConstraintTypes() {
    record Invalid(String type,String key,Object value) {}
    for (var invalid : List.of(
        new Invalid("integer","minInclusive",1), new Invalid("integer","maxInclusive","01"),
        new Invalid("decimal","minInclusive",new BigDecimal("1.5")), new Invalid("decimal","maxInclusive","1.0"),
        new Invalid("date","minInclusive","2026-02-29"), new Invalid("datetime","maxInclusive","2026-01-01T01:00:00+01:00"),
        new Invalid("string","maxLength","2"), new Invalid("string","maxLength",new BigDecimal("2")),
        new Invalid("string","maxLength",-1), new Invalid("concept","vocabularyRef","relative/path"))) {
      var registry=new PublishedValueTypes();
      bind(registry,"urn:limits",List.of(field("part",invalid.type(),"ONE",true,Map.of(invalid.key(),invalid.value()))));
      assertEquals("UDP_PINNED_DATATYPE_INVALID",assertThrows(IllegalArgumentException.class,
          ()->structured(registry,"urn:limits")).getMessage());
    }
    var registry=new PublishedValueTypes();
    bind(registry,"urn:range",List.of(field("count","integer","ONE",true,
        Map.of("minInclusive","9007199254740993","maxInclusive","9007199254740994")),
        field("name","string","ONE",false,Map.of("maxLength",new java.math.BigInteger("18446744073709551615")))));
    var shape=structured(registry,"urn:range");
    validate(shape,Map.of("count","9007199254740993","name","abc"));
    rejected(shape,Map.of("count","9007199254740992"));
  }
  @Test void d084HistoricalDatatypeStates() {
    var definition=Map.of("components",List.of(field("n","integer","ONE",true,Map.of())));
    var digest=hash(definition).substring(7);
    var binding=pin("urn:historical",definition,digest);
    var actual=new HashMap<String,Object>(Map.of("artifact_type","DATATYPE","definition",definition,"definitionSha256",digest));
    for(String status:List.of("ACTIVE","DEPRECATED","RETIRED")){
      var registry=new PublishedValueTypes();
      actual.put("status",status);
      registry.bind(binding,actual,ValueTypesContractTest::hash);
      validate(structured(registry,"urn:historical"),Map.of("n","1"));
    }
    for(String status:Arrays.asList("DRAFT","REVOKED","UNKNOWN",null)){
      actual.put("status",status);
      assertEquals("UDP_PINNED_DATATYPE_INVALID",assertThrows(IllegalArgumentException.class,
          ()->new PublishedValueTypes().bind(binding,actual,ValueTypesContractTest::hash)).getMessage());
    }
  }
  @Test void d084ManyStructuredItemsAreCanonicalObjects() {
    var registry=new PublishedValueTypes();
    bind(registry,"urn:part",List.of(field("n","integer","ONE",true,Map.of())));
    bind(registry,"urn:parent",List.of(field("parts","urn:part@"+SET,"MANY",false,Map.of()),
        field("name","string","ONE",true,Map.of())));
    var builders=List.of("first","second").stream().map(field->Map.of("structured",Map.of(
        "typeRef","urn:part@"+SET,"components",List.of(Map.of("component","n","fieldName",field,"reader","INTEGER_V1"))))).toList();
    var shape=registry.composition(Map.of("kind","STRUCTURED","typeRef","urn:parent@"+SET,"components",List.of(
        Map.of("component","parts","items",builders),Map.of("component","name","fieldName","title","reader","IDENTITY"))),"urn:parent");
    var nameOnly=Map.of("name","x");
    validate(shape,nameOnly);
    validate(shape,Map.of("name","x","parts",List.of(Map.of("n","2"),Map.of("n","1"))));
    for(Object invalid:List.of(List.of(),Map.of("n","1"),List.of(Map.of("n","1"),"2"),
        List.of(Map.of("n","1"),Map.of("n",2)),List.of(Map.of("extra",true)))) {
      rejected(shape,Map.of("name","x","parts",invalid));
    }
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
  @Test void d084r2SharedWhitespaceVectorsAtCanonicalBoundary() throws Exception {
    var vectors=JSON.readTree(Files.readAllBytes(Path.of("contracts/ouf-value-types-v1.json")))
        .required("whitespace").required("vectors");
    var trimmed=List.of("12.50","-0.0","+007","\u001c12","\ufeff12","Via Roma","","","A1","A1");
    assertEquals(trimmed.size(),vectors.size());
    var exact=new UdpPorts.PropertyRule("text","urn:text",CanonicalValueType.XSD+"string","OPEN",List.of(),
        new LinkedPropertyReconciler.Rule(LinkedPropertyReconciler.POLICY,"SINGLE","EXACT_TYPED",1,"UNBOUNDED",null,null));
    var nfc=new UdpPorts.PropertyRule("text","urn:text",CanonicalValueType.XSD+"string","OPEN",List.of(),
        new LinkedPropertyReconciler.Rule(LinkedPropertyReconciler.POLICY,"SINGLE","NFC_TRIM",1,"UNBOUNDED",null,null));
    var types=new PublishedValueTypes();
    var optional=field("value","date","ONE",false,Map.of());
    bind(types,"urn:optionalDate",List.of(optional,field("present","boolean","ONE",true,Map.of())));
    for(int i=0;i<vectors.size();i++){
      var vector=vectors.get(i);
      String cell=vector.required("cell").asText(), result=vector.required("result").asText();
      assertEquals(trimmed.get(i),ValueWhitespace.trim(cell));
      assertEquals("ABSENT".equals(result),ValueWhitespace.isEmpty(cell));
      switch(vector.required("reader").asText()){
        case "DECIMAL_DOT_V1" -> validate(CanonicalValueType.base("decimal",false),result);
        case "INTEGER_V1" -> {
          var integer=CanonicalValueType.base("integer",false);
          rejected(integer,cell);
          if(!"INVALID".equals(result))validate(integer,result);
        }
        case "TEXT_NFC_TRIM_V1" -> assertEquals("ABSENT".equals(result)?null:result,LinkedPropertyReconciler.normalize(cell,nfc));
        case "DATE_ISO_V1" -> {
          rejected(CanonicalValueType.base("date",false),cell);
          validate(structured(types,"urn:optionalDate"),Map.of("present",true));
        }
        case "VALUE_MAP_V1" -> assertEquals("A1",ValueWhitespace.trim(cell)); // UDP consumes the mapped concept.
        case "IDENTITY" -> assertEquals(result,LinkedPropertyReconciler.normalize(cell,exact));
        default -> throw new AssertionError("Unexpected shared vector");
      }
    }
    assertEquals("",LinkedPropertyReconciler.normalize("",exact));
    assertEquals("\u2003\u00a0\t",LinkedPropertyReconciler.normalize("\u2003\u00a0\t",exact));
    assertEquals("\u001c\ufeff",LinkedPropertyReconciler.normalize("\u001c\ufeff",nfc));
  }
  @Test void d084r2Binary64GeometrySurvivesDoubleReread() throws Exception {
    var point=Map.of("type","Point","coordinates",List.of(new BigDecimal("9007199254740993"),
        new BigDecimal("-0.0000000000000000000000000000000000000001"),Double.MIN_VALUE));
    var geometry=Map.of("crs","EPSG:3003","geoJson",Map.of("type","GeometryCollection","geometries",List.of(point,
         Map.of("type","Point","coordinates",List.of(-0.0,1e21,1e-6)))));
    var shape=CanonicalValueType.base("geometry",false);
    validate(shape,geometry);
    String canonical=JSON.writeValueAsString(shape.normalizeGeometry(geometry));
    assertEquals("{\"crs\":\"EPSG:3003\",\"geoJson\":{\"geometries\":[{\"coordinates\":[9007199254740992,-1e-40,5e-324],\"type\":\"Point\"},"
        +"{\"coordinates\":[0,1e+21,0.000001],\"type\":\"Point\"}],\"type\":\"GeometryCollection\"}}",canonical);
    var reread=JSON.readValue(canonical,Object.class); // Default predecessor JSON mapper.
    assertEquals(canonical,JSON.writeValueAsString(shape.normalizeGeometry(reread)));
    var json=GovernedIdentityEngine.ComparatorKind.JSON_V1;
    String observed="{\"amount\":\""+PRECISE+"\",\"shapes\":["+JSON.writeValueAsString(geometry)+"]}";
    String expected="{\"amount\":\""+PRECISE+"\",\"shapes\":["+canonical+"]}";
    assertEquals(expected,GovernedIdentityEngine.normalize(json,observed));
    assertNotEquals(expected,GovernedIdentityEngine.normalize(json,expected.replace(PRECISE,PRECISE+"1")));
    assertNotEquals(expected,GovernedIdentityEngine.normalize(json,expected.replace("9007199254740992","9007199254740994")));
  }
  @Test void d084r2RejectsMeasureWktAndInvalidCoordinates() {
    var shape=CanonicalValueType.base("geometry",false);
    for(Object invalid:List.of("POINT M (13 45 9)","POINT ZM (13 45 10 9)",
        Map.of("crs","EPSG:4326","geoJson",Map.of("type","Point","coordinates",List.of(13,45,10,9))),
        Map.of("crs","EPSG:4326","geoJson",Map.of("type","Point","coordinates",List.of(13,45,Double.NaN))),
        Map.of("crs","EPSG:3003","geoJson",Map.of("type","Point","coordinates",List.of(new BigDecimal("1e309"),1)))))
      rejected(shape,invalid);
    validate(shape,Map.of("crs","EPSG:4326","geoJson",Map.of("type","Point","coordinates",
        List.of(13,new BigDecimal("90.000000000000000001"),10))));
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
