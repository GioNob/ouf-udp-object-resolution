package it.comune.trieste.ouf.udp;

import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class HumanReviewSessionConfiguration {
  @Bean ServletContextInitializer reviewCookie(){return context->{var cookie=context.getSessionCookieConfig();cookie.setName("OUF_REVIEW_SESSION");cookie.setHttpOnly(true);cookie.setSecure(true);cookie.setPath("/api/udp/v1/governance");cookie.setAttribute("SameSite","Strict");};}
}
