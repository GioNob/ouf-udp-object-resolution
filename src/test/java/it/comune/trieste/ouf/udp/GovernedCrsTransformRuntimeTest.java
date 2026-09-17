package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class GovernedCrsTransformRuntimeTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->required("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->required("OUF_UDP_DB_PASSWORD"));}
 @Autowired JdbcClient db;@Autowired ObjectMapper json;
 String engine(){return db.sql("select postgis_proj_version()").query(String.class).single().split(" ")[0];}
 static final String FORWARD="+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad +step +proj=utm +zone=31 +ellps=WGS84";
 static final String INVERSE="+proj=pipeline +step +inv +proj=utm +zone=31 +ellps=WGS84 +step +proj=unitconvert +xy_in=rad +xy_out=deg";
 UdpPorts.CrsOperation forward(){return op(4326,32631,FORWARD,List.of(0.,-1.,6.,85.),List.of(new UdpPorts.CrsControlPoint(2,49,426857.9877165967,5427937.523342293,.001),new UdpPorts.CrsControlPoint(3,0,500000,0,.001)));}
 UdpPorts.CrsOperation backward(){return op(32631,4326,INVERSE,List.of(100000.,-100000.,900000.,9500000.),List.of(new UdpPorts.CrsControlPoint(426857.9877165967,5427937.523342293,2,49,1e-8),new UdpPorts.CrsControlPoint(500000,0,3,0,1e-8)));}
 UdpPorts.CrsOperation op(int source,int target,String pipeline,List<Double> bounds,List<UdpPorts.CrsControlPoint> controls){return new UdpPorts.CrsOperation("test-operation",source,target,pipeline,engine(),null,"Projection test; numerical tolerance does not assert survey or datum accuracy",bounds,controls,Map.of(),null);}
 UdpPorts.GeometryRule rule(int source,int target,String axes,String action,UdpPorts.CrsOperation in,UdpPorts.CrsOperation out){return new UdpPorts.GeometryRule("geometry","EPSG:"+source,target,"governed-crs/1","RESTRICTED",new UdpPorts.CrsPolicy(axes,action,in,out));}
 GovernedCrsTransform.Result run(Map<String,Object> envelope,UdpPorts.GeometryRule rule){return new GovernedCrsTransform(db,json,rule.canonicalSrid(),"").transform(envelope,rule);}
 Map<String,Object> point(String crs,double x,double y){return Map.of("crs",crs,"geoJson",Map.of("type","Point","coordinates",List.of(x,y)));}
 void xy(String ewkb,int srid,double x,double y,double tolerance){var row=db.sql("select ST_SRID(g) srid,ST_X(g) x,ST_Y(g) y from (select ST_GeomFromEWKB(decode(:g,'hex')) g) q").param("g",ewkb).query().singleRow();assertThat(((Number)row.get("srid")).intValue()).isEqualTo(srid);assertThat(((Number)row.get("x")).doubleValue()).isCloseTo(x,within(tolerance));assertThat(((Number)row.get("y")).doubleValue()).isCloseTo(y,within(tolerance));}
 @Test void differentMunicipalityUsesItsConfiguredCrsAndExplicitServingProjection(){var result=run(point("EPSG:4326",2,49),rule(4326,32631,"XY","CONVERT",forward(),backward()));assertThat(result.status()).isEqualTo("OK");xy(result.canonicalEwkb(),32631,426857.9877165967,5427937.523342293,.001);xy(result.servingEwkb(),4326,2,49,1e-8);}
 @Test void triesteCanonicalUses6708AndExplicitNorthEastInput(){
  // Central-meridian meridional arc on GRS80. Serving datum equivalence is explicit, not survey-grade.
  double north=4982950.4001;
  var f=op(6706,6708,FORWARD.replace("zone=31","zone=33").replace("WGS84","GRS80"),List.of(12.,40.,18.,50.),List.of(new UdpPorts.CrsControlPoint(15,45,500000,north,.002),new UdpPorts.CrsControlPoint(15,40,500000,4427757.2186,.002)));
  var b=op(6708,4326,INVERSE.replace("zone=31","zone=33").replace("WGS84","GRS80"),List.of(100000.,4000000.,900000.,5600000.),List.of(new UdpPorts.CrsControlPoint(500000,north,15,45,1e-7),new UdpPorts.CrsControlPoint(500000,4427757.2186,15,40,1e-7)));
  b=new UdpPorts.CrsOperation("test-only-rdn-wgs84-visualization-assumption",6708,4326,b.pipeline(),engine(),null,"TEST ONLY: RDN2008 geographic coordinates treated as WGS84 for display; datum accuracy unknown",b.sourceBounds(),b.controlPoints(),Map.of(),null);
  var result=run(point("EPSG:6706",45,15),rule(6706,6708,"YX","CONVERT",f,b));assertThat(result.status()).isEqualTo("OK");xy(result.canonicalEwkb(),6708,500000,north,.002);xy(result.servingEwkb(),4326,15,45,1e-7);
 }
 @Test void mismatchNeedsDecisionAndRejectNeverTransforms(){var envelope=point("EPSG:4326",2,49);assertThat(run(envelope,new UdpPorts.GeometryRule("geometry","EPSG:4326",32631,"v1","OPEN")).status()).isEqualTo("SPATIAL_CRS_DECISION_REQUIRED");assertThat(run(envelope,rule(4326,32631,"XY","REJECT",null,null)).status()).isEqualTo("SPATIAL_CRS_REJECTED");assertThat(run(envelope,rule(4326,32631,null,"CONVERT",forward(),backward())).status()).isEqualTo("SPATIAL_CRS_DECISION_REQUIRED");}
 @Test void changedSourceAndMunicipalConfigurationFailClosed(){var r=rule(4326,32631,"XY","CONVERT",forward(),backward());assertThat(run(point("EPSG:4258",2,49),r).status()).isEqualTo("SPATIAL_CRS_MISMATCH");assertThat(new GovernedCrsTransform(db,json,6708,"").transform(point("EPSG:4326",2,49),r).status()).isEqualTo("SPATIAL_POLICY_INVALID");}
 @Test void engineChangeAndWrongControlsStopConversion(){var f=forward();var changed=new UdpPorts.CrsOperation(f.operationId(),4326,32631,f.pipeline(),"0.0.0",null,f.accuracyStatement(),f.sourceBounds(),f.controlPoints(),Map.of(),null);assertThat(run(point("EPSG:4326",2,49),rule(4326,32631,"XY","CONVERT",changed,backward())).status()).isEqualTo("SPATIAL_TRANSFORM_RESOURCE_CHANGED");var wrong=op(4326,32631,FORWARD,f.sourceBounds(),List.of(new UdpPorts.CrsControlPoint(2,49,0,0,.001),f.controlPoints().get(1)));assertThat(run(point("EPSG:4326",2,49),rule(4326,32631,"XY","CONVERT",wrong,backward())).status()).isEqualTo("SPATIAL_TRANSFORM_CONTROL_FAILED");}
 @Test void operationAreaAndGeometryLimitsAreEnforced(){assertThat(run(point("EPSG:4326",15,45),rule(4326,32631,"XY","CONVERT",forward(),backward())).status()).isEqualTo("SPATIAL_OUTSIDE_OPERATION_AREA");var legacy=new UdpPorts.GeometryRule("geometry","EPSG:4326",4326,"v1","OPEN");assertThat(run(Map.of("crs","EPSG:4326","geoJson",Map.of("type","NOT_A_GEOMETRY")),legacy).status()).isEqualTo("SPATIAL_INVALID_GEOMETRY");assertThat(run(point("EPSG:4326",181,45),legacy).status()).isEqualTo("SPATIAL_INVALID_GEOMETRY");assertThat(run(Map.of("crs","EPSG:4326","geoJson",Map.of("type","Point","coordinates",List.of(1,2,3))),legacy).status()).isEqualTo("SPATIAL_INVALID_GEOMETRY");}
 @Test void mandatoryGridWithoutResourcesNeverFallsBack(){var f=forward();String pipeline="+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad +step +proj=hgridshift +grids=/opt/ouf/proj/missing.gsb +step +proj=utm +zone=31 +ellps=WGS84";var grid=new UdpPorts.CrsOperation("requires-grid",4326,32631,pipeline,engine(),null,"Known required grid",f.sourceBounds(),f.controlPoints(),Map.of("missing.gsb","a".repeat(64)),"sha256:"+"b".repeat(64));assertThat(run(point("EPSG:4326",2,49),rule(4326,32631,"XY","CONVERT",grid,backward())).status()).isEqualTo("SPATIAL_TRANSFORM_UNAVAILABLE");assertThatThrownBy(()->GovernedCrsTransform.validatePipeline(pipeline.replace("/opt/ouf/proj/missing.gsb","@missing.gsb,null"),Set.of("missing.gsb"))).hasMessage("SPATIAL_POLICY_INVALID");}
 static String required(String n){String value=System.getenv(n);if(value==null)throw new IllegalStateException(n+" required");return value;}
}
