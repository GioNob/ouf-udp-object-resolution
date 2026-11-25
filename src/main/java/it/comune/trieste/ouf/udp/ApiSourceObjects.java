package it.comune.trieste.ouf.udp;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

import static it.comune.trieste.ouf.udp.ApiObservation.Outcome;

/** All methods run inside the fenced claim transaction. */
final class ApiSourceObjects {
  private static final String KEY = "tenant_id=:tenant and source_id=:s and type_code=:t and source_object_id=:o";
  private final JdbcClient db;
  private final Map<String,Object> args = new HashMap<>();
  final ApiObservation observation;
  ApiObservation.State before;
  Outcome outcome;

  ApiSourceObjects(JdbcClient db, String tenant, ResolutionRepository.Claim claim) {
    this.db = db;
    var payload = claim.payload();
    observation = ApiObservation.parse(payload);
    var identity = HandoffIntakeService.object(payload,"sourceIdentity");
    if(tenant.isBlank() || (payload.containsKey("tenantId") && !tenant.equals(payload.get("tenantId"))))
      throw new IllegalStateException("UDP_API_BINDING_DRIFT");
    args.put("tenant",tenant);
    args.put("s",identity.get("sourceId"));
    args.put("t",identity.get("typeCode"));
    args.put("o",identity.get("sourceObjectId"));
    args.put("h",claim.handoffId());
    args.put("run",payload.get("ingestionRunId"));
    args.put("epoch",observation.epoch());
    args.put("rev",observation.revision());
    args.put("gen",observation.generation());
    args.put("kind",observation.kind());
    args.put("render",observation.rendering());
    args.put("hash",observation.hash());
    args.put("op",observation.operation());
  }
  private JdbcClient.StatementSpec sql(String query) { return db.sql(query).params(args); }

  String evaluate(String canonicalType) {
    // A V39 predecessor admits API handoffs without api_tenant_id (the fence still parks them).
    // Its intake authorized the same installation tenant, so the bridge adopts it here.
    sql("update ouf_udp.materialization_job set api_tenant_id=:tenant where handoff_id=:h and api_semantics and api_tenant_id is null").update();
    if(sql("select 1 from ouf_udp.materialization_job where handoff_id=:h and api_tenant_id=:tenant").query(Integer.class).optional().isEmpty())
      throw new IllegalStateException("UDP_API_BINDING_DRIFT");
    // Serialize outcome positions in commit order within each authorized pagination scope.
    sql("select pg_advisory_xact_lock(hashtextextended('api-source:'||jsonb_build_array(cast(:tenant as text),cast(:s as text))::text,0))").query().singleRow();
    if (sql("select 1 from ouf_udp.api_source_block where tenant_id=:tenant and source_id=:s").query(Integer.class).optional().isPresent())
      return "UDP_EVENT_CONFLICT";
    new GovernedIdentityScopeLock(db).acquire((String)args.get("tenant"),canonicalType);
    GovernedIdentityScopeLock.lockBinding(db,args.get("s"),args.get("t"),args.get("o"));
    var rows=sql("select * from ouf_udp.api_source_object where "+KEY+" for update")
        .query((r,n)->{
          if (!observation.epoch().equals(r.getString("epoch"))) throw new IllegalStateException("UDP_SOURCE_EPOCH_CHANGED");
          return new ApiObservation.State(r.getLong("last_revision"),r.getString("retired_by"),r.getObject("generation",Long.class),
              r.getString("absence_snapshot_at")==null?null:OffsetDateTime.parse(r.getString("absence_snapshot_at")),r.getObject("urban_object_id",UUID.class));
        }).list();
    before=rows.isEmpty()?null:rows.getFirst();
    boolean absence=observation.kind().equals("ABSENCE");
    String scope=" from ouf_udp.api_handoff_outcome where "+KEY+" and epoch=:epoch and "+
        (absence?"kind='ABSENCE' and generation=:gen":"kind<>'ABSENCE' and revision=:rev");
    String exact=(observation.kind().equals("PRESENCE") || observation.kind().equals("SNAPSHOT_DELETE")
        ?" and kind in ('PRESENCE','SNAPSHOT_DELETE')":" and kind=:kind")+" and generation is not distinct from cast(:gen as bigint) and rendering=:render";
    var prior=sql("select handoff_id,operation,item_sha256"+scope+exact+" and outcome<>'CONFLICT' order by position limit 1").query().listOfRows();
    boolean conflict=!prior.isEmpty() && (!observation.hash().equals(prior.getFirst().get("item_sha256"))
        || !observation.operation().equals(prior.getFirst().get("operation")));
    boolean republished=!absence && sql("select exists(select 1"+scope+" and rendering<>:render)").query(Boolean.class).single();
    outcome=observation.decide(before,!prior.isEmpty(),conflict,republished);
    args.put("conflict",conflict?prior.getFirst().get("handoff_id"):null);
    return null;
  }

