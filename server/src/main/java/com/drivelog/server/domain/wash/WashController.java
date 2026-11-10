package com.drivelog.server.domain.wash;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/wash")
class WashController {
    private final WashRecommendationService service;

    WashController(WashRecommendationService service) { this.service = service; }

    @GetMapping("/recommendation")
    WashRecommendationResponse recommendation(@RequestParam(defaultValue = "SEOUL") String region) {
        try {
            return service.recommendation(WashRegion.valueOf(region));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported region");
        }
    }
}
