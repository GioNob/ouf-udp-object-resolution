package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Exact historical publication resolution over the Gateway, including checksum validation. */
@Component @ConditionalOnProperty(name="ouf.udp.execution.enabled",havingValue="true")
public class PublishedRuntimeConfiguration {
  private final ObjectMapper json;private final URI gateway;private final Path token;private final String tenant;private final HttpClient http;
  public PublishedRuntimeConfiguration(ObjectMapper json,@Value("${ouf.udp.execution.gateway-url}") String gateway,@Value("${ouf.udp.execution.token-file}") String token,@Value("${ouf.udp.lake.tenant-id}") String tenant){
    this.json=json;this.gateway=URI.create(gateway);this.token=Path.of(token);this.tenant=tenant;
    if(tenant.isBlank()||!Set.of("http","https").contains(this.gateway.getScheme())||this.gateway.getHost()==null||this.gateway.getUserInfo()!=null||this.gateway.getQuery()!=null||this.gateway.getFragment()!=null||!Set.of("","/").contains(this.gateway.getPath()))throw new IllegalArgumentException("UDP_GATEWAY_INVALID");
    http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  }
  public Profiles resolve(String ref,String type){
    var envelope=get("/api/onboarding/v1/runtime/publications/resolve?bundleRef="+escape(ref));
    var bundle=object(envelope,"bundle");var unsigned=new TreeMap<>(bundle);unsigned.remove("checksum");
    String hash=hash(unsigned);
    if(!tenant.equals(envelope.get("tenantId"))||!hash.equals(bundle.get("checksum"))||!hash.equals(envelope.get("checksum"))||!ref.equals(text(bundle,"bundleId")+":"+text(bundle,"bundleVersion")+":"+hash))throw invalid();
    var semantic=object(bundle,"semanticMapping");var source=object(semantic,"sourceType");
    if(!type.equals(source.get("typeCode"))||!envelope.get("sourceId").equals(source.get("sourceId")))throw invalid();
    var profile=object(object(object(bundle,"extractionProfile"),"runtime"),"udp");
    UdpPorts.ResolutionProfile resolution=json.convertValue(object(profile,"resolution"),UdpPorts.ResolutionProfile.class);
    UdpPorts.MaterializationProfile materialization=json.convertValue(object(profile,"materialization"),UdpPorts.MaterializationProfile.class);
    if(resolution.canonicalType()==null||resolution.policyRef()==null||materialization.properties().isEmpty())throw invalid();
    if(!(semantic.get("targetClasses") instanceof List<?> classes)||classes.stream().noneMatch(c->c instanceof Map<?,?> target&&resolution.canonicalType().equals(target.get("classIri"))))throw invalid();
    var labels=new HashMap<String,String>();
    if(!(bundle.get("dataAccessPolicies") instanceof List<?> policies)||policies.isEmpty())throw invalid();
    for(Object p:policies){if(!(p instanceof Map<?,?> policy)||!Set.of("PROPERTY","RELATIONSHIP").contains(policy.get("scope")))throw invalid();labels.put(String.valueOf(policy.get("scope"))+":"+policy.get("target"),String.valueOf(policy.get("label")));}
    if(!(semantic.get("propertyMappings") instanceof List<?> mappings)||mappings.isEmpty())throw invalid();
    var targets=new HashSet<String>();for(Object m:mappings){if(!(m instanceof Map<?,?> mapping))throw invalid();targets.add(String.valueOf(mapping.get("targetPropertyIri")));}
    for(var property:materialization.properties())if(!targets.remove(property.propertyIri())||!property.sourceField().equals(property.propertyIri())||!property.accessLabel().equals(labels.get("PROPERTY:"+property.propertyIri())))throw invalid();
    if(!targets.isEmpty())throw invalid();
    UdpPorts.SpatialProfile spatial=null;
    if(profile.containsKey("spatial")){
      spatial=json.convertValue(object(profile,"spatial"),UdpPorts.SpatialProfile.class);
      if(spatial.geometry()==null||spatial.policyRef()==null||spatial.policyRef().isBlank()||spatial.relationships()==null||!spatial.geometry().accessLabel().equals(labels.get("PROPERTY:"+spatial.geometry().sourceField())))throw invalid();
      // Spatial relationship policy binding is deferred to R2e; do not accept unbound edge labels.
      if(!spatial.relationships().isEmpty())throw invalid();
    }
    if(resolution.weighted()!=null){
      Set<String> mapped=materialization.properties().stream().map(UdpPorts.PropertyRule::propertyIri).collect(java.util.stream.Collectors.toSet());
      if(!mapped.containsAll(resolution.weighted().blockingProperties()))throw invalid();
      for(var signal:resolution.weighted().signals()){
        if(!mapped.contains(signal.property()))throw invalid();
        if(signal.spatial()&&(spatial==null||!signal.property().equals(spatial.geometry().sourceField())))throw invalid();
      }
      if(resolution.weighted().blockingDistanceMeters()!=null&&spatial==null)throw invalid();
    }
    UdpPorts.RelationshipProfile relationships=null;
    if(profile.containsKey("relationships")){
      relationships=json.convertValue(object(profile,"relationships"),UdpPorts.RelationshipProfile.class);
      if(relationships.policyRef()==null||relationships.policyRef().isBlank()||relationships.relationships().isEmpty()||relationships.relationships().size()>64)throw invalid();
      var execution=object(object(object(bundle,"extractionProfile"),"runtime"),"execution");
      if(!(execution.get("relationshipResolutionStrategyRefs") instanceof List<?> strategies)||strategies.isEmpty())throw invalid();
      if(!(bundle.get("relationshipMappings") instanceof List<?> declared))throw invalid();
      var unique=new HashSet<String>();
      for(var rule:relationships.relationships()){
        if(!unique.add(rule.relationIri())||!"CANONICAL_KEY".equals(rule.resolutionStrategy())||!"QUARANTINE_RELATION".equals(rule.onNoMatch())||rule.targetPropertyIri()==null||rule.targetPropertyIri().isBlank()||!Objects.equals(rule.accessLabel(),labels.get("RELATIONSHIP:"+rule.relationIri())))throw invalid();
        boolean bound=false;
        for(Object raw:declared){if(!(raw instanceof Map<?,?> d))throw invalid();
          var mapped=mappings.stream().filter(m->m instanceof Map<?,?> mm&&Objects.equals(mm.get("sourceField"),d.get("sourceField"))&&Objects.equals(mm.get("targetPropertyIri"),rule.sourceField())).findFirst();
          if(mapped.isPresent()&&Objects.equals(d.get("relationIri"),rule.relationIri())&&Objects.equals(d.get("targetClassIri"),rule.targetCanonicalType())&&d.get("resolution") instanceof Map<?,?> r&&Objects.equals(r.get("strategy"),rule.resolutionStrategy())&&Objects.equals(r.get("onNoMatch"),rule.onNoMatch())&&Objects.equals(r.get("targetKeyProperty"),rule.targetPropertyIri())&&"REVIEW_REQUIRED".equals(r.get("onMultipleMatches"))&&strategies.contains(d.get("mappingId")))bound=true;
        }
        if(!bound)throw invalid();
      }
    }
    return new Profiles(bundle,resolution,materialization,spatial,relationships);
  }
  public HistoricalContractCatalog.Resolution resolveContracts(Map<String,Object> refs){
    String ref=text(refs,"bundleRef");
    var envelope=get("/api/onboarding/v1/runtime/publications/resolve?bundleRef="+escape(ref));
    String type=text(object(object(object(envelope,"bundle"),"semanticMapping"),"sourceType"),"typeCode");
    var profiles=resolve(ref,type);var bundle=profiles.bundle();
    var execution=object(object(object(bundle,"extractionProfile"),"runtime"),"execution");
    var resolved=new ArrayList<HistoricalContractCatalog.ResolvedRef>();
    for(String key:List.of("sourceSchemaRef","semanticPublicationSetRef","adapterProfileRef"))if(!text(execution,key).equals(text(refs,key)))throw invalid();
    for(String key:List.of("mappingRefs","authorityPolicyRef","relationshipResolutionStrategyRefs"))if(!Objects.equals(execution.get(key),refs.get(key)))throw invalid();
    Object raw=bundle.get("semanticReferenceBindings");if(!(raw instanceof List<?> bindings)||bindings.isEmpty()||bindings.size()>20)throw invalid();
    boolean publicationFound=false;
    for(Object item:bindings){if(!(item instanceof Map<?,?>))throw invalid();@SuppressWarnings("unchecked")var binding=(Map<String,Object>)item;
      String id=text(binding,"semanticId"),revision=UUID.fromString(text(binding,"revisionId")).toString(),publication=UUID.fromString(text(binding,"publicationSetId")).toString();
      var actual=get("/api/semantic/v1/references:resolve?semanticId="+escape(id)+"&revisionId="+revision+"&publicationSetId="+publication);
      if(!id.equals(actual.get("semantic_id"))||!text(binding,"semanticVersion").equals(actual.get("semantic_version"))||!revision.equals(actual.get("revision_id"))||!publication.equals(actual.get("publication_set_id")))throw invalid();
      publicationFound|=publication.equals(refs.get("semanticPublicationSetRef"));
    }
    if(!publicationFound)throw invalid();
    resolved.add(new HistoricalContractCatalog.ResolvedRef("BUNDLE",ref,text(bundle,"bundleVersion"),text(bundle,"checksum")));
    return new HistoricalContractCatalog.Resolution(List.copyOf(resolved),List.of(),hash(refs));
  }
  @SuppressWarnings("unchecked") private Map<String,Object> get(String path){
    try{
      String credential=Files.readString(token).strip();if(credential.isEmpty()||credential.length()>16384||credential.chars().anyMatch(Character::isWhitespace))throw invalid();
      var request=HttpRequest.newBuilder(gateway.resolve(path)).timeout(Duration.ofSeconds(4)).header("Authorization","Bearer "+credential).GET().build();
      var pending=http.sendAsync(request,info->new LimitedResponse());HttpResponse<byte[]> response;
      try{response=pending.get(5,TimeUnit.SECONDS);}catch(TimeoutException|ExecutionException e){pending.cancel(true);throw new IllegalStateException("UDP_PUBLICATION_UNAVAILABLE");}
      if(response.statusCode()!=200)throw new IllegalStateException("UDP_PUBLICATION_UNAVAILABLE");return json.readValue(response.body(),Map.class);
    }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("UDP_PUBLICATION_INTERRUPTED");}catch(java.io.IOException e){throw new IllegalStateException("UDP_PUBLICATION_UNAVAILABLE");}
  }
  private String hash(Object value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsBytes(value)));}catch(Exception e){throw invalid();}}
  @SuppressWarnings("unchecked") static Map<String,Object> object(Map<String,Object> parent,String key){if(!(parent.get(key) instanceof Map<?,?> value))throw invalid();return (Map<String,Object>)value;}
  static String text(Map<String,Object> parent,String key){if(!(parent.get(key) instanceof String value)||value.isBlank())throw invalid();return value;}
  private static String escape(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("UDP_PINNED_PROFILE_INVALID");}
  public record Profiles(Map<String,Object> bundle,UdpPorts.ResolutionProfile resolution,UdpPorts.MaterializationProfile materialization,UdpPorts.SpatialProfile spatial,UdpPorts.RelationshipProfile relationships){public Profiles(Map<String,Object> bundle,UdpPorts.ResolutionProfile resolution,UdpPorts.MaterializationProfile materialization,UdpPorts.SpatialProfile spatial){this(bundle,resolution,materialization,spatial,null);}}
  private static final class LimitedResponse implements HttpResponse.BodySubscriber<byte[]>{
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();private java.util.concurrent.Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody(){return result;}public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;s.request(1);}
    public void onNext(List<java.nio.ByteBuffer> buffers){for(var b:buffers){if(bytes.size()+b.remaining()>2097152){subscription.cancel();result.completeExceptionally(new java.io.IOException("UDP_RESPONSE_LIMIT"));return;}byte[] chunk=new byte[b.remaining()];b.get(chunk);bytes.writeBytes(chunk);}subscription.request(1);}
    public void onError(Throwable e){result.completeExceptionally(e);}public void onComplete(){result.complete(bytes.toByteArray());}
  }
}
