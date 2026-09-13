package it.comune.trieste.ouf.udp;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;

@Configuration
@ConditionalOnExpression("'${ouf.udp.lake.s3.bucket:}'.length() > 0")
public class S3LakeConfiguration {
  @Bean(destroyMethod="close") S3Client lakeS3Client(@Value("${ouf.udp.lake.s3.region:us-east-1}") String region,@Value("${ouf.udp.lake.s3.endpoint:}") String endpoint,@Value("${ouf.udp.lake.s3.path-style:false}") boolean pathStyle){var builder=S3Client.builder().region(Region.of(region)).credentialsProvider(DefaultCredentialsProvider.create()).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());if(endpoint!=null&&!endpoint.isBlank())builder.endpointOverride(URI.create(endpoint));return builder.build();}
}
