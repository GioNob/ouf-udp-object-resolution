package it.comune.trieste.ouf.udp;

import java.util.*;

public interface LakeObjectStoragePort {
  StoredObject put(String logicalKey,byte[] content,String mediaType,String expectedContentHash);
  Optional<StoredObject> head(String locator);
  byte[] read(String locator);
  Collection<StoredObject> inventory();
  void delete(String locator);
  record StoredObject(String locator,String contentHash,long sizeBytes,boolean durable){}
}
