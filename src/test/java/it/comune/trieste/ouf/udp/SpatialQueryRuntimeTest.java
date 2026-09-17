package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest
class SpatialQueryRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));}
  @Autowired SpatialQueryService spatial;@Autowired SpatialBudgetStore budgets;@Autowired JdbcClient db;
  @Autowired org.springframework.test.web.servlet.MockMvc http;
  private ServingAuthorizationContext auth;
  @BeforeEach void clean(){db.sql("truncate table ouf_udp.query_budget,ouf_udp.serving_access_audit,ouf_udp.merge_resolution_issue,ouf_udp.merge_property_contribution_link,ouf_udp.human_resolution_decision,ouf_udp.split_resolution_issue,ouf_udp.relationship_identity_history,ouf_udp.source_binding_history,ouf_udp.object_identity_history,ouf_udp.governance_audit,ouf_udp.governance_plan,ouf_udp.spatial_resolution_issue,ouf_udp.urban_geometry_current,ouf_udp.urban_geometry,ouf_udp.relationship_issue,ouf_udp.relationship_revision,ouf_udp.relationship_contribution,ouf_udp.urban_relationship,ouf_udp.property_conflict,ouf_udp.property_value,ouf_udp.materialization_observation,ouf_udp.property_contribution,ouf_udp.object_revision,ouf_udp.resolution_issue,ouf_udp.resolution_decision,ouf_udp.source_binding,ouf_udp.urban_object,ouf_udp.materialization_job,ouf_udp.handoff_event,ouf_udp.handoff_intake restart identity cascade").update();auth=context("corr-spatial");}

  @Test void nearbyUsesBoundedPostgisPlanAndFiltersLabels(){UUID anchor=place("a","ouf:Anchor","POINT(13.77 45.65)","OPEN","default"),open=place("b","ouf:Target","POINT(13.771 45.65)","OPEN","default"),restricted=place("c","ouf:Target","POINT(13.772 45.65)","RESTRICTED","default");var result=spatial.nearby(anchor,"ouf:Target",1000,10,auth);assertThat(result.hits()).extracting(SpatialQueryService.Hit::urbanObjectId).containsExactly(open).doesNotContain(restricted);assertThat(result.physicalPlan()).isEqualTo("POSTGIS_NEARBY");}
  @Test void envelopeIntersectsAndWithinRequireGovernedCrsAndArea(){UUID inside=place("in","ouf:Area","POINT(13.77 45.65)","OPEN","default");var intersects=spatial.envelope("INTERSECTS",13.7,45.6,13.8,45.7,"EPSG:4326","ouf:Area",10,auth);var within=spatial.envelope("WITHIN",13.7,45.6,13.8,45.7,"EPSG:4326","ouf:Area",10,auth);assertThat(intersects.hits()).extracting(SpatialQueryService.Hit::urbanObjectId).containsExactly(inside);assertThat(within.hits()).extracting(SpatialQueryService.Hit::urbanObjectId).containsExactly(inside);assertThatThrownBy(()->spatial.envelope("INTERSECTS",-180,-90,180,90,"EPSG:4326","ouf:Area",10,auth)).hasMessageContaining("AREA_OUT_OF_RANGE");assertThatThrownBy(()->spatial.envelope("INTERSECTS",13.7,45.6,13.8,45.7,"EPSG:3857","ouf:Area",10,auth)).hasMessageContaining("CRS_UNSUPPORTED");}
  @Test void intersectionSearchIsTenantAndLabelScoped(){UUID anchor=place("a","ouf:Anchor","POLYGON((13.7 45.6,13.8 45.6,13.8 45.7,13.7 45.7,13.7 45.6))","OPEN","default"),visible=place("b","ouf:Target","POINT(13.75 45.65)","OPEN","default");place("c","ouf:Target","POINT(13.75 45.65)","OPEN","other");var result=spatial.intersectionSearch(anchor,"ouf:Target",10,auth);assertThat(result.hits()).extracting(SpatialQueryService.Hit::urbanObjectId).containsExactly(visible);}
  @Test void invisibleAnchorIsAntiEnumeratedBeforeBudget(){UUID anchor=place("a","ouf:Anchor","POINT(13.77 45.65)","OPEN","other");assertThatThrownBy(()->spatial.nearby(anchor,"ouf:Target",1000,10,auth)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404").hasMessageNotContaining(anchor.toString());assertThat(db.sql("select count(*) from ouf_udp.query_budget").query(Long.class).single()).isZero();}
  @Test void cumulativeSpatialRadiusIsAtomicAndCannotReset(){budgets.consume(auth,10,100,100,50000,0);budgets.consume(auth,10,100,100,50000,0);assertThatThrownBy(()->budgets.consume(auth,10,100,100,1,0)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("429");}

  private UUID place(String handoff,String type,String wkt,String label,String tenant){UUID object=UUID.randomUUID(),geometry=UUID.randomUUID();db.sql("insert into ouf_udp.handoff_intake(handoff_id,ingestion_run_id,ingestion_id,source_id,type_code,source_object_id,content_hash,payload_json,receipt_ref) values(:h,'run','ing','spatial',:type,:h,:h,'{}',:receipt)").param("h",handoff).param("type",type).param("receipt","udp://"+handoff).update();db.sql("insert into ouf_udp.urban_object(urban_object_id,canonical_type,canonical_key,match_key,tenant_id) values(:id,:type,:key,:key,:tenant)").param("id",object).param("type",type).param("key",handoff).param("tenant",tenant).update();db.sql("insert into ouf_udp.urban_geometry(geometry_revision_id,urban_object_id,handoff_id,geometry,geometry_hash,source_crs,canonical_srid,normalization_version,access_label,evidence_json) values(:g,:o,:h,ST_GeomFromText(:wkt,4326),:hash,'EPSG:4326',4326,'test',:label,'{}')").param("g",geometry).param("o",object).param("h",handoff).param("wkt",wkt).param("hash","hash-"+handoff).param("label",label).update();db.sql("insert into ouf_udp.urban_geometry_current(urban_object_id,geometry_revision_id) values(:o,:g)").param("o",object).param("g",geometry).update();return object;}
  private static ServingAuthorizationContext context(String correlation){return new ServingAuthorizationContext("AI_AGENT","agent","default",Set.of("urban.spatial.nearby","urban.spatial.intersects","urban.spatial.within","urban.spatial.intersection_search"),Set.of("OPEN","ANONYMOUS"),"authz://spatial",correlation);}
  @Test void httpSpatialRoutesApplyGeometryAndSourcePolicyBeforeReturningHits()throws Exception{
    UUID a=place("http-anchor","ouf:Anchor","POINT(13.77 45.65)","OPEN","tenant-a"),b=place("http-open","ouf:Target","POINT(13.77 45.65)","OPEN","tenant-a"),secret=place("http-secret","ouf:Target","POINT(13.77 45.65)","RESTRICTED","tenant-a");
    Set<String> caps=Set.of("urban.object.read","urban.geometry.read","urban.spatial.nearby","urban.spatial.intersects","urban.spatial.within","urban.spatial.intersection_search");
    for(String route:List.of("nearby","intersects","within","intersection-search")){
      String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("anchorObjectId",a,"targetType","ouf:Target","maxResults",10,"maxDistanceMeters",1000,"minLon",13.7,"minLat",45.6,"maxLon",13.8,"maxLat",45.7,"crs","EPSG:4326"));
      http.perform(post("/api/udp/v1/spatial/"+route).contentType("application/json").content(json).with(policy(caps,null,null,null,Map.of())))
        .andExpect(status().isOk()).andExpect(jsonPath("$.partial").value(true)).andExpect(content().string(org.hamcrest.Matchers.containsString(b.toString())))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(secret.toString()))));
      http.perform(post("/api/udp/v1/spatial/"+route).contentType("application/json").content(json).with(policy(caps,"urban.geometry.read","object",b.toString(),Map.of("sourceRef","spatial","jobRef","run"))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.hits.length()").value(0)).andExpect(jsonPath("$.partial").value(true));
    }
    String nearby=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("anchorObjectId",a,"targetType","ouf:Target","maxResults",10,"maxDistanceMeters",1000));
    http.perform(post("/api/udp/v1/spatial/nearby").contentType("application/json").content(nearby).with(policy(caps,"urban.geometry.read","object",a.toString(),Map.of()))) .andExpect(status().isForbidden());
  }
  private org.springframework.test.web.servlet.request.RequestPostProcessor policy(Set<String> caps,String denyCap,String type,String id,Map<String,String> scope){return request->{
    TestAuthorization.bind(request,"reader","HUMAN",caps);
    request.setAttribute("ouf.allowedDataLabels",Set.of("OPEN","RESTRICTED"));
    if(denyCap!=null){var engine=(LocalAuthorization)request.getServletContext().getAttribute(ServletAuthorization.RUNTIME);var old=engine.currentSnapshot().bundle();var g=old.grants().stream().filter(x->x.capabilityId().equals(denyCap)).findFirst().orElseThrow();var grants=new ArrayList<>(old.grants());
      grants.add(new Grant("query-deny",denyCap,g.tenantId(),g.subjectId(),null,null,g.validFrom(),g.validUntil(),new GrantConstraints("DENY",null,type,id,scope,Set.of("OPEN"),Set.of(),null,Set.of(),null)));
      try{TestAuthorization.install(engine,new PolicyBundle(old.bundleId(),2,old.publishedAt(),old.capabilities(),grants));}catch(Exception e){throw new IllegalStateException(e);}
    }return request;};}
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
}
