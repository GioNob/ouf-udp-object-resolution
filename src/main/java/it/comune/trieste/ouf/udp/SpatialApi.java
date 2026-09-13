package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/udp/v1/spatial")
public class SpatialApi {
  private final SpatialQueryService service;public SpatialApi(SpatialQueryService service){this.service=service;}
  @PostMapping("/nearby") SpatialQueryService.Result nearby(@RequestBody NearbyRequest b,HttpServletRequest r){return service.nearby(b.anchorObjectId(),b.targetType(),b.maxDistanceMeters(),b.maxResults(),ServingApi.context(r));}
  @PostMapping("/intersects") SpatialQueryService.Result intersects(@RequestBody EnvelopeRequest b,HttpServletRequest r){return service.envelope("INTERSECTS",b.minLon(),b.minLat(),b.maxLon(),b.maxLat(),b.crs(),b.targetType(),b.maxResults(),ServingApi.context(r));}
  @PostMapping("/within") SpatialQueryService.Result within(@RequestBody EnvelopeRequest b,HttpServletRequest r){return service.envelope("WITHIN",b.minLon(),b.minLat(),b.maxLon(),b.maxLat(),b.crs(),b.targetType(),b.maxResults(),ServingApi.context(r));}
  @PostMapping("/intersection-search") SpatialQueryService.Result intersectionSearch(@RequestBody IntersectionRequest b,HttpServletRequest r){return service.intersectionSearch(b.anchorObjectId(),b.targetType(),b.maxResults(),ServingApi.context(r));}
  public record NearbyRequest(UUID anchorObjectId,String targetType,double maxDistanceMeters,int maxResults){}
  public record EnvelopeRequest(double minLon,double minLat,double maxLon,double maxLat,String crs,String targetType,int maxResults){}
  public record IntersectionRequest(UUID anchorObjectId,String targetType,int maxResults){}
}
