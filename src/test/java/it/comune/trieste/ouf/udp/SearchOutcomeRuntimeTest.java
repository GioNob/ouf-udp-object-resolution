package it.comune.trieste.ouf.udp;

import static it.comune.trieste.ouf.udp.SearchOutcome.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.InstanceOfAssertFactories;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"ouf.udp.lake.tenant-id=tenant-a","ouf.udp.execution.enabled=false"})
@AutoConfigureMockMvc
class SearchOutcomeRuntimeTest {
  private static final String SEARCH="urban.object.search";
  private static final Path RECEIPT_KEY=receiptKey();
  private static final ObjectMapper JSON=new ObjectMapper();
  @Autowired JdbcClient db;
  @Autowired GovernedServingService serving;
  @Autowired AcquisitionEvidenceService evidence;
  @Autowired ResolutionRepository jobs;
  @Autowired MockMvc http;

  @DynamicPropertySource static void configuration(DynamicPropertyRegistry props) {
    props.add("spring.datasource.url",()->System.getenv("OUF_UDP_DB_URL"));
    props.add("spring.datasource.username",()->System.getenv("OUF_UDP_DB_USER"));
    props.add("spring.datasource.password",()->System.getenv("OUF_UDP_DB_PASSWORD"));
    props.add("ouf.udp.search.tenant-id",()->"tenant-a");
    props.add("ouf.udp.search.issuer",()->"https://auth.test/realms/ouf");
    props.add("ouf.udp.search.audience",()->"gateway");
    props.add("ouf.udp.search.workload",()->"workload");
    props.add("ouf.udp.search.owner-key-file",RECEIPT_KEY::toString);
  }

  @Test void task50eEmptyPagesAreTerminalOnBothRoutes() throws Exception {
    var scope=scope(50);
    http.perform(get("/api/udp/v1/objects").param("type","ouf:"+UUID.randomUUID())
        .with(r->bind(r,null)))
      .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
      .andExpect(jsonPath("$.outcome").value("NO_OBJECTS")).andExpect(jsonPath("$.pollAfterSeconds").doesNotHaveJsonPath());
    var result=readAcquisition(scope);
    assertThat(result.path("items").isEmpty()).isTrue();
    assertThat(result.path("outcome").asText()).isEqualTo("NO_OBJECTS");
    assertThat(result.has("pollAfterSeconds")).isFalse();
  }

  @Test void task50eConfirmedMaterializationPollsUntilTheJobSettles() throws Exception {
    var scope=scope(50);
    String type="ouf:"+UUID.randomUUID();
    var job=handoff(scope,"a","tenant-a","OPEN","RUNNING");
    var actor=actor(scope.sourceId());

    // Run evidence is available before a canonical type has been verified.
    assertThat(evidence.read(scope,actor)).containsEntry("outcome",MATERIALIZATION_PENDING).containsEntry("pollAfterSeconds",5);
    assertThat(serving.search(type,10,null,actor).outcome()).isEqualTo(NO_OBJECTS);
    jobs.recordMaterializationType(job,type);
    jobs.recordMaterializationType(job,type);
    assertThat(db.sql("select count(*) from ouf_udp.handoff_event where handoff_id=:h and event_type='MATERIALIZATION_TYPE_VERIFIED'")
        .param("h",job.handoffId()).query(Long.class).single()).isEqualTo(1L);
    assertThatThrownBy(()->jobs.recordMaterializationType(job,"ouf:Drift")).hasMessageContaining("UDP_MATERIALIZATION_TYPE_DRIFT");
    http.perform(get("/api/udp/v1/objects").param("type",type).with(r->bind(r,scope.sourceId())))
      .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
      .andExpect(jsonPath("$.outcome").value("MATERIALIZATION_PENDING")).andExpect(jsonPath("$.pollAfterSeconds").value(5));
    var pending=readAcquisition(scope);
    assertThat(pending.path("outcome").asText()).isEqualTo("MATERIALIZATION_PENDING");
    assertThat(pending.path("pollAfterSeconds").asInt()).isEqualTo(5);
    assertThat(pending.path("ingestionAckProven").asBoolean()).isFalse();
    assertThat(pending.path("items").get(0).path("materializationObserved").asBoolean()).isFalse();

    UUID object=object(type);
    UUID revision=UUID.randomUUID();
    db.sql("""
        insert into ouf_udp.object_revision(revision_id,urban_object_id,revision_no,canonical_hash,authority_hash,
          authority_state,canonical_payload,access_labels,source_handoff_id,bundle_ref,semantic_publication_ref)
        values(:r,:o,1,'sha256:canonical','sha256:authority','{}','{}','{}',:h,'bundle:1','semantic:1')
        """).param("r",revision).param("o",object).param("h",job.handoffId()).update();
    db.sql("""
        insert into ouf_udp.materialization_observation(observation_id,handoff_id,urban_object_id,revision_id,material_change,canonical_hash)
        values(gen_random_uuid(),:h,:o,:r,true,'sha256:canonical')
        """).param("h",job.handoffId()).param("o",object).param("r",revision).update();
    jobs.complete(job);
    var page=serving.search(type,10,null,actor);
    assertThat(page.outcome()).isEqualTo(COMPLETE);
    assertThat(page.items()).hasSize(1);
    assertThat(page.pollAfterSeconds()).isNull();
    var materialized=readAcquisition(scope);
    assertThat(materialized.path("outcome").asText()).isEqualTo("COMPLETE");
    assertThat(materialized.path("items").get(0).path("materializationObserved").asBoolean()).isTrue();
    assertThat(materialized.has("pollAfterSeconds")).isFalse();
    assertThatThrownBy(()->jobs.recordMaterializationType(job,type)).hasMessageContaining("UDP_RESOLUTION_LEASE_LOST");
  }

