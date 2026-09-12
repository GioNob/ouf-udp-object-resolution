package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public final class FrozenContractValidator {
  private final ObjectMapper json;
  public FrozenContractValidator(ObjectMapper json){this.json=json;}
  public void validate(String resource,Object value){
    try(InputStream in=getClass().getResourceAsStream(resource)){
      if(in==null)throw new IOException("missing");validateNode(json.readTree(in),json.valueToTree(value),"$",Path.of(resource.substring(1)).getParent());
    }catch(IOException e){throw new ContractViolation("UDP_CONTRACT_SCHEMA_UNAVAILABLE",List.of("$"));}
  }
  private void validateNode(JsonNode schema,JsonNode value,String path,Path base){
    if(schema.has("$ref")){String raw=schema.get("$ref").asText();Path ref=base.resolve(raw).normalize();try(InputStream in=getClass().getResourceAsStream("/"+ref)){if(in==null)throw new IOException();validateNode(json.readTree(in),value,path,ref.getParent());return;}catch(IOException e){throw new ContractViolation("UDP_CONTRACT_REF_UNAVAILABLE",List.of(path));}}
    if(schema.has("oneOf")){int valid=0;for(JsonNode option:schema.get("oneOf"))if(isValid(option,value,path,base))valid++;if(valid!=1)fail(path);return;}
    if(schema.has("type")&&!matchesType(schema.get("type"),value))fail(path);
    if(schema.has("const")&&!schema.get("const").equals(value))fail(path);
    if(schema.has("enum")){boolean found=false;for(JsonNode item:schema.get("enum"))found|=item.equals(value);if(!found)fail(path);}
    if(value.isTextual()&&schema.path("minLength").isInt()&&value.textValue().length()<schema.get("minLength").intValue())fail(path);
    if(value.isNumber()&&schema.has("minimum")&&value.decimalValue().compareTo(schema.get("minimum").decimalValue())<0)fail(path);
    if(value.isTextual()&&"date-time".equals(schema.path("format").asText()))try{OffsetDateTime.parse(value.textValue());}catch(Exception e){fail(path);}
    if(value.isObject())validateObject(schema,value,path,base);if(value.isArray())validateArray(schema,value,path,base);
    if(schema.has("allOf"))for(JsonNode item:schema.get("allOf"))validateNode(item,value,path,base);
    if(schema.has("if")&&isValid(schema.get("if"),value,path,base)&&schema.has("then"))validateNode(schema.get("then"),value,path,base);
    if(schema.has("anyOf")){boolean valid=false;for(JsonNode option:schema.get("anyOf"))valid|=isValid(option,value,path,base);if(!valid)fail(path);}
  }
  private void validateObject(JsonNode schema,JsonNode value,String path,Path base){JsonNode props=schema.get("properties");if(schema.has("required"))for(JsonNode r:schema.get("required"))if(!value.has(r.asText()))fail(path+"."+r.asText());if(props!=null){Iterator<String> names=props.fieldNames();while(names.hasNext()){String n=names.next();if(value.has(n))validateNode(props.get(n),value.get(n),path+"."+n,base);}if(schema.path("additionalProperties").isBoolean()&&!schema.path("additionalProperties").asBoolean()){Iterator<String> actual=value.fieldNames();while(actual.hasNext()){String n=actual.next();if(!props.has(n))fail(path+"."+n);}}}}
  private void validateArray(JsonNode schema,JsonNode value,String path,Path base){if(schema.has("minItems")&&value.size()<schema.get("minItems").intValue())fail(path);if(schema.path("uniqueItems").asBoolean()){Set<JsonNode> seen=new HashSet<>();for(JsonNode item:value)if(!seen.add(item))fail(path);}if(schema.has("items"))for(int i=0;i<value.size();i++)validateNode(schema.get("items"),value.get(i),path+"["+i+"]",base);}
  private boolean isValid(JsonNode schema,JsonNode value,String path,Path base){try{validateNode(schema,value,path,base);return true;}catch(ContractViolation e){return false;}}
  private boolean matchesType(JsonNode type,JsonNode value){if(type.isArray()){for(JsonNode t:type)if(matches(t.asText(),value))return true;return false;}return matches(type.asText(),value);}
  private boolean matches(String t,JsonNode v){return switch(t){case "object"->v.isObject();case "array"->v.isArray();case "string"->v.isTextual();case "number"->v.isNumber();case "integer"->v.isIntegralNumber();case "boolean"->v.isBoolean();case "null"->v.isNull();default->false;};}
  private static void fail(String path){throw new ContractViolation("UDP_CONTRACT_INVALID",List.of(path));}
  public static final class ContractViolation extends RuntimeException {private final String safeCode;private final List<String> paths;public ContractViolation(String safeCode,List<String> paths){super(safeCode);this.safeCode=safeCode;this.paths=List.copyOf(paths);}public String safeCode(){return safeCode;}public List<String> paths(){return paths;}}
}
