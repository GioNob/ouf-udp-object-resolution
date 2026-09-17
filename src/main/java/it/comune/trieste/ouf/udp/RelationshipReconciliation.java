package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable bounded re-evaluation against the original approved profile, without network I/O. */
@Service
public class RelationshipReconciliation {
  private final JdbcClient db;private final ObjectMapper json;private final RelationshipMaterializer materializer;
  public RelationshipReconciliation(JdbcClient db,ObjectMapper json,RelationshipMaterializer materializer){this.db=db;this.json=json;this.materializer=materializer;}
  @Transactional public void accept(String handoff,UUID source,Map<String,Object> payload,UdpPorts.RelationshipProfile profile){
    lock();
    var identity=HandoffIntakeService.object(payload,"sourceIdentity");
    String binding=write(List.of(identity.get("sourceId"),identity.get("typeCode"),identity.get("sourceObjectId")));
    if(profile==null&&db.sql("select count(*) from ouf_udp.relationship_reconciliation where binding_key=:b and active").param("b",binding).query(Long.class).single()==0)return;
    if(!db.sql("select task_id from ouf_udp.relationship_reconciliation where handoff_id=:h and source_object_id=:s").param("h",handoff).param("s",source).query(UUID.class).list().isEmpty())return;
    // A delayed older observation must not restore a superseded edge or policy.
    long newer=db.sql("select count(*) from ouf_udp.relationship_reconciliation t join ouf_udp.handoff_intake old on old.handoff_id=t.handoff_id join ouf_udp.handoff_intake incoming on incoming.handoff_id=:h where t.binding_key=:b and t.active and (cast(old.payload_json->'sourceIdentity'->>'observedAt' as timestamptz),old.received_at,old.handoff_id) > (cast(incoming.payload_json->'sourceIdentity'->>'observedAt' as timestamptz),incoming.received_at,incoming.handoff_id)").param("h",handoff).param("b",binding).query(Long.class).single();
    if(newer>0)return;
    db.sql("update ouf_udp.relationship_issue set state='SUPERSEDED' where state='OPEN' and handoff_id in(select handoff_id from ouf_udp.relationship_reconciliation where binding_key=:b and active)").param("b",binding).update();
    db.sql("update ouf_udp.relationship_current_support set active=false where task_id in(select task_id from ouf_udp.relationship_reconciliation where binding_key=:b and active)").param("b",binding).update();
    db.sql("update ouf_udp.relationship_reconciliation set active=false where binding_key=:b and active").param("b",binding).update();
    UUID task=UUID.randomUUID();
    db.sql("insert into ouf_udp.relationship_reconciliation(task_id,handoff_id,source_object_id,binding_key,profile_json) values(:i,:h,:s,:b,cast(:p as jsonb))").param("i",task).param("h",handoff).param("s",source).param("b",binding).param("p",write(profile==null?new UdpPorts.RelationshipProfile("policy://relationships/none",List.of()):profile)).update();
    resolve(task,handoff,source,payload,profile);refresh(source);
  }
  @Transactional public void tick(){
    lock();
    var tasks=db.sql("select t.task_id,t.handoff_id,t.source_object_id,t.profile_json::text profile,h.payload_json::text payload from ouf_udp.relationship_reconciliation t join ouf_udp.handoff_intake h on h.handoff_id=t.handoff_id where t.active and t.next_attempt_at<=transaction_timestamp() order by t.next_attempt_at,t.task_id limit 10 for update of t skip locked").query().listOfRows();
    for(var task:tasks){
      UUID source=(UUID)task.get("source_object_id");
      resolve((UUID)task.get("task_id"),String.valueOf(task.get("handoff_id")),source,read(task.get("payload"),Map.class),read(task.get("profile"),UdpPorts.RelationshipProfile.class));refresh(source);
    }
  }
  private void resolve(UUID task,String handoff,UUID source,Map<String,Object> payload,UdpPorts.RelationshipProfile profile){
    db.sql("update ouf_udp.relationship_current_support set active=false where task_id=:t").param("t",task).update();
    boolean active=db.sql("select status='ACTIVE' from ouf_udp.urban_object where urban_object_id=:s").param("s",source).query(Boolean.class).single();
    if(active&&profile!=null&&!profile.relationships().isEmpty()){
      materializer.materialize(handoff,source,payload,profile);
      db.sql("insert into ouf_udp.relationship_current_support(task_id,relationship_id,revision_id) select :t,r.relationship_id,r.current_revision_id from ouf_udp.urban_relationship r join ouf_udp.relationship_revision v on v.relationship_revision_id=r.current_revision_id join ouf_udp.relationship_contribution c on c.contribution_id=v.contribution_id where c.handoff_id=:h and r.source_object_id=:s and not exists(select 1 from ouf_udp.relationship_issue i where i.contribution_id=c.contribution_id and i.state='OPEN') on conflict(task_id,relationship_id) do update set revision_id=excluded.revision_id,active=true").param("t",task).param("h",handoff).param("s",source).update();
    }
    db.sql("update ouf_udp.relationship_reconciliation set next_attempt_at=transaction_timestamp()+interval '30 seconds' where task_id=:t").param("t",task).update();
  }
  private void refresh(UUID source){
    // Highest classification among current supporting contributions governs shared edges.
    db.sql("update ouf_udp.urban_relationship r set status=case when exists(select 1 from ouf_udp.relationship_current_support s where s.relationship_id=r.relationship_id and s.active) then 'ACTIVE' else 'SUPERSEDED' end,current_revision_id=coalesce((select s.revision_id from ouf_udp.relationship_current_support s join ouf_udp.relationship_revision v on v.relationship_revision_id=s.revision_id where s.relationship_id=r.relationship_id and s.active order by case v.access_label when 'RESTRICTED' then 5 when 'SENSITIVE' then 4 when 'PERSONAL' then 3 when 'ANONYMOUS' then 2 else 1 end desc,v.revision_no desc limit 1),r.current_revision_id) where r.source_object_id=:u and exists(select 1 from ouf_udp.relationship_current_support s where s.relationship_id=r.relationship_id)").param("u",source).update();
  }
  private void lock(){db.sql("select pg_advisory_xact_lock(hashtextextended('relationship-reconciliation',0))").query().singleRow();}
  private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalArgumentException("UDP_RELATION_PROFILE_INVALID");}}
  private <T>T read(Object v,Class<T> type){try{return json.readValue(String.valueOf(v),type);}catch(Exception e){throw new IllegalArgumentException("UDP_RELATION_PROFILE_INVALID");}}
}
