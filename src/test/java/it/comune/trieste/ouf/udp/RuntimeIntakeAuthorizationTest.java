package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class RuntimeIntakeAuthorizationTest {
  @Test void onlyServiceWithExactTenantAndCapabilityCanWrite(){
    var guard=new RuntimeIntakeAuthorization("tenant-a");
    var service=new MockHttpServletRequest();TestAuthorization.bind(service,"ingestion","SERVICE",Set.of("datalake.write"));
    assertThat(guard.require(service,"datalake.write","source","run")).isEqualTo("tenant-a");
    assertThatThrownBy(()->guard.require(service,"udp.candidate.write","source","run")).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(()->new RuntimeIntakeAuthorization("tenant-b").require(service,"datalake.write","source","run")).isInstanceOf(ResponseStatusException.class);
    var human=new MockHttpServletRequest();TestAuthorization.bind(human,"person","HUMAN",Set.of("datalake.write"));
    assertThatThrownBy(()->guard.require(human,"datalake.write","source","run")).isInstanceOf(ResponseStatusException.class);
    var forged=new MockHttpServletRequest();forged.addHeader("X-Principal-Type","SERVICE");forged.addHeader("X-Capabilities","datalake.write");
    assertThatThrownBy(()->guard.require(forged,"datalake.write","source","run")).isInstanceOf(ResponseStatusException.class);
  }
}
