package it.comune.trieste.ouf.udp;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/** Safe classification of read-only owner transport failures, without response bodies or credentials. */
final class PublicationAccessFailure extends IllegalStateException {
  private final boolean retryable;
  private final Instant retryNotBefore;
  private PublicationAccessFailure(String code,boolean retryable,Instant retryNotBefore){
    super(code);this.retryable=retryable;this.retryNotBefore=retryNotBefore;
  }
  boolean retryable(){return retryable;}
  Instant retryNotBefore(){return retryNotBefore;}
  static PublicationAccessFailure network(){return new PublicationAccessFailure("UDP_PUBLICATION_UNAVAILABLE",true,null);}
  static PublicationAccessFailure invalid(){return new PublicationAccessFailure("UDP_PUBLICATION_RESPONSE_INVALID",false,null);}
  static PublicationAccessFailure credential(){return new PublicationAccessFailure("UDP_PUBLICATION_CREDENTIAL_UNAVAILABLE",false,null);}
  static PublicationAccessFailure status(int status,String retryAfter,Instant now){
    if(Set.of(408,429,500,502,503,504).contains(status))
      return new PublicationAccessFailure("UDP_PUBLICATION_UNAVAILABLE",true,retryAfter(retryAfter,now));
    return new PublicationAccessFailure(status==401||status==403?"UDP_PUBLICATION_ACCESS_DENIED":
      status==404||status==410?"UDP_PUBLICATION_REFERENCE_NOT_FOUND":"UDP_PUBLICATION_HTTP_PERMANENT",false,null);
  }
  static Instant retryAfter(String value,Instant now){
    if(value==null||value.isBlank())return null;
    String text=value.strip();
    try{
      if(text.matches("[0-9]{1,18}"))return now.plusSeconds(Long.parseLong(text));
      return ZonedDateTime.parse(text,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    }catch(RuntimeException malformed){
      // An uninterpretable server admission delay must not become an immediate retry.
      throw new PublicationAccessFailure("UDP_PUBLICATION_RETRY_AFTER_INVALID",false,null);
    }
  }
}
