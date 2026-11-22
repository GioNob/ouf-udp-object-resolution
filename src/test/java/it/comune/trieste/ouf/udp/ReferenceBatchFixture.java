package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static it.comune.trieste.ouf.udp.PublishedRuntimeConfiguration.*;

/** Authenticated loopback publication and compact semantic peers for component and DB tests. */
final class ReferenceBatchFixture implements AutoCloseable {
  static final int COUNT=1024;
  static final String SET=new UUID(1,1).toString();
  final ObjectMapper json=new ObjectMapper();
  final AtomicInteger publications=new AtomicInteger(), legacyReads=new AtomicInteger();
  final List<Integer> starts=new CopyOnWriteArrayList<>(), sizes=new CopyOnWriteArrayList<>(), bytes=new CopyOnWriteArrayList<>();
  final HttpServer server;
  final Path token;
  final Map<String,Object> refs;
  final byte[] envelope;
  final String prefix;
  volatile int maxItems=1000, maxBytes=262144, missing=-1, transientAt=-1, transientStatus=429, unsupported=0;
  volatile String versionMode="stable", fault="";
  volatile int faultAt=0;

  ReferenceBatchFixture(Path directory,int count,String suffix,int padding) throws Exception {
    prefix="urn:udp:"+suffix+":";
    var bundle=PublishedRelationshipBindingTest.bundle();
    var runtime=object(object(bundle,"extractionProfile"),"runtime");
    var udp=object(runtime,"udp");
    udp.remove("relationships");
    var execution=object(runtime,"execution");
    execution.put("sourceSchemaRef","schema:1");
    execution.put("adapterProfileRef","adapter:1");
    execution.put("semanticPublicationSetRef",SET);
    var pins=new ArrayList<Map<String,Object>>();
    var mappings=new ArrayList<Map<String,Object>>();
    var rules=new ArrayList<Map<String,Object>>();
    var labels=new ArrayList<Map<String,Object>>();
    var columns=new ArrayList<String>();
    for(int i=0;i<count;i++){
      String id=id(i), column="col"+i;
      columns.add(column);
      pins.add(Map.of("semanticId",id,"semanticVersion","1","revisionId",revision(i),"publicationSetId",SET));
      mappings.add(Map.of("sourceField",column,"targetPropertyIri",id));
      rules.add(Map.of("sourceField",id,"propertyIri",id,"datatype","string","accessLabel","OPEN","authorityOrder",List.of()));
      labels.add(Map.of("scope","PROPERTY","target",id,"label","OPEN"));
    }
    object(udp,"resolution").put("matchProperty",id(0));
    object(udp,"resolution").put("canonicalKeyProperty",id(0));
    object(bundle,"extractionProfile").put("projection",Map.of("FILE",columns));
    object(bundle,"semanticMapping").put("propertyMappings",mappings);
    object(udp,"materialization").put("properties",rules);
    bundle.put("dataAccessPolicies",labels);
    bundle.put("semanticReferenceBindings",pins);
    if(padding>0)bundle.put("description","x".repeat(padding));
    String hash=canonicalPublicationHash(json,bundle);
    bundle.put("checksum",hash);
    refs=new HashMap<>(execution);
    refs.put("bundleRef","bundle:1:"+hash);
    envelope=json.writeValueAsBytes(Map.of("tenantId","tenant-a","sourceId","cameras","checksum",hash,"bundle",bundle));
    token=Files.writeString(directory.resolve("token"),"fixture");
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",this::handle);
    server.start();
  }

