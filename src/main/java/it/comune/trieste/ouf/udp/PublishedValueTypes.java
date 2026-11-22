package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Function;
import static it.comune.trieste.ouf.udp.CanonicalValueType.*;

/** Builds the value checker from historical registry responses, once per pinned profile. */
final class PublishedValueTypes {
  private final Map<String, Map<String,Object>> definitions = new HashMap<>();
  private final Map<String, Set<String>> vocabularyMembers = new HashMap<>();
  private final Map<String,String> conceptVocabularies = new HashMap<>();
  private static final Map<String,String> READER_TYPES = readerTypes();

  private static Map<String,String> readerTypes() {
    try (var input = PublishedValueTypes.class.getResourceAsStream("/contracts/ouf-value-types-v1.json")) {
      var contract = new ObjectMapper().readTree(input);
      var types = new HashMap<String,String>();
      contract.required("readers").forEach(r -> types.put(r.required("id").asText(),r.required("output").asText()));
      return Map.copyOf(types);
    } catch (Exception e) {throw new IllegalStateException("UDP_VALUE_TYPE_CONTRACT_UNAVAILABLE");}
  }

  void bind(Map<String,Object> binding, Map<String,Object> actual, Function<Object,String> hash) {
    String id = text(binding,"semanticId");
    if ("DATATYPE".equals(actual.get("artifact_type")) && (binding.containsKey("definition") || binding.containsKey("definitionSha256"))) {
      if (!object(binding.get("definition")).equals(actual.get("definition"))
          || !text(binding,"definitionSha256").equals(actual.get("definitionSha256"))) throw definitionError();
      embedded(binding,hash);
    } else if (binding.containsKey("definitionSha256") || binding.containsKey("definition")) {
      throw definitionError();
    } else if ("CONCEPT".equals(actual.get("artifact_type"))) {
      // The resolver has already matched this revision in a PUBLISHED historical set.
      var definition = object(actual.get("definition"));
      String vocabulary=text(definition,"vocabularyRef");
      String previous=conceptVocabularies.putIfAbsent(id,vocabulary);
      if (previous!=null && !previous.equals(vocabulary)) throw definitionError();
      vocabularyMembers.computeIfAbsent(vocabulary, ignored -> new HashSet<>()).add(id);
    }
  }

  // Preflight can validate this frozen proposal locally. Runtime also calls bind against references:resolve.
  void embedded(Map<String,Object> binding, Function<Object,String> hash) {
    String ref=text(binding,"semanticId")+"@"+text(binding,"publicationSetId");
    var definition=object(binding.get("definition"));
    if (!hash.apply(definition).equals("sha256:"+text(binding,"definitionSha256"))
        || definitions.putIfAbsent(ref,definition)!=null) throw definitionError();
  }

  CanonicalValueType pinned(String datatype) {
    var refs=definitions.keySet().stream().filter(ref->semanticId(ref).equals(datatype)).toList();
    if (refs.size()!=1) throw definitionError();
    return compile(refs.getFirst(),false,new HashSet<>(),new int[1]);
  }
  CanonicalValueType composition(Map<String,Object> comp, String datatype) {
    if ("STRUCTURED".equals(comp.get("kind"))) {
      String ref = text(comp,"typeRef");
      if (BASE.containsKey(datatype) || !datatype.equals(semanticId(ref))) throw definitionError();
      return compile(ref,false,new HashSet<>(),new int[1]);
    }
    if (!"CONSTRUCTOR".equals(comp.get("kind"))) throw definitionError();
    String output = switch (text(comp,"constructorId")) {
      case "POINT_FROM_LAT_LON_V1" -> "geometry";
      case "TEXT_JOIN_V1" -> "string";
      case "LIST_SPLIT_V1" -> READER_TYPES.get(text(object(comp.get("params")),"itemReader"));
      default -> throw definitionError();
    };
    boolean list="LIST_SPLIT_V1".equals(comp.get("constructorId"));
    if (output==null || !(list ? "list".equals(datatype) : output.equals(BASE.get(datatype)))) throw definitionError();
    return base(output,list);
  }

