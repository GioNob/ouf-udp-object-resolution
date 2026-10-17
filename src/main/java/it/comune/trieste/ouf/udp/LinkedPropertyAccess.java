package it.comune.trieste.ouf.udp;

import java.util.*;

/** All provenance supporting a displayed member is authorized; labels are not ranked. */
final class LinkedPropertyAccess {
  private LinkedPropertyAccess(){}
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