  String id(int index){return prefix+index;}
  String revision(int index){return new UUID(0,index+1).toString();}
  PublishedRuntimeConfiguration client(ReferenceBatchSettings settings){
    return new PublishedRuntimeConfiguration(json,"http://127.0.0.1:"+server.getAddress().getPort(),token.toString(),"tenant-a",settings);
  }
  PublishedRuntimeConfiguration client(){
    return client(new ReferenceBatchSettings(262144,1000,2049,3145728,3,1,1));
  }
  private void handle(HttpExchange exchange) throws IOException {
    try(exchange){
      if(!"Bearer fixture".equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.sendResponseHeaders(403,-1);return;}
      String path=exchange.getRequestURI().getPath();
      if(path.equals("/api/onboarding/v1/runtime/publications/resolve") && exchange.getRequestMethod().equals("GET")){
        publications.incrementAndGet();
        send(exchange,envelope);
      } else if(path.equals(SemanticReferenceBatch.PATH) && exchange.getRequestMethod().equals("GET")){
        legacyReads.incrementAndGet();
        var query=new HashMap<String,String>();
        for(String pair:exchange.getRequestURI().getRawQuery().split("&")){
          String[] parts=pair.split("=",2);
          query.put(parts[0],URLDecoder.decode(parts[1],StandardCharsets.UTF_8));
        }
        int index=index(query);
        if(index==missing){exchange.sendResponseHeaders(404,-1);return;}
        send(exchange,json.writeValueAsBytes(actual(index)));
      } else if(path.equals(SemanticReferenceBatch.PATH+"-batch") && exchange.getRequestMethod().equals("POST")){
        if(unsupported!=0){exchange.sendResponseHeaders(unsupported,-1);return;}
        byte[] body=exchange.getRequestBody().readAllBytes();
        var root=json.readTree(body);
        var items=root.required("references");
        int start=index(json.convertValue(items.get(0),Map.class));
        starts.add(start);sizes.add(items.size());bytes.add(body.length);
        if(body.length>maxBytes || items.size()>maxItems){exchange.sendResponseHeaders(413,-1);return;}
        if(start==transientAt){
          transientAt=-1;
          exchange.getResponseHeaders().add("Retry-After","1");
          exchange.getResponseHeaders().add("X-RateLimit-Limit","10");
          exchange.getResponseHeaders().add("X-RateLimit-Remaining","0");
          exchange.getResponseHeaders().add("X-RateLimit-Reset","2");
          exchange.sendResponseHeaders(transientStatus,-1);return;
        }
        var results=new ArrayList<Map<String,Object>>();
        for(int i=0;i<items.size();i++){
          Map<String,Object> row=json.convertValue(items.get(i),Map.class);
          int index=index(row);
          row.put("index",i);
          row.put("outcome",index==missing?"NOT_RESOLVABLE":"RESOLVED");
          if(index!=missing)row.put("resolved",actual(index));
          if(index==faultAt){
            if(fault.equals("tuple"))row.put("revisionId",revision(index+1));
            if(fault.equals("index"))row.put("index",i+1);
            if(fault.equals("omit"))continue;
            if(fault.equals("duplicate"))results.add(row);
          }
          results.add(row);
        }
        String version=switch(versionMode){
          case "once" -> starts.size()==1?"old":"new";
          case "always" -> start==0?"old":"new";
          default -> "current";
        };
        send(exchange,json.writeValueAsBytes(Map.of("contract","ouf.semantic.reference-batch.v1","registryVersion",version,"results",results)));
      } else exchange.sendResponseHeaders(405,-1);
    }
  }
  private int index(Map<?,?> tuple){
    int index=Integer.parseInt(tuple.get("semanticId").toString().substring(prefix.length()));
    if(tuple.size()!=3 || !id(index).equals(tuple.get("semanticId")) || !revision(index).equals(tuple.get("revisionId"))
        || !SET.equals(tuple.get("publicationSetId")))throw new IllegalArgumentException("unexpected test tuple");
    return index;
  }
  private Map<String,Object> actual(int i){
    return Map.of("semantic_id",id(i),"revision_id",revision(i),"publication_set_id",SET,
        "semantic_version",fault.equals("version") && faultAt==i?"2":"1","artifact_type","PROPERTY",
        "status",fault.equals("revoked") && faultAt==i?"DRAFT":"ACTIVE","content_hash","fixture","publication_checksum","fixture");
  }
  private void send(HttpExchange exchange,byte[] body) throws IOException{
    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
  }
  public void close(){server.stop(0);}
}
