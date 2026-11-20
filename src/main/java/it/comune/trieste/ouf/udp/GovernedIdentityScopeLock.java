package it.comune.trieste.ouf.udp;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Uses the urban_object trigger's lock identity and ordering for multi-object decisions. */
@Repository
public class GovernedIdentityScopeLock {
  private final JdbcClient db;
  public GovernedIdentityScopeLock(JdbcClient db){this.db=db;}

  record Scope(String tenant,String canonicalClass,String categoryIri) {
    Scope {
      if(tenant==null||tenant.isBlank()||canonicalClass==null||canonicalClass.isBlank())
        throw new IllegalArgumentException("UDP_IDENTITY_SCOPE_INVALID");
    }
    static Scope object(Map<String,Object> row){
      return new Scope((String)row.get("tenant_id"),(String)row.get("canonical_type"),(String)row.get("category_iri"));
    }
  }

  public void acquire(String tenant,String canonicalClass){acquire(tenant,canonicalClass,null);}

  public void acquire(String tenant,String canonicalClass,String categoryIri){
    requireTransaction();
    lockQuery(new Scope(tenant,canonicalClass,categoryIri),true).query().singleRow();
  }

  void acquireAll(Set<Scope> scopes){
    requireTransaction();
    Set<Long> ordered=new TreeSet<>();
    for(var scope:scopes)ordered.add(lockQuery(scope,false).query(Long.class).single());
    for(long id:ordered)db.sql("select pg_advisory_xact_lock(:id)").param("id",id).query().singleRow();
  }

  private JdbcClient.StatementSpec lockQuery(Scope scope,boolean acquire){
    String key="ouf_udp.identity_scope_lock_id(:tenant,:type,:category)";
    return db.sql(acquire?"select pg_advisory_xact_lock("+key+")":"select "+key)
        .param("tenant",scope.tenant()).param("type",scope.canonicalClass())
        .param("category",scope.categoryIri(),java.sql.Types.VARCHAR);
  }

  private static void requireTransaction(){
    if(!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("UDP_IDENTITY_TRANSACTION_REQUIRED");
  }
}
