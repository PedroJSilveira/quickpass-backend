package br.com.quickpass;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class QuickpassApplication {
    public static void main(String[] args) {
        SpringApplication.run(QuickpassApplication.class, args);
    }
}
