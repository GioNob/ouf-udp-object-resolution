package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import it.comune.trieste.ouf.authorization.TestAuthorization;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/** ouf.delta-position.v1 §14: outcome reads are reserved to the configured Ingestion workload. */
class HandoffOutcomeApiTest {
  private final HandoffIntakeService service=mock(HandoffIntakeService.class);
  private MockHttpServletRequest request(String subject,Set<String> capabilities){
    var request=new MockHttpServletRequest();
    TestAuthorization.bind(request,subject,"SERVICE",capabilities);
    return request;
  }
  private HandoffApi api(String workload){return new HandoffApi(service,new RuntimeIntakeAuthorization("tenant-a",workload));}

  @Test void pageIsServedOnlyToTheIngestionWorkloadOfTheTenant(){
    when(service.outcomes("tenant-a","roads","run-1",0,100)).thenReturn(Map.of("items",List.of()));
    var ingestion=request("ingestion",Set.of("udp.candidate.write"));
    assertThat(api("ingestion").outcomes("roads","run-1",0,100,ingestion)).containsEntry("items",List.of());
    for(var denied:List.of(api("other"),api(""),new HandoffApi(service,new RuntimeIntakeAuthorization("tenant-b","ingestion"))))
      assertThatThrownBy(()->denied.outcomes("roads","run-1",0,100,ingestion))
          .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(403));
    var withoutGrant=request("ingestion",Set.of("datalake.write"));
    assertThatThrownBy(()->api("ingestion").outcomes("roads","run-1",0,100,withoutGrant)).isInstanceOf(ResponseStatusException.class);
    verify(service,times(1)).outcomes(anyString(),anyString(),anyString(),anyLong(),anyInt());
  }
  @Test void pageBoundsAreClosed(){
    var ingestion=request("ingestion",Set.of("udp.candidate.write"));
    for(Object[] invalid:List.of(new Object[]{" ","run",0L,1},new Object[]{"roads"," ",0L,1},new Object[]{"roads","run",-1L,1},
        new Object[]{"roads","run",0L,0},new Object[]{"roads","run",0L,201}))
      assertThatThrownBy(()->api("ingestion").outcomes((String)invalid[0],(String)invalid[1],(Long)invalid[2],(Integer)invalid[3],ingestion))
          .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(400));
    verifyNoInteractions(service);
  }
  @Test void handoffWithOutcomeRequiresTheWorkloadAndPendingStateDoesNot(){
    var other=request("other-service",Set.of("udp.candidate.write"));
    when(service.handoff("api:pending")).thenReturn(Map.of("source_id","roads","ingestion_run_id","run-1","state","DURABLE"));
    assertThat(api("ingestion").get("api:pending",other)).containsEntry("state","DURABLE");
    when(service.handoff("api:done")).thenReturn(Map.of("source_id","roads","ingestion_run_id","run-1","outcome","APPLIED"));
    assertThatThrownBy(()->api("ingestion").get("api:done",other)).isInstanceOf(ResponseStatusException.class);
    assertThat(api("ingestion").get("api:done",request("ingestion",Set.of("udp.candidate.write")))).containsEntry("outcome","APPLIED");
  }
}
