package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Exercise the shipped upgrade over populated V36 tables, inside a rollback-only schema. */
class IdentityCategoryMigrationTest {
  @Test void v37PreservesLegacyProofAndSeparatesNewObjectWrites() throws Exception {
    String schema="category_test_"+UUID.randomUUID().toString().replace("-","");
    var resources=new PathMatchingResourcePatternResolver();
    try(var connection=DriverManager.getConnection(System.getenv("OUF_UDP_DB_URL"),
        System.getenv("OUF_UDP_DB_USER"),System.getenv("OUF_UDP_DB_PASSWORD"))){
      connection.setAutoCommit(false);
      try(var sql=connection.createStatement()){
        sql.execute("create schema "+schema);
        sql.execute("set local search_path to "+schema+",public");
        // The test database may already provide PostGIS in a separate extension schema.
        sql.execute("""
            select set_config('search_path',current_setting('search_path') ||
              coalesce(',' || string_agg(quote_ident(n.nspname),','),''),true)
              from pg_extension e join pg_namespace n on n.oid=e.extnamespace
              where e.extname in ('postgis','pgcrypto')
            """);
        for(int version=1;version<=36;version++)migrate(resources,sql,schema,version);
        sql.execute("""
            insert into urban_object(urban_object_id,tenant_id,canonical_type)
              values(gen_random_uuid(),'example','urn:example:other');
            insert into identity_lookup_coverage(tenant_id,canonical_class,policy_ref,policy_version,
                coverage_ref,policy_fingerprint,field_set_hash,indexed_objects,complete)
              values('example','urn:example:poi','policy','1','original','fingerprint','shape',0,true);
            insert into identity_preactivation_attestation(attestation_id,configuration_hash,tenant_id,
                source_id,canonical_class,policy_ref,policy_version,policy_fingerprint,coverage_ref,indexed_objects,actor_subject)
              values(gen_random_uuid(),'sha256:'||repeat('0',64),'example','source','urn:example:poi',
                'policy','1','fingerprint','original',0,'reviewer')
            """);
        migrate(resources,sql,schema,37);
        assertThat(count(sql,"select count(*) from urban_object where category_iri is null")).isOne();
        assertThat(count(sql,"""
            select count(*) from identity_lookup_coverage c join identity_preactivation_attestation a
              using (tenant_id,canonical_class,policy_ref,policy_version,policy_fingerprint,coverage_ref)
              where c.complete and c.category_iri is null and a.category_iri is null and c.coverage_ref='original'
            """)).isOne();
        assertThat(sql.executeUpdate("""
            insert into identity_lookup_coverage select * from identity_lookup_coverage
              on conflict do nothing
            """)).isZero();
        sql.execute("""
            insert into urban_object(urban_object_id,tenant_id,canonical_type,category_iri)
              values(gen_random_uuid(),'example','urn:example:poi','urn:example:kiosk')
            """);
        assertThat(count(sql,"select count(*) from identity_lookup_coverage where complete")).isOne();
        sql.execute("""
            insert into urban_object(urban_object_id,tenant_id,canonical_type)
              values(gen_random_uuid(),'example','urn:example:poi')
            """);
        assertThat(count(sql,"select count(*) from identity_lookup_coverage where complete")).isZero();
        sql.execute("insert into urban_object(urban_object_id,tenant_id,canonical_type,canonical_key) values(gen_random_uuid(),'example','urn:example:poi','key')");
        assertThat(sql.executeUpdate("""
            insert into urban_object(urban_object_id,tenant_id,canonical_type,canonical_key)
              values(gen_random_uuid(),'example-other','urn:example:poi','key') on conflict do nothing
            """)).isZero();
        assertThat(sql.executeUpdate("""
            insert into urban_object(urban_object_id,tenant_id,canonical_type,canonical_key,category_iri)
              values(gen_random_uuid(),'example','urn:example:poi','key','urn:example:kiosk')
            """)).isOne();
      }finally{connection.rollback();}
    }
  }

  private static void migrate(PathMatchingResourcePatternResolver resources,Statement sql,String schema,int version)
      throws Exception {
    var files=resources.getResources("classpath*:db/migration/V"+version+"__*.sql");
    assertThat(files).hasSize(1);
    // Only substitute the owner schema to isolate this test from the runtime fixture.
    sql.execute(files[0].getContentAsString(StandardCharsets.UTF_8).replaceAll("\\bouf_udp\\b",schema));
  }

  private static long count(Statement sql,String query) throws SQLException {
    try(var rows=sql.executeQuery(query)){
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }
}
