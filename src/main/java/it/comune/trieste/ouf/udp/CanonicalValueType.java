package it.comune.trieste.ouf.udp;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.BiConsumer;

/** Compiled property shape. A nested type is identified by its exact publication reference. */
public record CanonicalValueType(String type, boolean list, Map<String,Component> components) {
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
    if (list) {
      if (!(value instanceof List<?> items) || items.isEmpty()) throw dataError();
      for (Object item : items) validateItem(item,geometry);
    } else validateItem(value,geometry);
  }
  void validateItem(Object value, BiConsumer<String,Map<?,?>> geometry) {
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
        component.shape().validate(field,geometry);
        if (component.shape().list()) {
          for (Object item : (List<?>)field) constrain(component,item);
        } else constrain(component,field);
      }
    } else validateBase(type,value,geometry);
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
  static void validateBase(String type, Object value, BiConsumer<String,Map<?,?>> geometry) {
    if ("geometry".equals(type)) {
      if (!(value instanceof Map<?,?> envelope) || !envelope.keySet().equals(Set.of("crs","geoJson"))
          || !(envelope.get("crs") instanceof String crs) || !crs.matches("EPSG:[1-9][0-9]{0,5}")
          || !(envelope.get("geoJson") instanceof Map<?,?> geo)) throw dataError();
      checkGeometry(geo,"EPSG:4326".equals(crs));
      geometry.accept(crs,geo);
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
  private static void checkGeometry(Map<?,?> geo, boolean geographic) {
    if (!(geo.get("type") instanceof String kind)) throw dataError();
    if ("GeometryCollection".equals(kind)) {
      if (!(geo.get("geometries") instanceof List<?> children) || children.isEmpty()) throw dataError();
      for (Object item : children) {
        if (!(item instanceof Map<?,?> child)) throw dataError();
        checkGeometry(child,geographic);
      }
      return;
    }
    int depth = switch (kind) {
      case "Point" -> 0;
      case "MultiPoint","LineString" -> 1;
      case "MultiLineString","Polygon" -> 2;
      case "MultiPolygon" -> 3;
      default -> throw dataError();
    };
    coordinates(geo.get("coordinates"),depth,geographic);
  }
  private static void coordinates(Object raw, int depth, boolean geographic) {
    if (!(raw instanceof List<?> parts) || parts.isEmpty()) throw dataError();
    if (depth>0) {
      for (Object part : parts) coordinates(part,depth-1,geographic);
      return;
    }
    if (parts.size()!=2) throw dataError(); // UDP spatial policy is two-dimensional.
    for (int axis=0; axis<2; axis++) {
      Object coordinate=parts.get(axis);
      if (!(coordinate instanceof Number n) || n instanceof Double || n instanceof Float) throw dataError();
      if (geographic && new BigDecimal(n.toString()).abs().compareTo(BigDecimal.valueOf(axis==0?180:90))>0)
        throw dataError();
    }
  }
  static int compare(String type, String a, String b) {
    return switch (type) {
      case "integer","decimal" -> new BigDecimal(a).compareTo(new BigDecimal(b));
      case "date" -> LocalDate.parse(a).compareTo(LocalDate.parse(b));
      case "datetime" -> OffsetDateTime.parse(a).toInstant().compareTo(OffsetDateTime.parse(b).toInstant());
      default -> throw definitionError();
    };
  }
  private static void constrain(Component component, Object value) {
    var limits = component.constraints();
    if (limits.isEmpty()) return;
    String type = component.shape().type();
    String s = (String)value;
    if (limits.containsKey("minInclusive") && compare(type,s,limits.get("minInclusive").toString())<0
        || limits.containsKey("maxInclusive") && compare(type,s,limits.get("maxInclusive").toString())>0
        || limits.containsKey("maxLength") && new BigDecimal(limits.get("maxLength").toString())
            .compareTo(BigDecimal.valueOf(s.codePointCount(0,s.length())))<0) throw dataError();
    if (limits.containsKey("vocabularyRef") && !component.vocabularyMembers().contains(s))
      throw new IllegalArgumentException("UDP_CONCEPT_MEMBERSHIP_UNVERIFIED");
  }
  static IllegalArgumentException dataError() {return new IllegalArgumentException("UDP_DATA_VALUE_NONCANONICAL");}
  static IllegalArgumentException definitionError() {return new IllegalArgumentException("UDP_PINNED_DATATYPE_INVALID");}
}
