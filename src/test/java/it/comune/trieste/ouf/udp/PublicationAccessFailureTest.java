package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PublicationAccessFailureTest {
 @Test void realHttpReadsSeparateDenialsFromTransientResponsesAndNeverFollowRedirects() throws Exception {
  var calls=new AtomicInteger();var status=new AtomicInteger(503);
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{calls.incrementAndGet();exchange.getResponseHeaders().add("Retry-After","60");
   exchange.getResponseHeaders().add("Location","/other");exchange.sendResponseHeaders(status.get(),-1);exchange.close();});
  server.start();var file=Files.createTempFile("udp-test-token-",".txt");Files.writeString(file,"test-only");
  try{

   for(int code:new int[]{408,429,500,502,503,504,401,403,404,410,302,400}){
   var configuration=new PublishedRuntimeConfiguration(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),file.toString(),"tenant",new ReferenceBatchSettings(262144,1000,2049,3145728,3,1,1));
    status.set(code);int before=calls.get();var now=Instant.now();
    assertThatThrownBy(()->configuration.resolve("bundle","ROAD")).isInstanceOfSatisfying(PublicationAccessFailure.class,failure->{
      boolean transientError=java.util.Set.of(408,429,500,502,503,504).contains(code);
      assertThat(failure.retryable()).isEqualTo(transientError);
      if(transientError)assertThat(failure.retryNotBefore()).isAfterOrEqualTo(now.plusSeconds(59));
    });
    assertThat(calls.get()).isEqualTo(before+1);
   }
  }finally{server.stop(0);Files.deleteIfExists(file);}
 }
 @Test void malformedSuccessfulResponseAndMissingCredentialArePermanent() throws Exception {
  var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{calls.incrementAndGet();byte[] body="not-json-private".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
  server.start();var file=Files.createTempFile("udp-test-token-",".txt");Files.writeString(file,"test-only");
  try{
   var configuration=new PublishedRuntimeConfiguration(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),file.toString(),"tenant",new ReferenceBatchSettings(262144,1000,2049,3145728,3,1,1));
   assertThatThrownBy(()->configuration.resolve("bundle","ROAD")).isInstanceOfSatisfying(PublicationAccessFailure.class,e->{
    assertThat(e.retryable()).isFalse();assertThat(e.getMessage()).isEqualTo("UDP_PUBLICATION_RESPONSE_INVALID");});
   Files.delete(file);
   assertThatThrownBy(()->configuration.resolve("bundle","ROAD")).isInstanceOfSatisfying(PublicationAccessFailure.class,e->assertThat(e.retryable()).isFalse());
   assertThat(calls).hasValue(1);
  }finally{server.stop(0);Files.deleteIfExists(file);}
 }
 @Test void quotaSpacesRemainingReadsAndHonoursAnExhaustedWindow(){
  var now=Instant.parse("2026-11-22T21:00:00Z");
  for(String reset:new String[]{"60",Long.toString(now.plusSeconds(60).getEpochSecond())}){
   for(String remaining:new String[]{"0","2","9"}){
    var headers=java.net.http.HttpHeaders.of(java.util.Map.of(
        "X-RateLimit-Limit",java.util.List.of("4"),"X-RateLimit-Remaining",java.util.List.of(remaining),
        "X-RateLimit-Reset",java.util.List.of(reset)),(k,v)->true);
    var admission=GatewayReadPacing.quota(headers,now);
    assertThat(admission).isEqualTo(now.plusSeconds(remaining.equals("0")?60:remaining.equals("2")?30:15));
    assertThat(PublicationAccessFailure.status(429,"5",now,admission).retryNotBefore()).isEqualTo(admission);
   }
  }
 }
 @Test void rateLimitedWithoutRetryAfterUsesFractionalResetThenCapAndJitter(){
  var now=Instant.parse("2026-11-22T21:30:00Z");
  java.util.function.Function<java.util.Map<String,java.util.List<String>>,java.net.http.HttpHeaders> h=
      m->java.net.http.HttpHeaders.of(m,(k,v)->true);
  var apisix=h.apply(java.util.Map.of("X-RateLimit-Limit",java.util.List.of("60"),
      "X-RateLimit-Remaining",java.util.List.of("0"),"X-RateLimit-Reset",java.util.List.of("2.5")));
  var pacing=new GatewayReadPacing();pacing.configure(60_000,1_000);
  // APISIX limit-count: only X-RateLimit-*, fractional seconds.
  var due=pacing.deferral(GatewayReadPacing.Route.BATCH,apisix,null,now);
  assertThat(due).isBetween(now.plusMillis(2500),now.plusMillis(2500+250));
  // Retry-After comes first when present.
  var first=new GatewayReadPacing();first.configure(60_000,0);
  assertThat(first.deferral(GatewayReadPacing.Route.BATCH,apisix,"5",now)).isEqualTo(now.plusSeconds(5));
  // A long window is capped by the installation parameter.
  var capped=new GatewayReadPacing();capped.configure(10_000,0);
  var longWindow=h.apply(java.util.Map.of("X-RateLimit-Remaining",java.util.List.of("0"),"X-RateLimit-Reset",java.util.List.of("120")));
  assertThat(capped.deferral(GatewayReadPacing.Route.BATCH,longWindow,null,now)).isEqualTo(now.plusSeconds(10));
  // Without either header the configured durable pacing applies.
  assertThat(new GatewayReadPacing().deferral(GatewayReadPacing.Route.BATCH,h.apply(java.util.Map.of()),null,now)).isNull();
  assertThat(PublicationAccessFailure.status(429,null,now,due).retryNotBefore()).isEqualTo(due);
 }
 @Test void retryAfterAndBackoffNeverDiscardServerAdmissionDelay(){
  Instant now=Instant.parse("2026-10-18T00:00:00Z");
  assertThat(PublicationAccessFailure.retryAfter("Sun, 18 Oct 2026 01:00:00 GMT",now)).isEqualTo(now.plusSeconds(3600));
  assertThatThrownBy(()->PublicationAccessFailure.retryAfter("bad",now)).isInstanceOfSatisfying(PublicationAccessFailure.class,e->assertThat(e.retryable()).isFalse());
  long[] expected={5000,15000,60000,300000,900000,900000};
  for(int n=1;n<=expected.length;n++){
   assertThat(PublicationDependencyRecovery.delayMillis(n,.8)).isEqualTo((long)(expected[n-1]*.8));
   assertThat(PublicationDependencyRecovery.delayMillis(n,1.2)).isEqualTo((long)(expected[n-1]*1.2));
  }
 }
}
