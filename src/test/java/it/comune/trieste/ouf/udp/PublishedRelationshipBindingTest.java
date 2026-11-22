package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PublishedRelationshipBindingTest {
  @TempDir Path temp;
  private final ObjectMapper json=new ObjectMapper();
  @Test void nativeClassAndTwentyPropertiesResolveEveryExactHistoricalReference()throws Exception{
    for(int count:List.of(21,32,33)){
      var b=bundle();var execution=map(map(map(b,"extractionProfile"),"runtime"),"execution");
      String publication=new UUID(1,1).toString();execution.put("sourceSchemaRef","schema:1");execution.put("semanticPublicationSetRef",publication);execution.put("adapterProfileRef","adapter:1");
      var bindings=new ArrayList<Map<String,Object>>();for(int i=0;i<count;i++)bindings.add(Map.of("semanticId","urn:native:"+i,"semanticVersion","1","revisionId",new UUID(0,i+1).toString(),"publicationSetId",publication));b.put("semanticReferenceBindings",bindings);
      String hash="sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsBytes(new TreeMap<>(b))));b.put("checksum",hash);
      var refs=new LinkedHashMap<String,Object>(execution);refs.put("bundleRef","bundle:1:"+hash);var calls=new java.util.concurrent.atomic.AtomicInteger();var corruptLast=new java.util.concurrent.atomic.AtomicBoolean();
      var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{
        if(!"Bearer fixture".equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.sendResponseHeaders(403,-1);return;}Map<String,Object> response;
        if(exchange.getRequestURI().getPath().startsWith("/api/semantic")){calls.incrementAndGet();var query=new HashMap<String,String>();for(String part:exchange.getRequestURI().getRawQuery().split("&")){String[] pair=part.split("=",2);query.put(pair[0],URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8));}String id=query.get("semanticId");int index=Integer.parseInt(id.substring(id.lastIndexOf(':')+1));response=Map.of("semantic_id",id,"semantic_version",corruptLast.get()&&index==count-1?"2":"1","revision_id",query.get("revisionId"),"publication_set_id",query.get("publicationSetId"));}
        else response=Map.of("tenantId","tenant-a","sourceId","cameras","checksum",hash,"bundle",b);
        byte[] bytes=json.writeValueAsBytes(response);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
      }finally{exchange.close();}});server.start();
      try{Path token=temp.resolve("native-token");Files.writeString(token,"fixture");var client=new PublishedRuntimeConfiguration(json,"http://127.0.0.1:"+server.getAddress().getPort(),token.toString(),"tenant-a");
        if(count>32){assertThatThrownBy(()->client.resolveContracts(refs)).hasMessage("UDP_PINNED_PROFILE_INVALID");assertThat(calls).hasValue(0);}
        else{assertThat(client.resolveContracts(refs)).isNotNull();assertThat(calls).hasValue(count);corruptLast.set(true);assertThatThrownBy(()->client.resolveContracts(refs)).hasMessage("UDP_PINNED_PROFILE_INVALID");assertThat(calls).hasValue(count*2);}
      }finally{server.stop(0);}
    }
  }
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
  @Test void d084ResolvesNestedDatatypePinsBeforeMaterialization()throws Exception{
    var b=bundle();
    var runtime=map(map(b,"extractionProfile"),"runtime");
    var udp=map(runtime,"udp");udp.remove("relationships");
    var set=new UUID(1,1).toString();map(runtime,"execution").put("semanticPublicationSetRef",set);
    String root="urn:d084:record", child="urn:d084:detail";
    String mapId="urn:d084:map", vocabulary="urn:d084:colours";
    var items=List.of("first","second").stream().map(prefix->Map.of("structured",Map.of("typeRef",child+"@"+set,
        "components",List.of(Map.of("component","value","fieldName",prefix,"reader","DECIMAL_DOT_V1"),
            Map.of("component","colour","fieldName",prefix+"Code","reader","VALUE_MAP_V1","transformParams",Map.of("valueMapRef",mapId)))))).toList();
    map(b,"semanticMapping").put("compositions",List.of(Map.of("kind","STRUCTURED","targetPropertyIri","urn:record",
        "dataAccessLabel","OPEN","typeRef",root+"@"+set,"components",List.of(Map.of("component","value","items",items)))));
    ((List<Object>)b.get("dataAccessPolicies")).add(Map.of("scope","PROPERTY","target","urn:record","label","OPEN"));
    ((List<Object>)map(udp,"materialization").get("properties")).add(Map.of("sourceField","urn:record",
        "propertyIri","urn:record","datatype",root,"accessLabel","OPEN","authorityOrder",List.of()));
    var responses=new HashMap<String,Map<String,Object>>();
    var bindings=new ArrayList<Map<String,Object>>();
    for(String iri:List.of(root,child)){
      String revision=UUID.randomUUID().toString();
      var fields=new ArrayList<Map<String,Object>>();
      fields.add(Map.of("name","value","type",iri.equals(root)?child+"@"+set:"decimal",
          "cardinality",iri.equals(root)?"MANY":"ONE","required",true));
      if(iri.equals(child))fields.add(Map.of("name","colour","type","concept","cardinality","ONE","required",true,
          "constraints",Map.of("vocabularyRef",vocabulary)));
      var definition=Map.of("components",fields);
      String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
          json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(definition)));
      bindings.add(Map.of("semanticId",iri,"semanticVersion","1","revisionId",revision,
          "publicationSetId",set,"definition",definition,"definitionSha256",digest));
      responses.put(iri,new HashMap<>(Map.of("semantic_id",iri,"semantic_version","1","revision_id",revision,
          "publication_set_id",set,"artifact_type","DATATYPE","status",iri.equals(root)?"DEPRECATED":"RETIRED","definition",definition,"definitionSha256",digest)));
    }
    String revision=UUID.randomUUID().toString();
    bindings.add(Map.of("semanticId",mapId,"semanticVersion","1","revisionId",revision,"publicationSetId",set,
        "valueMapRef",mapId+"@"+set,"vocabularyRef",vocabulary));
    responses.put(mapId,new HashMap<>(Map.of("semantic_id",mapId,"semantic_version","1","revision_id",revision,"publication_set_id",set)));
    b.put("semanticReferenceBindings",bindings);
    var profile=resolve(b,responses).materialization();
    var type=profile.properties().getLast().canonicalType();
    type.validate(Map.of("value",List.of(Map.of("value","98765432109876543210.1","colour","urn:red"),
        Map.of("value","0","colour","urn:blue"))),(crs,geo)->{});
    responses.get(mapId).put("revision_id",UUID.randomUUID().toString());
    assertThatThrownBy(()->resolve(b,responses)).hasMessage("UDP_PINNED_PROFILE_INVALID");
    responses.get(mapId).put("revision_id",revision);
    responses.get(child).put("status","DRAFT");
    assertThatThrownBy(()->resolve(b,responses)).hasMessage("UDP_PINNED_DATATYPE_INVALID");
    responses.get(child).put("status","RETIRED");
    responses.get(child).put("definitionSha256","0".repeat(64));
    assertThatThrownBy(()->resolve(b,responses)).hasMessage("UDP_PINNED_DATATYPE_INVALID");
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
    bundle.remove("checksum");String hash="sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsBytes(new TreeMap<>(bundle))));bundle.put("checksum",hash);
    byte[] response=json.writeValueAsBytes(Map.of("tenantId","tenant-a","sourceId","cameras","checksum",hash,"bundle",bundle));
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{byte[] body=response;
      if(exchange.getRequestURI().getPath().equals("/api/semantic/v1/references:resolve")){
        var query=exchange.getRequestURI().getQuery();
        String iri=query.substring("semanticId=".length(),query.indexOf('&'));
        body=json.writeValueAsBytes(registry.get(iri));
      }
      exchange.sendResponseHeaders(200,body.length);try(var output=exchange.getResponseBody()){output.write(body);}});server.start();
    try{Path token=temp.resolve("token");Files.writeString(token,"fixture");return new PublishedRuntimeConfiguration(json,"http://127.0.0.1:"+server.getAddress().getPort(),token.toString(),"tenant-a").resolve("bundle:1:"+hash,"FILE");}finally{server.stop(0);}
  }
  private Map<String,Object> bundle()throws Exception{
    var rule=Map.of("sourceField","ref","relationIri","connectedTo","targetCanonicalType","Cabinet","targetPropertyIri","code","resolutionStrategy","CANONICAL_KEY","onNoMatch","QUARANTINE_RELATION","accessLabel","OPEN","selfLoopAllowed",false);
    var udp=Map.of("resolution",Map.of("strategyId","key","strategyVersion","1","policyRef","policy://identity/1","canonicalType","Camera","canonicalKeyProperty","ref","matchProperty","ref"),"materialization",Map.of("policyRef","policy://authority/1","checkpointInterval",1,"bitemporalProperties",List.of(),"properties",List.of(Map.of("sourceField","ref","propertyIri","ref","datatype","string","accessLabel","OPEN","authorityOrder",List.of()))),"relationships",Map.of("policyRef","policy://relations/1","relationships",List.of(rule)));
    var b=Map.of("bundleId","bundle","bundleVersion","1","semanticMapping",Map.of("sourceType",Map.of("sourceId","cameras","typeCode","FILE"),"targetClasses",List.of(Map.of("classIri","Camera")),"propertyMappings",List.of(Map.of("sourceField","cabinet","targetPropertyIri","ref"))),"extractionProfile",Map.of("runtime",Map.of("udp",udp,"execution",Map.of("relationshipResolutionStrategyRefs",List.of("relationship://camera-cabinet/1")))),"dataAccessPolicies",List.of(Map.of("scope","PROPERTY","target","ref","label","OPEN"),Map.of("scope","RELATIONSHIP","target","connectedTo","label","OPEN")),"relationshipMappings",List.of(Map.of("mappingId","relationship://camera-cabinet/1","sourceField","cabinet","relationIri","connectedTo","targetClassIri","Cabinet","resolution",Map.of("strategy","CANONICAL_KEY","targetKeyProperty","code","onNoMatch","QUARANTINE_RELATION","onMultipleMatches","REVIEW_REQUIRED"))));
    return json.readValue(json.writeValueAsBytes(b),Map.class);
  }
  @SuppressWarnings("unchecked") private static Map<String,Object> map(Map<String,Object> p,String k){return (Map<String,Object>)p.get(k);}
}
