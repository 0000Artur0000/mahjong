package ru.dorahub.system;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SystemConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}

