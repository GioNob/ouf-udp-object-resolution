package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/udp/v1/graph")
public class GraphApi {
  private final GraphQueryService service;public GraphApi(GraphQueryService service){this.service=service;}
  @PostMapping("/neighbors") GraphQueryService.GraphResult neighbors(@RequestBody NeighborsRequest body,HttpServletRequest request){return service.neighbors(body.startObjectId(),body.relationTypes(),body.maxNodes(),body.maxEdges(),ServingApi.context(request));}
  @PostMapping("/traverse") GraphQueryService.GraphResult traverse(@RequestBody TraverseRequest body,HttpServletRequest request){return service.traverse(body.startObjectId(),body.relationTypes(),body.maxDepth(),body.maxNodes(),body.maxEdges(),body.purpose(),ServingApi.context(request));}
  public record NeighborsRequest(UUID startObjectId,List<String> relationTypes,int maxNodes,int maxEdges){}
  public record TraverseRequest(UUID startObjectId,List<String> relationTypes,int maxDepth,int maxNodes,int maxEdges,CapabilityRouter.TraversalPurpose purpose){}
}
