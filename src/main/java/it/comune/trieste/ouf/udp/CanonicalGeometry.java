package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;

/** Binary64 coordinates in the D-084 GeoJSON envelope. Foreign members keep their JSON values. */
final class CanonicalGeometry {
  private CanonicalGeometry() {}

  static boolean isEnvelope(Map<?,?> value) {
    return value.keySet().equals(Set.of("crs", "geoJson"))
        && value.get("crs") instanceof String crs && crs.matches("EPSG:[1-9][0-9]{0,5}")
        && value.get("geoJson") instanceof Map<?,?> geo && geo.get("type") instanceof String;
  }

  static Map<String,Object> normalize(Object value) {
    if (!(value instanceof Map<?,?> envelope) || !isEnvelope(envelope)) throw CanonicalValueType.dataError();
    String crs = (String)envelope.get("crs");
    return Map.of("crs", crs, "geoJson", geometry((Map<?,?>)envelope.get("geoJson"), "EPSG:4326".equals(crs), false));
  }

  // The existing PostGIS property validator checks XY topology. Z remains in the canonical JSON.
  static Map<String,Object> xy(Map<?,?> value) {
    return geometry(value, false, true);
  }

  private static Map<String,Object> geometry(Map<?,?> geo, boolean geographic, boolean xyOnly) {
    var result = new TreeMap<String,Object>();
    geo.forEach((key, item) -> result.put(String.valueOf(key), item));
    if (!(geo.get("type") instanceof String kind)) throw CanonicalValueType.dataError();
    if ("GeometryCollection".equals(kind)) {
      if (!(geo.get("geometries") instanceof List<?> children) || children.isEmpty())
        throw CanonicalValueType.dataError();
      result.put("geometries", children.stream().map(item -> {
        if (!(item instanceof Map<?,?> child)) throw CanonicalValueType.dataError();
        return geometry(child, geographic, xyOnly);
      }).toList());
    } else {
      int depth = switch (kind) {
        case "Point" -> 0;
        case "MultiPoint", "LineString" -> 1;
        case "MultiLineString", "Polygon" -> 2;
        case "MultiPolygon" -> 3;
        default -> throw CanonicalValueType.dataError();
      };
      result.put("coordinates", coordinates(geo.get("coordinates"), depth, geographic, xyOnly));
    }
    return result;
  }

  private static List<?> coordinates(Object raw, int depth, boolean geographic, boolean xyOnly) {
    if (!(raw instanceof List<?> parts) || parts.isEmpty()) throw CanonicalValueType.dataError();
    if (depth > 0) return parts.stream().map(part -> coordinates(part, depth - 1, geographic, xyOnly)).toList();
    if (parts.size() != 2 && parts.size() != 3) throw CanonicalValueType.dataError();
    var position = new ArrayList<Coordinate>(parts.size());
    for (int axis = 0; axis < parts.size(); axis++) {
      Object part = parts.get(axis);
      double number = switch (part) {
        case Coordinate coordinate -> coordinate.value();
        case Number coordinate -> coordinate.doubleValue();
        case null, default -> throw CanonicalValueType.dataError();
      };
      if (!Double.isFinite(number) || geographic && axis < 2 && Math.abs(number) > (axis == 0 ? 180 : 90))
        throw CanonicalValueType.dataError();
      if (!xyOnly || axis < 2) position.add(new Coordinate(number == 0 ? 0 : number));
    }
    return position;
  }

  @JsonSerialize(using = CoordinateSerializer.class)
  private record Coordinate(double value) {}

  static final class CoordinateSerializer extends JsonSerializer<Coordinate> {
    @Override public void serialize(Coordinate coordinate, JsonGenerator out, SerializerProvider provider)
        throws IOException {
      out.writeNumber(shortest(coordinate.value()));
    }
  }

  private static String shortest(double value) {
    var decimal = BigDecimal.valueOf(value).stripTrailingZeros();
    // Java 21 uses the nearest decimal from a set of one- and two-digit candidates
    // when the minimum precision is one. ECMAScript requires the one-digit form.
    if (decimal.precision() == 2) {
      var oneDigit = new BigDecimal(value).round(new MathContext(1, RoundingMode.HALF_EVEN));
      if (oneDigit.doubleValue() == value) decimal = oneDigit.stripTrailingZeros();
    }
    int exponent = decimal.precision() - decimal.scale() - 1;
    if (exponent >= -6 && exponent < 21) return decimal.toPlainString();
    return decimal.movePointLeft(exponent).stripTrailingZeros().toPlainString()
        + "e" + (exponent >= 0 ? "+" : "") + exponent;
  }
}