  UUID binding(){return before==null?null:before.binding();}
  boolean upsert(){return outcome==Outcome.APPLIED || outcome==Outcome.REACTIVATED;}

  void lockTarget(String type) {
    args.put("target",binding());
    args.put("type",type);
    var rows=sql("""
        select b.state binding_state,u.status from ouf_udp.source_binding b join ouf_udp.urban_object u using(urban_object_id)
        where b.source_id=:s and b.type_code=:t and b.source_object_id=:o and u.urban_object_id=:target
          and u.tenant_id=:tenant and u.canonical_type=:type for update of u,b
        """).query().listOfRows();
    String expected=before.retiredBy()==null?"ACTIVE":"RETIRED";
    if(rows.size()!=1 || !expected.equals(rows.getFirst().get("binding_state"))
        || !("ACTIVE".equals(rows.getFirst().get("status")) || ("RETIRED".equals(expected) && "RETIRED".equals(rows.getFirst().get("status")))))
      throw new IllegalStateException("UDP_API_BINDING_DRIFT");
  }
  void changeBinding(boolean active) {
    args.put("bindingState",active?"ACTIVE":"RETIRED");
    sql("update ouf_udp.source_binding set state=:bindingState where source_id=:s and type_code=:t and source_object_id=:o and urban_object_id=:target").update();
    if(active) sql("update ouf_udp.urban_object set status='ACTIVE' where urban_object_id=:target and status='RETIRED'").update();
    else sql("""
        update ouf_udp.urban_object set status='RETIRED' where urban_object_id=:target and status='ACTIVE'
        and not exists(select 1 from ouf_udp.source_binding where urban_object_id=:target and state='ACTIVE')
        """).update();
  }

  void withdrawPrevious(UUID target) {
    args.put("target",target);
    sql("""
        insert into ouf_udp.api_withdrawn_support(handoff_id,withdrawn_by)
        select m.handoff_id,:h from ouf_udp.materialization_observation m join ouf_udp.handoff_intake h using(handoff_id)
        where m.urban_object_id=:target and h.source_id=:s and h.type_code=:t and h.source_object_id=:o and h.handoff_id<>:h
          and not exists(select 1 from ouf_udp.api_withdrawn_support w where w.handoff_id=h.handoff_id)
        on conflict do nothing
        """).update();
  }

  void finish(UUID target) {
    args.put("target",target);
    args.put("result",outcome.name());
    boolean retired=outcome==Outcome.RETIRED;
    if (upsert() || retired) {
      args.put("state",retired?"RETIRED":"ACTIVE");
      args.put("by",retired?observation.kind():null);
      args.put("absence",retired && observation.kind().equals("ABSENCE")?observation.snapshotAt().toString():null);
      args.put("current",retired?null:args.get("h"));
      sql("""
          insert into ouf_udp.api_source_object as old
          (tenant_id,source_id,type_code,source_object_id,epoch,last_revision,state,retired_by,absence_snapshot_at,generation,urban_object_id,current_handoff)
          values(:tenant,:s,:t,:o,:epoch,:rev,:state,:by,:absence,:gen,:target,:current)
          on conflict(tenant_id,source_id,type_code,source_object_id) do update set
            last_revision=excluded.last_revision,state=excluded.state,retired_by=excluded.retired_by,
            absence_snapshot_at=excluded.absence_snapshot_at,generation=greatest(old.generation,excluded.generation),
            urban_object_id=excluded.urban_object_id,current_handoff=excluded.current_handoff
          """).update();
    } else if(outcome==Outcome.SEEN) {
      sql("update ouf_udp.api_source_object set generation=greatest(generation,:gen) where "+KEY).update();
    } else if(outcome==Outcome.DUPLICATE && before!=null && before.retiredBy()!=null && observation.operation().equals("TOMBSTONE")) {
      sql("update ouf_udp.api_source_object set last_revision=greatest(last_revision,:rev) where "+KEY).update();
    }
    sql("""
        insert into ouf_udp.api_handoff_outcome(handoff_id,tenant_id,source_id,type_code,source_object_id,ingestion_run_id,
          epoch,kind,revision,generation,rendering,item_sha256,operation,outcome,urban_object_id,object_status,conflict_with)
        values(:h,:tenant,:s,:t,:o,:run,:epoch,:kind,:rev,:gen,:render,:hash,:op,:result,:target,
          (select status from ouf_udp.urban_object where urban_object_id=:target),:conflict)
        """).update();
    if(outcome==Outcome.CONFLICT) sql("insert into ouf_udp.api_source_block values(:tenant,:s,:h)").update();
  }
}
