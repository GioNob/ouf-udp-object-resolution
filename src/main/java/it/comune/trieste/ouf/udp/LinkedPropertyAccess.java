package it.comune.trieste.ouf.udp;

import java.util.*;
import java.time.OffsetDateTime;

/** All provenance supporting a displayed member is authorized; labels are not ranked. */
final class LinkedPropertyAccess {
  private LinkedPropertyAccess(){}
  /** Current projections cannot continue serving an expired member between scheduler runs. */
  static boolean validAt(Object raw,OffsetDateTime now){
    if(raw==null)return true;
    Object decoded=LinkedPropertyReconciler.decode(raw);
    if(!(decoded instanceof Map<?,?> evidence)||!(evidence.get("rule") instanceof Map<?,?> rule)||!(rule.get("reconciliation") instanceof Map<?,?> config))return false;
    if("UNBOUNDED".equals(config.get("validity")))return true;
    if(!"SOURCE_INTERVAL".equals(config.get("validity"))||!(evidence.get("members") instanceof List<?> members)||members.isEmpty())return false;
    for(Object m:members){if(!(m instanceof Map<?,?> member)||!(member.get("contributions") instanceof List<?> contributions))return false;
      boolean valid=false;
      for(Object c:contributions){if(!(c instanceof Map<?,?> contribution)||!(contribution.get("provenance") instanceof Map<?,?> provenance)||!(provenance.get("sourceIdentity") instanceof Map<?,?> source))return false;
        try{OffsetDateTime from=source.get("validFrom")==null?null:OffsetDateTime.parse(source.get("validFrom").toString()),to=source.get("validTo")==null?null:OffsetDateTime.parse(source.get("validTo").toString());
          if((from==null||!now.isBefore(from))&&(to==null||now.isBefore(to)))valid=true;
        }catch(RuntimeException invalid){return false;}
      }
      if(!valid)return false;
    }return true;
  }
  @SuppressWarnings("unchecked") static boolean permits(Object raw,UUID object,UUID revision,String property,String capability,ServingAuthorizationContext auth){
    if(raw==null)return true;
    Object decoded=LinkedPropertyReconciler.decode(raw);
    if(!(decoded instanceof Map<?,?> evidence)||!(evidence.get("members") instanceof List<?> members)||members.isEmpty())return false;
    for(Object m:members){if(!(m instanceof Map<?,?> member)||!(member.get("contributions") instanceof List<?> contributions)||contributions.isEmpty())return false;
      for(Object c:contributions){if(!(c instanceof Map<?,?> contribution)||!(contribution.get("provenance") instanceof Map<?,?> provenance))return false;
        Object label=contribution.get("accessLabel"),source=contribution.get("sourceId"),run=provenance.get("ingestionRunId");
        if(!(label instanceof String)||source==null||run==null)return false;
        if(!auth.permits(capability,"object",object,(String)label,Map.of("sourceRef",source.toString(),"jobRef",run.toString(),"propertyIri",property,"revisionRef",revision.toString(),"projection","properties")))return false;
      }
    }return true;
  }
}
