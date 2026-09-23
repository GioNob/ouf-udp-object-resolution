package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest
class GovernedServingRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));}
  @Autowired org.springframework.test.web.servlet.MockMvc http;
  @Autowired HandoffIntakeService intake;@Autowired ObjectResolutionService resolution;@Autowired CanonicalMaterializer canonical;@Autowired RelationshipMaterializer relationship;@Autowired GovernedServingService serving;@Autowired JdbcClient db;
  private final UdpPorts.ResolutionProfile resolutionProfile=new UdpPorts.ResolutionProfile("CANONICAL_KEY","1","policy://resolution/1","ouf:Asset","identity","identity");
  private final UdpPorts.MaterializationProfile materialization=new UdpPorts.MaterializationProfile("policy://authority/1",List.of(new UdpPorts.PropertyRule("name","ouf:name","string","OPEN",List.of("registry")),new UdpPorts.PropertyRule("secret","ouf:secret","string","RESTRICTED",List.of("registry"))));
  private final ServingAuthorizationContext open=new ServingAuthorizationContext("AI_AGENT","agent-1","default",Set.of("urban.object.read","urban.object.history.read","urban.object.search","urban.relationship.read","lineage.object.read","lineage.property.read"),Set.of("OPEN","ANONYMOUS"),"authz://serve/1","corr-serve-1");

  @BeforeEach void clean(){db.sql("truncate table ouf_udp.serving_access_audit,ouf_udp.merge_resolution_issue,ouf_udp.merge_property_contribution_link,ouf_udp.human_resolution_decision,ouf_udp.split_resolution_issue,ouf_udp.relationship_identity_history,ouf_udp.source_binding_history,ouf_udp.object_identity_history,ouf_udp.governance_audit,ouf_udp.governance_plan,ouf_udp.spatial_resolution_issue,ouf_udp.urban_geometry_current,ouf_udp.urban_geometry,ouf_udp.relationship_issue,ouf_udp.relationship_revision,ouf_udp.relationship_contribution,ouf_udp.urban_relationship,ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();}

  @Test void currentOmitsUnauthorizedPropertyBeforeSerializationAndAuditsRedaction(){Fixture f=materialize("h-1","asset-1","Visible","classified",null);Map<String,Object> view=serving.current(f.objectId,open);Map<String,Object> properties=map(view.get("properties"));assertThat(properties).containsEntry("ouf:name","Visible").doesNotContainKey("ouf:secret");assertThat(view.toString()).doesNotContain("classified");assertThat(db.sql("select outcome from ouf_udp.serving_access_audit").query(String.class).single()).isEqualTo("REDACTED");}

  @Test void historyUsesOpaqueCursorAndSameSourceUpdateCreatesRevision(){Fixture first=materialize("h-old","asset-1","Old","s1",null);Fixture second=materialize("h-new","asset-1","New","s2",null);assertThat(second.objectId).isEqualTo(first.objectId);var page1=serving.history(first.objectId,1,null,null,open);assertThat(page1.items()).hasSize(1);assertThat(page1.nextCursor()).isNotBlank().doesNotContain("1");assertThat(map(page1.items().getFirst().get("properties"))).containsEntry("ouf:name","New");var page2=serving.history(first.objectId,1,page1.nextCursor(),null,open);assertThat(map(page2.items().getFirst().get("properties"))).containsEntry("ouf:name","Old");assertThat(db.sql("select count(*) from ouf_udp.property_conflict").query(Long.class).single()).isZero();}

  @Test void tenantMismatchUsesAntiEnumerationNotFound(){Fixture f=materialize("tenant-h","asset-2","Name","secret",null);db.sql("update ouf_udp.urban_object set tenant_id='other' where urban_object_id=:u").param("u",f.objectId).update();assertThatThrownBy(()->serving.current(f.objectId,open)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404").hasMessageNotContaining(f.objectId.toString());}

  @Test void lineageOmitsSourceAndRawReferencesWithoutSeparateCapabilities(){Fixture f=materialize("lineage-h","asset-3","Name","secret",null);List<Map<String,Object>> lineage=serving.lineage(f.objectId,"ouf:name",open);assertThat(lineage).hasSize(1);assertThat(lineage.getFirst()).containsKeys("handoffRef","lineageRef","contractRefs").doesNotContainKeys("sourceIdentity","rawObjectRef");}

  @Test void restrictedRelationshipIsOmittedWithoutDegreeOrCursorLeak(){Fixture target=materialize("target-h","target","Target","s",null);Fixture source=materialize("source-h","source","Source","s","Target");var profile=new UdpPorts.RelationshipProfile("policy://relation/1",List.of(new UdpPorts.RelationshipRule("ref","ouf:linkedTo","ouf:Asset","ouf:name","CANONICAL_KEY","QUARANTINE_RELATION","RESTRICTED",false)));relationship.materialize("source-h",source.objectId,source.payload,profile);var page=serving.relationships(source.objectId,10,null,open);assertThat(page.items()).isEmpty();assertThat(page.nextCursor()).isNull();assertThat(db.sql("select count(*) from ouf_udp.urban_relationship where target_object_id=:t").param("t",target.objectId).query(Long.class).single()).isOne();}

  @Test void inboundRelationshipsUseSameEdgeAndDoNotExposeRestrictedEdges(){
    Fixture target=materialize("inbound-target","it","Target","s",null);Fixture source=materialize("inbound-source","is","Source","s","Target");
    var profile=new UdpPorts.RelationshipProfile("policy://relation/1",List.of(new UdpPorts.RelationshipRule("ref","ouf:linkedTo","ouf:Asset","ouf:name","CANONICAL_KEY","QUARANTINE_RELATION","OPEN",false)));
    relationship.materialize("inbound-source",source.objectId,source.payload,profile);
    var outbound=serving.relationships(source.objectId,10,null,open);var inbound=serving.relationships(target.objectId,10,null,"INBOUND",open);
    assertThat(inbound.items()).hasSize(1);assertThat(inbound.items().getFirst().get("relationship_id")).isEqualTo(outbound.items().getFirst().get("relationship_id"));assertThat(inbound.items().getFirst().get("source_object_id")).isEqualTo(source.objectId);
    assertThatThrownBy(()->serving.relationships(target.objectId,10,null,"BOTH",open)).hasMessage("UDP_RELATION_DIRECTION_INVALID");
  }

  @Test void sourceGeometryPropertyCannotBypassGeometryCapability(){
    var payload=handoff("geometry-property","geometry-property","Camera","secret",null);
    map(payload.get("canonicalPayload")).put("geometry",Map.of("crs","EPSG:4326","geoJson",Map.of("type","Point","coordinates",List.of(13.77,45.65))));
    intake.accept(payload);var decision=resolution.resolve("geometry-property",payload,resolutionProfile);var rules=new ArrayList<>(materialization.properties());rules.add(new UdpPorts.PropertyRule("geometry","ouf:geometry","geometry","OPEN",List.of()));
    canonical.materialize("geometry-property",decision.targetUrbanObjectId(),payload,new UdpPorts.MaterializationProfile("policy://authority/1",rules));
    assertThat(map(serving.current(decision.targetUrbanObjectId(),open).get("properties"))).doesNotContainKey("ouf:geometry");
    assertThat(map(serving.history(decision.targetUrbanObjectId(),10,null,null,open).items().getFirst().get("properties"))).doesNotContainKey("ouf:geometry");
    assertThat(map(serving.search("ouf:Asset",10,null,open).items().getFirst().get("properties"))).doesNotContainKey("ouf:geometry");
    var geometric=new ServingAuthorizationContext("HUMAN_USER","reader","default",Set.of("urban.object.read","urban.geometry.read"),Set.of("OPEN"),"authz://geometry","geometry");
    assertThat(map(serving.current(decision.targetUrbanObjectId(),geometric).get("properties"))).containsKey("ouf:geometry");
  }

  @Test void searchRequiresIndexedExactTypeAndBoundedPage(){assertThatThrownBy(()->serving.search("*",10,null,open)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->serving.search("ouf:Asset",101,null,open)).hasMessageContaining("UDP_PAGE_SIZE_OUT_OF_RANGE");}

  @Test void postSearchUsesOwnerAuthorizationAndMinimizesProperties()throws Exception{
    Fixture f=materialize("post-search","post-asset","Visible","classified",null);
    db.sql("update ouf_udp.urban_object set tenant_id='tenant-a' where urban_object_id=:id").param("id",f.objectId).update();
    var post=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/udp/v1/objects/search")
      .contentType("application/json").content("{\"type\":\"ouf:Asset\",\"pageSize\":10}");
    http.perform(post).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    var authorized=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/udp/v1/objects/search")
      .contentType("application/json").content("{\"type\":\"ouf:Asset\",\"pageSize\":10}")
      .with(request->{it.comune.trieste.ouf.authorization.TestAuthorization.bind(request,"reader","HUMAN",Set.of("urban.object.search"));return request;});
    http.perform(authorized).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString(f.objectId.toString())))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("classified"))));
    for(String invalid:List.of("{\"type\":\"*\"}","{\"type\":\"ouf:Asset\",\"sql\":\"select 1\"}","{\"type\":\"ouf:Asset\",\"pageSize\":\"10\"}","{\"type\":\"ouf:Asset\",\"cursor\":\"invalid\"}")){
      http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/udp/v1/objects/search")
        .contentType("application/json").content(invalid))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/udp/v1/objects/search")
      .contentType("application/json").content("{\"type\":\"ouf:Asset\"}")
      .with(request->{it.comune.trieste.ouf.authorization.TestAuthorization.bind(request,"reader","HUMAN",Set.of("urban.object.read"));return request;}))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    db.sql("update ouf_udp.urban_object set tenant_id='another-tenant' where urban_object_id=:id").param("id",f.objectId).update();
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/udp/v1/objects/search")
      .contentType("application/json").content("{\"type\":\"ouf:Asset\"}")
      .with(request->{it.comune.trieste.ouf.authorization.TestAuthorization.bind(request,"reader","HUMAN",Set.of("urban.object.search"));return request;}))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(f.objectId.toString()))));
  }

  @Test void httpNeverTrustsAllowedLabelsAndDeniesWrongObject()throws Exception{
    Fixture f=materialize("http-label","http-object","Visible","classified",null);db.sql("update ouf_udp.urban_object set tenant_id='tenant-a' where urban_object_id=:id").param("id",f.objectId).update();
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/udp/v1/objects/"+f.objectId).with(request->{it.comune.trieste.ouf.authorization.TestAuthorization.bind(request,"reader","HUMAN",Set.of("urban.object.read"));request.setAttribute("ouf.allowedDataLabels",Set.of("OPEN","RESTRICTED"));return request;}))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("Visible")))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("classified"))));
    for(String target:List.of(f.objectId.toString(),"another-object"))http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/udp/v1/objects/"+f.objectId).with(request->{
      it.comune.trieste.ouf.authorization.TestAuthorization.bind(request,"reader","HUMAN",Set.of("urban.object.read"));
      var engine=(it.comune.trieste.ouf.authorization.LocalAuthorization)request.getServletContext().getAttribute(it.comune.trieste.ouf.authorization.ServletAuthorization.RUNTIME);var old=engine.currentSnapshot().bundle();var g=old.grants().getFirst();
      var constraint=new it.comune.trieste.ouf.authorization.AuthorizationPolicy.GrantConstraints("ALLOW",null,"object",target,Map.of(),Set.of("OPEN"),Set.of(),null,Set.of(),null);
      var grant=new it.comune.trieste.ouf.authorization.AuthorizationPolicy.Grant(g.grantId(),g.capabilityId(),g.tenantId(),g.subjectId(),g.servicePrincipalId(),g.organizationId(),g.validFrom(),g.validUntil(),constraint);
      try{it.comune.trieste.ouf.authorization.TestAuthorization.install(engine,new it.comune.trieste.ouf.authorization.AuthorizationPolicy.PolicyBundle(old.bundleId(),2,old.publishedAt(),old.capabilities(),List.of(grant)));}catch(Exception failure){throw new IllegalStateException(failure);}return request;
    })).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(target.equals(f.objectId.toString())?200:403));
  }

  @Test void storedSourceScopeAndExplicitDenyControlPropertyProjection()throws Exception{
    Fixture f=materialize("http-scope","scope-object","ScopedValue","classified",null);db.sql("update ouf_udp.urban_object set tenant_id='tenant-a' where urban_object_id=:id").param("id",f.objectId).update();
    for(String source:List.of("another-source","registry")){
      var result=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/udp/v1/objects/"+f.objectId).with(request->{
        it.comune.trieste.ouf.authorization.TestAuthorization.bind(request,"reader","HUMAN",Set.of("urban.object.read"));
        var engine=(it.comune.trieste.ouf.authorization.LocalAuthorization)request.getServletContext().getAttribute(it.comune.trieste.ouf.authorization.ServletAuthorization.RUNTIME);var old=engine.currentSnapshot().bundle();var g=old.grants().getFirst();var grants=new ArrayList<>(old.grants());
        var c=new it.comune.trieste.ouf.authorization.AuthorizationPolicy.GrantConstraints("DENY",null,"object",f.objectId.toString(),Map.of("propertyIri","ouf:name","sourceRef",source,"jobRef","run-1"),Set.of("OPEN"),Set.of(),null,Set.of(),null);
        grants.add(new it.comune.trieste.ouf.authorization.AuthorizationPolicy.Grant("source-deny",g.capabilityId(),g.tenantId(),g.subjectId(),null,null,g.validFrom(),g.validUntil(),c));
        try{it.comune.trieste.ouf.authorization.TestAuthorization.install(engine,new it.comune.trieste.ouf.authorization.AuthorizationPolicy.PolicyBundle(old.bundleId(),2,old.publishedAt(),old.capabilities(),grants));}catch(Exception e){throw new IllegalStateException(e);}return request;
      })).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
      assertThat(result.getResponse().getContentAsString().contains("ScopedValue")).isEqualTo(!source.equals("registry"));
    }
  }
  @Test void relationshipTargetDenialSuppressesEdgeAndCursor()throws Exception{
    Fixture target=materialize("http-target","target","Target","s",null),source=materialize("http-source","source","Source","s","Target");
    relationship.materialize("http-source",source.objectId,source.payload,new UdpPorts.RelationshipProfile("policy://relation/1",List.of(new UdpPorts.RelationshipRule("ref","ouf:linkedTo","ouf:Asset","ouf:name","CANONICAL_KEY","QUARANTINE_RELATION","OPEN",false))));
    db.sql("update ouf_udp.urban_object set tenant_id='tenant-a'").update();
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/udp/v1/objects/"+source.objectId+"/relationships").with(request->{
      it.comune.trieste.ouf.authorization.TestAuthorization.bind(request,"reader","HUMAN",Set.of("urban.object.read","urban.relationship.read"));
      var engine=(it.comune.trieste.ouf.authorization.LocalAuthorization)request.getServletContext().getAttribute(it.comune.trieste.ouf.authorization.ServletAuthorization.RUNTIME);var old=engine.currentSnapshot().bundle();var g=old.grants().stream().filter(x->x.capabilityId().equals("urban.object.read")).findFirst().orElseThrow();var grants=new ArrayList<>(old.grants());
      var c=new it.comune.trieste.ouf.authorization.AuthorizationPolicy.GrantConstraints("DENY",null,"object",target.objectId.toString(),Map.of(),Set.of("OPEN"),Set.of(),null,Set.of(),null);
      grants.add(new it.comune.trieste.ouf.authorization.AuthorizationPolicy.Grant("target-deny",g.capabilityId(),g.tenantId(),g.subjectId(),null,null,g.validFrom(),g.validUntil(),c));
      try{it.comune.trieste.ouf.authorization.TestAuthorization.install(engine,new it.comune.trieste.ouf.authorization.AuthorizationPolicy.PolicyBundle(old.bundleId(),2,old.publishedAt(),old.capabilities(),grants));}catch(Exception e){throw new IllegalStateException(e);}return request;
    })).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items.length()").value(0)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.partial").value(true)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(target.objectId.toString()))));
  }

  private Fixture materialize(String handoff,String object,String name,String secret,String ref){Map<String,Object> payload=handoff(handoff,object,name,secret,ref);intake.accept(payload);var decision=resolution.resolve(handoff,payload,resolutionProfile);canonical.materialize(handoff,decision.targetUrbanObjectId(),payload,materialization);return new Fixture(decision.targetUrbanObjectId(),payload);}
  private static Map<String,Object> handoff(String id,String object,String name,String secret,String ref){Map<String,Object> content=new LinkedHashMap<>();content.put("identity",object);content.put("name",name);content.put("secret",secret);if(ref!=null)content.put("ref",ref);return new LinkedHashMap<>(Map.ofEntries(Map.entry("handoffId",id),Map.entry("ingestionRunId","run-1"),Map.entry("ingestionId","ing-"+id),Map.entry("sourceIdentity",new LinkedHashMap<>(Map.of("sourceId","registry","typeCode","ASSET","sourceObjectId",object,"observedAt","2026-09-13T00:00:00Z"))),Map.entry("operation","UPSERT"),Map.entry("canonicalPayload",content),Map.entry("rawObjectRef","raw://"+id),Map.entry("contractRefs",Map.of("sourceSchemaRef","schema://1","bundleRef","bundle://1","semanticPublicationSetRef","semantic://1","adapterProfileRef","adapter://1")),Map.entry("lineageId","lineage-"+id),Map.entry("contentHash","sha256:"+id),Map.entry("acquiredAt","2026-09-13T00:00:00Z"),Map.entry("changeRepresentation",Map.of("mode","FULL_SNAPSHOT"))));}
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
  @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
  private record Fixture(UUID objectId,Map<String,Object> payload){}
}
