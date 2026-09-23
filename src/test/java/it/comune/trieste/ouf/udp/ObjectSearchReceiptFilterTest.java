package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.Principal;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class ObjectSearchReceiptFilterTest {
  @TempDir Path dir;
  private static final String KEY="ab".repeat(32);
  private final ObjectMapper json=new ObjectMapper();
  private MockEnvironment environment()throws Exception {
    Path file=dir.resolve("owner-key");Files.writeString(file,KEY);
    return new MockEnvironment().withProperty("ouf.udp.search.tenant-id","tenant-a")
      .withProperty("ouf.udp.search.issuer","https://auth.test/realms/ouf")
      .withProperty("ouf.udp.search.audience","gateway")
      .withProperty("ouf.udp.search.workload","workload")
      .withProperty("ouf.udp.search.owner-key-file",file.toString());
  }
  private Map<String,Object> payload(byte[] body){
    var receipt=new LinkedHashMap<String,Object>();
    receipt.put("v",1);receipt.put("purpose","udp-object-search-owner");receipt.put("method","POST");receipt.put("path",ObjectSearchReceiptFilter.PATH);
    receipt.put("bodyHash",HexFormat.of().formatHex(sha(body)));receipt.put("capability","urban.object.search");receipt.put("iat",1000);receipt.put("exp",1030);
    receipt.put("issuer","https://auth.test/realms/ouf");receipt.put("audience","gateway");receipt.put("workload","workload");
    receipt.put("subject","human-a");receipt.put("tenant","tenant-a");receipt.put("client","chatgpt");receipt.put("acr","1");receipt.put("roles","ente:viewer");
    receipt.put("scope","mcp.connect urban.object.search");receipt.put("requestHash","a".repeat(64));receipt.put("correlationId","corr-1");return receipt;
  }
  private static byte[] sha(byte[] value){try{return java.security.MessageDigest.getInstance("SHA-256").digest(value);}catch(Exception e){throw new RuntimeException(e);}}
  private String signed(Map<String,Object> receipt)throws Exception {
    String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(receipt));
    Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.US_ASCII),"HmacSHA256"));
    return encoded+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("ouf-udp-search-owner-v1."+encoded).getBytes(StandardCharsets.US_ASCII)));
  }
  private record Run(int status,Principal principal,String body,String correlation){}
  private Run run(Map<String,Object> receipt,byte[] body,String header)throws Exception {
    return run(receipt,body,header,false);
  }
  private Run run(Map<String,Object> receipt,byte[] body,String header,boolean denyOwner)throws Exception {
    var req=new MockHttpServletRequest("POST",ObjectSearchReceiptFilter.PATH);req.setContent(body);req.addHeader(ObjectSearchReceiptFilter.HEADER,header==null?signed(receipt):header);
    TestAuthorization.bind(req,denyOwner?"someone-else":"human-a","HUMAN",Set.of("urban.object.search"));
    var res=new MockHttpServletResponse();var chain=new MockFilterChain();
    new ObjectSearchReceiptFilter(environment(),Clock.fixed(Instant.ofEpochSecond(1001),ZoneOffset.UTC)).doFilter(req,res,chain);
    var forwarded=chain.getRequest() instanceof jakarta.servlet.http.HttpServletRequest r?r:null;
    return new Run(res.getStatus(),forwarded==null?null:forwarded.getUserPrincipal(),forwarded==null?null:new String(forwarded.getInputStream().readAllBytes(),StandardCharsets.UTF_8),forwarded==null?null:(String)forwarded.getAttribute("ouf.correlationId"));
  }
  @Test void validReceiptCreatesPrincipalOnlyAfterOwnerAdmission()throws Exception{
    byte[] body="{\"type\":\"ouf:Asset\"}".getBytes(StandardCharsets.UTF_8);
    Run result=run(payload(body),body,null);
    assertThat(result.status()).isEqualTo(200);assertThat(result.principal()).isInstanceOf(TrustedPrincipal.class);
    assertThat(((TrustedPrincipal)result.principal()).context().tenantId()).isEqualTo("tenant-a");
    assertThat(result.body()).isEqualTo(new String(body,StandardCharsets.UTF_8));assertThat(result.correlation()).isEqualTo("corr-1");
  }
  @Test void acceptsGatewayLuaReceiptOnExactForwardedBody()throws Exception{
    String fixture=System.getenv("OUF_GATEWAY_SEARCH_FIXTURE");
    org.junit.jupiter.api.Assumptions.assumeTrue(fixture!=null&&!fixture.isBlank(),"cross-repository Gateway fixture");
    var generated=json.readTree(Files.readString(Path.of(fixture)));
    byte[] body=generated.path("body").asText().getBytes(StandardCharsets.UTF_8);
    String header=generated.path("receipt").asText();
    Run accepted=run(payload(body),body,header);
    assertThat(accepted.status()).isEqualTo(200);
    assertThat(accepted.principal()).isInstanceOf(TrustedPrincipal.class);
    assertThat(accepted.body()).isEqualTo(new String(body,StandardCharsets.UTF_8));
    assertThat(accepted.correlation()).isEqualTo("corr");
    assertThat(run(payload(body),"{}".getBytes(StandardCharsets.UTF_8),header).status()).isEqualTo(403);
    assertThat(run(payload(body),body,header,true).status()).isEqualTo(403);
  }
  @Test void rejectsForgedBodySignatureTenantScopeAndExpiry()throws Exception{
    byte[] body="{\"type\":\"ouf:Asset\"}".getBytes(StandardCharsets.UTF_8);
    Map<String,Object> original=payload(body);
    assertThat(run(original,body,"").status()).isEqualTo(403);
    assertThat(run(original,body,null,true).status()).isEqualTo(403);
    assertThat(run(original,"{}".getBytes(StandardCharsets.UTF_8),null).status()).isEqualTo(403);
    assertThat(run(original,body,signed(original)+"corrupt").status()).isEqualTo(403);
    for(String field:List.of("tenant","scope","exp")){
      Map<String,Object> modified=new LinkedHashMap<>(original);
      modified.put(field,field.equals("tenant")?"tenant-b":field.equals("scope")?"mcp.connect":999);
      assertThat(run(modified,body,null).status()).isEqualTo(403);
    }
  }
}
