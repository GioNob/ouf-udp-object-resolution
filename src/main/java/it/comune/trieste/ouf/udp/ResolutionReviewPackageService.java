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
import org.springframework.web.server.ResponseStatusException;

/** One human confirmation applies the entire tenant-visible issue snapshot atomically. */
@Service
public class ResolutionReviewPackageService {
  private final JdbcClient db;
  private final ObjectMapper json;
  private final IdentityGovernanceService governance;
  public ResolutionReviewPackageService(JdbcClient db,ObjectMapper json,IdentityGovernanceService governance){
    this.db=db;this.json=json;this.governance=governance;
  }

  @Transactional(readOnly=true) public PackageView prepare(TrustedHumanContext actor){
    actor.require("resolution.issue.read");
    return snapshot(actor.tenantId(),false);
  }

  @Transactional public Confirmation confirm(String expectedHash,List<Choice> choices,TrustedHumanContext actor){
    actor.require("resolution.match.approve");
    if(expectedHash==null||expectedHash.isBlank()||choices==null)throw invalid();
    PackageView current=snapshot(actor.tenantId(),true);
    if(!expectedHash.equals(current.snapshotHash())||current.issues().isEmpty())
      throw new ResponseStatusException(HttpStatus.CONFLICT,"UDP_REVIEW_PACKAGE_STALE");
    Map<UUID,Choice> selected=new HashMap<>();
    for(Choice choice:choices){
      if(choice==null||choice.issueId()==null||choice.reason()==null||choice.reason().isBlank()
          ||selected.putIfAbsent(choice.issueId(),choice)!=null)throw invalid();
    }
    if(selected.size()!=current.issues().size()
        ||!selected.keySet().equals(current.issues().stream().map(IssueCard::issueId).collect(
            java.util.stream.Collectors.toSet())))throw invalid();
    for(IssueCard issue:current.issues()){
      Choice choice=selected.get(issue.issueId());
      if(choice.expectedVersion()!=issue.version())
        throw new ResponseStatusException(HttpStatus.CONFLICT,"UDP_REVIEW_PACKAGE_STALE");
      governance.decideResolutionIssue(issue.issueId(),issue.version(),choice.action(),
          choice.reason(),choice.targetUrbanObjectId(),actor);
    }
    UUID packageId=UUID.randomUUID();
    db.sql("insert into ouf_udp.resolution_review_package_confirmation(package_id,tenant_id,snapshot_hash,issue_count,actor_subject,authorization_decision_ref,correlation_id) values(:id,:tenant,:hash,:count,:actor,:auth,:correlation)")
        .param("id",packageId).param("tenant",actor.tenantId()).param("hash",expectedHash)
        .param("count",choices.size()).param("actor",actor.subject())
        .param("auth",actor.authorizationDecisionRef()).param("correlation",actor.correlationId()).update();
    return new Confirmation(packageId,expectedHash,choices.size());
  }

  private PackageView snapshot(String tenant,boolean lock){
    if(tenant==null||tenant.isBlank())throw new SecurityException("UDP_REVIEW_TENANT_REQUIRED");
    long unscoped=db.sql("select count(*) from ouf_udp.resolution_issue where state='OPEN' and tenant_id is null")
        .query(Long.class).single();
    if(unscoped>0)throw new ResponseStatusException(HttpStatus.CONFLICT,
        "UDP_REVIEW_TENANT_BACKFILL_REQUIRED");
    String sql="select issue_id,version,reason_code,candidate_refs::text candidates,evidence_refs::text evidence "
        +"from ouf_udp.resolution_issue where tenant_id=:tenant and state='OPEN' order by issue_id"
        +(lock?" for update":"");
    List<Map<String,Object>> rows=db.sql(sql).param("tenant",tenant).query().listOfRows();
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
      cards.add(new IssueCard(id,version,reason,refs,readEvidence(evidence),suggested));
      Map<String,Object> item=new LinkedHashMap<>();
      item.put("id",id);item.put("version",version);item.put("reason",reason);
      item.put("candidates",candidates);item.put("evidence",evidence);
      canonical.add(item);
    }
    return new PackageView(tenant,digest(canonical),List.copyOf(cards));
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
                          Object evidence,UUID suggestedTargetUrbanObjectId){}
  public record PackageView(String tenantId,String snapshotHash,List<IssueCard> issues){}
  public record Choice(UUID issueId,long expectedVersion,String action,String reason,UUID targetUrbanObjectId){}
  public record Confirmation(UUID packageId,String snapshotHash,int issueCount){}
}
