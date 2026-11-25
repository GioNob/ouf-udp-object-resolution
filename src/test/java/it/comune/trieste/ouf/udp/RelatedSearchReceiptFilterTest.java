package it.comune.trieste.ouf.udp;

import static it.comune.trieste.ouf.udp.SearchReceiptFixture.*;
import it.comune.trieste.ouf.authorization.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.Principal;
import java.time.*;
import java.util.*;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

/** Contratto C-RS per l’ammissione della ricerca correlata. */
class RelatedSearchReceiptFilterTest {
  @TempDir Path dir;
  private static final String RELATED="urban.object.related_search", SEARCH="urban.object.search";
  private static final byte[] BODY=("{\"anchorObjectId\":\"00000000-0000-0000-0000-000000000001\",\"anchorType\":\"ouf:Street\","
    +"\"relationIri\":\"ouf:locatedOn\",\"direction\":\"INBOUND\",\"targetTypes\":[\"ouf:Dehor\"],\"maxResults\":10}").getBytes(StandardCharsets.UTF_8);
  private final Map<String,String> properties=new LinkedHashMap<>();

  private MockEnvironment environment()throws Exception {
    Path file=dir.resolve("owner-key");if(!Files.exists(file))Files.writeString(file,KEY);
    var defaults=new LinkedHashMap<String,String>();
    defaults.put("ouf.udp.search.tenant-id","tenant-a");defaults.put("ouf.udp.search.issuer",ISSUER);
    defaults.put("ouf.udp.search.audience","gateway");defaults.put("ouf.udp.search.workload","workload");
    defaults.put("ouf.udp.search.owner-key-file",file.toString());defaults.putAll(properties);
    var env=new MockEnvironment();defaults.forEach(env::setProperty);return env;
  }
  private static Map<String,Object> payload(String path,String capability,byte[] body){
    return SearchReceiptFixture.payload(path, capability, body, 1000);
  }
  private static Map<String,Object> related(){return payload(RELATED_PATH,RELATED,BODY);}
  private record Run(int status,String error,boolean forwarded,Principal principal,String body,String correlation){}
  private Run run(String method,String uri,byte[] body,String header,String grantedSubject,Set<String> granted)throws Exception {
    var req=new MockHttpServletRequest(method,uri);req.setContent(body);if(header!=null)req.addHeader(HEADER,header);
    TestAuthorization.bind(req,grantedSubject,"HUMAN",granted);
    var res=new MockHttpServletResponse();var chain=new MockFilterChain();
    new ObjectSearchReceiptFilter(environment(),Clock.fixed(Instant.ofEpochSecond(1001),ZoneOffset.UTC)).doFilter(req,res,chain);
    var next=chain.getRequest() instanceof HttpServletRequest r?r:null;
    return new Run(res.getStatus(),res.getErrorMessage(),next!=null,next==null?null:next.getUserPrincipal(),
      next==null?null:new String(next.getInputStream().readAllBytes(),StandardCharsets.UTF_8),next==null?null:(String)next.getAttribute("ouf.correlationId"));
  }
  private Run related(String header)throws Exception {return run("POST",RELATED_PATH,BODY,header,"human-a",Set.of(RELATED));}
  private void assertDenied(Run run){
    assertThat(run.status()).isEqualTo(403);assertThat(run.error()).isEqualTo("INVALID_UDP_SEARCH_RECEIPT_OR_POLICY");assertThat(run.forwarded()).isFalse();
  }

  @Test @DisplayName("UDP-60m-01 Principal, corpo e correlazione dopo l’ammissione")
  void validReceiptForwardsExpectedPrincipalBodyAndCorrelation()throws Exception{
    Run result=related(signed(related()));
    assertThat(result.status()).isEqualTo(200);assertThat(result.forwarded()).isTrue();
    assertThat(result.principal()).isInstanceOf(TrustedPrincipal.class);
    PrincipalContext principal=((TrustedPrincipal)result.principal()).context();
    assertThat(principal.subjectId()).isEqualTo("human-a");assertThat(principal.tenantId()).isEqualTo("tenant-a");
    assertThat(principal.actorType()).isEqualTo(PrincipalContext.ActorType.HUMAN);
    assertThat(principal.scopes()).containsExactlyInAnyOrder("mcp.connect","urban.object.read",RELATED);
    assertThat(principal.issuer()).isEqualTo(ISSUER);assertThat(principal.audience()).isEqualTo("gateway");
    assertThat(principal.servicePrincipalId()).isEqualTo("client-test");assertThat(principal.claims().externalRoleRefs()).containsExactly("role-test");
    assertThat(result.body()).isEqualTo(new String(BODY,StandardCharsets.UTF_8));assertThat(result.correlation()).isEqualTo("corr-related");
  }

