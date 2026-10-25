package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;

class AcquisitionReceiptPairwiseTest {
  @TempDir Path directory;
  @Test void actualGatewayLuaReceiptBindsTheUdpPrincipalAndRejectsChangedBody()throws Exception{
    String vectors=System.getenv("OUF_ACQUISITION_VECTORS");Assumptions.assumeTrue(vectors!=null,"composed Gateway gate supplies actual Lua receipts");
    var root=new ObjectMapper().readTree(Files.readAllBytes(Path.of(vectors)));int seen=0;
    for(var v:root.get("vectors"))if(v.path("capability").asText().equals("urban.acquisition.read")){
      seen++;Path key=directory.resolve("key");Files.writeString(key,"ef".repeat(32));
      var env=new MockEnvironment().withProperty("ouf.udp.search.tenant-id","tenant-a").withProperty("ouf.udp.search.issuer","https://auth.test/realms/ouf").withProperty("ouf.udp.search.audience","gateway").withProperty("ouf.udp.search.workload","workload").withProperty("ouf.udp.search.owner-key-file",key.toString());
      for(boolean changed:new boolean[]{false,true}){
        var request=new MockHttpServletRequest("POST",v.get("path").asText());request.setContentType("application/json");request.setContent((changed?"{}":v.get("body").asText()).getBytes(StandardCharsets.UTF_8));request.addHeader(ObjectSearchReceiptFilter.ACQUISITION_HEADER,v.get("receipt").asText());TestAuthorization.bind(request,"human-a","HUMAN",AcquisitionEvidenceService.CAPABILITIES);
        var response=new MockHttpServletResponse();var chain=new MockFilterChain();new ObjectSearchReceiptFilter(env,Clock.fixed(Instant.ofEpochSecond(1001),ZoneOffset.UTC)).doFilter(request,response,chain);
        assertThat(response.getStatus()).isEqualTo(changed?403:200);
        if(!changed)assertThat(((jakarta.servlet.http.HttpServletRequest)chain.getRequest()).getUserPrincipal()).isInstanceOf(TrustedPrincipal.class);
      }
    }
    assertThat(seen).isOne();
  }
}
