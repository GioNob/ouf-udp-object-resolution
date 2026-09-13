package it.comune.trieste.ouf.udp;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Component
@ConditionalOnExpression("'${ouf.udp.lake.s3.bucket:}'.length() > 0")
public class S3LakeObjectStorageAdapter implements LakeObjectStoragePort {
  private static final String HASH="ouf-content-hash";
  private final S3Client s3;private final String bucket;
  public S3LakeObjectStorageAdapter(S3Client s3,@Value("${ouf.udp.lake.s3.bucket}") String bucket){this.s3=s3;this.bucket=bucket;}
  public StoredObject put(String key,byte[] content,String mediaType,String expectedHash){s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(mediaType).contentLength((long)content.length).metadata(Map.of(HASH,expectedHash)).build(),RequestBody.fromBytes(content));return head(key).orElseThrow(()->new IllegalStateException("UDP_LAKE_OBJECT_MISSING_AFTER_PUT"));}
  public Optional<StoredObject> head(String key){try{HeadObjectResponse h=s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());String hash=h.metadata().get(HASH);return Optional.of(new StoredObject(key,hash,h.contentLength(),hash!=null&&!hash.isBlank()));}catch(NoSuchKeyException e){return Optional.empty();}catch(S3Exception e){if(e.statusCode()==404)return Optional.empty();throw e;}}
  public byte[] read(String key){return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();}
  public Collection<StoredObject> inventory(){List<StoredObject> result=new ArrayList<>();String token=null;do{ListObjectsV2Response page=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).continuationToken(token).build());for(S3Object o:page.contents())head(o.key()).ifPresent(result::add);token=page.nextContinuationToken();}while(token!=null);return result;}
  public void delete(String key){s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());}
}
