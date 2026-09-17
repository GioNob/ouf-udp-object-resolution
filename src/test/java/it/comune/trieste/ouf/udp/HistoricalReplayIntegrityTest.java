package it.comune.trieste.ouf.udp;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
class HistoricalReplayIntegrityTest {
 @Test void storedBytesMustMatchBothLakeChecksumAndOriginalHandoff()throws Exception{
  var json=new ObjectMapper();byte[] original="{\"handoffId\":\"old\",\"contentHash\":\"sha256:canonical\"}".getBytes(StandardCharsets.UTF_8);
  String digest="sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
  HistoricalReplayService.verifyRaw(json,original,original.length,digest,"{\"contentHash\":\"sha256:canonical\",\"handoffId\":\"old\"}");
  assertThatThrownBy(()->HistoricalReplayService.verifyRaw(json,original,original.length,"sha256:canonical",new String(original,StandardCharsets.UTF_8))).hasMessage("UDP_REPLAY_RAW_MISMATCH");
  assertThatThrownBy(()->HistoricalReplayService.verifyRaw(json,original,original.length,digest,"{\"handoffId\":\"other\"}")).hasMessage("UDP_REPLAY_RAW_MISMATCH");
  assertThatThrownBy(()->HistoricalReplayService.verifyRaw(json,original,original.length+1,digest,new String(original,StandardCharsets.UTF_8))).hasMessage("UDP_REPLAY_RAW_MISMATCH");
 }
}
