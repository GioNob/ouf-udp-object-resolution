package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PublishedRelationshipBindingTest {
  private static final String ROOT_TYPE="urn:d084:record", DETAIL_TYPE="urn:d084:detail", VALUE_MAP="urn:d084:map";
  private static final String PUBLICATION_SET=new UUID(1,1).toString();
  private static final String TARGET_VOCABULARY="urn:d084:colours@"+new UUID(1,2);
  @TempDir Path temp;
  private final ObjectMapper json=new ObjectMapper();
  @Test void bindsRelationshipToDeclaredMappingTargetKeyLabelAndHistoricalReference()throws Exception{
    assertThat(resolve(bundle()).relationships().relationships()).hasSize(1);
    for(String fault:List.of("label","targetKey","strategyRef","mapping")){
      var b=bundle();var runtime=map(map(b,"extractionProfile"),"runtime");
      if(fault.equals("label"))((List<Map<String,Object>>)b.get("dataAccessPolicies")).get(1).put("label","RESTRICTED");
      if(fault.equals("strategyRef"))map(runtime,"execution").put("relationshipResolutionStrategyRefs",List.of("relationship://different/1"));
      if(fault.equals("mapping"))b.put("relationshipMappings",List.of());
      if(fault.equals("targetKey"))map(((List<Map<String,Object>>)b.get("relationshipMappings")).getFirst(),"resolution").put("targetKeyProperty","other");
      assertThatThrownBy(()->resolve(b)).hasMessage("UDP_PINNED_PROFILE_INVALID");
    }
  }
  @Test void acceptsOnboardingChecksumWithSupplementaryCharacterWithoutValueMaps()throws Exception{
    var b=bundle();
    var label=Map.of("it","Telecamera \uD83D\uDCF7");
    map(map(b,"semanticMapping"),"sourceType").put("label",label);

    var resolved=resolve(b);
    assertThat(map(map(resolved.bundle(),"semanticMapping"),"sourceType").get("label")).isEqualTo(label);
  }

  @Test void d084AcceptsRegistryDatatypeDigestWithSupplementaryCharacterWithoutValueMaps()throws Exception{
    var b=bundle();
    var runtime=map(map(b,"extractionProfile"),"runtime");
    map(runtime,"execution").put("semanticPublicationSetRef",PUBLICATION_SET);
    var properties=(List<Map<String,Object>>)map(map(runtime,"udp"),"materialization").get("properties");
    properties.getFirst().put("datatype",ROOT_TYPE);
    var definition=Map.of("components",List.of(Map.of("name","value","type","string","cardinality","ONE",
        "required",true,"labels",Map.of("it","Valore \uD83C\uDF3F"))));
    String revision=new UUID(1,3).toString();
    String digest=definitionDigest(definition);
    b.put("semanticReferenceBindings",List.of(Map.of("semanticId",ROOT_TYPE,"semanticVersion","1","revisionId",revision,
        "publicationSetId",PUBLICATION_SET,"definition",definition,"definitionSha256",digest)));
    var registry=Map.of(ROOT_TYPE,Map.<String,Object>of("semantic_id",ROOT_TYPE,"semantic_version","1",
        "revision_id",revision,"publication_set_id",PUBLICATION_SET,"artifact_type","DATATYPE","status","ACTIVE",
        "definition",definition,"definitionSha256",digest));

    var type=resolve(b,registry).materialization().properties().getFirst().canonicalType();
    assertThat(type.type()).isEqualTo(ROOT_TYPE+"@"+PUBLICATION_SET);
    type.validate(Map.of("value","sample"),(crs,geo)->{});
  }

  @Test void preservesHistoricalAsciiBundleChecksum()throws Exception{
    var b=bundle();
    String expected="sha256:f228e9f8977d1b508f3e9861faf29fa591ffe9ba1b63e3c4346be550f6ecb907";
    byte[] legacy=json.writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(b);
    assertThat("sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(legacy))).isEqualTo(expected);
    assertThat(resolve(b).bundle().get("checksum")).isEqualTo(expected);
  }

  @Test void d084ResolvesNestedDatatypePinsBeforeMaterialization()throws Exception{
    var fixture=nestedValueBundle();
    var responses=fixture.registry();
    assertThat(resolve(fixture.bundle(),responses).materialization().properties().getLast().canonicalType().type())
        .isEqualTo(ROOT_TYPE+"@"+PUBLICATION_SET);
    responses.get(DETAIL_TYPE).put("status","DRAFT");
    assertThatThrownBy(()->resolve(fixture.bundle(),responses)).hasMessage("UDP_SEMANTIC_REFERENCE_MISMATCH");
    responses.get(DETAIL_TYPE).put("status","RETIRED");
    responses.get(DETAIL_TYPE).put("definitionSha256","0".repeat(64));
    assertThatThrownBy(()->resolve(fixture.bundle(),responses)).hasMessage("UDP_SEMANTIC_REFERENCE_MISMATCH");
  }

  @Test void compactD084DigestsVerifyEmbeddedDefinitionsAndHistoricalStatus()throws Exception{
    var fixture=nestedValueBundle();
    assertThat(resolve(fixture.bundle(),fixture.registry(),true).materialization().properties().getLast().canonicalType()).isNotNull();
    fixture.registry().get(VALUE_MAP).put("definitionSha256","f".repeat(64));
    assertThatThrownBy(()->resolve(fixture.bundle(),fixture.registry(),true))
        .isInstanceOfSatisfying(SemanticReferenceFailure.class,e->
            assertThat(e.detail).containsEntry("index",2).containsEntry("semanticId",VALUE_MAP));
    fixture.valueMapPin().put("definitionSha256","f".repeat(64));
    assertThatThrownBy(()->resolve(fixture.bundle(),fixture.registry(),true)).hasMessage("UDP_SEMANTIC_REFERENCE_MISMATCH");
  }

  @Test void compactConceptKeepsIndependentVocabularyProof()throws Exception{
    var fixture=nestedValueBundle();
    String id="urn:d084:extra-concept",revision=new UUID(0,17).toString();
    Map<String,Object> definition=Map.of("vocabularyRef","urn:d084:colours");
    ((List<Map<String,Object>>)fixture.bundle().get("semanticReferenceBindings")).add(Map.of("semanticId",id,
        "semanticVersion","1","revisionId",revision,"publicationSetId",PUBLICATION_SET,"definition",definition));
    var actual=new HashMap<String,Object>(Map.of("semantic_id",id,"semantic_version","1","revision_id",revision,
        "publication_set_id",PUBLICATION_SET,"artifact_type","CONCEPT","status","RETIRED","definition",definition));
    fixture.registry().put(id,actual);
    assertThat(resolve(fixture.bundle(),fixture.registry(),true)).isNotNull();
    actual.put("definition",Map.of("vocabularyRef","urn:d084:elsewhere"));
    assertThatThrownBy(()->resolve(fixture.bundle(),fixture.registry(),true))
        .isInstanceOfSatisfying(SemanticReferenceFailure.class,e->assertThat(e.detail).containsEntry("semanticId",id));
  }

  @Test void d084OnboardingValueMapPinVerifiesNestedConceptMembership()throws Exception{
    var fixture=nestedValueBundle();
    for(String status:List.of("ACTIVE","DEPRECATED","RETIRED")){
      fixture.registry().get(VALUE_MAP).put("status",status);
      var type=resolve(fixture.bundle(),fixture.registry()).materialization().properties().getLast().canonicalType();
      type.validate(mappedRecord(),(crs,geo)->{});
    }
    var wrongVocabulary=nestedValueBundle();
    setValueMapTarget(wrongVocabulary,"urn:d084:elsewhere@"+PUBLICATION_SET);
    var type=resolve(wrongVocabulary.bundle(),wrongVocabulary.registry()).materialization().properties().getLast().canonicalType();
    assertThatThrownBy(()->type.validate(mappedRecord(),(crs,geo)->{})).hasMessage("UDP_CONCEPT_MEMBERSHIP_UNVERIFIED");
  }

  @Test void d084RejectsInvalidOnboardingValueMapPins()throws Exception{
    var fixture=nestedValueBundle();
    var actual=fixture.registry().get(VALUE_MAP);
    var pin=fixture.valueMapPin();
    String digest=(String)pin.get("definitionSha256");
    pin.put("definitionSha256","0".repeat(64));
    assertInvalidValueMap(fixture);
    actual.put("definitionSha256",pin.get("definitionSha256"));
    assertInvalidValueMap(fixture); // Even matching digests must match the canonical content.
    pin.put("definitionSha256",digest);
    actual.put("definitionSha256",digest);

    var definition=actual.get("definition");
    actual.put("definition",Map.of("targetVocabulary",TARGET_VOCABULARY,"entries",List.of(Map.of("code","G","concept","urn:green"))));
    assertInvalidValueMap(fixture);
    actual.put("definition",definition);
    actual.put("artifact_type","DATATYPE");
    assertInvalidValueMap(fixture);
    actual.put("artifact_type","VALUE_MAP");
    actual.put("status","DRAFT");
    assertInvalidValueMap(fixture);
    actual.put("status","ACTIVE");
    actual.put("revision_id",UUID.randomUUID().toString());
    assertThatThrownBy(()->resolve(fixture.bundle(),fixture.registry())).hasMessage("UDP_SEMANTIC_REFERENCE_MISMATCH");

    for(String target:List.of("urn:d084:colours","relative@"+PUBLICATION_SET,"urn:d084:colours@not-a-uuid")){
      var malformed=nestedValueBundle();
      setValueMapTarget(malformed,target);
      assertInvalidValueMap(malformed);
    }
  }

  @Test void d084VerifiesValueMapPinsEvenWithoutStructuredTypes()throws Exception{
    var fixture=nestedValueBundle();
    var b=bundle();
    var execution=map(map(map(b,"extractionProfile"),"runtime"),"execution");
    execution.put("semanticPublicationSetRef",PUBLICATION_SET);
    b.put("semanticReferenceBindings",List.of(fixture.valueMapPin()));
    assertThat(resolve(b,fixture.registry()).relationships().relationships()).hasSize(1);
    fixture.valueMapPin().remove("definitionSha256");
    assertThatThrownBy(()->resolve(b,fixture.registry())).hasMessage("UDP_SEMANTIC_REFERENCE_MISMATCH");
  }

  private void assertInvalidValueMap(NestedValueBundle fixture){
    assertThatThrownBy(()->resolve(fixture.bundle(),fixture.registry())).hasMessage("UDP_SEMANTIC_REFERENCE_MISMATCH");
  }
  private static Map<String,Object> mappedRecord(){
    return Map.of("value",List.of(Map.of("value","98765432109876543210.1","colour","urn:red"),
        Map.of("value","0","colour","urn:blue")));
  }
  private record NestedValueBundle(Map<String,Object> bundle,Map<String,Map<String,Object>> registry,
      Map<String,Object> valueMapPin){}

  private NestedValueBundle nestedValueBundle()throws Exception{
    var b=bundle();
    var runtime=map(map(b,"extractionProfile"),"runtime");
    var udp=map(runtime,"udp");udp.remove("relationships");
    map(runtime,"execution").put("semanticPublicationSetRef",PUBLICATION_SET);
    var items=List.of("first","second").stream().map(prefix->Map.of("structured",Map.of("typeRef",DETAIL_TYPE+"@"+PUBLICATION_SET,
        "components",List.of(Map.of("component","value","fieldName",prefix,"reader","DECIMAL_DOT_V1"),
            Map.of("component","colour","fieldName",prefix+"Code","reader","VALUE_MAP_V1",
                "transformParams",Map.of("valueMapRef",VALUE_MAP+"@"+PUBLICATION_SET)))))).toList();
    map(b,"semanticMapping").put("compositions",List.of(Map.of("kind","STRUCTURED","targetPropertyIri","urn:record",
        "dataAccessLabel","OPEN","typeRef",ROOT_TYPE+"@"+PUBLICATION_SET,"components",List.of(Map.of("component","value","items",items)))));
    ((List<Object>)b.get("dataAccessPolicies")).add(Map.of("scope","PROPERTY","target","urn:record","label","OPEN"));
    ((List<Object>)map(udp,"materialization").get("properties")).add(Map.of("sourceField","urn:record",
        "propertyIri","urn:record","datatype",ROOT_TYPE,"accessLabel","OPEN","authorityOrder",List.of()));
    var responses=new HashMap<String,Map<String,Object>>();
    var bindings=new ArrayList<Map<String,Object>>();
    for(String iri:List.of(ROOT_TYPE,DETAIL_TYPE)){
      String revision=UUID.randomUUID().toString();
      var fields=new ArrayList<Map<String,Object>>();
      boolean root=iri.equals(ROOT_TYPE);
      fields.add(Map.of("name","value","type",root?DETAIL_TYPE+"@"+PUBLICATION_SET:"decimal",
          "cardinality",root?"MANY":"ONE","required",true));
      if(!root)fields.add(Map.of("name","colour","type","concept","cardinality","ONE","required",true,
          "constraints",Map.of("vocabularyRef","urn:d084:colours")));
      var definition=Map.of("components",fields);
      String digest=definitionDigest(definition);
      bindings.add(Map.of("semanticId",iri,"semanticVersion","1","revisionId",revision,
          "publicationSetId",PUBLICATION_SET,"definition",definition,"definitionSha256",digest));
      responses.put(iri,new HashMap<>(Map.of("semantic_id",iri,"semantic_version","1","revision_id",revision,
          "publication_set_id",PUBLICATION_SET,"artifact_type","DATATYPE","status",root?"DEPRECATED":"RETIRED",
          "definition",definition,"definitionSha256",digest)));
    }
    String revision=UUID.randomUUID().toString();
    Map<String,Object> label=Map.of("it","Colori \uD83C\uDF3F");
    var pin=new HashMap<String,Object>(Map.of("semanticId",VALUE_MAP,"semanticVersion","1","revisionId",revision,
        "publicationSetId",PUBLICATION_SET,"valueMapRef",VALUE_MAP+"@"+PUBLICATION_SET,"label",label));
    bindings.add(pin);
    responses.put(VALUE_MAP,new HashMap<>(Map.of("semantic_id",VALUE_MAP,"semantic_version","1","revision_id",revision,
        "publication_set_id",PUBLICATION_SET,"artifact_type","VALUE_MAP","status","ACTIVE","label",label)));
    b.put("semanticReferenceBindings",bindings);
    var fixture=new NestedValueBundle(b,responses,pin);
    setValueMapTarget(fixture,TARGET_VOCABULARY);
    return fixture;
  }
  private void setValueMapTarget(NestedValueBundle fixture,String target)throws Exception{
    var definition=Map.of("targetVocabulary",target,"entries",List.of(
        Map.of("code","B","concept","urn:blue"),Map.of("code","R","concept","urn:red")),
        "labels",Map.of("it","Colori \uD83C\uDF3F"));
    var payload=Map.of("definition",definition,"definitionSha256",definitionDigest(definition));
    fixture.valueMapPin().put("targetVocabulary",target);
    fixture.valueMapPin().putAll(payload);
    fixture.registry().get(VALUE_MAP).putAll(payload);
  }
  private String definitionDigest(Map<String,?> definition)throws Exception{
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
        json.writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(definition).getBytes(StandardCharsets.UTF_8)));
  }

  @Test void cannotConvertAmbiguousMatchingIntoAnAutomaticFirstMatch()throws Exception{
    var b=bundle();map(((List<Map<String,Object>>)b.get("relationshipMappings")).getFirst(),"resolution").put("onMultipleMatches","FIRST");
    assertThatThrownBy(()->resolve(b)).hasMessage("UDP_PINNED_PROFILE_INVALID");
  }
  @Test void weightedProposalBindsItsPolicyAndRejectsUnmappedBlockingFields()throws Exception{
    var b=bundle();var runtime=map(map(b,"extractionProfile"),"runtime");
    var udp=map(runtime,"udp");var resolution=map(udp,"resolution");
    resolution.put("weighted",new LinkedHashMap<>(Map.of("signals",List.of(Map.of("property","ref","comparator","TEXT","weight",1)),"blockingProperties",List.of("ref"),"maxCandidates",10,"highThreshold",.85,"reviewThreshold",.5,"minimumMargin",.1,"allowSpatialIdentity",false)));
    assertThat(resolve(b).resolution().weighted().blockingProperties()).containsExactly("ref");
    map(resolution,"weighted").put("blockingProperties",List.of("controller"));
    assertThatThrownBy(()->resolve(b)).hasMessage("UDP_PINNED_PROFILE_INVALID");
  }
  @Test void futureOrIncompleteIdentityProfileCannotFallBackToLegacyMatch()throws Exception{
    var governed=bundle();map(map(map(map(governed,"extractionProfile"),"runtime"),"udp"),"resolution")
        .put("governedIdentity",Map.of("ref","policy://future"));
    assertThatThrownBy(()->resolve(governed)).hasMessage("UDP_GOVERNED_IDENTITY_PROFILE_INVALID");
    var unknown=bundle();map(map(map(map(unknown,"extractionProfile"),"runtime"),"udp"),"resolution")
        .put("futureStrategy",Map.of());
    assertThatThrownBy(()->resolve(unknown)).hasMessage("UDP_RESOLUTION_PROFILE_UNSUPPORTED");
    var incomplete=bundle();map(map(map(map(incomplete,"extractionProfile"),"runtime"),"udp"),"resolution")
        .remove("matchProperty");
    assertThatThrownBy(()->resolve(incomplete)).hasMessage("UDP_RESOLUTION_PROFILE_UNSUPPORTED");
  }
  private PublishedRuntimeConfiguration.Profiles resolve(Map<String,Object> bundle)throws Exception{
    return resolve(bundle,Map.of());
  }
  private PublishedRuntimeConfiguration.Profiles resolve(Map<String,Object> bundle,Map<String,Map<String,Object>> registry)throws Exception{
    return resolve(bundle,registry,false);
  }
  private PublishedRuntimeConfiguration.Profiles resolve(Map<String,Object> bundle,Map<String,Map<String,Object>> registry,boolean compact)throws Exception{
    bundle.remove("checksum");String hash="sha256:"+definitionDigest(bundle);bundle.put("checksum",hash);
    byte[] response=json.writeValueAsBytes(Map.of("tenantId","tenant-a","sourceId","cameras","checksum",hash,"bundle",bundle));
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{byte[] body=response;
      if(exchange.getRequestURI().getPath().endsWith(":resolve-batch")){
        if(!compact){exchange.sendResponseHeaders(405,-1);exchange.close();return;}
        var results=new ArrayList<Map<String,Object>>();
        for(var pin:json.readTree(exchange.getRequestBody()).required("references")){
          Map<String,Object> row=json.convertValue(pin,Map.class);
          var actual=new HashMap<>(registry.get(row.get("semanticId")));
          // Compact v1 (Semantic 51a): definitions only for CONCEPT, as membership proof.
          if(!"CONCEPT".equals(actual.get("artifact_type")))actual.remove("definition");
          row.put("index",results.size());row.put("outcome","RESOLVED");row.put("resolved",actual);results.add(row);
        }
        body=json.writeValueAsBytes(Map.of("contract","ouf.semantic.reference-batch.v1","registryVersion","d084","results",results));
      }
      if(exchange.getRequestURI().getPath().equals("/api/semantic/v1/references:resolve")){
        var query=exchange.getRequestURI().getQuery();
        String iri=query.substring("semanticId=".length(),query.indexOf('&'));
        body=json.writeValueAsBytes(registry.get(iri));
      }
      exchange.sendResponseHeaders(200,body.length);try(var output=exchange.getResponseBody()){output.write(body);}});server.start();
    try{Path token=temp.resolve("token");Files.writeString(token,"fixture");return new PublishedRuntimeConfiguration(json,"http://127.0.0.1:"+server.getAddress().getPort(),token.toString(),"tenant-a",new ReferenceBatchSettings(262144,1000,2049,3145728,3,1,1)).resolve("bundle:1:"+hash,"FILE");}finally{server.stop(0);}
  }
  static Map<String,Object> bundle()throws Exception{
    var rule=Map.of("sourceField","ref","relationIri","connectedTo","targetCanonicalType","Cabinet","targetPropertyIri","code","resolutionStrategy","CANONICAL_KEY","onNoMatch","QUARANTINE_RELATION","accessLabel","OPEN","selfLoopAllowed",false);
    var udp=Map.of("resolution",Map.of("strategyId","key","strategyVersion","1","policyRef","policy://identity/1","canonicalType","Camera","canonicalKeyProperty","ref","matchProperty","ref"),"materialization",Map.of("policyRef","policy://authority/1","checkpointInterval",1,"bitemporalProperties",List.of(),"properties",List.of(Map.of("sourceField","ref","propertyIri","ref","datatype","string","accessLabel","OPEN","authorityOrder",List.of()))),"relationships",Map.of("policyRef","policy://relations/1","relationships",List.of(rule)));
    var b=Map.of("bundleId","bundle","bundleVersion","1","semanticMapping",Map.of("sourceType",Map.of("sourceId","cameras","typeCode","FILE"),"targetClasses",List.of(Map.of("classIri","Camera")),"propertyMappings",List.of(Map.of("sourceField","cabinet","targetPropertyIri","ref"))),"extractionProfile",Map.of("runtime",Map.of("udp",udp,"execution",Map.of("relationshipResolutionStrategyRefs",List.of("relationship://camera-cabinet/1")))),"dataAccessPolicies",List.of(Map.of("scope","PROPERTY","target","ref","label","OPEN"),Map.of("scope","RELATIONSHIP","target","connectedTo","label","OPEN")),"relationshipMappings",List.of(Map.of("mappingId","relationship://camera-cabinet/1","sourceField","cabinet","relationIri","connectedTo","targetClassIri","Cabinet","resolution",Map.of("strategy","CANONICAL_KEY","targetKeyProperty","code","onNoMatch","QUARANTINE_RELATION","onMultipleMatches","REVIEW_REQUIRED"))));
    var json=new ObjectMapper();
    return json.readValue(json.writeValueAsBytes(b),Map.class);
  }
  @SuppressWarnings("unchecked") private static Map<String,Object> map(Map<String,Object> p,String k){return (Map<String,Object>)p.get(k);}
}
