package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/udp/v1/operations")
public class AcquisitionEvidenceApi {
  private final AcquisitionEvidenceService service;
  public AcquisitionEvidenceApi(AcquisitionEvidenceService service){this.service=service;}
  @PostMapping(value="/acquisition",consumes="application/json",produces="application/json")
  public Map<String,Object> read(@RequestBody byte[] raw,HttpServletRequest request){
    Query query;
    try{if(raw.length>4096)throw invalid();
      var json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
      query=decode(json.readTree(raw));
    }catch(java.io.IOException bad){throw invalid();}
    return service.read(query,ServingApi.context(request));
  }
  public record Query(String sourceId,UUID runId,int limit,String afterHandoffId){
    public Query{if(!text(sourceId)||runId==null||runId.equals(new UUID(0,0))||limit<1||limit>100||afterHandoffId!=null&&!text(afterHandoffId))throw invalid();}
  }
  static boolean text(String value){return value!=null&&!value.isBlank()&&value.length()<=200&&value.chars().noneMatch(Character::isISOControl);}
  static Query decode(JsonNode body){
    if(body==null||!body.isObject()||!body.hasNonNull("sourceId")||!body.hasNonNull("runId"))throw invalid();
    for(var names=body.fieldNames();names.hasNext();)if(!Set.of("sourceId","runId","limit","afterHandoffId").contains(names.next()))throw invalid();
    if(!body.get("sourceId").isTextual()||!body.get("runId").isTextual()
      ||body.has("limit")&&(!body.get("limit").isIntegralNumber()||!body.get("limit").canConvertToInt())
      ||body.has("afterHandoffId")&&!body.get("afterHandoffId").isTextual())throw invalid();
    try{String id=body.get("runId").textValue();UUID run=UUID.fromString(id);if(!run.toString().equals(id))throw invalid();
      return new Query(body.get("sourceId").textValue(),run,body.has("limit")?body.get("limit").intValue():50,body.has("afterHandoffId")?body.get("afterHandoffId").textValue():null);
    }catch(IllegalArgumentException bad){throw invalid();}
  }
  static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"UDP_ACQUISITION_QUERY_INVALID");}
}
