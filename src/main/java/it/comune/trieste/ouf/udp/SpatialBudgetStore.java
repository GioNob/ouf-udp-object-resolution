package it.comune.trieste.ouf.udp;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SpatialBudgetStore {
  private final JdbcClient db;
  public SpatialBudgetStore(JdbcClient db){this.db=db;}

  @Transactional(timeout=1,propagation=Propagation.REQUIRES_NEW)
  public Snapshot consume(ServingAuthorizationContext auth,long estimatedRows,long resultBytes,long dbTimeMs,double radiusMeters,double areaSqKm){
    db.sql("delete from ouf_udp.query_budget where correlation_id=:c and principal_subject=:p and tenant_id=:t and workload_class='SPATIAL' and expires_at<=transaction_timestamp()")
      .param("c",auth.correlationId()).param("p",auth.subject()).param("t",auth.tenantId()).update();
    var rows=db.sql("insert into ouf_udp.query_budget(correlation_id,principal_subject,tenant_id,workload_class,expires_at,query_calls,nodes_observed,result_bytes,db_time_ms,spatial_radius_meters,spatial_area_sq_km) values(:c,:p,:t,'SPATIAL',:x,1,:rows,:bytes,:ms,:radius,:area) on conflict(correlation_id,principal_subject,tenant_id,workload_class) do update set query_calls=query_budget.query_calls+1,nodes_observed=query_budget.nodes_observed+excluded.nodes_observed,result_bytes=query_budget.result_bytes+excluded.result_bytes,db_time_ms=query_budget.db_time_ms+excluded.db_time_ms,spatial_radius_meters=query_budget.spatial_radius_meters+excluded.spatial_radius_meters,spatial_area_sq_km=query_budget.spatial_area_sq_km+excluded.spatial_area_sq_km,lock_version=query_budget.lock_version+1 where query_budget.query_calls+1<=30 and query_budget.nodes_observed+excluded.nodes_observed<=2000 and query_budget.result_bytes+excluded.result_bytes<=2097152 and query_budget.db_time_ms+excluded.db_time_ms<=15000 and query_budget.spatial_radius_meters+excluded.spatial_radius_meters<=100000 and query_budget.spatial_area_sq_km+excluded.spatial_area_sq_km<=5000 returning query_calls,nodes_observed,spatial_radius_meters,spatial_area_sq_km")
      .param("c",auth.correlationId()).param("p",auth.subject()).param("t",auth.tenantId()).param("x",OffsetDateTime.now().plusMinutes(15),Types.TIMESTAMP_WITH_TIMEZONE).param("rows",estimatedRows).param("bytes",resultBytes).param("ms",dbTimeMs).param("radius",radiusMeters).param("area",areaSqKm).query().listOfRows();
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"UDP_SPATIAL_BUDGET_EXHAUSTED");
    Map<String,Object> r=rows.getFirst();return new Snapshot(((Number)r.get("query_calls")).longValue(),((Number)r.get("nodes_observed")).longValue(),((Number)r.get("spatial_radius_meters")).doubleValue(),((Number)r.get("spatial_area_sq_km")).doubleValue());
  }
  public record Snapshot(long queryCalls,long estimatedRows,double radiusMeters,double areaSqKm){}
}
