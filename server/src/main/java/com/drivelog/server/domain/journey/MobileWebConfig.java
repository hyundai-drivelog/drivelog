package com.drivelog.server.domain.journey;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Spring only serves a welcome page at the root, so the mobile web's short address needs mapping. */
@Configuration
public class MobileWebConfig implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/m", "/m/");
        registry.addViewController("/m/").setViewName("forward:/m/index.html");
    }
}
