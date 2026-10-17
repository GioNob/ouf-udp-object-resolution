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
   var configuration=new PublishedRuntimeConfiguration(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),file.toString(),"tenant");
   for(int code:new int[]{408,429,500,502,503,504,401,403,404,410,302,400}){
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
   var configuration=new PublishedRuntimeConfiguration(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),file.toString(),"tenant");
   assertThatThrownBy(()->configuration.resolve("bundle","ROAD")).isInstanceOfSatisfying(PublicationAccessFailure.class,e->{
    assertThat(e.retryable()).isFalse();assertThat(e.getMessage()).isEqualTo("UDP_PUBLICATION_RESPONSE_INVALID");});
   Files.delete(file);
   assertThatThrownBy(()->configuration.resolve("bundle","ROAD")).isInstanceOfSatisfying(PublicationAccessFailure.class,e->assertThat(e.retryable()).isFalse());
   assertThat(calls).hasValue(1);
  }finally{server.stop(0);Files.deleteIfExists(file);}
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
