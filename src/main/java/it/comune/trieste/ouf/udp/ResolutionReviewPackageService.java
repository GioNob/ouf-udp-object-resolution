package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

/** One human confirmation applies the entire tenant-visible issue snapshot atomically. */
@Service
public class ResolutionReviewPackageService {
  private final JdbcClient db;
  private final ObjectMapper json;
  private final IdentityGovernanceService governance;
  private final GovernedIdentityScopeLock identityScope;
  public ResolutionReviewPackageService(JdbcClient db,ObjectMapper json,IdentityGovernanceService governance,GovernedIdentityScopeLock identityScope){
    this.db=db;this.json=json;this.governance=governance;this.identityScope=identityScope;
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public PackageView prepare(TrustedHumanContext actor){
    actor.require("resolution.issue.read");
    return snapshot(actor.tenantId(),false);
  }

  @Transactional public Confirmation confirm(String expectedHash,List<Choice> choices,TrustedHumanContext actor){
    actor.require("resolution.match.approve");
    if(expectedHash==null||expectedHash.isBlank()||choices==null)throw invalid();
    Map<UUID,Choice> selected=new HashMap<>();
    for(Choice choice:choices){
      if(choice==null||choice.issueId()==null||(choice.reason()!=null&&choice.reason().length()>1000)
          ||selected.putIfAbsent(choice.issueId(),choice)!=null)throw invalid();
    }
    if(selected.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"UDP_REVIEW_PACKAGE_STALE");
    boolean selective=choices.stream().anyMatch(c->c.expectedReviewHash()!=null);
    if(selective&&choices.stream().anyMatch(c->c.expectedReviewHash()==null
        ||!c.expectedReviewHash().matches("sha256:[0-9a-f]{64}")))throw invalid();
    lockReviewScopes(actor.tenantId(),selective?selected.keySet():null);
    PackageView current=snapshot(actor.tenantId(),true,selective?selected.keySet():null);
    if((!selective&&!expectedHash.equals(current.snapshotHash()))||current.issues().isEmpty()
        ||selected.size()!=current.issues().size()
        ||!selected.keySet().equals(current.issues().stream().map(IssueCard::issueId).collect(
            java.util.stream.Collectors.toSet())))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"UDP_REVIEW_PACKAGE_STALE");
    for(IssueCard issue:current.issues()){
      Choice choice=selected.get(issue.issueId());
      if(choice.expectedVersion()!=issue.version()
          ||selective&&!issue.reviewHash().equals(choice.expectedReviewHash()))
        throw new ResponseStatusException(HttpStatus.CONFLICT,"UDP_REVIEW_PACKAGE_STALE");
      governance.decideResolutionIssue(issue.issueId(),issue.version(),choice.action(),
          choice.reason(),choice.targetUrbanObjectId(),actor);
    }
    UUID packageId=UUID.randomUUID();
    db.sql("insert into ouf_udp.resolution_review_package_confirmation(package_id,tenant_id,snapshot_hash,issue_count,actor_subject,authorization_decision_ref,correlation_id) values(:id,:tenant,:hash,:count,:actor,:auth,:correlation)")
        .param("id",packageId).param("tenant",actor.tenantId()).param("hash",current.snapshotHash())
        .param("count",choices.size()).param("actor",actor.subject())
        .param("auth",actor.authorizationDecisionRef()).param("correlation",actor.correlationId()).update();
    return new Confirmation(packageId,current.snapshotHash(),choices.size());
  }

  private PackageView snapshot(String tenant,boolean lock){return snapshot(tenant,lock,null);}

