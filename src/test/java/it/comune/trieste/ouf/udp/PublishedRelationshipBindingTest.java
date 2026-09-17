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
  @Test void cannotConvertAmbiguousMatchingIntoAnAutomaticFirstMatch()throws Exception{
    var b=bundle();map(((List<Map<String,Object>>)b.get("relationshipMappings")).getFirst(),"resolution").put("onMultipleMatches","FIRST");
    assertThatThrownBy(()->resolve(b)).hasMessage("UDP_PINNED_PROFILE_INVALID");
  }
  private PublishedRuntimeConfiguration.Profiles resolve(Map<String,Object> bundle)throws Exception{
    bundle.remove("checksum");String hash="sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsBytes(new TreeMap<>(bundle))));bundle.put("checksum",hash);
    byte[] response=json.writeValueAsBytes(Map.of("tenantId","tenant-a","sourceId","cameras","checksum",hash,"bundle",bundle));
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{exchange.sendResponseHeaders(200,response.length);try(var output=exchange.getResponseBody()){output.write(response);}});server.start();
    try{Path token=temp.resolve("token");Files.writeString(token,"fixture");return new PublishedRuntimeConfiguration(json,"http://127.0.0.1:"+server.getAddress().getPort(),token.toString(),"tenant-a").resolve("bundle:1:"+hash,"FILE");}finally{server.stop(0);}
  }
  private Map<String,Object> bundle()throws Exception{
    var rule=Map.of("sourceField","ref","relationIri","connectedTo","targetCanonicalType","Cabinet","targetPropertyIri","code","resolutionStrategy","CANONICAL_KEY","onNoMatch","QUARANTINE_RELATION","accessLabel","OPEN","selfLoopAllowed",false);
    var udp=Map.of("resolution",Map.of("strategyId","key","strategyVersion","1","policyRef","policy://identity/1","canonicalType","Camera","canonicalKeyProperty","ref","matchProperty","ref"),"materialization",Map.of("policyRef","policy://authority/1","checkpointInterval",1,"properties",List.of(Map.of("sourceField","ref","propertyIri","ref","datatype","string","accessLabel","OPEN","authorityOrder",List.of()))),"relationships",Map.of("policyRef","policy://relations/1","relationships",List.of(rule)));
    var b=Map.of("bundleId","bundle","bundleVersion","1","semanticMapping",Map.of("sourceType",Map.of("sourceId","cameras","typeCode","FILE"),"targetClasses",List.of(Map.of("classIri","Camera")),"propertyMappings",List.of(Map.of("sourceField","cabinet","targetPropertyIri","ref"))),"extractionProfile",Map.of("runtime",Map.of("udp",udp,"execution",Map.of("relationshipResolutionStrategyRefs",List.of("relationship://camera-cabinet/1")))),"dataAccessPolicies",List.of(Map.of("scope","PROPERTY","target","ref","label","OPEN"),Map.of("scope","RELATIONSHIP","target","connectedTo","label","OPEN")),"relationshipMappings",List.of(Map.of("mappingId","relationship://camera-cabinet/1","sourceField","cabinet","relationIri","connectedTo","targetClassIri","Cabinet","resolution",Map.of("strategy","CANONICAL_KEY","targetKeyProperty","code","onNoMatch","QUARANTINE_RELATION","onMultipleMatches","REVIEW_REQUIRED"))));
    return json.readValue(json.writeValueAsBytes(b),Map.class);
  }
  @SuppressWarnings("unchecked") private static Map<String,Object> map(Map<String,Object> p,String k){return (Map<String,Object>)p.get(k);}
}
