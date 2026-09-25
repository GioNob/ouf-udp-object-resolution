package it.comune.trieste.ouf.udp;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Metadata is read within the query's repeatable-read snapshot; never serialize it. */
final class OwnerQueryGuard {
 private final JdbcClient db;
 OwnerQueryGuard(JdbcClient db){this.db=db;}
 boolean object(UUID id,ServingAuthorizationContext auth){return auth.owner()==null||auth.permits("urban.object.read","object",id,"OPEN",Map.of());}
 void requireObject(UUID id,ServingAuthorizationContext auth){if(!object(id,auth))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"UDP_OBJECT_NOT_AUTHORIZED");}
 Map<UUID,Boolean> edges(String capability,Collection<UUID> ids,ServingAuthorizationContext auth){
  var allowed=new HashMap<UUID,Boolean>();if(auth.owner()==null){ids.forEach(id->allowed.put(id,true));return allowed;}if(ids.isEmpty())return allowed;
  var rows=db.sql("select r.relationship_id,r.source_object_id,r.target_object_id,r.relation_iri,v.access_label,h.source_id,h.ingestion_run_id from ouf_udp.urban_relationship r join ouf_udp.relationship_revision v on v.relationship_revision_id=r.current_revision_id join ouf_udp.relationship_contribution c on c.contribution_id=v.contribution_id join ouf_udp.handoff_intake h on h.handoff_id=c.handoff_id where r.relationship_id in (:ids)").param("ids",new HashSet<>(ids)).query().listOfRows();
  for(var row:rows){UUID id=(UUID)row.get("relationship_id");var scope=Map.of("sourceObjectRef",row.get("source_object_id").toString(),"targetObjectRef",row.get("target_object_id").toString(),"relationIri",row.get("relation_iri").toString(),"sourceRef",row.get("source_id").toString(),"jobRef",row.get("ingestion_run_id").toString());
   boolean permit=object((UUID)row.get("source_object_id"),auth)&&object((UUID)row.get("target_object_id"),auth)&&auth.permits(capability,"relationship",id,(String)row.get("access_label"),scope);allowed.put(id,permit);if(!permit)audit(auth,capability,"relationship://"+id,(String)row.get("access_label"));
  }return allowed;
 }
 Map<UUID,Boolean> geometries(String capability,Collection<UUID> ids,ServingAuthorizationContext auth){
  var allowed=new HashMap<UUID,Boolean>();if(auth.owner()==null){ids.forEach(id->allowed.put(id,true));return allowed;}if(ids.isEmpty())return allowed;
  var rows=db.sql("select c.urban_object_id,g.access_label,h.source_id,h.ingestion_run_id from ouf_udp.urban_geometry_active c join ouf_udp.urban_geometry g on g.geometry_revision_id=c.geometry_revision_id join ouf_udp.handoff_intake h on h.handoff_id=g.handoff_id join ouf_udp.urban_object o on o.urban_object_id=c.urban_object_id where c.urban_object_id in (:ids) and o.tenant_id=:tenant").param("ids",new HashSet<>(ids)).param("tenant",auth.tenantId()).query().listOfRows();
  for(var row:rows){UUID id=(UUID)row.get("urban_object_id");var scope=Map.of("projection","geometry","sourceRef",row.get("source_id").toString(),"jobRef",row.get("ingestion_run_id").toString());String label=(String)row.get("access_label");boolean permit=object(id,auth)&&auth.permits("urban.geometry.read","object",id,label,scope)&&auth.permits(capability,"object",id,label,scope);allowed.put(id,permit);if(!permit)audit(auth,capability,"geometry://"+id,label);}
  return allowed;
 }
 void requireGeometry(String capability,UUID id,ServingAuthorizationContext auth){if(!Boolean.TRUE.equals(geometries(capability,List.of(id),auth).get(id)))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"UDP_GEOMETRY_NOT_AUTHORIZED");}
 private void audit(ServingAuthorizationContext auth,String cap,String resource,String label){db.sql("insert into ouf_udp.serving_access_audit(audit_id,principal_type,principal_subject,tenant_id,capability,resource_ref,classification,outcome,authorization_decision_ref,correlation_id) values(gen_random_uuid(),:p,:s,:t,:c,:r,:l,'REDACTED',:d,:x)").param("p",auth.principalType()).param("s",auth.subject()).param("t",auth.tenantId()).param("c",cap).param("r",resource).param("l",label==null?"UNRESOLVED":label).param("d",auth.decisionRef(cap)).param("x",auth.correlationId()).update();}
}
