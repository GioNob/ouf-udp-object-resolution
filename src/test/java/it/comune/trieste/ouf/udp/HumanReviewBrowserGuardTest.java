package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class HumanReviewBrowserGuardTest {
  private MockHttpServletRequest request(String subject,String actor){var r=new MockHttpServletRequest();TestAuthorization.bind(r,subject,actor,Set.of("resolution.issue.read","authority.override"));return r;}
  @Test void csrfTokenIsBoundToHumanSessionAndSameOrigin(){
    var guard=new HumanReviewBrowserGuard("https://ouf.example");var r=request("alice","HUMAN");
    String token=guard.token(r);assertThatThrownBy(()->guard.require(r)).hasMessageContaining("403");
    r.addHeader("X-OUF-CSRF",token);r.addHeader("Origin","https://ouf.example");r.addHeader("Sec-Fetch-Site","same-origin");guard.require(r);
    var other=request("bob","HUMAN");other.setSession(r.getSession(false));other.addHeader("X-OUF-CSRF",token);assertThatThrownBy(()->guard.require(other)).hasMessageContaining("403");
    var cross=request("alice","HUMAN");cross.setSession(r.getSession(false));cross.addHeader("X-OUF-CSRF",token);cross.addHeader("Origin","https://evil.example");assertThatThrownBy(()->guard.require(cross)).hasMessageContaining("403");
    assertThatThrownBy(()->guard.token(request("worker","SERVICE"))).isInstanceOf(SecurityException.class);
  }
}
