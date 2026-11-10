package com.drivelog.server.domain.journey;

import com.drivelog.server.domain.journey.JourneyDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class JourneyController {
    private static final int MAX_CLAIMS_PER_MINUTE = 10;
    private static final int MAX_PHOTO_BYTES = 15 * 1024 * 1024;

    private final JourneyService service;
    private final Map<String, long[]> claimAttempts = new ConcurrentHashMap<>();

    public JourneyController(JourneyService service) {
        this.service = service;
    }

    @PostMapping("/link/codes")
    public LinkCodeResponse createCode() { return service.createLinkCode(); }

    @GetMapping("/link/codes/{pollToken}")
    public LinkPollResponse poll(@PathVariable String pollToken) { return service.pollLink(pollToken); }

    /** Six digits are guessable without a limit, so claims are throttled per client address. */
    @PostMapping("/link/claim")
    public LinkClaimResponse claim(@Valid @RequestBody LinkClaimRequest request, HttpServletRequest http,
                                   @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        long minute = Instant.now().getEpochSecond() / 60;
        long[] window = claimAttempts.compute(http.getRemoteAddr(), (key, current) ->
                current == null || current[0] != minute ? new long[]{minute, 1} : new long[]{minute, current[1] + 1});
        if (window[1] > MAX_CLAIMS_PER_MINUTE) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도해 주세요.");
        if (claimAttempts.size() > 10_000) claimAttempts.clear();
        return service.claimLink(request.code(), authorization);
    }

    @GetMapping("/journey")
    public JourneyState state(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return service.state(service.accountFor(auth));
    }

    @GetMapping("/stamps")
    public ResponseEntity<java.util.List<StampCollection.Visit>> stamps(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.stamps(service.accountFor(auth)));
    }

    @GetMapping("/journey/vehicle")
    public ResponseEntity<JourneyState> vehicleState(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.vehicleState(service.accountFor(auth)));
    }

    @PutMapping("/goals/{goalId}")
    public GoalView putGoal(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                            @PathVariable String goalId, @Valid @RequestBody GoalPutRequest request) {
        if (goalId.length() > 36) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid id");
        return service.putGoal(service.accountFor(auth), goalId, request);
    }

    @PatchMapping("/goals/{goalId}")
    public GoalView renameGoal(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                               @PathVariable String goalId, @RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "").trim();
        if (name.isEmpty() || name.length() > 60) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid name");
        return service.renameGoal(service.accountFor(auth), goalId, name);
    }

    @PutMapping("/goals/{goalId}/places/{placeId}")
    public GoalView replacePlace(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                 @PathVariable String goalId, @PathVariable String placeId,
                                 @Valid @RequestBody PlaceInput place) {
        return service.replacePlace(service.accountFor(auth), goalId, placeId, place);
    }

    @DeleteMapping("/goals/{goalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGoal(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                           @PathVariable String goalId) {
        service.deleteGoal(service.accountFor(auth), goalId);
    }

    @PostMapping("/goals/{goalId}/archive")
    public GoalView archive(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                            @PathVariable String goalId) {
        return service.archiveGoal(service.accountFor(auth), goalId);
    }

    @PutMapping("/records/{recordId}")
    public RecordView upsert(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                             @PathVariable String recordId, @Valid @RequestBody RecordUpsertRequest request) {
        if (recordId.length() > 64) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid id");
        return service.upsertRecord(service.accountFor(auth), recordId, request);
    }

    @PutMapping("/records/{recordId}/visit")
    public GoalView confirmVisit(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                 @PathVariable String recordId) {
        return service.confirmVisit(service.accountFor(auth), recordId);
    }

    @DeleteMapping("/records/{recordId}/visit")
    public GoalView cancelVisit(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                @PathVariable String recordId) {
        return service.cancelVisit(service.accountFor(auth), recordId);
    }

    @PutMapping("/records/{recordId}/card")
    public RecordView updateCard(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                 @PathVariable String recordId, @Valid @RequestBody CardUpdateRequest request) {
        return service.updateCard(service.accountFor(auth), recordId, request);
    }

    @DeleteMapping("/records/{recordId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRecord(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                             @PathVariable String recordId) {
        service.deleteRecord(service.accountFor(auth), recordId);
    }

    @PutMapping(value = "/records/{recordId}/photo", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadPhoto(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                            @PathVariable String recordId, @RequestBody byte[] data) {
        if (data.length > MAX_PHOTO_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "사진은 15MB 이하만 가능합니다.");
        service.storePhoto(service.accountFor(auth), recordId, data);
    }

    @GetMapping("/records/{recordId}/photo")
    public ResponseEntity<byte[]> photo(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                        @PathVariable String recordId) {
        JourneyService.StoredPhoto photo = service.loadPhoto(service.accountFor(auth), recordId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType()))
                .cacheControl(CacheControl.noStore()).body(photo.data());
    }

    @DeleteMapping("/records/{recordId}/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePhoto(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                            @PathVariable String recordId) {
        service.deletePhoto(service.accountFor(auth), recordId);
    }
}
