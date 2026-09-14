package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HistoricalContractCatalog {
  private static final Set<String> KINDS=Set.of("SOURCE_SCHEMA","BUNDLE","SEMANTIC_PUBLICATION_SET","ADAPTER_PROFILE","MAPPING","AUTHORITY_POLICY","RELATIONSHIP_RESOLUTION_STRATEGY");
  private final ObjectMapper json;private final String path;
  public HistoricalContractCatalog(ObjectMapper json,@Value("${ouf.udp.historical-contracts.catalog-path:}") String path){this.json=json;this.path=path;}

  public Resolution resolve(Map<String,Object> contractRefs){
    List<RequiredRef> required=required(contractRefs);Map<String,Entry> catalog=load();List<ResolvedRef> resolved=new ArrayList<>();List<String> missing=new ArrayList<>();
    for(RequiredRef ref:required){Entry entry=catalog.get(key(ref.kind(),ref.ref()));if(entry==null||!"AVAILABLE".equals(entry.status())||!valid(entry,ref)){missing.add(hash(ref.kind()+"\n"+ref.ref()));continue;}resolved.add(new ResolvedRef(ref.kind(),ref.ref(),entry.version(),entry.contentHash()));}
    resolved.sort(Comparator.comparing(ResolvedRef::kind).thenComparing(ResolvedRef::ref));missing.sort(String::compareTo);
    String baselineHash=missing.isEmpty()?hash(write(resolved)):null;return new Resolution(List.copyOf(resolved),List.copyOf(missing),baselineHash);
  }

  private Map<String,Entry> load(){if(path==null||path.isBlank())return Map.of();try{List<Entry> entries=json.readValue(Files.readAllBytes(Path.of(path)),new TypeReference<>(){});Map<String,Entry> result=new HashMap<>();for(Entry e:entries){if(!KINDS.contains(e.kind())||e.ref()==null||e.ref().isBlank()||e.version()==null||e.version().isBlank()||e.contentHash()==null||!e.contentHash().matches("sha256:[0-9a-f]{64}")||!Set.of("AVAILABLE","REVOKED").contains(e.status()))throw new IllegalStateException("UDP_HISTORICAL_CATALOG_INVALID");if(result.put(key(e.kind(),e.ref()),e)!=null)throw new IllegalStateException("UDP_HISTORICAL_CATALOG_DUPLICATE");}return result;}catch(IllegalStateException e){throw e;}catch(Exception e){return Map.of();}}
  private static boolean valid(Entry e,RequiredRef r){return e.kind().equals(r.kind())&&e.ref().equals(r.ref());}
  private static String key(String kind,String ref){return kind+"\n"+ref;}
  private List<RequiredRef> required(Map<String,Object> refs){if(refs==null)throw new IllegalArgumentException("UDP_CONTRACT_REFS_REQUIRED");List<RequiredRef> result=new ArrayList<>();one(result,"SOURCE_SCHEMA",refs.get("sourceSchemaRef"));one(result,"BUNDLE",refs.get("bundleRef"));one(result,"SEMANTIC_PUBLICATION_SET",refs.get("semanticPublicationSetRef"));one(result,"ADAPTER_PROFILE",refs.get("adapterProfileRef"));many(result,"MAPPING",refs.get("mappingRefs"));oneOptional(result,"AUTHORITY_POLICY",refs.get("authorityPolicyRef"));many(result,"RELATIONSHIP_RESOLUTION_STRATEGY",refs.get("relationshipResolutionStrategyRefs"));return result;}
  private static void one(List<RequiredRef> out,String kind,Object value){if(value==null||String.valueOf(value).isBlank())throw new IllegalArgumentException("UDP_CONTRACT_REF_REQUIRED");out.add(new RequiredRef(kind,String.valueOf(value)));}
  private static void oneOptional(List<RequiredRef> out,String kind,Object value){if(value!=null&&!String.valueOf(value).isBlank())out.add(new RequiredRef(kind,String.valueOf(value)));}
  private static void many(List<RequiredRef> out,String kind,Object value){if(value==null)return;if(!(value instanceof Collection<?> values))throw new IllegalArgumentException("UDP_CONTRACT_REF_INVALID");for(Object item:values)one(out,kind,item);}
  private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("UDP_HISTORICAL_BASELINE_INVALID",e);}}
  static String hash(String value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("UDP_HASH_FAILED",e);}}

  public record Entry(String kind,String ref,String version,String contentHash,String status){}
  public record RequiredRef(String kind,String ref){}
  public record ResolvedRef(String kind,String ref,String version,String contentHash){}
  public record Resolution(List<ResolvedRef> refs,List<String> missingRefHashes,String baselineHash){public boolean ready(){return missingRefHashes.isEmpty();}}
}
