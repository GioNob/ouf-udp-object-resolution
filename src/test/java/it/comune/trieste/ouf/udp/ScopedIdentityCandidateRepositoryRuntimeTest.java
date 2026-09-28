package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ScopedIdentityCandidateRepositoryRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));
    r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));
    r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));
  }
  @Autowired ScopedIdentityCandidateRepository candidates;

  @Test void neverInfersCompletenessFromClassSizeOrEmptyResults(){
    var policy=new GovernedIdentityEngine.Policy("policy://identity/1","1","default","ouf:Road","registry",1,true,
        List.of(new GovernedIdentityEngine.Signal("ouf:name","ouf:name@semantic://publication/1",
            GovernedIdentityEngine.ComparatorKind.TEXT_V1,false,false,"assertion://name/1")),
        List.of(new GovernedIdentityEngine.SufficientRule("name",Set.of("ouf:name"),"assertion://rule/1")));
    var result=candidates.retrieve(policy);
    assertThat(result.complete()).isFalse();
    assertThat(result.rows()).isEmpty();
    assertThat(result.coverageRef()).isNull();
    var subject=new GovernedIdentityEngine.Subject("default","ouf:Road","registry",Map.of("ouf:name",
        new GovernedIdentityEngine.Value("ouf:name@semantic://publication/1","Alpha","handoff://probe")));
    assertThat(new GovernedIdentityEngine().decide(policy,subject,result).outcome())
        .isEqualTo(GovernedIdentityEngine.Outcome.REVIEW_REQUIRED);
    assertThat(new GovernedIdentityEngine().decide(policy,subject,result).reason())
        .isEqualTo("CANDIDATE_COVERAGE_UNVERIFIED");
  }
  private static String required(String n){String value=System.getenv(n);if(value==null)throw new IllegalStateException(n+" required");return value;}
}
