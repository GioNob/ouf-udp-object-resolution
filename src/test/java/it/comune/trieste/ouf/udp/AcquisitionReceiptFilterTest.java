package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;

class AcquisitionReceiptFilterTest {
  @TempDir Path dir;
  final String key="ab".repeat(32);final byte[] body="{\"sourceId\":\"s\",\"runId\":\"11111111-1111-4111-8111-111111111111\"}".getBytes(StandardCharsets.UTF_8);
  Map<String,Object> payload()throws Exception{
    var p=new LinkedHashMap<String,Object>();p.put("v",1);p.put("purpose","udp-acquisition-owner");p.put("method","POST");p.put("path",ObjectSearchReceiptFilter.ACQUISITION_PATH);
    p.put("capability","urban.acquisition.read");p.put("bodyHash",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body)));p.put("iat",1000);p.put("exp",1030);
    p.put("issuer","https://auth.test/realms/ouf");p.put("audience","gateway");p.put("workload","workload");p.put("subject","reader");p.put("tenant","tenant-a");p.put("client","chatgpt");p.put("acr","1");p.put("roles","");p.put("scope",String.join(" ",new TreeSet<>(AcquisitionEvidenceService.CAPABILITIES)));p.put("requestHash","a".repeat(64));p.put("correlationId","corr");return p;
  }
  String sign(Map<String,Object> p,String domain)throws Exception{
    String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(new ObjectMapper().writeValueAsBytes(p));var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII),"HmacSHA256"));return encoded+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((domain+encoded).getBytes(StandardCharsets.US_ASCII)));
  }
  int run(Map<String,Object> p,String domain,byte[] sent)throws Exception{
    Path file=dir.resolve("key");Files.writeString(file,key);var env=new MockEnvironment().withProperty("ouf.udp.search.tenant-id","tenant-a").withProperty("ouf.udp.search.issuer","https://auth.test/realms/ouf").withProperty("ouf.udp.search.audience","gateway").withProperty("ouf.udp.search.workload","workload").withProperty("ouf.udp.search.owner-key-file",file.toString());
    var request=new MockHttpServletRequest("POST",ObjectSearchReceiptFilter.ACQUISITION_PATH);request.setContentType("application/json");request.setContent(sent);request.addHeader(ObjectSearchReceiptFilter.ACQUISITION_HEADER,sign(p,domain));TestAuthorization.bind(request,"reader","HUMAN",AcquisitionEvidenceService.CAPABILITIES);
    var response=new MockHttpServletResponse();var chain=new MockFilterChain();new ObjectSearchReceiptFilter(env,Clock.fixed(Instant.ofEpochSecond(1001),ZoneOffset.UTC)).doFilter(request,response,chain);
    if(response.getStatus()==200)assertThat(((jakarta.servlet.http.HttpServletRequest)chain.getRequest()).getUserPrincipal()).isInstanceOf(TrustedPrincipal.class);
    return response.getStatus();
  }
  @Test void separateDomainBodyAndAllThreeScopesAreRequired()throws Exception{
    String domain="ouf-udp-acquisition-owner-v1.";assertThat(run(payload(),domain,body)).isEqualTo(200);
    assertThat(run(payload(),"ouf-udp-search-owner-v1.",body)).isEqualTo(403);assertThat(run(payload(),domain,"{}".getBytes(StandardCharsets.UTF_8))).isEqualTo(403);
    for(String missing:AcquisitionEvidenceService.CAPABILITIES){var p=payload();var scopes=new TreeSet<>(AcquisitionEvidenceService.CAPABILITIES);scopes.remove(missing);p.put("scope",String.join(" ",scopes));assertThat(run(p,domain,body)).isEqualTo(403);}
    for(var change:Map.<String,Object>of("capability","urban.object.search","tenant","other","exp",999,"unknown",true).entrySet()){var p=payload();p.put(change.getKey(),change.getValue());assertThat(run(p,domain,body)).isEqualTo(403);}
  }
}
