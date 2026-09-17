package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest @EnabledIfEnvironmentVariable(named="OUF_R2D_GRID_MANIFEST",matches=".+")
class GovernedGridRuntimeTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("OUF_UDP_DB_URL"));r.add("spring.datasource.username",()->System.getenv("OUF_UDP_DB_USER"));r.add("spring.datasource.password",()->System.getenv("OUF_UDP_DB_PASSWORD"));}
 @Autowired JdbcClient db;@Autowired ObjectMapper json;
 @Test void actualMandatoryGridExecutesAndPinsDatabaseAttestation() throws Exception {
  String manifest=System.getenv("OUF_R2D_GRID_MANIFEST");byte[] bytes=Files.readAllBytes(Path.of(manifest));String hash="sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  String grid=json.readTree(bytes).path("grids").path("synthetic.gsb").asText();String engine=db.sql("select postgis_proj_version()").query(String.class).single().split(" ")[0];
  String pipeline="+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad +step +proj=hgridshift +grids=/opt/ouf/proj/synthetic.gsb +step +proj=unitconvert +xy_in=rad +xy_out=deg";
  var controls=List.of(new UdpPorts.CrsControlPoint(.5,.5,.5-2./3600,.5+1./3600,1e-9),new UdpPorts.CrsControlPoint(1,1,1-2./3600,1+1./3600,1e-9));
  var operation=new UdpPorts.CrsOperation("SYNTHETIC-TEST-NOT-REAL-DATUM",4258,4326,pipeline,engine(),null,"Synthetic test displacement; no real datum accuracy",List.of(0.,0.,2.,2.),controls,Map.of("synthetic.gsb",grid),hash);
  var rule=new UdpPorts.GeometryRule("geometry","EPSG:4258",4326,"test-grid-v1","OPEN",new UdpPorts.CrsPolicy("XY","CONVERT",operation,null));
  var envelope=Map.<String,Object>of("crs","EPSG:4258","geoJson",Map.of("type","Point","coordinates",List.of(1.,1.)));
  var transform=new GovernedCrsTransform(db,json,4326,manifest);var result=transform.transform(envelope,rule);assertThat(result.status()).isEqualTo("OK");
  double x=db.sql("select ST_X(ST_GeomFromEWKB(decode(:g,'hex')))").param("g",result.canonicalEwkb()).query(Double.class).single();assertThat(x).isCloseTo(1-2./3600,within(1e-9));
  var changed=new UdpPorts.CrsOperation(operation.operationId(),4258,4326,pipeline,engine(),null,operation.accuracyStatement(),operation.sourceBounds(),controls,operation.requiredGrids(),"sha256:"+"0".repeat(64));
  assertThat(transform.transform(envelope,new UdpPorts.GeometryRule("geometry","EPSG:4258",4326,"v1","OPEN",new UdpPorts.CrsPolicy("XY","CONVERT",changed,null))).status()).isEqualTo("SPATIAL_TRANSFORM_RESOURCE_CHANGED");
 }
 private String engine(){return db.sql("select postgis_proj_version()").query(String.class).single().split(" ")[0];}
}
