package dev.nivic.blockchain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BlockchainListenerApp {

  public static void main(String[] args) {
    SpringApplication.run(BlockchainListenerApp.class, args);
  }
}