  @Test void task50ePartialPagesKeepTheExistingCursors() throws Exception {
    var scope=scope(1);
    handoff(scope,"a","tenant-a","OPEN","READY");
    handoff(scope,"b","tenant-a","OPEN","SUCCEEDED");
    var actor=actor(scope.sourceId());
    var page=readAcquisition(scope);
    assertThat(page.path("outcome").asText()).isEqualTo("PARTIAL");
    assertThat(page.path("partial").asBoolean()).isTrue();
    assertThat(page.has("pollAfterSeconds")).isFalse();
    String cursor=page.path("nextHandoffId").asText();
    assertThat(cursor).endsWith("a");
    var second=new AcquisitionEvidenceApi.Query(scope.sourceId(),scope.runId(),1,cursor);
    assertThat(evidence.read(second,actor)).containsEntry("outcome",MATERIALIZATION_PENDING);

    String type="ouf:"+UUID.randomUUID();
    object(type);
    object(type);
    var first=serving.search(type,1,null,actor);
    assertThat(first.outcome()).isEqualTo(PARTIAL);
    assertThat(first.partial()).isFalse(); // The legacy search flag denotes row filtering.
    assertThat(first.nextCursor()).isNotBlank();
    var last=serving.search(type,1,first.nextCursor(),actor);
    assertThat(last.outcome()).isEqualTo(COMPLETE);
    assertThat(last.items().getFirst().get("urbanObjectId")).isNotEqualTo(first.items().getFirst().get("urbanObjectId"));
    assertThat(last.nextCursor()).isNull();
  }

  @Test void task50ePollRequiresTenantLabelAndScopeAndStopsAtQuarantine() throws Exception {
    String type="ouf:"+UUID.randomUUID();
    var scope=scope(50);
    var foreign=handoff(scope,"f","tenant-b","OPEN","RUNNING");
    var hidden=handoff(scope,"s","tenant-a","SENSITIVE","RUNNING");
    jobs.recordMaterializationType(foreign,type);
    jobs.recordMaterializationType(hidden,type);
    var actor=actor(scope.sourceId());
    assertThat(evidence.read(scope,actor)).containsEntry("outcome",INCOMPLETE).doesNotContainKey("pollAfterSeconds");
    assertThat(serving.search(type,10,null,actor).outcome()).isEqualTo(NO_OBJECTS);
    assertThatThrownBy(()->evidence.read(scope,actor("unrelated-source"))).hasMessageContaining("403");

    var visible=handoff(scope,"v","tenant-a","OPEN","RUNNING");
    jobs.recordMaterializationType(visible,type);
    assertThat(serving.search(type,10,null,actor("unrelated-source")).outcome()).isEqualTo(NO_OBJECTS);
    assertThat(serving.search(type,10,null,actor).outcome()).isEqualTo(MATERIALIZATION_PENDING);
    jobs.quarantine(visible,"UDP_RESOLUTION_REVIEW_REQUIRED");
    assertThat(serving.search(type,10,null,actor).outcome()).isEqualTo(NO_OBJECTS);
    assertThat(evidence.read(scope,actor)).containsEntry("outcome",INCOMPLETE).doesNotContainKey("pollAfterSeconds");
  }

