package it.comune.trieste.ouf.udp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LakeLifecycleService {
  private final LakeCatalogRepository catalog;private final ObjectProvider<LakeObjectStoragePort> storageProvider;
  public LakeLifecycleService(LakeCatalogRepository catalog,ObjectProvider<LakeObjectStoragePort> storageProvider){this.catalog=catalog;this.storageProvider=storageProvider;}

  public LakeCatalogRepository.LakeObject store(byte[] content,String tenant,String source,String type,String tier,
      String mediaType,String retentionClass,String accessLabel,OffsetDateTime retentionUntil,String correlation){
    validate(tier,retentionClass,retentionUntil);
    String hash=hash(content);UUID id=UUID.randomUUID();
    String locator="lake/"+tenant+"/"+tier.toLowerCase(Locale.ROOT)+"/"+hash.substring(7)+("RAW".equals(tier)?"/"+id:"");
    boolean recoverInPlace="NORMALIZED".equals(tier)||"CURATED".equals(tier);
    LakeCatalogRepository.LakeObject object;
    try{
      object=catalog.stage(id,tenant,source,type,tier,hash,mediaType,content.length,locator,retentionClass,
          accessLabel,retentionUntil,correlation);
    }catch(LakeCatalogRepository.CopyPolicyConflict conflict){
      if(!recoverInPlace)throw conflict;
      throw new StoreRejected(HttpStatus.CONFLICT,conflict.getMessage());
    }
    if("VERIFIED".equals(object.state()))return object;
    if(!recoverInPlace)return storeCopy(object,content,correlation);
    return storeWithLease(object,content,id.toString(),correlation);
  }

  private LakeCatalogRepository.LakeObject storeWithLease(LakeCatalogRepository.LakeObject object,byte[] content,
      String owner,String correlation){
    requireWritable(object);
    var storage=storage();
    var claim=catalog.claimWrite(object,owner,correlation);
    if(claim.isEmpty())return currentOrRetry(object.id());
    object=claim.get();
    if("STAGED".equals(object.state())){
      try{
        put(storage,object,content);
      }catch(RuntimeException failure){
        if(catalog.advanceWrite(object,"FAILED",correlation,Map.of("safeErrorCode","UDP_LAKE_WRITE_FAILED")).isEmpty())
          return currentOrRetry(object.id());
        throw failure;
      }
      var durable=catalog.advanceWrite(object,"DURABLE",correlation,Map.of("locatorFingerprint",fingerprint(object.locator())));
      if(durable.isEmpty())return currentOrRetry(object.id());
      object=durable.get();
    }
    String proofError;
    try{
      proofError=invalidProof(storage,object,content);
    }catch(RuntimeException failure){
      if(catalog.advanceWrite(object,"DURABLE",correlation,Map.of("safeErrorCode","UDP_LAKE_VERIFICATION_INTERRUPTED")).isEmpty())
        return currentOrRetry(object.id());
      throw failure;
    }
    if(proofError!=null){
      if(catalog.advanceWrite(object,"QUARANTINED",correlation,Map.of("safeErrorCode",proofError)).isEmpty())
        return currentOrRetry(object.id());
      throw quarantined();
    }
    var verified=catalog.advanceWrite(object,"VERIFIED",correlation,Map.of("locatorFingerprint",fingerprint(object.locator())));
    return verified.isPresent()?verified.get():currentOrRetry(object.id());
  }

  private LakeCatalogRepository.LakeObject storeCopy(LakeCatalogRepository.LakeObject object,byte[] content,String correlation){
    if("STAGED".equals(object.state())){
      try{
        put(storage(),object,content);
      }catch(RuntimeException failure){
        catalog.transition(object.id(),object.version(),"STAGED","FAILED","SYSTEM","",correlation,
            Map.of("safeErrorCode","UDP_LAKE_WRITE_FAILED"));
        throw failure;
      }
      object=advanceCopy(object,"DURABLE",correlation);
    }
    if("VERIFIED".equals(object.state()))return object;
    if(!"DURABLE".equals(object.state()))throw new IllegalStateException("UDP_LAKE_DUPLICATE_NOT_USABLE");
    try{
      String proofError=invalidProof(storage(),object,content);
      if(proofError!=null)throw new IllegalStateException(proofError);
    }catch(RuntimeException failure){
      catalog.transition(object.id(),object.version(),"DURABLE","QUARANTINED","SYSTEM","",correlation,
          Map.of("safeErrorCode","UDP_LAKE_VERIFY_FAILED"));
      throw failure;
    }
    return advanceCopy(object,"VERIFIED",correlation);
  }

  private static void put(LakeObjectStoragePort storage,LakeCatalogRepository.LakeObject object,byte[] content){
    var receipt=storage.put(object.locator(),content,object.mediaType(),object.hash());
    if(!receipt.durable()||!object.locator().equals(receipt.locator())||!object.hash().equals(receipt.contentHash())
        ||receipt.sizeBytes()!=content.length)throw new IllegalStateException("UDP_LAKE_DURABILITY_PROOF_INVALID");
  }

  private static String invalidProof(LakeObjectStoragePort storage,LakeCatalogRepository.LakeObject object,byte[] content){
    var head=storage.head(object.locator()).orElse(null);
    if(head==null)return "UDP_LAKE_OBJECT_MISSING_AFTER_PUT";
    if(!head.durable()||!object.hash().equals(head.contentHash())||head.sizeBytes()!=content.length
        ||("RAW".equals(object.tier())&&!Arrays.equals(content,storage.read(object.locator()))))return "UDP_LAKE_CHECKSUM_MISMATCH";
    return null;
  }
  LakeCatalogRepository.Segment verifyCompacted(LakeCatalogRepository.LakeObject input){
    var segment=catalog.compactedSegment(input);
    if(segment.packSize()>67_108_864L||segment.length()!=input.size()||segment.offset()>segment.packSize()-segment.length())
      throw new IllegalStateException("UDP_LAKE_MANIFEST_UNVERIFIED");
    byte[] pack=storage().read(segment.locator());
    if(pack.length!=segment.packSize()||!hash(pack).equals(segment.hash())
        ||!hash(Arrays.copyOfRange(pack,(int)segment.offset(),(int)(segment.offset()+segment.length()))).equals(input.hash()))
      throw new IllegalStateException("UDP_LAKE_CHECKSUM_MISMATCH");
    return segment;
  }
  private LakeCatalogRepository.LakeObject advanceCopy(LakeCatalogRepository.LakeObject object,String to,String correlation){
    try{return catalog.transition(object.id(),object.version(),object.state(),to,"SYSTEM","",correlation,
        Map.of("locatorFingerprint",fingerprint(object.locator())));}
    catch(IllegalStateException conflict){
      if(!"UDP_LAKE_STATE_CONFLICT".equals(conflict.getMessage()))throw conflict;
      var current=catalog.get(object.id());
      if(to.equals(current.state())||"VERIFIED".equals(current.state()))return current;
      throw conflict;
    }
  }

  private LakeCatalogRepository.LakeObject currentOrRetry(UUID id){
    var current=catalog.get(id);
    if("VERIFIED".equals(current.state()))return current;
    requireWritable(current);
    throw new StoreRejected(HttpStatus.SERVICE_UNAVAILABLE,"UDP_LAKE_WRITE_CONTENDED");
  }

  private static void requireWritable(LakeCatalogRepository.LakeObject object){
    switch(object.state()){
      case "FAILED", "STAGED", "DURABLE" -> { }
      case "QUARANTINED" -> throw quarantined();
      default -> throw new StoreRejected(HttpStatus.CONFLICT,"UDP_LAKE_OBJECT_NOT_WRITABLE");
    }
  }

  private static StoreRejected quarantined(){
    return new StoreRejected(HttpStatus.CONFLICT,"UDP_LAKE_OBJECT_QUARANTINED");
  }

  static final class StoreRejected extends ResponseStatusException {
    StoreRejected(HttpStatus status,String code){super(status,code);}
  }

  public LakeCatalogRepository.LakeObject authorizeDeletion(UUID id,long expected,String reason,TrustedHumanContext actor){if(reason==null||reason.isBlank())throw new IllegalArgumentException("UDP_LAKE_DELETION_REASON_REQUIRED");return catalog.requestDeletion(id,expected,OffsetDateTime.now().plusHours(1),reason,actor);}

  public int purge(String worker,int limit,String correlation){int completed=0;for(var object:catalog.claimPurge(worker,Math.min(Math.max(limit,1),100))){String locator=object.previousLocator()!=null?object.previousLocator():object.locator();storage().delete(locator);catalog.completePurge(object.id(),worker,object.leaseGeneration(),object.version(),correlation);completed++;}return completed;}

  public UUID compact(List<UUID> inputIds,String correlation){if(inputIds==null||inputIds.size()<2||inputIds.size()>100)throw new IllegalArgumentException("UDP_COMPACTION_INPUT_LIMIT");List<LakeCatalogRepository.LakeObject> inputs=inputIds.stream().distinct().map(catalog::get).toList();if(inputs.size()!=inputIds.size())throw new IllegalArgumentException("UDP_COMPACTION_DUPLICATE_INPUT");var first=inputs.getFirst();if(inputs.stream().anyMatch(x->!"VERIFIED".equals(x.state())||!x.tenant().equals(first.tenant())||!x.tier().equals(first.tier())||!x.retentionClass().equals(first.retentionClass())||!x.accessLabel().equals(first.accessLabel())))throw new IllegalArgumentException("UDP_COMPACTION_BOUNDARY_VIOLATION");long total=inputs.stream().mapToLong(LakeCatalogRepository.LakeObject::size).sum();if(total>67_108_864L)throw new IllegalArgumentException("UDP_COMPACTION_SIZE_LIMIT");ByteArrayOutputStream packed=new ByteArrayOutputStream((int)total);for(var input:inputs)packed.writeBytes(storage().read(input.locator()));var output=store(packed.toByteArray(),first.tenant(),"compaction",first.type(),first.tier(),"application/vnd.ouf.compaction-pack",first.retentionClass(),first.accessLabel(),inputs.stream().map(LakeCatalogRepository.LakeObject::retentionUntil).max(Comparator.naturalOrder()).orElseThrow(),correlation);UUID manifest=UUID.randomUUID();catalog.finalizeCompaction(manifest,first.tenant(),output.id(),output.hash(),inputs,correlation);return manifest;}

  public ScanResult reconcile(){UUID scan=UUID.randomUUID();Map<String,LakeObjectStoragePort.StoredObject> inventory=new HashMap<>();for(var stored:storage().inventory())inventory.put(stored.locator(),stored);Set<String> referenced=new HashSet<>();int missing=0,mismatch=0,orphan=0;for(var object:catalog.active()){if("COMPACTED".equals(object.state())){if(object.previousLocator()!=null)referenced.add(object.previousLocator());continue;}referenced.add(object.locator());var actual=inventory.get(object.locator());if(actual==null){catalog.finding(scan,"MISSING_OBJECT","CRITICAL",object.id(),fingerprint(object.locator()),Map.of("state",object.state()));quarantine(object,"UDP_LAKE_MISSING",scan.toString());missing++;}else if(!object.hash().equals(actual.contentHash())||object.size()!=actual.sizeBytes()){catalog.finding(scan,"CHECKSUM_MISMATCH","CRITICAL",object.id(),fingerprint(object.locator()),Map.of("state",object.state()));quarantine(object,"UDP_LAKE_CHECKSUM_MISMATCH",scan.toString());mismatch++;}}for(var stored:inventory.values())if(!referenced.contains(stored.locator())){catalog.finding(scan,"ORPHAN_STORAGE","HIGH",null,fingerprint(stored.locator()),Map.of("sizeBytes",stored.sizeBytes()));orphan++;}return new ScanResult(scan,missing,mismatch,orphan);}

  private void quarantine(LakeCatalogRepository.LakeObject object,String code,String correlation){if(Set.of("DURABLE","VERIFIED","COMPACTED","COLD").contains(object.state()))catalog.transition(object.id(),object.version(),object.state(),"QUARANTINED","SYSTEM","",correlation,Map.of("safeErrorCode",code));}
  private LakeObjectStoragePort storage(){LakeObjectStoragePort port=storageProvider.getIfAvailable();if(port==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"UDP_LAKE_STORAGE_UNAVAILABLE");return port;}
  private static void validate(String tier,String retention,OffsetDateTime until){if(!Set.of("RAW","NORMALIZED","CURATED","EXPORT").contains(tier)||!Set.of("OPERATIONAL","AUDIT","ARCHIVAL","LEGAL_HOLD","DERIVED_REBUILDABLE").contains(retention)||until==null)throw new IllegalArgumentException("UDP_LAKE_POLICY_INVALID");}
  private static String hash(byte[] bytes){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
  private static String fingerprint(String locator){return hash(locator.getBytes(StandardCharsets.UTF_8));}
  public record ScanResult(UUID scanId,int missing,int checksumMismatch,int orphan){}
}
