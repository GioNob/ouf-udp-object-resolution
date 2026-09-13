package it.comune.trieste.ouf.udp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UdpApplication {
  public static void main(String[] args){SpringApplication.run(UdpApplication.class,args);}
}