  @Test void task50eScheduledPauseUsesThePersistedDelay() {
    var scope=scope(50);
    var job=handoff(scope,"a","tenant-a","OPEN","RUNNING");
    jobs.awaitValidity(job,java.time.OffsetDateTime.now().plusMinutes(15));
    var result=evidence.read(scope,actor(scope.sourceId()));
    assertThat(result).containsEntry("outcome",MATERIALIZATION_PENDING);
    assertThat((Integer)result.get("pollAfterSeconds")).isBetween(870,901);
  }

  @Test void task61iQueuedApiJobIsPendingUntilItsSourceIsBlocked() {
    var scope=scope(50);
    var job=handoff(scope,"a","tenant-a","OPEN","READY",true);
    // The V40 fence parks a queued API job outside the V39 claim; the evidence still reports it as queued.
    assertThat(db.sql("select state||':'||api_semantics from ouf_udp.materialization_job where handoff_id=:h")
        .param("h",job.handoffId()).query(String.class).single()).isEqualTo("PAUSED:true");
    var actor=actor(scope.sourceId());
    var queued=evidence.read(scope,actor);
    assertThat(queued).containsEntry("outcome",MATERIALIZATION_PENDING).containsEntry("pollAfterSeconds",5);
    assertThat((List<?>)queued.get("items")).first().asInstanceOf(InstanceOfAssertFactories.MAP).containsEntry("job_state","READY");
    var evidenceRow=handoff(scope,"b","tenant-a","OPEN","SUCCEEDED");
    db.sql("""
        insert into ouf_udp.api_handoff_outcome(handoff_id,tenant_id,source_id,type_code,source_object_id,ingestion_run_id,
          epoch,kind,revision,rendering,item_sha256,operation,outcome)
        values(:h,'tenant-a',:s,'asset',:h,:run,'epoch','EVENT',1,'1','x','UPSERT','APPLIED')
        """).param("h",evidenceRow.handoffId()).param("s",scope.sourceId()).param("run",scope.runId().toString()).update();
    db.sql("insert into ouf_udp.api_source_block(tenant_id,source_id,evidence_handoff) values('tenant-a',:s,:h)")
        .param("s",scope.sourceId()).param("h",evidenceRow.handoffId()).update();
    var blocked=evidence.read(scope,actor);
    assertThat(blocked).containsEntry("outcome",INCOMPLETE).doesNotContainKey("pollAfterSeconds");
    assertThat((List<?>)blocked.get("items")).first().asInstanceOf(InstanceOfAssertFactories.MAP).containsEntry("job_state","PAUSED");
  }