  private CanonicalValueType compile(String ref, boolean list, Set<String> path, int[] leaves) {
    if (BASE.containsValue(ref)) {
      if (++leaves[0]>256) throw definitionError();
      return base(ref,list);
    }
    String id = semanticId(ref);
    var definition = definitions.get(ref);
    if (definition==null || !definition.keySet().equals(Set.of("components")) || path.size()>=4 || !path.add(id)
        || !(definition.get("components") instanceof List<?> fields) || fields.isEmpty() || fields.size()>32)
      throw definitionError();
    Map<String,Component> components = new LinkedHashMap<>();
    for (var raw : fields) {
      var field = object(raw);
      if (!Set.of("name","type","cardinality","required","labels","constraints").containsAll(field.keySet()))
        throw definitionError();
      String name = text(field,"name");
      String type = text(field,"type");
      String cardinality = text(field,"cardinality");
      if (!name.matches("[a-z][A-Za-z0-9]{0,63}") || components.containsKey(name)
          || !(field.get("required") instanceof Boolean required)
          || !Set.of("ONE","MANY").contains(cardinality)) throw definitionError();
      if (field.containsKey("labels") && object(field.get("labels")).values().stream().anyMatch(v->!(v instanceof String)))
        throw definitionError();
      var shape = compile(type,"MANY".equals(cardinality),path,leaves);
      var constraints = object(field.getOrDefault("constraints",Map.of()));
      validateConstraints(type,constraints);
      var members = vocabularyMembers.getOrDefault(constraints.get("vocabularyRef"),Set.of());
      components.put(name,new Component(shape,required,constraints,members));
    }
    path.remove(id);
    return new CanonicalValueType(ref,list,components);
  }
  private static String semanticId(String ref) {
    int split = ref.lastIndexOf('@');
    if (split<=0 || !absolute(ref.substring(0,split))) throw definitionError();
    try {
      String set = ref.substring(split+1);
      if (!UUID.fromString(set).toString().equals(set)) throw definitionError();
    } catch (IllegalArgumentException e) {throw definitionError();}
    return ref.substring(0,split);
  }
  private static void validateConstraints(String type, Map<String,Object> constraints) {
    Set<String> allowed = switch (type) {
      case "integer","decimal","date","datetime" -> Set.of("minInclusive","maxInclusive");
      case "string" -> Set.of("maxLength");
      case "concept" -> Set.of("vocabularyRef");
      default -> Set.of();
    };
    if (!allowed.containsAll(constraints.keySet()) || constraints.values().stream().anyMatch(Objects::isNull))
      throw definitionError();
    try {
      for (var entry : constraints.entrySet()) {
        Object value = entry.getValue();
        switch (entry.getKey()) {
          case "maxLength" -> {
            if (!(value instanceof Number) || !value.toString().matches("0|[1-9][0-9]*"))
              throw definitionError();
          }
          case "vocabularyRef" -> {if (!(value instanceof String s) || s.isBlank()) throw definitionError();}
          default -> {
            validateBase(type,value,(crs,geo)->{});
          }
        }
      }
      if (constraints.containsKey("minInclusive") && constraints.containsKey("maxInclusive")
          && compare(type,constraints.get("minInclusive").toString(),constraints.get("maxInclusive").toString())>0)
        throw definitionError();
    } catch (RuntimeException e) {throw definitionError();}
  }
  @SuppressWarnings("unchecked")
  static Map<String,Object> object(Object raw) {
    if (!(raw instanceof Map<?,?> m)) throw definitionError();
    return (Map<String,Object>)m;
  }
  private static String text(Map<String,Object> map, String key) {
    if (!(map.get(key) instanceof String text) || text.isBlank()) throw definitionError();
    return text;
  }
}