  private PackageView snapshot(String tenant,boolean lock,Set<UUID> selected){
    if(tenant==null||tenant.isBlank())throw new SecurityException("UDP_REVIEW_TENANT_REQUIRED");
    long unscoped=db.sql("select count(*) from ouf_udp.resolution_issue where state='OPEN' and tenant_id is null")
        .query(Long.class).single();
    if(unscoped>0)throw new ResponseStatusException(HttpStatus.CONFLICT,
        "UDP_REVIEW_TENANT_BACKFILL_REQUIRED");
    String sql="select i.issue_id,i.version,i.reason_code,i.candidate_refs::text candidates,i.evidence_refs::text evidence,"
        +"h.handoff_id,h.source_id,h.source_object_id,h.type_code,(h.payload_json->'canonicalPayload')::text incoming_fields "
        +"from ouf_udp.resolution_issue i join ouf_udp.handoff_intake h on h.handoff_id=i.handoff_id "
        +"where i.tenant_id=:tenant and i.state='OPEN'"
        +(selected==null?"":" and i.issue_id in (:selected)")+" order by i.issue_id"
        +(lock?" for update of i":"");
    var query=db.sql(sql).param("tenant",tenant);
    if(selected!=null)query.param("selected",selected);
    List<Map<String,Object>> rows=query.query().listOfRows();
    List<IssueCard> cards=new ArrayList<>();
    List<Map<String,Object>> canonical=new ArrayList<>();
    for(var row:rows){
      UUID id=(UUID)row.get("issue_id");
      long version=((Number)row.get("version")).longValue();
      String reason=String.valueOf(row.get("reason_code"));
      String candidates=String.valueOf(row.get("candidates"));
      String evidence=String.valueOf(row.get("evidence"));
      List<String> refs=readCandidates(candidates);
      UUID suggested=null;
      if(refs.size()==1)try{suggested=UUID.fromString(refs.getFirst());}
        catch(IllegalArgumentException ignored){/* No automatic selection. */}
      Object decodedEvidence=readEvidence(evidence);
      String expectedType=null;
      if(decodedEvidence instanceof List<?> items&&!items.isEmpty()
          &&items.getFirst() instanceof Map<?,?> first&&first.get("canonicalClass") instanceof String type)
        expectedType=type;
      ReviewObject incoming=new ReviewObject("handoff://"+row.get("handoff_id"),
          String.valueOf(row.get("source_id")),String.valueOf(row.get("source_object_id")),
          String.valueOf(row.get("type_code")),null,null,row.get("incoming_fields")!=null,
          fields(row.get("incoming_fields")));
      Map<String,ReviewObject> objects=new LinkedHashMap<>();
      for(String ref:refs)objects.put(ref,candidate(ref,tenant,expectedType,lock));
      Map<String,Object> item=new LinkedHashMap<>();
      item.put("id",id);item.put("version",version);item.put("reason",reason);
      item.put("candidates",candidates);item.put("evidence",evidence);
      item.put("incomingObject",incoming);item.put("candidateObjects",objects);
      canonical.add(item);
      cards.add(new IssueCard(id,version,reason,refs,decodedEvidence,suggested,incoming,Collections.unmodifiableMap(objects),digest(item)));
    }
    return new PackageView(tenant,digest(canonical),List.copyOf(cards));
  }

  // Match materialization's lock order: identity scope, then issue/object rows.
  private void lockReviewScopes(String tenant,Set<UUID> selected){
    var query=db.sql("select candidate_refs::text candidates,evidence_refs::text evidence from ouf_udp.resolution_issue where tenant_id=:tenant and state='OPEN'"
        +(selected==null?"":" and issue_id in (:selected)")).param("tenant",tenant);
    if(selected!=null)query.param("selected",selected);
    Set<String> classes=new TreeSet<>();Set<UUID> objects=new HashSet<>();
    for(var row:query.query().listOfRows()){
      for(String ref:readCandidates(String.valueOf(row.get("candidates"))))
        try{objects.add(UUID.fromString(ref));}catch(IllegalArgumentException ignored){/* unavailable reference */}
      Object decoded=readEvidence(String.valueOf(row.get("evidence")));
      if(decoded instanceof List<?> items&&!items.isEmpty()&&items.getFirst() instanceof Map<?,?> first
          &&first.get("canonicalClass") instanceof String type&&!type.isBlank())classes.add(type);
    }
    if(!objects.isEmpty())classes.addAll(db.sql("select distinct canonical_type from ouf_udp.urban_object where tenant_id=:tenant and urban_object_id in (:objects)")
        .param("tenant",tenant).param("objects",objects).query(String.class).list());
    for(String type:classes)identityScope.acquire(tenant,type);
  }

