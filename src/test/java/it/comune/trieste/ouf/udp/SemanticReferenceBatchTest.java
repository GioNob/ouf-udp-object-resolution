package it.comune.trieste.ouf.udp;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class SemanticReferenceBatchTest {
  @TempDir Path temp;

  @Test void completePublicationUsesOneDownloadAndTwoAuthenticatedBatches() throws Exception {
    try(var peer=new ReferenceBatchFixture(temp,1024,"base",2*1024*1024)){
      assertThat(peer.envelope.length).isBetween(2097153,3145728);
      var client=peer.client();
      assertThat(client.resolveContracts(peer.refs).ready()).isTrue();
      assertThat(peer.publications).hasValue(1);
      assertThat(peer.sizes).containsExactly(1000,24);
      assertThat(peer.bytes).allMatch(n->n<=262144);
      assertThat(peer.legacyReads).hasValue(0);
      peer.fault="revoked";peer.faultAt=1023;
      assertThatThrownBy(()->client.resolveContracts(peer.refs))
          .isInstanceOfSatisfying(SemanticReferenceFailure.class,e->{
            assertThat(e.getMessage()).isEqualTo("UDP_SEMANTIC_REFERENCE_MISMATCH");
            assertThat(e.detail).containsEntry("index",1023).containsEntry("semanticId",peer.id(1023));
          });
      var smaller=peer.client(new ReferenceBatchSettings(262144,1000,2049,2097152,3,1,1));
      assertThatThrownBy(()->smaller.resolveContracts(peer.refs)).hasMessage("UDP_PUBLICATION_RESPONSE_INVALID");
      assertThat(peer.sizes).hasSize(4);
    }
  }

  @Test void utf8ByteLimitAnd413HalvingCoverEveryTuple() throws Exception {
    try(var peer=new ReferenceBatchFixture(temp,1024,"é".repeat(24),0)){
      var client=peer.client(new ReferenceBatchSettings(16384,1000,2049,3145728,3,1,1));
      peer.maxBytes=6000;
      assertThat(client.resolveContracts(peer.refs).ready()).isTrue();
      assertThat(peer.bytes).allMatch(n->n<=16384);
      assertThat(peer.starts.subList(0,3)).containsExactly(0,0,0);
      assertThat(peer.sizes.get(1)).isEqualTo(peer.sizes.get(0)/2);
      int verified=0;
      for(int i=0;i<peer.sizes.size();i++)if(peer.bytes.get(i)<=peer.maxBytes){
        assertThat(peer.starts.get(i)).isEqualTo(verified);
        verified+=peer.sizes.get(i);
      }
      assertThat(verified).isEqualTo(1024);
    }
  }

  @Test void registryDriftRestartsAtZeroWithinFiniteBudget() throws Exception {
    try(var peer=new ReferenceBatchFixture(temp,1024,"marker",0)){
      var client=peer.client();peer.versionMode="once";
      assertThat(client.resolveContracts(peer.refs).ready()).isTrue();
      assertThat(peer.starts).containsExactly(0,1000,0,1000);
      assertThat(peer.publications).hasValue(1);
      peer.versionMode="always";peer.starts.clear();
      assertThatThrownBy(()->client.resolveContracts(peer.refs)).hasMessage("UDP_SEMANTIC_REGISTRY_VERSION_UNSTABLE");
      assertThat(peer.starts).containsExactly(0,1000,0,1000,0,1000);
      assertThat(peer.legacyReads).hasValue(0);
    }
  }

  @Test void absenceOfBatchEndpointRestartsWithExactLegacyReads() throws Exception {
    try(var peer=new ReferenceBatchFixture(temp,33,"legacy",0)){
      var client=peer.client();
      for(int code:new int[]{404,405}){
        peer.unsupported=code;
        assertThat(client.resolveContracts(peer.refs).ready()).isTrue();
        assertThat(peer.legacyReads.getAndSet(0)).isEqualTo(33);
      }
      peer.missing=32;
      assertThatThrownBy(()->client.resolveContracts(peer.refs)).hasMessage("UDP_SEMANTIC_REFERENCE_UNRESOLVABLE");
    }
  }

  @Test void invalidResultsAndOversizeSingletonFailClosed() throws Exception {
    try(var peer=new ReferenceBatchFixture(temp,3,"bad",0)){
      var client=peer.client();peer.faultAt=1;
      for(String fault:new String[]{"omit","duplicate","index","tuple","version"}){
        peer.fault=fault;
        assertThatThrownBy(()->client.resolveContracts(peer.refs))
            .hasMessage(fault.equals("tuple")||fault.equals("version")?"UDP_SEMANTIC_REFERENCE_MISMATCH":"UDP_PINNED_PROFILE_INVALID");
      }
      peer.maxBytes=1;
      assertThatThrownBy(()->client.resolveContracts(peer.refs)).hasMessage("UDP_SEMANTIC_REFERENCE_TOO_LARGE");
      int calls=peer.starts.size();
      var tiny=peer.client(new ReferenceBatchSettings(5,1000,2049,3145728,3,1,1));
      assertThatThrownBy(()->tiny.resolveContracts(peer.refs)).hasMessage("UDP_SEMANTIC_REFERENCE_TOO_LARGE");
      var few=peer.client(new ReferenceBatchSettings(262144,1000,2,3145728,3,1,1));
      assertThatThrownBy(()->few.resolveContracts(peer.refs)).hasMessage("UDP_PINNED_PROFILE_INVALID");
      assertThat(peer.starts).hasSize(calls);
      assertThat(peer.legacyReads).hasValue(0);
    }
  }

  @Test void conceptMembershipIsProvenByTheBatchWithoutPerElementReads(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
    try(var peer=new ReferenceBatchFixture(temp,1024,"concept",0)){
      peer.kind="CONCEPT";
      var limits=new ReferenceBatchSettings(262144,1000,2049,3145728,3,1,1);
      var verified=new SemanticReferenceBatch(peer.client(limits),new com.fasterxml.jackson.databind.ObjectMapper(),limits,peer.pins,true).verify();
      assertThat(verified).isNotNull();
      assertThat(peer.sizes).containsExactly(1000,24);
      assertThat(peer.legacyReads).hasValue(0);
      // A CONCEPT result without its definition is not a membership proof: the pin is rejected, no GET.
      peer.conceptDefinition=false;
      assertThatThrownBy(()->new SemanticReferenceBatch(peer.client(limits),new com.fasterxml.jackson.databind.ObjectMapper(),limits,peer.pins,true).verify())
          .isInstanceOfSatisfying(SemanticReferenceFailure.class,e->{
            assertThat(e.getMessage()).isEqualTo("UDP_SEMANTIC_REFERENCE_MISMATCH");
            assertThat(e.detail).containsEntry("index",0);
          });
      assertThat(peer.legacyReads).hasValue(0);
    }
  }
}
