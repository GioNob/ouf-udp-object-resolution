package it.comune.trieste.ouf.udp;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Executes only the operations frozen in the approved source profile. Geometry storage uses XY. */
@Service
public class GovernedCrsTransform {
 private final JdbcClient db;private final ObjectMapper json;private final int municipalSrid;private final String manifest;
 public GovernedCrsTransform(JdbcClient db,ObjectMapper json,@Value("${ouf.udp.spatial.canonical-srid:4326}") int municipalSrid,@Value("${ouf.udp.spatial.grid-manifest:}") String manifest){this.db=db;this.json=json;this.municipalSrid=municipalSrid;this.manifest=manifest;}
 public record Result(String status,String canonicalEwkb,String servingEwkb,Map<String,Object> evidence){}
 public Result transform(Map<?,?> envelope,UdpPorts.GeometryRule rule){
  try{
   String crs=Objects.toString(envelope.get("crs"),"");
   if(crs.isBlank())return failure("SPATIAL_CRS_REQUIRED");
   if(!crs.equals(rule.expectedSourceCrs()))return failure("SPATIAL_CRS_MISMATCH");
   if(!crs.matches("EPSG:[1-9][0-9]{0,5}")||rule.canonicalSrid()!=municipalSrid)return failure("SPATIAL_POLICY_INVALID");
   int source=Integer.parseInt(crs.substring(5));
   if(db.sql("select count(*) from spatial_ref_sys where srid in (:source,:target)").param("source",source).param("target",municipalSrid).query(Long.class).single()!=(source==municipalSrid?1:2))return failure("SPATIAL_POLICY_INVALID");
   var policy=rule.crsPolicy();boolean legacy=policy==null&&source==4326&&municipalSrid==4326;
   if(!legacy&&(policy==null||!Set.of("XY","YX").contains(Objects.toString(policy.sourceAxisOrder(),""))||!Set.of("CONVERT","REJECT").contains(Objects.toString(policy.mismatchAction(),""))))return failure("SPATIAL_CRS_DECISION_REQUIRED");
   if(source!=municipalSrid&&"REJECT".equals(policy.mismatchAction()))return failure("SPATIAL_CRS_REJECTED");
   String input=write(envelope.get("geoJson"));if(input.getBytes(StandardCharsets.UTF_8).length>1_048_576)return failure("SPATIAL_INVALID_GEOMETRY");
   var original=project(input,source,source,null,!legacy&&"YX".equals(policy.sourceAxisOrder()));
   if(!"OK".equals(original.get("status")))return failure(original.get("status"));
   String engine=db.sql("select postgis_proj_version()").query(String.class).single();
   String canonical=original.get("ewkb");
   if(source!=municipalSrid)canonical=apply(canonical,policy.sourceOperation(),source,municipalSrid,engine);
   String serving=municipalSrid==4326?canonical:apply(canonical,policy.servingOperation(),municipalSrid,4326,engine);
   Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("sourceCrs",crs);evidence.put("canonicalSrid",municipalSrid);evidence.put("canonicalAxisOrder","XY");evidence.put("servingSrid",4326);evidence.put("projVersion",engine);evidence.put("policy",legacy?Map.of("mode","LEGACY_4326_IDENTITY","sourceAxisOrder","XY"):policy);
   return new Result("OK",canonical,serving,Collections.unmodifiableMap(evidence));
  }catch(Rejected e){return failure(e.getMessage());}catch(IllegalArgumentException e){return failure("SPATIAL_POLICY_INVALID");}
 }
 private String apply(String ewkb,UdpPorts.CrsOperation op,int source,int target,String engine){
  validateOperation(op,source,target,engine);
  String geometry=db.sql("select ST_AsGeoJSON(ST_GeomFromEWKB(decode(:g,'hex')),15,0)").param("g",ewkb).query(String.class).single();
  var b=op.sourceBounds();boolean covered=db.sql("select ST_XMin(Box3D(g))>=:xmin and ST_YMin(Box3D(g))>=:ymin and ST_XMax(Box3D(g))<=:xmax and ST_YMax(Box3D(g))<=:ymax from (select ST_GeomFromEWKB(decode(:g,'hex')) g) x").param("g",ewkb).param("xmin",b.get(0)).param("ymin",b.get(1)).param("xmax",b.get(2)).param("ymax",b.get(3)).query(Boolean.class).single();
  if(!covered)throw reject("SPATIAL_OUTSIDE_OPERATION_AREA");
  for(var p:op.controlPoints()){
   var result=project(write(Map.of("type","Point","coordinates",List.of(p.sourceX(),p.sourceY()))),source,target,op.pipeline(),false);
   if(!"OK".equals(result.get("status")))throw reject(result.get("status"));
   double error=db.sql("select ST_Distance(ST_GeomFromEWKB(decode(:g,'hex')),ST_SetSRID(ST_MakePoint(:x,:y),:s))").param("g",result.get("ewkb")).param("x",p.targetX()).param("y",p.targetY()).param("s",target).query(Double.class).single();
   if(!Double.isFinite(error)||error>p.tolerance())throw reject("SPATIAL_TRANSFORM_CONTROL_FAILED");
  }
  var result=project(geometry,source,target,op.pipeline(),false);if(!"OK".equals(result.get("status")))throw reject(result.get("status"));return result.get("ewkb");
 }
 private void validateOperation(UdpPorts.CrsOperation op,int source,int target,String engine){
  if(op==null||op.operationId()==null||op.operationId().isBlank()||op.sourceSrid()!=source||op.targetSrid()!=target||op.accuracyStatement()==null||op.accuracyStatement().isBlank()||op.projVersion()==null)throw reject("SPATIAL_CRS_DECISION_REQUIRED");
  if(!op.projVersion().equals(engine.split(" ")[0]))throw reject("SPATIAL_TRANSFORM_RESOURCE_CHANGED");
  if(op.accuracyMeters()!=null&&(!Double.isFinite(op.accuracyMeters())||op.accuracyMeters()<0))throw reject("SPATIAL_POLICY_INVALID");
  if(op.sourceBounds()==null||op.sourceBounds().size()!=4||op.sourceBounds().stream().anyMatch(x->x==null||!Double.isFinite(x))||op.sourceBounds().get(0)>=op.sourceBounds().get(2)||op.sourceBounds().get(1)>=op.sourceBounds().get(3))throw reject("SPATIAL_POLICY_INVALID");
  if(op.controlPoints()==null||op.controlPoints().size()<2||op.controlPoints().size()>20||op.controlPoints().stream().anyMatch(p->p==null||!Double.isFinite(p.sourceX())||!Double.isFinite(p.sourceY())||!Double.isFinite(p.targetX())||!Double.isFinite(p.targetY())||!Double.isFinite(p.tolerance())||p.tolerance()<=0))throw reject("SPATIAL_POLICY_INVALID");
  if(op.controlPoints().stream().map(p->List.of(p.sourceX(),p.sourceY())).distinct().count()<2)throw reject("SPATIAL_POLICY_INVALID");
  if(op.controlPoints().stream().anyMatch(p->p.sourceX()<op.sourceBounds().get(0)||p.sourceY()<op.sourceBounds().get(1)||p.sourceX()>op.sourceBounds().get(2)||p.sourceY()>op.sourceBounds().get(3)))throw reject("SPATIAL_POLICY_INVALID");
  if(op.requiredGrids()==null)throw reject("SPATIAL_POLICY_INVALID");
  validatePipeline(op.pipeline(),op.requiredGrids().keySet());
  if(!op.requiredGrids().isEmpty()){
   if(manifest.isBlank()||op.resourceVersion()==null)throw reject("SPATIAL_TRANSFORM_UNAVAILABLE");
   try{if(Files.size(Path.of(manifest))>65536)throw reject("SPATIAL_POLICY_INVALID");byte[] bytes=Files.readAllBytes(Path.of(manifest));if(bytes.length>65536)throw reject("SPATIAL_POLICY_INVALID");String hash="sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    var files=json.readTree(bytes).path("grids");String databaseManifest=db.sql("select current_setting('ouf.proj_manifest_sha256',true)").query(String.class).optional().orElse("");if(!hash.equals(databaseManifest))throw reject("SPATIAL_TRANSFORM_RESOURCE_CHANGED");if(!hash.equals(op.resourceVersion()))throw reject("SPATIAL_TRANSFORM_RESOURCE_CHANGED");
    for(var entry:op.requiredGrids().entrySet())if((entry.getValue()==null||!entry.getValue().matches("[a-f0-9]{64}"))||!entry.getValue().equals(files.path(entry.getKey()).asText()))throw reject("SPATIAL_TRANSFORM_RESOURCE_CHANGED");
   }catch(Rejected e){throw e;}catch(Exception e){throw reject("SPATIAL_TRANSFORM_UNAVAILABLE");}
  }
 }
 static void validatePipeline(String pipeline,Set<String> expectedGrids){
  if(pipeline==null||pipeline.length()>4096||!pipeline.startsWith("+proj=pipeline "))throw reject("SPATIAL_POLICY_INVALID");
  Set<String> used=new HashSet<>();
  Set<String> keys=Set.of("proj","step","inv","xy_in","xy_out","zone","south","ellps","a","b","rf","lat_0","lon_0","k","k_0","x_0","y_0","x","y","z","rx","ry","rz","s","convention","grids","order");
  for(String token:pipeline.strip().split("\\s+")){
   if(!token.startsWith("+"))throw reject("SPATIAL_POLICY_INVALID");String[] pair=token.substring(1).split("=",2);if(!keys.contains(pair[0]))throw reject("SPATIAL_POLICY_INVALID");
   if(pair.length==2){String value=pair[1];if(pair[0].equals("grids")){for(String grid:value.split(",")){if(!grid.matches("/opt/ouf/proj/[A-Za-z0-9_-]+\\.(tif|gsb|gtx)"))throw reject("SPATIAL_POLICY_INVALID");used.add(grid.substring("/opt/ouf/proj/".length()));}}
    else if(!value.matches("[A-Za-z0-9_.,+-]+"))throw reject("SPATIAL_POLICY_INVALID");
    if(pair[0].equals("proj")&&!Set.of("pipeline","unitconvert","utm","tmerc","cart","helmert","hgridshift","axisswap","noop").contains(value))throw reject("SPATIAL_POLICY_INVALID");
   }
  }
  if(!used.equals(expectedGrids))throw reject("SPATIAL_POLICY_INVALID");
 }
 private Map<String,String> project(String geometry,int source,int target,String pipeline,boolean swap){return db.sql("select status,ewkb from ouf_udp.project_geometry(:g,:source,:target,:pipeline,:swap)").param("g",geometry).param("source",source).param("target",target).param("pipeline",pipeline,java.sql.Types.VARCHAR).param("swap",swap).query((rs,n)->{var m=new HashMap<String,String>();m.put("status",rs.getString(1));m.put("ewkb",rs.getString(2));return m;}).single();}
 private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw reject("SPATIAL_INVALID_GEOMETRY");}}
 private static Result failure(String code){return new Result(code,null,null,Map.of("reasonCode",code));}
 private static Rejected reject(String code){return new Rejected(code);}
 static final class Rejected extends RuntimeException{Rejected(String code){super(code);}}
}
