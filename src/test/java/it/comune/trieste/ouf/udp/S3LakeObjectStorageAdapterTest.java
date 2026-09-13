package it.comune.trieste.ouf.udp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class S3LakeObjectStorageAdapterTest {
  @Test void putUsesPrivateProviderDefaultsAndRequiresDurabilityMetadata(){S3Client s3=mock(S3Client.class);when(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenReturn(PutObjectResponse.builder().build());when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(3L).metadata(Map.of("ouf-content-hash","sha256:abc")).build());var adapter=new S3LakeObjectStorageAdapter(s3,"bucket");var stored=adapter.put("lake/key","raw".getBytes(StandardCharsets.UTF_8),"application/json","sha256:abc");ArgumentCaptor<PutObjectRequest> request=ArgumentCaptor.forClass(PutObjectRequest.class);verify(s3).putObject(request.capture(),any(RequestBody.class));assertThat(request.getValue().acl()).isNull();assertThat(request.getValue().metadata()).containsEntry("ouf-content-hash","sha256:abc");assertThat(stored.durable()).isTrue();}
  @Test void missingHeadIsNotInventedAsDurable(){S3Client s3=mock(S3Client.class);when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());assertThat(new S3LakeObjectStorageAdapter(s3,"bucket").head("missing")).isEmpty();}
}

