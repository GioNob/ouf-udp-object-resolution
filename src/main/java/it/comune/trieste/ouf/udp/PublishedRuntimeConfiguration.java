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
@org.springframework.boot.context.properties.EnableConfigurationProperties(ReferenceBatchSettings.class)
public class PublishedRuntimeConfiguration {
  private final ReferenceBatchSettings limits;
  private final GatewayReadPacing pacing = new GatewayReadPacing();
  private final ObjectMapper json;private final URI gateway;private final Path token;private final String tenant;private final HttpClient http;
  public PublishedRuntimeConfiguration(ObjectMapper json,@Value("${ouf.udp.execution.gateway-url}") String gateway,@Value("${ouf.udp.execution.token-file}") String token,@Value("${ouf.udp.lake.tenant-id}") String tenant, ReferenceBatchSettings limits){
    this.limits=limits;
    this.json=json;this.gateway=URI.create(gateway);this.token=Path.of(token);this.tenant=tenant;
    if(tenant.isBlank()||!Set.of("http","https").contains(this.gateway.getScheme())||this.gateway.getHost()==null||this.gateway.getUserInfo()!=null||this.gateway.getQuery()!=null||this.gateway.getFragment()!=null||!Set.of("","/").contains(this.gateway.getPath()))throw new IllegalArgumentException("UDP_GATEWAY_INVALID");
    http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  }
  public Profiles resolve(String ref,String type){
    return resolve(ref,type,publication(ref),null);
  }
  Profiles resolveForSource(String ref,String type,String source){
    var envelope=publication(ref);
    if(!source.equals(envelope.get("sourceId")))throw new IllegalStateException("UDP_API_PUBLICATION_SOURCE_MISMATCH");
    return resolve(ref,type,envelope,null);
  }
  private Map<String,Object> publication(String ref){
    return read(exchange("/api/onboarding/v1/runtime/publications/resolve?bundleRef="+escape(ref),null));
  }
  private Profiles resolve(String ref,String type,Map<String,Object> envelope,Map<String,Object> refs){
    var bundle=object(envelope,"bundle");var unsigned=new TreeMap<>(bundle);unsigned.remove("checksum");
    Object rawBindings=bundle.get("semanticReferenceBindings");
    if(rawBindings instanceof List<?> pins && pins.size()>limits.references())throw invalid();
    String hash=canonicalPublicationHash(json,unsigned);
    if(!tenant.equals(envelope.get("tenantId"))||!hash.equals(bundle.get("checksum"))||!hash.equals(envelope.get("checksum"))||!ref.equals(text(bundle,"bundleId")+":"+text(bundle,"bundleVersion")+":"+hash))throw invalid();
    var semantic=object(bundle,"semanticMapping");var source=object(semantic,"sourceType");
    if(!type.equals(source.get("typeCode"))||!envelope.get("sourceId").equals(source.get("sourceId")))throw invalid();
    var profile=object(object(object(bundle,"extractionProfile"),"runtime"),"udp");
    var resolutionDefinition=object(profile,"resolution");
    boolean governed=resolutionDefinition.containsKey("governedIdentity");
    if(governed&&!Set.of("strategyId","strategyVersion","policyRef","governedIdentity")
        .equals(resolutionDefinition.keySet()))throw new IllegalArgumentException("UDP_GOVERNED_IDENTITY_PROFILE_INVALID");
    if(!governed&&!Set.of("strategyId","strategyVersion","policyRef","canonicalType","canonicalKeyProperty","matchProperty","weighted")
        .containsAll(resolutionDefinition.keySet()))throw new IllegalArgumentException("UDP_RESOLUTION_PROFILE_UNSUPPORTED");
    UdpPorts.ResolutionProfile resolution=governed?null:json.convertValue(resolutionDefinition,UdpPorts.ResolutionProfile.class);
    var compositions=compositions(semantic);
    var rawMaterialization=object(profile,"materialization");
    if(!(rawMaterialization.get("properties") instanceof List<?> rawProperties))throw invalid();
    boolean structured=rawProperties.stream().map(PublishedValueTypes::object).map(p->text(p,"datatype"))
        .anyMatch(d->CanonicalValueType.absolute(d)&&!CanonicalValueType.BASE.containsKey(d));
    if(refs!=null){
      var execution=object(object(object(bundle,"extractionProfile"),"runtime"),"execution");
      for(String key:List.of("sourceSchemaRef","semanticPublicationSetRef","adapterProfileRef"))if(!text(execution,key).equals(text(refs,key)))throw invalid();
      for(String key:List.of("mappingRefs","authorityPolicyRef","relationshipResolutionStrategyRefs"))if(!Objects.equals(execution.get(key),refs.get(key)))throw invalid();
    }
    boolean embeddedDefinitions=rawBindings instanceof List<?> bindings
        && bindings.stream().anyMatch(b->b instanceof Map<?,?> pin && PublishedValueTypes.hasEmbeddedDefinition(pin));
    var types = refs!=null || structured || embeddedDefinitions ? verifyBindings(bundle,structured) : new PublishedValueTypes();
    var materialization=materialization(json,rawMaterialization,compositions,types);
    if(!governed&&java.util.stream.Stream.of(resolution.strategyId(),resolution.strategyVersion(),resolution.policyRef(),resolution.canonicalType(),
        resolution.canonicalKeyProperty(),resolution.matchProperty()).anyMatch(v->v==null||v.isBlank()))
      throw new IllegalArgumentException("UDP_RESOLUTION_PROFILE_UNSUPPORTED");
    if(materialization.properties().isEmpty())throw invalid();
    String canonicalClass=governed?text(object(resolutionDefinition,"governedIdentity"),"canonicalClass"):resolution.canonicalType();
    if(!(semantic.get("targetClasses") instanceof List<?> classes)||classes.stream().noneMatch(c->c instanceof Map<?,?> target&&canonicalClass.equals(target.get("classIri"))))throw invalid();
    var labels=new HashMap<String,String>();
    if(!(bundle.get("dataAccessPolicies") instanceof List<?> policies)||policies.isEmpty())throw invalid();
    for(Object p:policies){if(!(p instanceof Map<?,?> policy)||!Set.of("PROPERTY","RELATIONSHIP").contains(policy.get("scope")))throw invalid();labels.put(String.valueOf(policy.get("scope"))+":"+policy.get("target"),String.valueOf(policy.get("label")));}
    if(!(semantic.get("propertyMappings") instanceof List<?> mappings)||(mappings.isEmpty()&&compositions.isEmpty()))throw invalid();
    var targets=new HashSet<String>();for(Object m:mappings){if(!(m instanceof Map<?,?> mapping))throw invalid();if(!targets.add(String.valueOf(mapping.get("targetPropertyIri"))))throw invalid();}
    for(var entry:compositions.entrySet())if(!targets.add(entry.getKey()) ||
        !text(entry.getValue(),"dataAccessLabel").equals(labels.get("PROPERTY:"+entry.getKey())))throw invalid();
    for(var property:materialization.properties())if(!targets.remove(property.propertyIri())||!property.sourceField().equals(property.propertyIri())||!property.accessLabel().equals(labels.get("PROPERTY:"+property.propertyIri())))throw invalid();
    if(!targets.isEmpty())throw invalid();
    GovernedIdentityEngine.Policy identity=governed?PublishedIdentityPolicy.decode(json,resolutionDefinition,
        tenant,text(source,"sourceId"),canonicalClass,Set.copyOf(materialization.properties().stream()
            .map(UdpPorts.PropertyRule::propertyIri).toList())):null;
    if(identity!=null) validateComparators(identity,materialization);
    UdpPorts.SpatialProfile spatial=null;
    if(profile.containsKey("spatial")){
      spatial=json.convertValue(object(profile,"spatial"),UdpPorts.SpatialProfile.class);
      if(spatial.geometry()==null||spatial.policyRef()==null||spatial.policyRef().isBlank()||spatial.relationships()==null||!spatial.geometry().accessLabel().equals(labels.get("PROPERTY:"+spatial.geometry().sourceField())))throw invalid();
      // Spatial relationship policy binding is deferred to R2e; do not accept unbound edge labels.
      if(!spatial.relationships().isEmpty())throw invalid();
    }
    if(resolution!=null&&resolution.weighted()!=null){
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
    return new Profiles(bundle,resolution,materialization,spatial,relationships,identity);
  }
  public HistoricalContractCatalog.Resolution resolveContracts(Map<String,Object> refs){
    String ref=text(refs,"bundleRef");
    var envelope=publication(ref);
    String type=text(object(object(object(envelope,"bundle"),"semanticMapping"),"sourceType"),"typeCode");
    var bundle=resolve(ref,type,envelope,refs).bundle();
    var resolved=new ArrayList<HistoricalContractCatalog.ResolvedRef>();
    resolved.add(new HistoricalContractCatalog.ResolvedRef("BUNDLE",ref,text(bundle,"bundleVersion"),text(bundle,"checksum")));
    return new HistoricalContractCatalog.Resolution(List.copyOf(resolved),List.of(),contractRefsHash(refs));
  }
  private PublishedValueTypes verifyBindings(Map<String,Object> bundle,boolean structured){
    var execution=object(object(object(bundle,"extractionProfile"),"runtime"),"execution");
    if (!(bundle.get("semanticReferenceBindings") instanceof List<?> bindings) || bindings.isEmpty()) throw invalid();
    String publication=text(execution,"semanticPublicationSetRef");
    if (bindings.stream().map(PublishedValueTypes::object).noneMatch(pin -> publication.equals(uuid(pin,"publicationSetId"))))
      throw invalid();
    return new SemanticReferenceBatch(this,json,limits,bindings,structured).verify();
  }
  static Map<String,Map<String,Object>> compositions(Map<String,Object> semantic){
    var result=new HashMap<String,Map<String,Object>>();
    if(!(semantic.getOrDefault("compositions",List.of()) instanceof List<?> items))throw invalid();
    for(Object raw:items){
      var composition=PublishedValueTypes.object(raw);
      if(result.putIfAbsent(text(composition,"targetPropertyIri"),composition)!=null)throw invalid();
    }
    return result;
  }
  static UdpPorts.MaterializationProfile materialization(ObjectMapper json,Map<String,Object> raw,
      Map<String,Map<String,Object>> compositions,PublishedValueTypes types){
    if(!(raw.get("properties") instanceof List<?> properties))throw invalid();
    var rules=new ArrayList<Map<String,Object>>();
    for(Object item:properties){
      var rule=new LinkedHashMap<>(PublishedValueTypes.object(item));
      if(rule.containsKey("valueType"))throw invalid(); // Only the registry verification path builds this shape.
      var composition=compositions.get(text(rule,"propertyIri"));
      String datatype=text(rule,"datatype");
      if(composition!=null)rule.put("valueType",types.composition(composition,datatype));
      else if(CanonicalValueType.absolute(datatype)&&!CanonicalValueType.BASE.containsKey(datatype))
        rule.put("valueType",types.pinned(datatype));
      else if("list".equals(datatype))throw CanonicalValueType.definitionError();
      rules.add(rule);
    }
    var projected=new LinkedHashMap<>(raw);projected.put("properties",rules);
    return json.convertValue(projected,UdpPorts.MaterializationProfile.class);
  }
  static void validateComparators(GovernedIdentityEngine.Policy policy,UdpPorts.MaterializationProfile materialization){
    var comparators=new HashMap<String,GovernedIdentityEngine.ComparatorKind>();
    for(var signal:policy.signals())comparators.put(signal.id(),signal.comparator());
    for(var rule:materialization.properties()){
      var type=rule.canonicalType();
      if(type==null || !comparators.containsKey(rule.propertyIri()))continue;
      var expected=rule.reconciliation()!=null&&"MULTI".equals(rule.reconciliation().cardinality())
          ?GovernedIdentityEngine.ComparatorKind.JSON_V1:type.comparator();
      if(comparators.get(rule.propertyIri())!=expected)throw new IllegalArgumentException("UDP_IDENTITY_DATATYPE_COMPARATOR_INVALID");
    }
  }
  HttpResponse<byte[]> exchange(String path,byte[] body){
    boolean publication=path.startsWith("/api/onboarding/");
    boolean legacy=!publication && body==null;
    var route=publication ? GatewayReadPacing.Route.PUBLICATION : legacy ? GatewayReadPacing.Route.LEGACY : GatewayReadPacing.Route.BATCH;
    pacing.before(route,legacy ? limits.fallbackIntervalMs() : limits.gatewayIntervalMs());

    String credential;
    try{credential=Files.readString(token).strip();}
    catch(java.io.IOException failure){throw PublicationAccessFailure.credential();}
    if(credential.isEmpty()||credential.length()>16384||credential.chars().anyMatch(Character::isWhitespace))
      throw PublicationAccessFailure.credential();
    var builder=HttpRequest.newBuilder(gateway.resolve(path)).timeout(Duration.ofSeconds(4))
        .header("Authorization","Bearer "+credential);
    var request=body==null ? builder.GET().build() : builder.header("Content-Type","application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    var pending=http.sendAsync(request,info->info.statusCode()==200
        ? new LimitedResponse(publication ? limits.bundleBytes() : 2*1024*1024)
        : HttpResponse.BodySubscribers.replacing(new byte[0]));
    HttpResponse<byte[]> response;
    try{response=pending.get(5,TimeUnit.SECONDS);}
    catch(TimeoutException failure){pending.cancel(true);throw PublicationAccessFailure.network();}
    catch(InterruptedException failure){pending.cancel(true);Thread.currentThread().interrupt();throw PublicationAccessFailure.network();}
    catch(ExecutionException failure){
      pending.cancel(true);Throwable cause=failure.getCause();
      for(Throwable item=cause;item!=null;item=item.getCause()){
        if(item instanceof javax.net.ssl.SSLException)throw PublicationAccessFailure.invalid();
        if(item instanceof PublicationAccessFailure classified)throw classified;
      }
      if(cause instanceof java.io.IOException)throw PublicationAccessFailure.network();
      throw PublicationAccessFailure.invalid();
    }
    var now=java.time.Instant.now();
    String retryAfter=response.headers().firstValue("Retry-After").orElse(null);
    boolean throttled=response.statusCode()==429 || response.statusCode()==503;
    var admission=throttled ? pacing.deferral(route,response.headers(),retryAfter,now)
        : pacing.observe(route,response.headers(),now,null);
    if (response.statusCode()!=200 && !(body!=null && Set.of(404,405,413).contains(response.statusCode()))
        && !(legacy && Set.of(404,410).contains(response.statusCode()))) {
      throw PublicationAccessFailure.status(response.statusCode(),throttled?null:retryAfter,now,admission);
    }
    return response;
  }
  @org.springframework.beans.factory.annotation.Autowired
  void configureGatewayRetry(@Value("${ouf.udp.gateway-retry.max-delay-ms:60000}") long maxDelayMs,
      @Value("${ouf.udp.gateway-retry.jitter-ms:1000}") long jitterMs) {
    pacing.configure(maxDelayMs,jitterMs);
  }
  GatewayReadPacing pacing(){return pacing;}
  @SuppressWarnings("unchecked") Map<String,Object> read(HttpResponse<byte[]> response){
    try{
      Map<String,Object> result=json.readValue(response.body(),Map.class);
      if(result==null)throw PublicationAccessFailure.invalid();
      return result;
    }
    catch(java.io.IOException failure){throw PublicationAccessFailure.invalid();}
  }
  private String contractRefsHash(Map<String,Object> refs){try{return sha256(json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsBytes(refs));}catch(Exception e){throw invalid();}}
  static String canonicalPublicationHash(ObjectMapper json,Object value){
    // Onboarding and the Registry encode compact JSON as UTF-8, including supplementary characters.
    try{return sha256(json.writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).without(SerializationFeature.INDENT_OUTPUT)
        .writeValueAsString(value).getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw invalid();}
  }
  private static String sha256(byte[] bytes)throws java.security.NoSuchAlgorithmException{
    return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
  @SuppressWarnings("unchecked") static Map<String,Object> object(Map<String,Object> parent,String key){if(!(parent.get(key) instanceof Map<?,?> value))throw invalid();return (Map<String,Object>)value;}
  static String text(Map<String,Object> parent,String key){if(!(parent.get(key) instanceof String value)||value.isBlank())throw invalid();return value;}
  static String uuid(Map<String,Object> binding,String field){
    try{
      String value=text(binding,field);
      String canonical=UUID.fromString(value).toString();
      if(!canonical.equalsIgnoreCase(value))throw invalid();
      return canonical;
    }catch(IllegalArgumentException failure){throw invalid();}
  }
  private static String escape(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
  static IllegalArgumentException invalid(){return new IllegalArgumentException("UDP_PINNED_PROFILE_INVALID");}
  public record Profiles(Map<String,Object> bundle,UdpPorts.ResolutionProfile resolution,UdpPorts.MaterializationProfile materialization,UdpPorts.SpatialProfile spatial,UdpPorts.RelationshipProfile relationships,GovernedIdentityEngine.Policy identity){
    public Profiles(Map<String,Object> bundle,UdpPorts.ResolutionProfile resolution,UdpPorts.MaterializationProfile materialization,UdpPorts.SpatialProfile spatial){this(bundle,resolution,materialization,spatial,null,null);}
    public Profiles(Map<String,Object> bundle,UdpPorts.ResolutionProfile resolution,UdpPorts.MaterializationProfile materialization,UdpPorts.SpatialProfile spatial,UdpPorts.RelationshipProfile relationships){this(bundle,resolution,materialization,spatial,relationships,null);}
    public String canonicalType(){return identity==null?resolution.canonicalType():identity.canonicalClass();}
  }
  private static final class LimitedResponse implements HttpResponse.BodySubscriber<byte[]>{
    private final int limit;
    LimitedResponse(int limit){this.limit=limit;}
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();private java.util.concurrent.Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody(){return result;}public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;s.request(1);}
    public void onNext(List<java.nio.ByteBuffer> buffers){for(var b:buffers){if(b.remaining()>limit-bytes.size()){result.completeExceptionally(PublicationAccessFailure.invalid());subscription.cancel();return;}byte[] chunk=new byte[b.remaining()];b.get(chunk);bytes.writeBytes(chunk);}subscription.request(1);}
    public void onError(Throwable e){result.completeExceptionally(e);}public void onComplete(){result.complete(bytes.toByteArray());}
  }
}
