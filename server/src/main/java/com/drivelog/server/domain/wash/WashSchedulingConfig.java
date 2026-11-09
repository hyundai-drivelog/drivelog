package com.drivelog.server.domain.wash;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
class WashSchedulingConfig {
    @Bean
    Clock washClock() { return Clock.system(ZoneId.of("Asia/Seoul")); }
}
