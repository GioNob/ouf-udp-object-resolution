package it.comune.trieste.ouf.udp;

import java.net.URI;
import java.util.*;

/** The published vocabulary declaration. Only the concept IRI identifies the category scope. */
public record IdentityCategory(String schemeIri, String conceptIri) {
  public IdentityCategory {
    requireAbsolute(schemeIri);
    requireAbsolute(conceptIri);
  }

  static void requireAbsolute(String iri) {
    if (iri != null) {
      try {
        if (URI.create(iri).isAbsolute()) return;
      } catch (IllegalArgumentException ignored) { /* Report the safe configuration error below. */ }
    }
    throw invalid();
  }

  static IdentityCategory declared(Map<?,?> target) {
    if (!target.containsKey("category")) return null;
    if (!(target.get("category") instanceof Map<?,?> value)
        || !value.keySet().equals(Set.of("schemeIri","conceptIri"))
        || !(value.get("schemeIri") instanceof String scheme)
        || !(value.get("conceptIri") instanceof String concept)) throw invalid();
    return new IdentityCategory(scheme,concept);
  }

  static IdentityCategory forClass(List<?> targets,String canonicalClass) {
    Map<String,IdentityCategory> categories = new HashMap<>();
    for (Object item : targets) {
      if (!(item instanceof Map<?,?> target) || !(target.get("classIri") instanceof String iri)) continue;
      var category = declared(target);
      if (categories.containsKey(iri) && !Objects.equals(category,categories.get(iri))) throw conflict();
      categories.put(iri,category);
    }
    return categories.get(canonicalClass);
  }

  static String legacyField(List<?> evidence,String prefix) {
    List<String> values=evidence.stream().filter(String.class::isInstance).map(String.class::cast)
        .filter(v->v.startsWith(prefix)).map(v->v.substring(prefix.length())).toList();
    if(values.size()>1 || !values.isEmpty() && values.getFirst().isBlank())
      throw new IllegalArgumentException("UDP_RESOLUTION_ISSUE_SCOPE_UNPROVEN");
    return values.isEmpty()?null:values.getFirst();
  }

  static Rejected drift(){return new Rejected("UDP_IDENTITY_CATEGORY_DRIFT");}

  static Rejected mismatch(){return new Rejected("UDP_IDENTITY_CATEGORY_MISMATCH");}

  static final class Rejected extends IllegalArgumentException {
    private Rejected(String code){super(code);}
  }

  static Rejected conflict() {
    return new Rejected("UDP_IDENTITY_CATEGORY_CONFLICT");
  }

  private static Rejected invalid() {
    return new Rejected("UDP_IDENTITY_CATEGORY_INVALID");
  }
}
