package it.comune.trieste.ouf.udp;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds chunked as well as declared-length bodies before JSON allocation. */
@Component @Order(0)
public class RuntimeIntakeSizeFilter extends OncePerRequestFilter {
  private static final long LIMIT=10_485_760;
  @Override protected boolean shouldNotFilter(HttpServletRequest r){return !(r.getRequestURI().equals("/api/internal/v1/handoffs")||r.getRequestURI().equals("/api/internal/v1/lake/objects"));}
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
    if(request.getContentLengthLong()>LIMIT){response.sendError(413);return;}
    chain.doFilter(new HttpServletRequestWrapper(request){
      private ServletInputStream bounded;
      @Override public ServletInputStream getInputStream()throws IOException{
        if(bounded==null){var input=super.getInputStream();bounded=new ServletInputStream(){
          private long count;
          private void received(int n)throws IOException{if(n>0&&((count+=n)>LIMIT))throw new IOException("UDP_INTAKE_BODY_LIMIT");}
          @Override public int read()throws IOException{int n=input.read();received(n<0?0:1);return n;}
          @Override public int read(byte[] b,int off,int len)throws IOException{int n=input.read(b,off,(int)Math.min(len,LIMIT-count+1));received(n);return n;}
          @Override public boolean isFinished(){return input.isFinished();}@Override public boolean isReady(){return input.isReady();}@Override public void setReadListener(ReadListener listener){input.setReadListener(listener);}
          @Override public void close()throws IOException{input.close();}
        };}return bounded;
      }
    },response);
  }
}
