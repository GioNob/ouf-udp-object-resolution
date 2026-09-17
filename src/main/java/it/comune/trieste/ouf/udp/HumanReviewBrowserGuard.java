package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Synchronizer token bound to the verified human, never to caller-supplied identity headers. */
@Component
public class HumanReviewBrowserGuard {
  private final String origin;
  public HumanReviewBrowserGuard(@Value("${ouf.ths.origin:}") String origin){this.origin=origin;}
  public String token(HttpServletRequest request){
    var human=TrustedHumanApi.trusted(request);human.require("resolution.issue.read");checkOrigin(request);
    var session=request.getSession(true);String binding=human.tenantId()+"\n"+human.subject();
    if(!binding.equals(session.getAttribute("ouf.review.subject"))){session.setAttribute("ouf.review.subject",binding);session.setAttribute("ouf.review.csrf",UUID.randomUUID()+"-"+UUID.randomUUID());}
    return (String)session.getAttribute("ouf.review.csrf");
  }
  public void require(HttpServletRequest request){
    var human=TrustedHumanApi.trusted(request);human.require("authority.override");checkOrigin(request);
    var session=request.getSession(false);String supplied=request.getHeader("X-OUF-CSRF");
    if(session==null||supplied==null||!Objects.equals(session.getAttribute("ouf.review.subject"),human.tenantId()+"\n"+human.subject()))throw denied();
    Object expected=session.getAttribute("ouf.review.csrf");
    if(!(expected instanceof String token)||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw denied();
  }
  private void checkOrigin(HttpServletRequest request){
    String source=request.getHeader("Origin"),site=request.getHeader("Sec-Fetch-Site");
    if(source!=null&&(origin.isBlank()||!origin.equals(source)))throw denied();
    if(site!=null&&!Set.of("same-origin","none").contains(site))throw denied();
  }
  private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"UDP_HUMAN_REVIEW_CSRF_DENIED");}
}