  private com.fasterxml.jackson.databind.JsonNode readAcquisition(AcquisitionEvidenceApi.Query query) throws Exception {
    var args=new LinkedHashMap<String,Object>();
    args.put("sourceId",query.sourceId());args.put("runId",query.runId());args.put("limit",query.limit());
    byte[] bytes=JSON.writeValueAsBytes(args);
    var signer=new AcquisitionReceiptFilterTest();
    var receipt=signer.payload();
    long now=Instant.now().getEpochSecond();
    receipt.put("iat",now);receipt.put("exp",now+30);
    receipt.put("bodyHash",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    var response=http.perform(post(ObjectSearchReceiptFilter.ACQUISITION_PATH).contentType("application/json").content(bytes)
        .header(ObjectSearchReceiptFilter.ACQUISITION_HEADER,signer.sign(receipt,"ouf-udp-acquisition-owner-v1."))
        .with(r->bind(r,query.sourceId())))
      .andExpect(status().isOk()).andReturn().getResponse();
    return JSON.readTree(response.getContentAsByteArray());
  }

  private ServingAuthorizationContext actor(String source) {
    return ServingApi.context(bind(new MockHttpServletRequest(),source));
  }

  private MockHttpServletRequest bind(MockHttpServletRequest request,String source) {
    var caps=new HashSet<>(AcquisitionEvidenceService.CAPABILITIES);
    caps.add(SEARCH);
    TestAuthorization.bind(request,"reader","HUMAN",caps);
    var engine=(LocalAuthorization)request.getServletContext().getAttribute(ServletAuthorization.RUNTIME);
    var bundle=engine.currentSnapshot().bundle();
    var grants=bundle.grants().stream().map(g->{
      boolean search=SEARCH.equals(g.capabilityId());
      var attributes=source==null||search?Map.of("module","UDP"):Map.of("module","UDP","sourceRef",source);
      var constraints=new GrantConstraints("ALLOW",null,null,null,attributes,Set.of("OPEN"),
        search?Set.of():Set.of("TENANT_OPERATIONAL"),null,Set.of(),null);
      return new Grant(g.grantId(),g.capabilityId(),g.tenantId(),g.subjectId(),null,null,g.validFrom(),g.validUntil(),constraints);
    }).toList();
    try{TestAuthorization.install(engine,new PolicyBundle(bundle.bundleId(),2,bundle.publishedAt(),bundle.capabilities(),grants));}
    catch(Exception e){throw new IllegalStateException(e);}
    return request;
  }

  private AcquisitionEvidenceApi.Query scope(int limit) {
    UUID run=UUID.randomUUID();
    return new AcquisitionEvidenceApi.Query("source-"+run,run,limit,null);
  }

  private ResolutionRepository.Claim handoff(AcquisitionEvidenceApi.Query scope,String suffix,String tenant,String label,String state) {
    return handoff(scope,suffix,tenant,label,state,false);
  }

  private ResolutionRepository.Claim handoff(AcquisitionEvidenceApi.Query scope,String suffix,String tenant,String label,String state,boolean api) {
    UUID raw=UUID.randomUUID(),job=UUID.randomUUID();
    String handoff=scope.runId()+"-"+suffix;
    db.sql("""
        insert into ouf_udp.lake_object(lake_object_id,tenant_id,source_id,type_code,tier,content_hash,media_type,
          logical_size_bytes,locator,state,retention_class,access_label,retention_until)
        values(:raw,:tenant,:source,'asset','RAW',:hash,'application/json',1,:hash,'VERIFIED','AUDIT',:label,
          transaction_timestamp()+interval '1 day')
        """).param("raw",raw).param("tenant",tenant).param("source",scope.sourceId()).param("hash",handoff).param("label",label).update();
    db.sql("""
        insert into ouf_udp.handoff_intake(handoff_id,ingestion_run_id,ingestion_id,source_id,type_code,source_object_id,
          content_hash,payload_json,receipt_ref,raw_lake_object_id,state)
        values(:h,:run,:h,:source,'asset',:h,:h,jsonb_build_object('dataAccessLabels',
          jsonb_build_array(jsonb_build_object('labelId',cast(:label as text))))
          ||case when :api then jsonb_build_object('apiObservation',jsonb_build_object()) else '{}'::jsonb end,:receipt,:raw,:state)
        """).param("h",handoff).param("run",scope.runId().toString()).param("source",scope.sourceId()).param("label",label)
      .param("api",api).param("receipt","udp://handoffs/"+handoff).param("raw",raw).param("state","SUCCEEDED".equals(state)?"PROCESSED":"DURABLE").update();
    db.sql("""
        insert into ouf_udp.materialization_job(job_id,handoff_id,state,claimed_by,lease_until,api_tenant_id)
        values(:job,:h,:state,case when :state='RUNNING' then 'test-worker' end,
          case when :state='RUNNING' then transaction_timestamp()+interval '10 minutes' end,case when :api then cast(:tenant as text) end)
        """).param("job",job).param("h",handoff).param("state",state).param("api",api).param("tenant",tenant).update();
    return new ResolutionRepository.Claim(job,handoff,"asset",Map.of(),"test-worker",null,0,0);
  }

  private UUID object(String type) {
    return db.sql("insert into ouf_udp.urban_object(urban_object_id,tenant_id,canonical_type) values(gen_random_uuid(),'tenant-a',:t) returning urban_object_id")
      .param("t",type).query(UUID.class).single();
  }

  private static Path receiptKey() {
    try{
      var file=Files.createTempFile("udp-acquisition-fixture-",".key");
      Files.writeString(file,"ab".repeat(32),StandardCharsets.US_ASCII);
      file.toFile().deleteOnExit();
      return file;
    }catch(java.io.IOException e){throw new IllegalStateException(e);}
  }
}
