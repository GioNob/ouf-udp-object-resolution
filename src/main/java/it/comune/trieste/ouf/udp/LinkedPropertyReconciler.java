package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.*;

/** Versioned, deterministic property reconciliation. No identity decisions or I/O. */
public final class LinkedPropertyReconciler {
  public static final String POLICY="urn:ouf:policy:linked-object-property-reconciliation:v1";
  private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private LinkedPropertyReconciler(){}
  public record Rule(String policyRef,String cardinality,String normalizerRef,int normalizerVersion,
                     String validity,String revisionField,String deletionField){
    public Rule{
      if(!POLICY.equals(policyRef)||!Set.of("SINGLE","MULTI").contains(Objects.toString(cardinality,""))
          ||!Set.of("EXACT_TYPED","NFC_TRIM").contains(Objects.toString(normalizerRef,""))||normalizerVersion!=1
          ||!Set.of("UNBOUNDED","SOURCE_INTERVAL").contains(Objects.toString(validity,""))
          ||revisionField!=null&&revisionField.isBlank()||deletionField!=null&&deletionField.isBlank())throw invalid();
      if(deletionField!=null&&(revisionField==null||!"SINGLE".equals(cardinality)))throw invalid();
    }
  }
  public record Member(Object value,List<Map<String,Object>> contributions){}
  public record Result(String status,Object value,boolean determined,boolean deleted,
                       List<Member> members,List<Map<String,Object>> candidates,String evidenceHash){}
  // Preserve published IRIs. Only exact supported XSD aliases affect comparison.
  static String comparisonDatatype(String datatype){return switch(Objects.toString(datatype,"")){
    case "http://www.w3.org/2001/XMLSchema#string" -> "string";
    case "http://www.w3.org/2001/XMLSchema#decimal" -> "number";
    case "http://www.w3.org/2001/XMLSchema#date" -> "date";
    default -> datatype;
  };}
  static void validate(String datatype,Rule rule,List<String> order){
    datatype=comparisonDatatype(datatype);
    if(!Set.of("string","number","integer","boolean","object","date").contains(Objects.toString(datatype,""))
        ||"NFC_TRIM".equals(rule.normalizerRef())&&!"string".equals(datatype)
        ||rule.deletionField()!=null&&order.isEmpty()||new HashSet<>(order).size()!=order.size()||order.stream().anyMatch(String::isBlank))throw invalid();
  }
  static boolean absent(Object value){return value==null||value instanceof String s&&s.isBlank();}
  /** Build exactly the same candidate for preview and commit from a canonical handoff. */
  static Map<String,Object> incoming(String handoff,Map<String,Object> envelope,UdpPorts.PropertyRule rule){
    var config=rule.reconciliation();var payload=HandoffIntakeService.object(envelope,"canonicalPayload");
    var source=HandoffIntakeService.object(envelope,"sourceIdentity");boolean deletion=false;
    if(config.deletionField()!=null&&payload.containsKey(config.deletionField())){
      if(!(payload.get(config.deletionField()) instanceof Boolean))throw invalidValue();deletion=Boolean.TRUE.equals(payload.get(config.deletionField()));
    }
    Object value=payload.get(rule.sourceField());if(!deletion&&absent(value))return null;
    var provenance=new LinkedHashMap<String,Object>();provenance.put("handoffRef","handoff://"+handoff);provenance.put("ingestionRunId",envelope.get("ingestionRunId"));provenance.put("lineageRef",envelope.get("lineageId"));provenance.put("sourceIdentity",source);provenance.put("rawObjectRef",envelope.get("rawObjectRef"));provenance.put("contractRefs",envelope.get("contractRefs"));provenance.put("propertyIri",rule.propertyIri());provenance.put("reconciliationRule",config);
    if(deletion){if(!rule.authorityOrder().contains(String.valueOf(source.get("sourceId"))))throw invalidValue();value=null;provenance.put("explicitPropertyDeletion",true);}
    else if("MULTI".equals(config.cardinality())&&value instanceof List<?> items){for(Object item:items)normalize(item,rule);}
    else normalize(value,rule);
    if(config.revisionField()!=null){Object revision=payload.get(config.revisionField());if(!(revision instanceof Number))throw invalidValue();var n=new BigDecimal(revision.toString());if(n.signum()<0||n.stripTrailingZeros().scale()>0)throw invalidValue();provenance.put("sourceRecordRevision",revision);}
    var row=new LinkedHashMap<String,Object>();row.put("contribution_id",UUID.nameUUIDFromBytes((handoff+"\n"+rule.propertyIri()).getBytes(StandardCharsets.UTF_8)));row.put("source_id",source.get("sourceId"));row.put("access_label",rule.accessLabel());row.put("value_json",write(value));row.put("value_hash",hash(value));row.put("provenance",write(provenance));var identity=new TreeMap<>(source);identity.remove("observedAt");row.put("source_identity",write(identity));return row;
  }
  static String write(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw invalidValue();}}
  static Object normalize(Object value,UdpPorts.PropertyRule rule){
    if(absent(value))return null;
    return switch(comparisonDatatype(rule.datatype())){
      case "string" -> {if(!(value instanceof String s))throw invalidValue();yield "NFC_TRIM".equals(rule.reconciliation().normalizerRef())?Normalizer.normalize(s.strip(),Normalizer.Form.NFC):s;}
      case "number","integer" -> {if(!(value instanceof Number))throw invalidValue();BigDecimal n;try{n=new BigDecimal(value.toString()).stripTrailingZeros();}catch(NumberFormatException e){throw invalidValue();}if("integer".equals(rule.datatype())&&n.scale()>0)throw invalidValue();yield n;}
      case "date" -> {if(!(value instanceof String s)||!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw invalidValue();try{LocalDate date=LocalDate.parse(s);if(date.getYear()<1||!date.toString().equals(s))throw invalidValue();}catch(RuntimeException failure){throw invalidValue();}yield s;}
      case "boolean" -> {if(!(value instanceof Boolean))throw invalidValue();yield value;}
      case "object" -> {if(!(value instanceof Map<?,?> map)||map.containsKey("geoJson")||map.containsKey("coordinates")||map.containsKey("geometries"))throw invalidValue();yield canonical(value);}
      default -> throw invalidValue();
    };
  }
  static Object canonical(Object value){if(value instanceof Map<?,?> map){var out=new TreeMap<String,Object>();map.forEach((k,v)->out.put(String.valueOf(k),canonical(v)));return out;}if(value instanceof List<?> list)return list.stream().map(LinkedPropertyReconciler::canonical).toList();return value;}
  static String hash(Object value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(canonical(value))));}catch(Exception e){throw new IllegalArgumentException("UDP_PROPERTY_EVIDENCE_INVALID",e);}}
  static Object decode(Object value){try{return JSON.readValue(String.valueOf(value),Object.class);}catch(Exception e){throw invalidValue();}}
  @SuppressWarnings("unchecked") static Map<String,Object> provenance(Map<String,Object> row){return (Map<String,Object>)decode(row.get("provenance"));}
  @SuppressWarnings("unchecked") static Map<String,Object> identity(Map<String,Object> row){return (Map<String,Object>)provenance(row).get("sourceIdentity");}
  static boolean deleted(Map<String,Object> row){return Boolean.TRUE.equals(provenance(row).get("explicitPropertyDeletion"));}
  static List<Map<String,Object>> eligible(List<Map<String,Object>> input,UdpPorts.PropertyRule rule,OffsetDateTime now){
    var active=new ArrayList<Map<String,Object>>();
    for(var original:input){var row=new LinkedHashMap<>(original);var id=identity(row);if(id==null||id.get("sourceObjectId")==null||id.get("sourceId")==null)throw invalidValue();
      if("SOURCE_INTERVAL".equals(rule.reconciliation().validity())){
        OffsetDateTime from=time(id.get("validFrom")),to=time(id.get("validTo"));if(from!=null&&to!=null&&!from.isBefore(to))throw invalidValue();
        if(from!=null&&now.isBefore(from))continue;
      }
      if(deleted(row)&&(!rule.authorityOrder().contains(String.valueOf(row.get("source_id")))||rule.reconciliation().deletionField()==null))throw invalidValue();
      if(!deleted(row)&&absent(decode(row.get("value_json"))))continue;
      int rank=rule.authorityOrder().indexOf(String.valueOf(row.get("source_id")));row.put("authority_rank",rank<0?10000:rank);active.add(row);
    }
    if(rule.reconciliation().revisionField()!=null){
      var newest=new HashMap<String,BigDecimal>();
      for(var row:active){String key=recordKey(row);BigDecimal revision=revision(row);newest.merge(key,revision,(a,b)->a.max(b));}
      active.removeIf(row->revision(row).compareTo(newest.get(recordKey(row)))!=0);
    }
    if("SOURCE_INTERVAL".equals(rule.reconciliation().validity()))active.removeIf(row->{var until=time(identity(row).get("validTo"));return until!=null&&!now.isBefore(until);});
    active.sort(Comparator.comparing(row->String.valueOf(row.get("contribution_id"))));
    return active;
  }
  private static String recordKey(Map<String,Object> row){var id=identity(row);return hash(Map.of("source",id.get("sourceId"),"object",id.get("sourceObjectId"),"type",Objects.toString(id.get("typeCode"),"")));}
  private static BigDecimal revision(Map<String,Object> row){Object raw=provenance(row).get("sourceRecordRevision");if(!(raw instanceof Number))throw invalidValue();try{var n=new BigDecimal(raw.toString());if(n.signum()<0||n.stripTrailingZeros().scale()>0)throw invalidValue();return n;}catch(NumberFormatException e){throw invalidValue();}}
  private static OffsetDateTime time(Object raw){try{return raw==null?null:OffsetDateTime.parse(raw.toString());}catch(Exception e){throw invalidValue();}}
  static List<Map<String,Object>> top(List<Map<String,Object>> rows,UdpPorts.PropertyRule rule){
    if("MULTI".equals(rule.reconciliation().cardinality()))return rows;
    int rank=rows.stream().mapToInt(r->((Number)r.get("authority_rank")).intValue()).min().orElse(10000);
    return rows.stream().filter(r->((Number)r.get("authority_rank")).intValue()==rank).toList();
  }
  public static Result reconcile(List<Map<String,Object>> input,UdpPorts.PropertyRule rule,Object current,OffsetDateTime now,UUID selected){
    var candidates=top(eligible(input,rule,now),rule);
    String evidence=PropertyEvidence.set(candidates,rule);
    if(candidates.isEmpty())return new Result("NO_VALID_CONTRIBUTION",current,false,false,List.of(),candidates,evidence);
    if(selected!=null){var chosen=candidates.stream().filter(r->selected.equals(r.get("contribution_id"))).findFirst().orElseThrow(LinkedPropertyReconciler::invalidValue);candidates=candidates.stream().filter(r->deleted(chosen)?deleted(r):!deleted(r)&&Objects.equals(normalize(decode(chosen.get("value_json")),rule),normalize(decode(r.get("value_json")),rule))).toList();}
    var values=new TreeMap<String,Object>();var sources=new TreeMap<String,List<Map<String,Object>>>();
    boolean deletion=false;
    for(var row:candidates){if(deleted(row)){deletion=true;continue;}Object raw=decode(row.get("value_json"));
      List<?> items="MULTI".equals(rule.reconciliation().cardinality())?(raw instanceof List<?> list?list:List.of(raw)):List.of(raw);
      for(Object item:items){Object value=normalize(item,rule);if(value==null)continue;String key=hash(Map.of("datatype",rule.datatype(),"value",value));values.putIfAbsent(key,value);sources.computeIfAbsent(key,k->new ArrayList<>()).add(evidence(row,item,value));}
    }
    if(deletion&&!values.isEmpty()||"SINGLE".equals(rule.reconciliation().cardinality())&&values.size()>1)
      return new Result("CONFLICT",current,false,false,List.of(),candidates,evidence);
    if(deletion)return new Result("DELETED",null,true,true,List.of(),candidates,evidence);
    var members=new ArrayList<Member>();for(var entry:values.entrySet())members.add(new Member(entry.getValue(),List.copyOf(sources.get(entry.getKey()))));
    if(members.isEmpty())return new Result("NO_VALID_CONTRIBUTION",current,false,false,List.of(),candidates,evidence);
    Object value="MULTI".equals(rule.reconciliation().cardinality())?members.stream().map(Member::value).toList():members.getFirst().value();
    return new Result(selected!=null?"HUMAN_SELECTED":Objects.equals(canonical(current),canonical(value))?"UNCHANGED":current==null?"ADDED":"RECONCILED",value,true,false,List.copyOf(members),candidates,evidence);
  }
  private static Map<String,Object> evidence(Map<String,Object> row,Object original,Object normalized){var item=new LinkedHashMap<String,Object>();item.put("contributionId",row.get("contribution_id"));item.put("sourceId",row.get("source_id"));item.put("accessLabel",row.get("access_label"));item.put("originalValue",original);item.put("normalizedValue",normalized);item.put("provenance",provenance(row));return Collections.unmodifiableMap(item);}
  static IllegalArgumentException invalid(){return new IllegalArgumentException("UDP_PROPERTY_RECONCILIATION_PROFILE_UNSUPPORTED");}
  static IllegalArgumentException invalidValue(){return new IllegalArgumentException("UDP_PROPERTY_CONTRIBUTION_INVALID");}
}
