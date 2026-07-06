package com.metabion;

import com.metabion.config.OAuthAuthorizationProperties;
import com.metabion.service.DietLogPhotoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableConfigurationProperties({DietLogPhotoProperties.class, OAuthAuthorizationProperties.class})
@EnableScheduling
@SpringBootApplication
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
