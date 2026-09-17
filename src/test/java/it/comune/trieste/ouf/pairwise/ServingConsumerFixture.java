package it.comune.trieste.ouf.pairwise;

import it.comune.trieste.ouf.udp.UdpApplication;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

/** Test-only identity/bootstrap; business writes use production APIs and scheduled workers. */
public class ServingConsumerFixture {
  public static void main(String[] args){SpringApplication.run(new Class<?>[]{UdpApplication.class,Fixture.class},args);}
  @Configuration(proxyBeanMethods=false) public static class Fixture {
    @Bean FilterRegistrationBean<Filter> identity()throws Exception{
      String serviceToken=required("OUF_PAIRWISE_TOKEN"),humanToken=required("OUF_PAIRWISE_HUMAN_TOKEN");
      Set<String> serviceCaps=Set.of("datalake.write","udp.candidate.write");
      Set<String> humanCaps=Set.of("authority.override","resolution.issue.read","urban.relationship.read","urban.geometry.read","urban.object.search","urban.object.read","urban.object.history.read","lineage.object.read","lineage.source.read","udp.replay.plan","udp.replay.execute");
      var service=TestAuthorization.runtime("fixture-ingestion","SERVICE",serviceCaps);var human=TestAuthorization.runtime("fixture-human","HUMAN",humanCaps);
      var descriptors=new ArrayList<CapabilityDescriptor>(service.currentSnapshot().bundle().capabilities());descriptors.addAll(human.currentSnapshot().bundle().capabilities());
      var grants=new ArrayList<Grant>(service.currentSnapshot().bundle().grants());grants.addAll(human.currentSnapshot().bundle().grants());
      TestAuthorization.install(service,new PolicyBundle("fixture",2,Instant.now(),descriptors,grants));
      Filter filter=(input,output,chain)->{
        var request=(HttpServletRequest)input;var response=(HttpServletResponse)output;String header=request.getHeader("Authorization");
        boolean workload=matches(header,serviceToken),person=matches(header,humanToken);
        if(!workload&&!person){response.sendError(401);return;}
        request.getServletContext().setAttribute(ServletAuthorization.RUNTIME,service);
        chain.doFilter(new HttpServletRequestWrapper(request){@Override public Principal getUserPrincipal(){return TestAuthorization.principal(workload?"fixture-ingestion":"fixture-human",workload?"SERVICE":"HUMAN",workload?serviceCaps:humanCaps);}},response);
      };
      var registration=new FilterRegistrationBean<>(filter);registration.addUrlPatterns("/api/*");registration.setOrder(-100);return registration;
    }
    @Bean ApplicationRunner bucket(S3Client s3){return args->{try{s3.createBucket(CreateBucketRequest.builder().bucket("r2b-lake").build());}catch(BucketAlreadyOwnedByYouException ignored){}};}
    private static String required(String name){String value=System.getenv(name);if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Ephemeral fixture token required");return value;}
    private static boolean matches(String header,String token){return header!=null&&MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),("Bearer "+token).getBytes(StandardCharsets.UTF_8));}
  }
}
