package it.comune.trieste.ouf.udp;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Acquires the same lock as the urban_object write trigger before snapshot retrieval. */
@Repository
public class GovernedIdentityScopeLock {
  private final JdbcClient db;
  public GovernedIdentityScopeLock(JdbcClient db){this.db=db;}
  public void acquire(String tenant,String canonicalClass){
    if(!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("UDP_IDENTITY_TRANSACTION_REQUIRED");
    if(tenant==null||tenant.isBlank()||canonicalClass==null||canonicalClass.isBlank())
      throw new IllegalArgumentException("UDP_IDENTITY_SCOPE_INVALID");
    db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))")
        .param("key","identity:"+tenant+":"+canonicalClass).query().singleRow();
  }
}