  private ReviewObject candidate(String ref,String tenant,String expectedType,boolean lock){
    UUID id;
    try{id=UUID.fromString(ref);}catch(IllegalArgumentException failure){return unavailable(ref);}
    String sql="select o.canonical_type,o.revision,s.revision_id,s.canonical_payload::text fields "
        +"from ouf_udp.urban_object o left join ouf_udp.urban_object_current_state s "
        +"on s.urban_object_id=o.urban_object_id and s.revision_id=o.current_revision_id "
        +"where o.urban_object_id=:id and o.tenant_id=:tenant and o.status='ACTIVE'"
        +(expectedType==null?"":" and o.canonical_type=:type")+(lock?" for share of o":"");
    var query=db.sql(sql).param("id",id).param("tenant",tenant);
    if(expectedType!=null)query.param("type",expectedType);
    var matches=query.query().listOfRows();
    if(matches.isEmpty())return unavailable(ref);
    var row=matches.getFirst();
    return new ReviewObject(ref,null,null,String.valueOf(row.get("canonical_type")),
        ((Number)row.get("revision")).longValue(),Objects.toString(row.get("revision_id"),null),
        row.get("fields")!=null,fields(row.get("fields")));
  }
  private static ReviewObject unavailable(String ref){
    return new ReviewObject(ref,null,null,null,null,null,false,Map.of());
  }
  private Map<String,Object> fields(Object raw){
    if(raw==null)return Map.of();
    try{
      Map<String,Object> value=json.readValue(String.valueOf(raw),new TypeReference<Map<String,Object>>(){});
      if(value==null)throw new IllegalArgumentException();
      return Collections.unmodifiableMap(new TreeMap<>(value));
    }catch(Exception failure){throw new IllegalStateException("UDP_REVIEW_OBJECT_FIELDS_INVALID",failure);}
  }

  private List<String> readCandidates(String raw){
    try{return json.readValue(raw,new TypeReference<List<String>>(){});}
    catch(Exception failure){throw new IllegalStateException("UDP_REVIEW_CANDIDATES_INVALID",failure);}
  }
  private Object readEvidence(String raw){
    try{return json.readValue(raw,Object.class);}
    catch(Exception failure){throw new IllegalStateException("UDP_REVIEW_EVIDENCE_INVALID",failure);}
  }
  private String digest(Object value){
    try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}
    catch(Exception failure){throw new IllegalStateException("UDP_REVIEW_HASH_INVALID",failure);}
  }
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("UDP_REVIEW_PACKAGE_INVALID");}
  public record IssueCard(UUID issueId,long version,String reasonCode,List<String> candidateRefs,
                          Object evidence,UUID suggestedTargetUrbanObjectId,ReviewObject incomingObject,
                          Map<String,ReviewObject> candidateObjects,String reviewHash){}
  public record ReviewObject(String reference,String sourceId,String sourceObjectId,String type,
                             Long revision,String revisionRef,boolean available,Map<String,Object> fields){}
  public record PackageView(String tenantId,String snapshotHash,List<IssueCard> issues){}
  public record Choice(UUID issueId,long expectedVersion,String action,String reason,UUID targetUrbanObjectId,String expectedReviewHash){
    public Choice(UUID issueId,long expectedVersion,String action,String reason,UUID targetUrbanObjectId){
      this(issueId,expectedVersion,action,reason,targetUrbanObjectId,null);
    }
  }
  public record Confirmation(UUID packageId,String snapshotHash,int issueCount){}
}
