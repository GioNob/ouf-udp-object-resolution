package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/udp/v1/objects")
public class RelatedSearchApi {
  private final RelatedSearchService service;

  public RelatedSearchApi(RelatedSearchService service) { this.service = service; }

  @PostMapping("/related-search")
  RelatedSearchService.Result relatedSearch(@RequestBody RelatedSearchService.Request body,
                                             HttpServletRequest request) {
    return service.search(body, ServingApi.context(request));
  }
}