  @Test @DisplayName("UDP-60m-02 Ricevute distinte nelle due direzioni")
  void receiptsAreBoundToTheirOwnRouteAndCapability()throws Exception{
    assertDenied(related(signed(payload(ObjectSearchReceiptFilter.PATH,SEARCH,BODY))));
    var searchScope=payload(ObjectSearchReceiptFilter.PATH,SEARCH,BODY);searchScope.put("scope","mcp.connect urban.object.search urban.object.related_search");
    assertDenied(related(signed(searchScope)));
    assertDenied(related(signed(payload(RELATED_PATH,SEARCH,BODY))));
    assertDenied(related(signed(payload(ObjectSearchReceiptFilter.PATH,RELATED,BODY))));
    assertDenied(run("POST",ObjectSearchReceiptFilter.PATH,BODY,signed(related()),"human-a",Set.of(SEARCH,RELATED)));
    var both=related();both.put("scope","urban.object.search urban.object.related_search");
    assertDenied(run("POST",ObjectSearchReceiptFilter.PATH,BODY,signed(both),"human-a",Set.of(SEARCH,RELATED)));
    // Controllo positivo sulla rotta di ricerca storica.
    assertThat(run("POST",ObjectSearchReceiptFilter.PATH,BODY,signed(payload(ObjectSearchReceiptFilter.PATH,SEARCH,BODY)),"human-a",Set.of(SEARCH)).status()).isEqualTo(200);
  }

  @Test @DisplayName("UDP-60m-03 Integrità del corpo, firma e dominio")
  void alteredBodyOrSignatureIsRejected()throws Exception{
    String header=signed(related());
    assertDenied(run("POST",RELATED_PATH,"{}".getBytes(StandardCharsets.UTF_8),header,"human-a",Set.of(RELATED)));
    byte[] tampered=new String(BODY,StandardCharsets.UTF_8).replace("\"maxResults\":10","\"maxResults\":11").getBytes(StandardCharsets.UTF_8);
    assertDenied(run("POST",RELATED_PATH,tampered,header,"human-a",Set.of(RELATED)));
    assertDenied(related(header+"corrupt"));
    assertDenied(related(signed(related(),"ouf-udp-related-search-owner-v1.")));
    assertDenied(related(""));
    assertDenied(related(null));
  }

