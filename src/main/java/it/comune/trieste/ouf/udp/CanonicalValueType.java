package it.comune.trieste.ouf.udp;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.BiConsumer;

/** Compiled property shape. A nested type is identified by its exact publication reference. */
public record CanonicalValueType(String type, boolean list, Map<String,Component> components,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    ValueMapProof valueMapProof) {
  static final String XSD = "http://www.w3.org/2001/XMLSchema#";
  static final String GEO = "http://www.opengis.net/ont/geosparql#geoJSONLiteral";
  static final Map<String,String> BASE = Map.of(
      XSD+"string","string", XSD+"integer","integer", XSD+"decimal","decimal",
      XSD+"boolean","boolean", XSD+"date","date", XSD+"dateTime","datetime",
      GEO,"geometry", XSD+"anyURI","concept");
  public record Component(CanonicalValueType shape, boolean required, Map<String,Object> constraints,
                          Set<String> vocabularyMembers) {
    public Component {
      constraints = Collections.unmodifiableSortedMap(new TreeMap<>(constraints));
      vocabularyMembers = Collections.unmodifiableSortedSet(new TreeSet<>(vocabularyMembers));
    }
  }
  public CanonicalValueType {
    components = Collections.unmodifiableSortedMap(new TreeMap<>(components));
  }
  public CanonicalValueType(String type,boolean list,Map<String,Component> components) {
    this(type,list,components,null);
  }
  /** Attestation from the published builder at this path. Only a VALUE_MAP_V1 leaf carries a vocabulary. */
  public record ValueMapProof(String vocabulary,Map<String,ValueMapProof> fields,List<ValueMapProof> items) {
    static final ValueMapProof NONE = new ValueMapProof(null,Map.of(),List.of());
    public ValueMapProof {
      fields = Collections.unmodifiableSortedMap(new TreeMap<>(fields));
      items = List.copyOf(items);
    }
    boolean empty(){return vocabulary==null && fields.isEmpty() && items.isEmpty();}
    private ValueMapProof sharedWith(ValueMapProof other) {
      if (empty() || other.empty()) return NONE;
      if (equals(other)) return this;
      var common = new TreeMap<String,ValueMapProof>();
      fields.forEach((name,field)->{
        var shared=field.sharedWith(other.fields.getOrDefault(name,NONE));
        if (!shared.empty()) common.put(name,shared);
      });
      if (!common.isEmpty()) return new ValueMapProof(null,common,List.of());
      if (items.isEmpty() || items.size()!=other.items.size()) return NONE;
      var sharedItems = new ArrayList<ValueMapProof>();
      for (int i=0;i<items.size();i++) sharedItems.add(items.get(i).sharedWith(other.items.get(i)));
      return sharedItems.stream().allMatch(ValueMapProof::empty) ? NONE : new ValueMapProof(null,Map.of(),sharedItems);
    }
    private List<ValueMapProof> origins(int count) {
      if (items.isEmpty()) return Collections.nCopies(count,this); // LIST_SPLIT_V1 has one producer.
      if (count==items.size()) return items;
      if (count>items.size()) return Collections.nCopies(count,NONE);
      // With k omitted inputs, output i can originate at i..i+k. Intersect each window
      // using block prefixes and suffixes so a sparse MANY does not rescan every builder per value.
      int width=items.size()-count+1;
      var prefix=new ValueMapProof[items.size()];
      var suffix=new ValueMapProof[items.size()];
      for (int i=0;i<items.size();i++) {
        prefix[i]=i%width==0 ? items.get(i) : prefix[i-1].sharedWith(items.get(i));
        int j=items.size()-1-i;
        suffix[j]=j==items.size()-1 || (j+1)%width==0 ? items.get(j) : suffix[j+1].sharedWith(items.get(j));
      }
      var result=new ArrayList<ValueMapProof>(count);
      for (int i=0;i<count;i++) result.add(suffix[i].sharedWith(prefix[i+width-1]));
      return result;
    }
  }
  static CanonicalValueType base(String id, boolean list) {
    if (!BASE.containsValue(id)) throw definitionError();
    return new CanonicalValueType(id,list,Map.of());
  }
  GovernedIdentityEngine.ComparatorKind comparator() {
    if (list || !components.isEmpty()) return GovernedIdentityEngine.ComparatorKind.JSON_V1;
    return switch (type) {
      case "integer","decimal" -> GovernedIdentityEngine.ComparatorKind.DECIMAL_V1;
      case "geometry" -> GovernedIdentityEngine.ComparatorKind.JSON_V1;
      case "concept" -> GovernedIdentityEngine.ComparatorKind.CONCEPT;
      default -> GovernedIdentityEngine.ComparatorKind.TEXT_V1;
    };
  }
  void validate(Object value, BiConsumer<String,Map<?,?>> geometry) {
    validate(value,geometry,valueMapProof==null ? ValueMapProof.NONE : valueMapProof,null);
  }
  private void validate(Object value,BiConsumer<String,Map<?,?>> geometry,ValueMapProof proof,Component component) {
    if (list) {
      if (!(value instanceof List<?> items) || items.isEmpty()) throw dataError();
      var origins = proof.origins(items.size());
      for (int i=0;i<items.size();i++) {
        validateItem(items.get(i),geometry,origins.get(i),component);
      }
    } else validateItem(value,geometry,proof,component);
  }
  void validateItem(Object value, BiConsumer<String,Map<?,?>> geometry) {
    validateItem(value,geometry,valueMapProof==null ? ValueMapProof.NONE : valueMapProof,null);
  }
  private void validateItem(Object value,BiConsumer<String,Map<?,?>> geometry,ValueMapProof proof,Component enclosing) {
    if (!components.isEmpty()) {
      if (!(value instanceof Map<?,?> fields) || fields.isEmpty()
          || !components.keySet().containsAll(fields.keySet())) throw dataError();
      for (var entry : components.entrySet()) {
        var component = entry.getValue();
        if (!fields.containsKey(entry.getKey())) {
          if (component.required()) throw dataError();
          continue;
        }
        Object field = fields.get(entry.getKey());
        var origin = proof.fields().getOrDefault(entry.getKey(),ValueMapProof.NONE);
        component.shape().validate(field,geometry,origin,component);
      }
    } else validateBase(type,value,geometry);
    if (enclosing!=null) constrain(enclosing,value,proof);
  }
  boolean hasGeometry(Object value) {
    if (list) return ((List<?>)value).stream().anyMatch(this::itemHasGeometry);
    return itemHasGeometry(value);
  }
  boolean itemHasGeometry(Object value) {
    if (components.isEmpty()) return "geometry".equals(type);
    var fields=(Map<?,?>)value;
    return components.entrySet().stream().anyMatch(e->fields.containsKey(e.getKey())
        && e.getValue().shape().hasGeometry(fields.get(e.getKey())));
  }
  static boolean absolute(String iri) {
    try {return iri!=null && URI.create(iri).isAbsolute();}
    catch (IllegalArgumentException e) {return false;}
  }
  boolean containsGeometry() {
    return "geometry".equals(type) || components.values().stream().anyMatch(c -> c.shape().containsGeometry());
  }
  Object normalizeGeometry(Object value) {
    if (!containsGeometry()) return value;
    if (!list) return normalizeItemGeometry(value);
    if (!(value instanceof List<?> items)) throw dataError();
    return items.stream().map(this::normalizeItemGeometry).toList();
  }
  Object normalizeItemGeometry(Object value) {
    if ("geometry".equals(type)) return CanonicalGeometry.normalize(value);
    if (!containsGeometry()) return value;
    if (!(value instanceof Map<?,?> fields)) throw dataError();
    var normalized = new TreeMap<String,Object>();
    fields.forEach((key, field) -> {
      if (!(key instanceof String name)) throw dataError();
      var component = components.get(key);
      normalized.put(name, component == null ? field : component.shape().normalizeGeometry(field));
    });
    return normalized;
  }
  static void validateBase(String type, Object value, BiConsumer<String,Map<?,?>> geometry) {
    if ("geometry".equals(type)) {
      var envelope = CanonicalGeometry.normalize(value);
      geometry.accept((String)envelope.get("crs"),(Map<?,?>)envelope.get("geoJson"));
      return;
    }
    if ("boolean".equals(type)) {
      if (!(value instanceof Boolean)) throw dataError();
      return;
    }
    if (!(value instanceof String s)) throw dataError();
    try {
      boolean valid = switch (type) {
        case "string" -> true;
        case "integer" -> s.matches("0|-?[1-9][0-9]*");
        case "decimal" -> s.matches("-?(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?") && !s.equals("-0");
        case "date" -> s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && LocalDate.parse(s)!=null;
        case "datetime" -> s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z")
            && OffsetDateTime.parse(s)!=null;
        case "concept" -> absolute(s);
        default -> false;
      };
      if (!valid) throw dataError();
    } catch (DateTimeParseException e) {throw dataError();}
  }
  static int compare(String type, String a, String b) {
    return switch (type) {
      case "integer","decimal" -> new BigDecimal(a).compareTo(new BigDecimal(b));
      case "date" -> LocalDate.parse(a).compareTo(LocalDate.parse(b));
      case "datetime" -> OffsetDateTime.parse(a).toInstant().compareTo(OffsetDateTime.parse(b).toInstant());
      default -> throw definitionError();
    };
  }
  private static void constrain(Component component, Object value,ValueMapProof proof) {
    var limits = component.constraints();
    if (limits.isEmpty()) return;
    String type = component.shape().type();
    String s = (String)value;
    if (limits.containsKey("minInclusive") && compare(type,s,limits.get("minInclusive").toString())<0
        || limits.containsKey("maxInclusive") && compare(type,s,limits.get("maxInclusive").toString())>0
        || limits.containsKey("maxLength") && new BigDecimal(limits.get("maxLength").toString())
            .compareTo(BigDecimal.valueOf(s.codePointCount(0,s.length())))<0) throw dataError();
    if (limits.containsKey("vocabularyRef") && !limits.get("vocabularyRef").equals(proof.vocabulary())
        && !component.vocabularyMembers().contains(s))
      throw new IllegalArgumentException("UDP_CONCEPT_MEMBERSHIP_UNVERIFIED");
  }
  static IllegalArgumentException dataError() {return new IllegalArgumentException("UDP_DATA_VALUE_NONCANONICAL");}
  static IllegalArgumentException definitionError() {return new IllegalArgumentException("UDP_PINNED_DATATYPE_INVALID");}
}
