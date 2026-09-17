package it.comune.trieste.ouf.udp;

import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

/** Reject unauthenticated intake before request-body conversion or owner storage access. */
@Configuration
public class RuntimeIntakeBoundary implements WebMvcConfigurer {
  private final RuntimeIntakeAuthorization authorization;
  public RuntimeIntakeBoundary(RuntimeIntakeAuthorization authorization){this.authorization=authorization;}
  @Override public void addInterceptors(InterceptorRegistry registry){
    registry.addInterceptor(new HandlerInterceptor(){@Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
      String capability=request.getRequestURI().startsWith("/api/internal/v1/lake/")?"datalake.write":"udp.candidate.write";
      authorization.admit(request,capability);
      if(request.getContentLengthLong()>10_485_760)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,"UDP_INTAKE_BODY_LIMIT");return true;
    }}).addPathPatterns("/api/internal/v1/handoffs","/api/internal/v1/handoffs/**","/api/internal/v1/lake/objects");
  }
}