  @Test @DisplayName("UDP-60m-04 Finestra temporale e limiti numerici")
  void windowLongerThirtySecondsExpiredOrFutureIsRejected()throws Exception{
    var tooLong=related();tooLong.put("exp",1031);assertDenied(related(signed(tooLong)));
    var wide = related();
    wide.put("iat", Long.MIN_VALUE);
    assertDenied(related(signed(wide)));
    var outsideLong = related();
    outsideLong.put("exp", BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(1030)));
    assertDenied(related(signed(outsideLong)));
    var expired=related();expired.put("iat",970);expired.put("exp",1000);assertDenied(related(signed(expired)));
    var future=related();future.put("iat",1002);future.put("exp",1020);assertDenied(related(signed(future)));
    var inverted=related();inverted.put("iat",1000);inverted.put("exp",1000);assertDenied(related(signed(inverted)));
    var edge=related();edge.put("iat",1000);edge.put("exp",1030);assertThat(related(signed(edge)).status()).isEqualTo(200);
  }

  @Test @DisplayName("UDP-60m-05 Scope obbligatorio per la ricerca correlata")
  void missingRelatedScopeIsRejected()throws Exception{
    var absent = related();
    absent.remove("scope");
    assertDenied(related(signed(absent)));
    for(String scope:List.of("mcp.connect","mcp.connect urban.object.search urban.object.read","urban.object.related_search_extra")){
      var receipt=related();receipt.put("scope",scope);assertDenied(related(signed(receipt)));
    }
  }

  @Test @DisplayName("UDP-60m-06 Metodo diverso da POST")
  void nonPostMethodsAreRefusedWith405()throws Exception{
    for(String method:List.of("GET","PUT","PATCH","DELETE")){
      Run result=run(method,RELATED_PATH,BODY,signed(related()),"human-a",Set.of(RELATED));
      assertThat(result.status()).isEqualTo(405);assertThat(result.forwarded()).isFalse();
    }
  }

  @Test @DisplayName("UDP-60m-07 Configurazione mancante o inutilizzabile")
  void missingConfigurationFailsClosedWith503()throws Exception{
    for(String property:List.of("ouf.udp.search.tenant-id","ouf.udp.search.issuer","ouf.udp.search.audience","ouf.udp.search.workload","ouf.udp.search.owner-key-file")){
      properties.clear();properties.put(property,"");
      Run result=related(signed(related()));
      assertThat(result.status()).as(property).isEqualTo(503);assertThat(result.error()).isEqualTo("UDP_SEARCH_IDENTITY_UNAVAILABLE");assertThat(result.forwarded()).isFalse();
    }
    Path weak=dir.resolve("weak-key");Files.writeString(weak,"not-a-hex-key");
    properties.clear();properties.put("ouf.udp.search.owner-key-file",weak.toString());
    assertThat(related(signed(related())).status()).isEqualTo(503);
    properties.clear();properties.put("ouf.udp.search.owner-key-file",dir.resolve("missing-key").toString());
    assertThat(related(signed(related())).status()).isEqualTo(503);
  }

  @Test @DisplayName("UDP-60m-08 Limite del corpo di 64 KiB")
  void oversizedBodyIsRefusedWith413()throws Exception{
    byte[] limit = new byte[65536];
    Run accepted = run("POST", RELATED_PATH, limit, signed(payload(RELATED_PATH, RELATED, limit)), "human-a", Set.of(RELATED));
    assertThat(accepted.forwarded()).isTrue();
    byte[] large=new byte[65537];Arrays.fill(large,(byte)' ');
    Run result=run("POST",RELATED_PATH,large,signed(payload(RELATED_PATH,RELATED,large)),"human-a",Set.of(RELATED));
    assertThat(result.status()).isEqualTo(413);assertThat(result.forwarded()).isFalse();
  }

  @Test @DisplayName("UDP-60m-09 Ammissione tramite policy owner")
  void ownerPolicyMustAdmitRelatedSearch()throws Exception{
    assertDenied(run("POST",RELATED_PATH,BODY,signed(related()),"someone-else",Set.of(RELATED)));
    assertDenied(run("POST",RELATED_PATH,BODY,signed(related()),"human-a",Set.of(SEARCH)));
    for(String field:List.of("tenant","issuer","audience","workload","purpose","method")){
      var receipt=related();receipt.put(field,"other");assertDenied(related(signed(receipt)));
    }
  }

  @Test @DisplayName("UDP-60m-10 Percorsi equivalenti e rotte estranee")
  void routeVariantsAreFilteredAndOtherRoutesPassThrough()throws Exception{
    for(String uri:List.of("/api/udp/v1/objects/related%2Dsearch","/api/udp/v1/objects/related-search;x=1")){
      assertDenied(run("POST",uri,BODY,null,"human-a",Set.of(RELATED,SEARCH)));
    }
    assertThat(run("POST","/api/udp/v1/objects/related%2Dsearch",BODY,signed(related()),"human-a",Set.of(RELATED)).status()).isEqualTo(200);
    Run graph=run("POST","/api/udp/v1/graph/neighbors",BODY,null,"human-a",Set.of(RELATED));
    assertThat(graph.status()).isEqualTo(200);assertThat(graph.forwarded()).isTrue();
    assertThat(((TrustedPrincipal)graph.principal()).context().issuer()).isEqualTo("fixture-issuer");
  }
}
