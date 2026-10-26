package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.*;

@SpringBootTest
class AcquisitionEvidenceRuntimeTest {
  @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));r.add("ouf.udp.lake.tenant-id",()->"tenant-a");}
  @Autowired JdbcClient db;@Autowired AcquisitionEvidenceService service;
  record Fixture(UUID run,String source,String handoff,UUID object){}
  @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
  ServingAuthorizationContext actor(Set<String> caps){return actor(caps,null);}
  ServingAuthorizationContext actor(Set<String> caps,String source){return actor(caps,source,null);}
  ServingAuthorizationContext actor(Set<String> caps,String source,String resourceType){
    var request=new MockHttpServletRequest();TestAuthorization.bind(request,"reader","HUMAN",caps);
    var engine=(LocalAuthorization)request.getServletContext().getAttribute(ServletAuthorization.RUNTIME);var old=engine.currentSnapshot().bundle();
    var attrs=source==null?Map.of("module","UDP"):Map.of("module","UDP","sourceRef",source);
    var grants=old.grants().stream().map(g->new Grant(g.grantId(),g.capabilityId(),g.tenantId(),g.subjectId(),g.servicePrincipalId(),g.organizationId(),g.validFrom(),g.validUntil(),new GrantConstraints("ALLOW",null,resourceType,null,attrs,Set.of("OPEN"),Set.of("TENANT_OPERATIONAL"),null,Set.of(),null))).toList();
    try{TestAuthorization.install(engine,new PolicyBundle(old.bundleId(),2,old.publishedAt(),old.capabilities(),grants));}catch(Exception failure){throw new IllegalStateException(failure);}
    return ServingApi.context(request);
  }
  AcquisitionEvidenceApi.Query query(Fixture f){return new AcquisitionEvidenceApi.Query(f.source(),f.run(),50,null);}
  @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="EVIDENCE_READ_IMAGE",matches=".+")
  void packagedImageUsesSignedHttpReadAndDeniesMutation()throws Exception{
    Fixture f=fixture("tenant-a","OPEN",true);long before=count();
    var directory=java.nio.file.Files.createTempDirectory("udp-packaged-read-");var key=directory.resolve("key");java.nio.file.Files.writeString(key,"ab".repeat(32));
    var engine=TestAuthorization.runtime("reader","HUMAN",AcquisitionEvidenceService.CAPABILITIES);var old=engine.currentSnapshot().bundle();
    var grants=old.grants().stream().map(g->new Grant(g.grantId(),g.capabilityId(),g.tenantId(),g.subjectId(),null,null,g.validFrom(),g.validUntil(),new GrantConstraints("ALLOW",null,null,null,Map.of("module","UDP"),Set.of("OPEN"),Set.of("TENANT_OPERATIONAL"),null,Set.of(),null))).toList();
    var policy=new PolicyBundle("fixture",2,old.publishedAt(),old.capabilities(),grants);byte[] bytes=new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).writeValueAsBytes(policy);var file=directory.resolve("policy.json");java.nio.file.Files.write(file,bytes);String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    var args=List.of("--spring.main.sources=it.comune.trieste.ouf.udp.UdpApplication","--spring.main.web-application-type=none","--spring.flyway.enabled=true","--spring.sql.init.mode=always","--ouf.udp.execution.enabled=true","--ouf.udp.lake.tenant-id=tenant-a",
      "--ouf.udp.search.tenant-id=tenant-a","--ouf.udp.search.issuer=https://auth.test/realms/ouf","--ouf.udp.search.audience=gateway","--ouf.udp.search.workload=workload","--ouf.udp.search.owner-key-file="+key,
      "--ouf.authorization.bundle-file="+file,"--ouf.authorization.bundle-id=fixture","--ouf.authorization.bundle-version=2","--ouf.authorization.bundle-sha256="+sha);
    try(var container=new PackagedReadProcess(directory,args,"OUF_UDP_DB_")){
      container.ready();byte[] body=("{\"sourceId\":\""+f.source()+"\",\"runId\":\""+f.run()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
      var signer=new AcquisitionReceiptFilterTest();var receipt=signer.payload();long now=java.time.Instant.now().getEpochSecond();receipt.put("iat",now);receipt.put("exp",now+30);receipt.put("bodyHash",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body)));
      var uri=java.net.URI.create("http://127.0.0.1:"+container.port+ObjectSearchReceiptFilter.ACQUISITION_PATH);
      var request=java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(5)).header("Content-Type","application/json")
        .header(ObjectSearchReceiptFilter.ACQUISITION_HEADER,signer.sign(receipt,"ouf-udp-acquisition-owner-v1.")).header("X-Correlation-ID","corr").header("X-Tool-Attempt-ID","11111111-1111-4111-8111-111111111111").header("Idempotency-Key","idem")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)).build();
      var client=container.client();var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      assertThat(new ObjectMapper().readTree(response.body()).path("authorization").asText()).isEqualTo("AUTHORIZED");
      assertThat(new ObjectMapper().readTree(response.body()).path("redactionReasons").isEmpty()).isTrue();
      var denied=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+container.port+"/api/udp/v1/governance/properties/conflicts/arbitrary/decisions")).timeout(java.time.Duration.ofSeconds(5)).POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(denied.statusCode()).isIn(401,403);
    }
    assertThat(count()).isEqualTo(before);
  }
  @Test void separateReadApplicationUsesRealHttpWithNoExecutionBeansOrWritableConnections()throws Exception{
    Fixture f=fixture("tenant-a","OPEN",true);var directory=java.nio.file.Files.createTempDirectory("udp-evidence-http-");
    var key=directory.resolve("key");java.nio.file.Files.writeString(key,"ab".repeat(32));
    var engine=TestAuthorization.runtime("reader","HUMAN",AcquisitionEvidenceService.CAPABILITIES);var old=engine.currentSnapshot().bundle();
    var grants=old.grants().stream().map(g->new Grant(g.grantId(),g.capabilityId(),g.tenantId(),g.subjectId(),null,null,g.validFrom(),g.validUntil(),new GrantConstraints("ALLOW",null,null,null,Map.of("module","UDP"),Set.of("OPEN"),Set.of("TENANT_OPERATIONAL"),null,Set.of(),null))).toList();
    var policy=new PolicyBundle("fixture",2,old.publishedAt(),old.capabilities(),grants);
    byte[] bytes=new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).writeValueAsBytes(policy);var file=directory.resolve("policy.json");java.nio.file.Files.write(file,bytes);
    String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    try(var context=it.comune.trieste.ouf.evidence.UdpAcquisitionReadApplication.application().run(
      "--server.port=0","--spring.jmx.enabled=false","--spring.main.sources=it.comune.trieste.ouf.udp.UdpApplication","--spring.main.web-application-type=none","--spring.flyway.enabled=true","--spring.sql.init.mode=always","--ouf.udp.execution.enabled=true","--ouf.udp.lake.tenant-id=tenant-a",
      "--ouf.udp.search.tenant-id=tenant-a","--ouf.udp.search.issuer=https://auth.test/realms/ouf","--ouf.udp.search.audience=gateway","--ouf.udp.search.workload=workload","--ouf.udp.search.owner-key-file="+key,
      "--ouf.authorization.bundle-file="+file,"--ouf.authorization.bundle-id=fixture","--ouf.authorization.bundle-version=2","--ouf.authorization.bundle-sha256="+sha)){
      assertThat(context.getBeansOfType(ResolutionWorker.class)).isEmpty();assertThat(context.getBeansOfType(LakeMaintenanceWorker.class)).isEmpty();assertThat(context.getBeansOfType(LakeShadowRebuildWorker.class)).isEmpty();
      assertThat(context.getBeansOfType(org.flywaydb.core.Flyway.class)).isEmpty();
      var ds=context.getBean(com.zaxxer.hikari.HikariDataSource.class);assertThat(ds.getMaximumPoolSize()).isEqualTo(2);assertThat(ds.getMinimumIdle()).isZero();
      try(var connection=ds.getConnection()){
        assertThat(connection.isReadOnly()).isTrue();assertThat(connection.getAutoCommit()).isFalse();
        assertThatThrownBy(()->connection.createStatement().execute("create table ouf_udp.evidence_forbidden_write(id int)")).hasMessageContaining("read-only");connection.rollback();
      }
      int port=context.getEnvironment().getRequiredProperty("local.server.port",Integer.class);
      byte[] body=("{\"sourceId\":\""+f.source()+"\",\"runId\":\""+f.run()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
      var signer=new AcquisitionReceiptFilterTest();var p=signer.payload();long now=java.time.Instant.now().getEpochSecond();p.put("iat",now);p.put("exp",now+30);p.put("bodyHash",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body)));
      var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+ObjectSearchReceiptFilter.ACQUISITION_PATH)).header("Content-Type","application/json")
        .header(ObjectSearchReceiptFilter.ACQUISITION_HEADER,signer.sign(p,"ouf-udp-acquisition-owner-v1.")).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)).build();
      var client=java.net.http.HttpClient.newHttpClient();var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);assertThat(new ObjectMapper().readTree(response.body()).path("items").get(0).path("read_only_snapshot").asBoolean()).isTrue();
      var denied=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/udp/v1/governance/properties/conflicts/arbitrary/decisions")).POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(denied.statusCode()).isIn(401,403);
    }
  }
  @Test void exactRunReadsDurableIntakeAndObservedRevisionWithoutReturningRawData()throws Exception{
    Fixture f=fixture("tenant-a","OPEN",true);long before=count();
    var report=service.read(query(f),actor(AcquisitionEvidenceService.CAPABILITIES,f.source()));
    assertThat(report).containsEntry("authorization","AUTHORIZED").containsEntry("partial",false).containsEntry("redactionReasons",List.of()).containsEntry("ingestionAckProven",false);
    assertThat(rows(report.get("items"))).singleElement().satisfies(i->{
      assertThat(i).containsEntry("handoff_id",f.handoff()).containsEntry("urban_object_id",f.object()).containsEntry("read_only_snapshot",true)
        .containsEntry("durableIntakeRecorded",true).containsEntry("receiptHash",AcquisitionEvidenceService.hash("udp://handoffs/"+f.handoff()));
      assertThat(rows(i.get("properties"))).singleElement().satisfies(p->assertThat(p).containsEntry("property_iri","ouf:name").containsEntry("selected_in_observed_revision",true).containsEntry("selected_in_current_revision",true));
    });
    String serialized=new ObjectMapper().writeValueAsString(report);
    for(String secret:List.of("private-source-key","private-raw-path","private-property-value"))assertThat(serialized).doesNotContain(secret);
    assertThat(count()).isEqualTo(before);
    var otherRun=new AcquisitionEvidenceApi.Query(f.source(),UUID.randomUUID(),50,null);
    assertThat(rows(service.read(otherRun,actor(AcquisitionEvidenceService.CAPABILITIES)).get("items"))).isEmpty();
  }
  @Test void unrelatedTenantOrUnprovenHistoricalTenantBindingCannotBecomeSuccess(){
    Fixture foreign=fixture("tenant-b","OPEN",true);var hidden=service.read(query(foreign),actor(AcquisitionEvidenceService.CAPABILITIES));
    assertThat(hidden).containsEntry("authorization","REDACTED").containsEntry("partial",true);assertThat(rows(hidden.get("items"))).isEmpty();
    Fixture unbound=fixture("tenant-a","OPEN",false);var unknown=service.read(query(unbound),actor(AcquisitionEvidenceService.CAPABILITIES));
    assertThat(unknown).containsEntry("partial",true);assertThat(rows(unknown.get("items"))).isEmpty();
  }
  @Test void everyReadPermissionAndTheExactSourceConstraintRemainRequired(){
    Fixture f=fixture("tenant-a","OPEN",true);
    for(String missing:AcquisitionEvidenceService.CAPABILITIES){var caps=new HashSet<>(AcquisitionEvidenceService.CAPABILITIES);caps.remove(missing);assertThatThrownBy(()->service.read(query(f),actor(caps))).hasMessageContaining("403");}
    assertThatThrownBy(()->service.read(query(f),actor(AcquisitionEvidenceService.CAPABILITIES,"another-source"))).hasMessageContaining("403");
  }
  @Test void accessLabelDenialRedactsRatherThanReturningAPropertyOrFalseEmptySuccess(){
    Fixture f=fixture("tenant-a","SENSITIVE",true);var hidden=service.read(query(f),actor(AcquisitionEvidenceService.CAPABILITIES));
    assertThat(hidden).containsEntry("partial",true).containsEntry("authorization","REDACTED");assertThat(rows(hidden.get("items"))).isEmpty();
  }
  @Test void acknowledgedIntakeWithNoObservationDoesNotProveMaterialization(){
    Fixture f=fixture("tenant-a","OPEN",true);
    // A second retained intake on the same source/run is durable but has no observation or RAW tenant pin.
    String pending="z-"+UUID.randomUUID();insertHandoff(pending,f.run(),f.source(),"OPEN");
    var report=service.read(query(f),actor(AcquisitionEvidenceService.CAPABILITIES));
    assertThat(report).containsEntry("partial",true).containsEntry("authorization","REDACTED");
    assertThat(rows(report.get("items"))).hasSize(1);
  }

  @Test void redactionReasonsAreCoarseAndTenantFailureDoesNotExposeLabelDetails()throws Exception{
    var a=actor(AcquisitionEvidenceService.CAPABILITIES);
    Fixture foreign=fixture("tenant-b","SENSITIVE",true);
    var hidden=service.read(query(foreign),a);
    assertThat(hidden).containsEntry("redactionReasons",List.of("RETAINED_TENANT_NOT_VERIFIED"));
    Fixture f=fixture("tenant-a","OPEN",true);long before=count();
    for(String labels:List.of("[]","{}","[{}]","[{\"labelId\":\"UNKNOWN_PRIVATE_LABEL\"}]")){
      db.sql("update ouf_udp.handoff_intake set payload_json=jsonb_set(payload_json,'{dataAccessLabels}',cast(:j as jsonb)) where handoff_id=:h")
        .param("j",labels).param("h",f.handoff()).update();
      hidden=service.read(query(f),a);
      assertThat(hidden).containsEntry("authorization","REDACTED").containsEntry("partial",true)
        .containsEntry("redactionReasons",List.of("DATA_LABEL_METADATA_INVALID")).containsEntry("nextHandoffId",null);
      assertThat(rows(hidden.get("items"))).isEmpty();
      assertThat(new ObjectMapper().writeValueAsString(hidden)).doesNotContain(f.handoff(),f.object().toString(),"UNKNOWN_PRIVATE_LABEL");
    }
    db.sql("update ouf_udp.handoff_intake set payload_json=payload_json-'dataAccessLabels' where handoff_id=:h").param("h",f.handoff()).update();
    assertThat(service.read(query(f),a)).containsEntry("redactionReasons",List.of("DATA_LABEL_METADATA_INVALID"));
    db.sql("update ouf_udp.handoff_intake set payload_json=jsonb_set(payload_json,'{dataAccessLabels}','[{\"labelId\":\"RESTRICTED\"}]'::jsonb) where handoff_id=:h")
      .param("h",f.handoff()).update();
    hidden=service.read(query(f),a);
    assertThat(hidden).containsEntry("redactionReasons",List.of("DATA_LABEL_NOT_AUTHORIZED"));
    assertThat(new ObjectMapper().writeValueAsString(hidden)).doesNotContain("RESTRICTED",f.handoff(),f.object().toString());
    assertThat(count()).isEqualTo(before);
  }
  @Test void objectAndPropertyDenialsHaveDistinctReasonsAndKeepTheirDataHidden(){
    Fixture f=fixture("tenant-a","OPEN",true);
    var objectHidden=service.read(query(f),actor(AcquisitionEvidenceService.CAPABILITIES,f.source(),"operational"));
    assertThat(objectHidden).containsEntry("redactionReasons",List.of("OBJECT_NOT_AUTHORIZED"));
    assertThat(rows(objectHidden.get("items"))).isEmpty();
    db.sql("update ouf_udp.property_contribution set access_label='SENSITIVE' where handoff_id=:h").param("h",f.handoff()).update();
    var propertyHidden=service.read(query(f),actor(AcquisitionEvidenceService.CAPABILITIES));
    assertThat(propertyHidden).containsEntry("partial",true).containsEntry("authorization","REDACTED")
      .containsEntry("redactionReasons",List.of("PROPERTY_NOT_AUTHORIZED"));
    assertThat(rows(propertyHidden.get("items"))).singleElement().satisfies(i->assertThat(rows(i.get("properties"))).isEmpty());
  }

  long count(){return db.sql("select (select count(*) from ouf_udp.handoff_intake)+(select count(*) from ouf_udp.property_contribution)+(select count(*) from ouf_udp.materialization_observation)").query(Long.class).single();}
  Fixture fixture(String tenant,String label,boolean observe){
    UUID run=UUID.randomUUID(),object=UUID.randomUUID(),revision=UUID.randomUUID(),contribution=UUID.randomUUID();String source="evidence-"+run,handoff=UUID.randomUUID().toString();
    insertHandoff(handoff,run,source,label);
    db.sql("insert into ouf_udp.urban_object(urban_object_id,canonical_type,tenant_id) values(:o,'ouf:Asset',:t)").param("o",object).param("t",tenant).update();
    if(observe){
      db.sql("insert into ouf_udp.object_revision(revision_id,urban_object_id,revision_no,canonical_hash,authority_hash,authority_state,canonical_payload,access_labels,source_handoff_id,bundle_ref,semantic_publication_ref) values(:v,:o,1,'sha256:canonical','sha256:authority','{}','{}','{}',:h,'bundle:1:sha256:pinned','semantic:1')").param("v",revision).param("o",object).param("h",handoff).update();
      db.sql("update ouf_udp.urban_object set current_revision_id=:v where urban_object_id=:o").param("v",revision).param("o",object).update();
      db.sql("insert into ouf_udp.materialization_observation(observation_id,handoff_id,urban_object_id,revision_id,material_change,canonical_hash) values(:i,:h,:o,:v,true,'sha256:canonical')").param("i",UUID.randomUUID()).param("h",handoff).param("o",object).param("v",revision).update();
      db.sql("insert into ouf_udp.property_contribution(contribution_id,urban_object_id,handoff_id,property_iri,value_json,value_hash,access_label,source_id,authority_rank,provenance_json) values(:c,:o,:h,'ouf:name','\"private-property-value\"','sha256:value',:label,:s,0,jsonb_build_object('lineageRef',cast(:l as text),'ingestionRunId',cast(:r as text),'rawObjectRef','private-raw-path','contractRefs',jsonb_build_object('bundleRef','bundle:1:sha256:pinned')))")
        .param("c",contribution).param("o",object).param("h",handoff).param("label",label).param("s",source).param("l",UUID.randomUUID().toString()).param("r",run.toString()).update();
      db.sql("insert into ouf_udp.property_value(property_value_id,revision_id,property_iri,value_json,datatype,access_label,contribution_id) values(:i,:v,'ouf:name','\"private-property-value\"','string',:label,:c)").param("i",UUID.randomUUID()).param("v",revision).param("label",label).param("c",contribution).update();
    }
    return new Fixture(run,source,handoff,object);
  }
  void insertHandoff(String handoff,UUID run,String source,String label){
    db.sql("insert into ouf_udp.handoff_intake(handoff_id,ingestion_run_id,ingestion_id,source_id,type_code,source_object_id,content_hash,payload_json,receipt_ref,state) values(:h,:r,:i,:s,'asset','private-source-key','sha256:content',cast(:p as jsonb),:receipt,'PROCESSED')")
      .param("h",handoff).param("r",run.toString()).param("i",UUID.randomUUID().toString()).param("s",source).param("receipt","udp://handoffs/"+handoff)
      .param("p","{\"lineageId\":\""+UUID.randomUUID()+"\",\"dataAccessLabels\":[{\"labelId\":\""+label+"\"}],\"canonicalPayload\":{\"name\":\"private-property-value\"}}").update();
  }
  static String required(String name){return Objects.requireNonNull(System.getenv(name),name);}
}
