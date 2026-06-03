package dev.nivic.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {"dev.nivic.gateway", "dev.nivic.coa"})
public class SavingGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SavingGatewayApplication.class, args);
    }
}
