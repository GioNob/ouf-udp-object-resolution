package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static it.comune.trieste.ouf.udp.PublishedRuntimeConfiguration.*;

/** One verification. Neither registry generations nor successful pins survive the call. */
final class SemanticReferenceBatch {
  static final String PATH = "/api/semantic/v1/references:resolve";
  private static final byte[] PREFIX = "{\"references\":[".getBytes(StandardCharsets.UTF_8);
  private final ObjectMapper json;
  private final ReferenceBatchSettings limits;
  private final PublishedRuntimeConfiguration gateway;
  private final List<Pin> pins = new ArrayList<>();
  private final boolean structured;
  private int blockLimit;

  private record Pin(int index, Map<String,Object> binding, Map<String,String> tuple, byte[] bytes,
      List<Map<String,Object>> aliases) {
    SemanticReferenceFailure failure(String code) { return new SemanticReferenceFailure(code,index,binding); }
    SemanticReferenceFailure mismatch() { return failure("UDP_SEMANTIC_REFERENCE_MISMATCH"); }
  }

  SemanticReferenceBatch(PublishedRuntimeConfiguration gateway, ObjectMapper json, ReferenceBatchSettings limits,
      List<?> bindings, boolean structured) {
    this.gateway = gateway;
    this.json = json;
    this.limits = limits;
    this.structured = structured;
    blockLimit = limits.items();
    var unique = new HashMap<Map<String,String>,Pin>();
    for (Object item : bindings) {
      var binding = PublishedValueTypes.object(item);
      var tuple = Map.of("semanticId",text(binding,"semanticId"),"revisionId",uuid(binding,"revisionId"),
          "publicationSetId",uuid(binding,"publicationSetId"));
      // A repeated pin is resolved once and every occurrence is still verified, as before.
      var existing = unique.get(tuple);
      if (existing != null) { existing.aliases().add(binding); continue; }
      try {
        byte[] bytes = json.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(tuple);
        var pin = new Pin(pins.size(),binding,tuple,bytes,new ArrayList<>());
        unique.put(tuple,pin);
        if ((long)PREFIX.length + bytes.length + 2 > limits.requestBytes()) throw pin.failure("UDP_SEMANTIC_REFERENCE_TOO_LARGE");
        pins.add(pin);
      } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) { throw invalid(); }
    }
  }

  PublishedValueTypes verify() {
    for (int attempt = 0; attempt < limits.verificationAttempts(); attempt++) {
      PublishedValueTypes types = verifyGeneration();
      if (types != null) return types;
    }
    throw new IllegalArgumentException("UDP_SEMANTIC_REGISTRY_VERSION_UNSTABLE");
  }

  private PublishedValueTypes verifyGeneration() {
    var types = new PublishedValueTypes();
    SemanticReferenceFailure rejected = null;
    String version = null;
    for (int start = 0; start < pins.size();) {
      var body = new ByteArrayOutputStream();
      body.writeBytes(PREFIX);
      int end = start;
      while (end < pins.size() && end - start < blockLimit) {
        byte[] item = pins.get(end).bytes();
        int separator = end == start ? 0 : 1;
        if ((long)body.size() + separator + item.length + 2 > limits.requestBytes()) break;
        if (separator != 0) body.write(',');
        body.writeBytes(item);
        end++;
      }
      body.write(']');
      body.write('}');
      var response = gateway.exchange(PATH+"-batch",body.toByteArray());
      if (response.statusCode() == 404 || response.statusCode() == 405) return verifyLegacy();
      if (response.statusCode() == 413) {
        if (end - start == 1) throw pins.get(start).failure("UDP_SEMANTIC_REFERENCE_TOO_LARGE");
        blockLimit = (end - start) / 2;
        continue;
      }
      var page = gateway.read(response);
      if (!"ouf.semantic.reference-batch.v1".equals(page.get("contract"))) throw invalid();
      String marker = text(page,"registryVersion");
      if (version != null && !version.equals(marker)) return null;
      version = marker;
      if (!(page.get("results") instanceof List<?> results) || results.size() != end-start) throw invalid();
      for (int i = 0; i < results.size(); i++) {
        Pin pin = pins.get(start+i);
        var result = PublishedValueTypes.object(results.get(i));
        if (!Integer.valueOf(i).equals(result.get("index"))) throw invalid();
        if (!pin.tuple().entrySet().stream().allMatch(e -> e.getValue().equals(result.get(e.getKey()))))
          throw pin.mismatch();
        try {
          switch (text(result,"outcome")) {
            case "NOT_RESOLVABLE" -> throw pin.failure("UDP_SEMANTIC_REFERENCE_UNRESOLVABLE");
            case "RESOLVED" -> verifyPin(pin,object(result,"resolved"),types,true);
            default -> throw invalid();
          }
        } catch (SemanticReferenceFailure failure) {
          if (rejected == null) rejected = failure;
        }
      }
      start = end;
    }
    if (rejected != null) throw rejected;
    return types;
  }

  private PublishedValueTypes verifyLegacy() {
    var types = new PublishedValueTypes();
    for (Pin pin : pins) verifyPin(pin,get(pin),types,false);
    return types;
  }

  private Map<String,Object> get(Pin pin) {
    var tuple = pin.tuple();
    String path = PATH+"?semanticId="+URLEncoder.encode(tuple.get("semanticId"),StandardCharsets.UTF_8)
        +"&revisionId="+tuple.get("revisionId")+"&publicationSetId="+tuple.get("publicationSetId");
    HttpResponse<byte[]> response = gateway.exchange(path,null);
    if (response.statusCode() == 404 || response.statusCode() == 410)
      throw pin.failure("UDP_SEMANTIC_REFERENCE_UNRESOLVABLE");
    return gateway.read(response);
  }

  private void verifyPin(Pin pin, Map<String,Object> actual, PublishedValueTypes types, boolean compact) {
    var occurrences = new ArrayList<Map<String,Object>>();
    occurrences.add(pin.binding());
    occurrences.addAll(pin.aliases());
    for (var binding : occurrences) verifyOccurrence(pin,binding,actual,types,compact);
  }

  private void verifyOccurrence(Pin pin, Map<String,Object> binding, Map<String,Object> actual,
      PublishedValueTypes types, boolean compact) {
    var tuple = pin.tuple();
    try {
      // Identity, version, revision and set as before; the status must be a published one
      // (the historical acceptance set), so a revoked or draft revision is a mismatch.
      if (!tuple.get("semanticId").equals(actual.get("semantic_id"))
          || !text(binding,"semanticVersion").equals(actual.get("semantic_version"))
          || !tuple.get("revisionId").equals(actual.get("revision_id"))
          || !tuple.get("publicationSetId").equals(actual.get("publication_set_id"))
          || !Set.of("ACTIVE","DEPRECATED","RETIRED").contains(text(actual,"status"))) throw pin.mismatch();
      String kind = text(actual,"artifact_type");
      if (structured || "DATATYPE".equals(kind) || "VALUE_MAP".equals(kind) || PublishedValueTypes.hasEmbeddedDefinition(binding)) {
        // v1 has no proof of CONCEPT vocabulary membership in the compact result.
        if (compact && "CONCEPT".equals(kind)) {
          var full = get(pin);
          if (!kind.equals(full.get("artifact_type"))) throw pin.mismatch();
          verifyOccurrence(pin,binding,full,types,false);
        } else {
          types.bind(binding,actual,definition -> canonicalPublicationHash(json,definition),compact);
        }
      }
    } catch (SemanticReferenceFailure failure) { throw failure; }
    catch (IllegalArgumentException malformed) { throw pin.mismatch(); }
  }
}
