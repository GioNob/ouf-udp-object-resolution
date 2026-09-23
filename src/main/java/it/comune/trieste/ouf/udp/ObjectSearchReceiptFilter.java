package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import it.comune.trieste.ouf.authorization.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Clock;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Only a short-lived, body-bound gateway receipt may install the UDP search principal. */
@Component
public final class ObjectSearchReceiptFilter extends OncePerRequestFilter {
  static final String PATH="/api/udp/v1/objects/search", HEADER="X-OUF-UDP-Search-Receipt";
  private final Environment env;
  private final Clock clock;
  private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  public ObjectSearchReceiptFilter(Environment env){this(env,Clock.systemUTC());}
  ObjectSearchReceiptFilter(Environment env,Clock clock){this.env=env;this.clock=clock;}
  @Override protected boolean shouldNotFilter(HttpServletRequest req){return !PATH.equals(req.getRequestURI());}
  @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException{
    if(!"POST".equals(req.getMethod())){res.sendError(405);return;}
    String tenant=env.getProperty("ouf.udp.search.tenant-id","");
    String issuer=env.getProperty("ouf.udp.search.issuer","");
    String audience=env.getProperty("ouf.udp.search.audience","");
    String workload=env.getProperty("ouf.udp.search.workload","");
    String file=env.getProperty("ouf.udp.search.owner-key-file","");
    byte[] key;
    try{
      if(tenant.isBlank()||issuer.isBlank()||audience.isBlank()||workload.isBlank()||file.isBlank())throw new IllegalStateException();
      String secret=Files.readString(Path.of(file),StandardCharsets.US_ASCII).strip();
      if(!secret.matches("[a-fA-F0-9]{64}"))throw new IllegalStateException();
      key=secret.getBytes(StandardCharsets.US_ASCII);
    }catch(Exception e){res.sendError(503,"UDP_SEARCH_IDENTITY_UNAVAILABLE");return;}
    byte[] body=req.getInputStream().readNBytes(65537);
    if(body.length>65536){res.sendError(413);return;}
    HttpServletRequest wrapped;
    try{
      String signed=req.getHeader(HEADER);
      if(signed==null||signed.length()>16384)throw new SecurityException();
      String[] parts=signed.split("\\.",-1);
      if(parts.length!=2||!parts[0].matches("[A-Za-z0-9_-]+")||!parts[1].matches("[A-Za-z0-9_-]+"))throw new SecurityException();
      Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
      if(!MessageDigest.isEqual(mac.doFinal(("ouf-udp-search-owner-v1."+parts[0]).getBytes(StandardCharsets.US_ASCII)),Base64.getUrlDecoder().decode(parts[1])))throw new SecurityException();
      JsonNode receipt=json.readTree(Base64.getUrlDecoder().decode(parts[0]));
      long now=clock.instant().getEpochSecond();
      if(!receipt.isObject()||!receipt.path("v").isIntegralNumber()||receipt.path("v").asInt()!=1||!receipt.path("iat").isIntegralNumber()||!receipt.path("exp").isIntegralNumber())throw new SecurityException();
      long issued=receipt.path("iat").asLong(),expires=receipt.path("exp").asLong();
      if(issued>now||expires<=now||expires<=issued||expires-issued>30)throw new SecurityException();
      Map<String,String> expected=Map.of("purpose","udp-object-search-owner","method","POST","path",PATH,"capability","urban.object.search","tenant",tenant,"issuer",issuer,"audience",audience,"workload",workload);
      for(var e:expected.entrySet())if(!e.getValue().equals(text(receipt,e.getKey())))throw new SecurityException();
      if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)).equals(text(receipt,"bodyHash")))throw new SecurityException();
      Set<String> scopes=words(receipt,"scope",false),roles=words(receipt,"roles",true);
      if(roles.size()>32||!scopes.contains("urban.object.search"))throw new SecurityException();
      String subject=text(receipt,"subject"),client=text(receipt,"client"),acr=text(receipt,"acr"),correlation=text(receipt,"correlationId");
      text(receipt,"requestHash");
      var principal=new TrustedPrincipal(new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,client,acr,issuer,audience,scopes,new PrincipalContext.IdentityClaims(roles,acr,Set.of(),null)));
      wrapped=new HttpServletRequestWrapper(req){
        @Override public Principal getUserPrincipal(){return principal;}
        @Override public String getRemoteUser(){return principal.getName();}
        @Override public ServletInputStream getInputStream(){var stream=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return stream.read();}public boolean isFinished(){return stream.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener l){throw new UnsupportedOperationException();}};}
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
      };
      wrapped.setAttribute("ouf.correlationId",correlation);
      // Admission is owner policy, not a gateway assertion. Per-object/property decisions run in ServingApi.
      OwnerAuthorization.bind(wrapped).require("urban.object.search",new ResourceContext("capability",null,tenant,null,Map.of()));
    }catch(Exception e){res.sendError(403,"INVALID_UDP_SEARCH_RECEIPT_OR_POLICY");return;}
    chain.doFilter(wrapped,res);
  }
  private static String text(JsonNode node,String key){JsonNode value=node.get(key);if(value==null||!value.isTextual()||value.asText().isBlank()||value.asText().length()>4096||value.asText().chars().anyMatch(Character::isISOControl))throw new SecurityException();return value.asText();}
  private static Set<String> words(JsonNode node,String key,boolean empty){if(empty&&node.path(key).isTextual()&&node.path(key).asText().isEmpty())return Set.of();String value=text(node,key);String[] parts=value.split(" ",-1);if(parts.length>128||Arrays.stream(parts).anyMatch(String::isBlank))throw new SecurityException();return Set.copyOf(Arrays.asList(parts));}
}
