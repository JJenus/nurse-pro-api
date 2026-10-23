package com.surf.nursepro.nurse_pro_api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surf.nursepro.nurse_pro_api.entity.Nurse;
import com.surf.nursepro.nurse_pro_api.enums.ExperienceLevel;
import com.surf.nursepro.nurse_pro_api.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests over HTTP against a real PostgreSQL (the one CI provides; locally use the dev profile).
 * They cover what unit tests cannot: database constraints, transactions and concurrency.
 * Months in 2031 are used so "past dates" rules never get in the way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleApiIntegrationTest {

    private static final int YEAR = 2031;
    private static final int MONTH = 11;

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired NurseRepository nurses;
    @Autowired ScheduleRepository schedules;
    @Autowired ShiftRepository shifts;
    @Autowired SwapRequestRepository swaps;
    @Autowired ScheduleConflictRepository conflicts;
    @Autowired WorkloadDataRepository workload;
    @Autowired ScheduleChangeRepository changes;
    @Autowired IdempotencyKeyRepository idempotencyKeys;

    private List<String> nurseIds;

    @BeforeEach
    void freshData() {
        swaps.deleteAll();
        conflicts.deleteAll();
        workload.deleteAll();
        changes.deleteAll();
        idempotencyKeys.deleteAll();
        schedules.deleteAll();   // cascades to the schedule's shifts
        shifts.deleteAll();
        nurses.deleteAll();

        nurseIds = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            Nurse n = new Nurse();
            n.setFirstName("Nurse");
            n.setLastName(String.format("%02d", i));
            n.setEmail("nurse" + i + "@example.test");
            n.setDepartment("General");
            n.setExperienceLevel(ExperienceLevel.values()[0]);
            n.setMaxHoursPerWeek(40);
            n.setSpecializations(new ArrayList<>());
            n.setPreferredShifts(new ArrayList<>());
            n.setUnavailableDates(new ArrayList<>());
            nurseIds.add(nurses.save(n).getId());
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private Map<String, Object> generateBody(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>();
        body.put("month", MONTH);
        body.put("year", YEAR);
        body.put("seed", 7);
        body.putAll(extra);
        return body;
    }

    private ResponseEntity<String> generate(Map<String, Object> extra, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return http.exchange("/api/schedules/generate", HttpMethod.POST,
                new HttpEntity<>(generateBody(extra), headers), String.class);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return json.readTree(response.getBody()).get("data");
    }

    private <T> ResponseEntity<String> send(String url, HttpMethod method, T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    private String generateSchedule() throws Exception {
        ResponseEntity<String> response = generate(Map.of(), null);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return data(response).get("schedule").get("id").asText();
    }

    // ---- tests -------------------------------------------------------------------------------------------

    @Test
    void generatingTheSameMonthConcurrentlyCreatesExactlyOneSchedule() throws Exception {
        int callers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatusCode>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return generate(Map.of(), null).getStatusCode();
            }));
        }
        start.countDown();

        int ok = 0;
        int conflict = 0;
        for (Future<HttpStatusCode> f : results) {
            HttpStatusCode status = f.get(60, TimeUnit.SECONDS);
            if (status.value() == 200) ok++;
            else if (status.value() == 409) conflict++;
            else fail("unexpected status " + status);
        }
        pool.shutdown();

        assertEquals(1, ok, "exactly one caller creates the schedule");
        assertEquals(callers - 1, conflict);
        assertEquals(1, schedules.count());
    }

    @Test
    void retryWithTheSameIdempotencyKeyReturnsTheFirstSchedule() throws Exception {
        ResponseEntity<String> first = generate(Map.of(), "key-1");
        ResponseEntity<String> retry = generate(Map.of(), "key-1");

        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        assertEquals(HttpStatus.OK, retry.getStatusCode(), retry.getBody());
        assertEquals(data(first).get("schedule").get("id").asText(), data(retry).get("schedule").get("id").asText());
        assertEquals(1, schedules.count());

        ResponseEntity<String> differentRequest = generate(Map.of("seed", 99), "key-1");
        assertEquals(HttpStatus.CONFLICT, differentRequest.getStatusCode(), "same key, different request");
    }

    @Test
    void previewSavesNothingAndTheSameSeedGivesTheSameScheduleWhenSaved() throws Exception {
        ResponseEntity<String> preview = generate(Map.of("preview", true), null);
        assertEquals(HttpStatus.OK, preview.getStatusCode(), preview.getBody());
        assertEquals(0, schedules.count(), "a preview must not save anything");
        JsonNode previewData = data(preview);
        assertEquals(30 * 3, previewData.get("schedule").get("shifts").size());
        long seed = previewData.get("seed").asLong();

        ResponseEntity<String> saved = generate(Map.of("seed", seed), null);
        assertEquals(HttpStatus.OK, saved.getStatusCode(), saved.getBody());
        assertEquals(1, schedules.count());

        // same shifts and nurses as the preview
        List<String> fromPreview = new ArrayList<>();
        previewData.get("schedule").get("shifts").forEach(s -> fromPreview.add(describe(s)));
        List<String> fromSaved = new ArrayList<>();
        data(saved).get("schedule").get("shifts").forEach(s -> fromSaved.add(describe(s)));
        Collections.sort(fromPreview);
        Collections.sort(fromSaved);
        assertEquals(fromPreview, fromSaved);
    }

    private String describe(JsonNode shift) {
        List<String> nurses = new ArrayList<>();
        shift.get("assignedNurses").forEach(n -> nurses.add(n.asText()));
        Collections.sort(nurses);
        return shift.get("date").asText() + "|" + shift.get("type").asText() + "|" + nurses;
    }

    @Test
    void anExistingDraftIsOnlyReplacedWhenAskedTo() throws Exception {
        String first = generateSchedule();

        assertEquals(HttpStatus.CONFLICT, generate(Map.of(), null).getStatusCode());
        assertEquals(1, schedules.count());

        ResponseEntity<String> replaced = generate(Map.of("replaceDraft", true), null);
        assertEquals(HttpStatus.OK, replaced.getStatusCode(), replaced.getBody());
        assertEquals(1, schedules.count());
        assertNotEquals(first, data(replaced).get("schedule").get("id").asText());
    }

    @Test
    void aSecondShiftInTheSameSlotIsRejected() throws Exception {
        generateSchedule();
        Map<String, Object> duplicate = new HashMap<>();
        duplicate.put("date", String.format("%d-%02d-10", YEAR, MONTH));
        duplicate.put("startTime", "07:00");
        duplicate.put("endTime", "15:00");
        duplicate.put("type", "Day");
        duplicate.put("department", "General");
        duplicate.put("requiredStaff", 2);
        duplicate.put("assignedNurses", List.of());

        ResponseEntity<String> response = send("/api/schedules/shifts", HttpMethod.POST, duplicate);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode(), response.getBody());
    }

    @Test
    void approvingASwapTwiceAtTheSameTimeSwapsOnlyOnce() throws Exception {
        String scheduleId = generateSchedule();
        JsonNode schedule = data(http.getForEntity("/api/schedules/" + scheduleId, String.class));

        // a shift in the middle of the month, one of its nurses (the requester) and a nurse who is free around it (the target)
        JsonNode shift = null;
        String requester = null;
        String target = null;
        for (JsonNode candidate : schedule.get("shifts")) {
            if (!"Day".equals(candidate.get("type").asText()) || candidate.get("assignedNurses").isEmpty()) continue;
            LocalDate date = LocalDate.parse(candidate.get("date").asText());
            if (date.getDayOfMonth() < 8 || date.getDayOfMonth() > 22) continue;
            Set<String> busy = new HashSet<>();
            for (JsonNode other : schedule.get("shifts")) {
                long gap = Math.abs(LocalDate.parse(other.get("date").asText()).toEpochDay() - date.toEpochDay());
                if (gap <= 2) other.get("assignedNurses").forEach(n -> busy.add(n.asText()));
            }
            Optional<String> free = nurseIds.stream().filter(id -> !busy.contains(id)).findFirst();
            if (free.isPresent()) {
                shift = candidate;
                requester = candidate.get("assignedNurses").get(0).asText();
                target = free.get();
                break;
            }
        }
        assertNotNull(shift, "test data should contain a shift with a free nurse around it");
        String shiftId = shift.get("id").asText();

        Map<String, Object> swap = new HashMap<>();
        swap.put("requesterId", requester);
        swap.put("targetId", target);
        swap.put("shiftId", shiftId);
        swap.put("reason", "integration test");
        ResponseEntity<String> created = send("/api/schedules/swap-requests", HttpMethod.POST, swap);
        assertEquals(HttpStatus.OK, created.getStatusCode(), created.getBody());
        String swapId = data(created).get("id").asText();

        // the same request again while one is pending is a conflict (one pending swap per shift and requester)
        assertEquals(HttpStatus.CONFLICT, send("/api/schedules/swap-requests", HttpMethod.POST, swap).getStatusCode());

        // the check endpoint says it can be approved
        JsonNode check = data(http.getForEntity("/api/schedules/swap-requests/" + swapId + "/check", String.class));
        assertTrue(check.get("canApprove").asBoolean(), check.toString());

        int callers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatusCode>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return http.exchange("/api/schedules/swap-requests/" + swapId + "/approve", HttpMethod.PUT,
                        HttpEntity.EMPTY, String.class).getStatusCode();
            }));
        }
        start.countDown();
        for (Future<HttpStatusCode> f : results) {
            assertEquals(200, f.get(60, TimeUnit.SECONDS).value(), "every approve call succeeds; later ones are no-ops");
        }
        pool.shutdown();

        JsonNode after = data(http.getForEntity("/api/schedules/" + scheduleId, String.class));
        for (JsonNode s : after.get("shifts")) {
            if (s.get("id").asText().equals(shiftId)) {
                Set<String> assigned = new HashSet<>();
                s.get("assignedNurses").forEach(n -> assigned.add(n.asText()));
                assertTrue(assigned.contains(target));
                assertFalse(assigned.contains(requester));
            }
        }
        assertEquals(1, shifts.findById(shiftId).orElseThrow().getAssignedNurses().stream().filter(target::equals).count());

        // an approved request can no longer be rejected
        assertEquals(HttpStatus.CONFLICT, http.exchange("/api/schedules/swap-requests/" + swapId + "/reject",
                HttpMethod.PUT, HttpEntity.EMPTY, String.class).getStatusCode());
    }

    @Test
    void rescheduleNeedsAFreshPreviewAndRemovesTheExcludedNurse() throws Exception {
        String scheduleId = generateSchedule();
        String excluded = nurseIds.get(0);
        String from = String.format("%d-%02d-15", YEAR, MONTH);

        Map<String, Object> request = new HashMap<>();
        request.put("fromDate", from);
        request.put("mode", "fill_gaps");
        request.put("excludeNurseIds", List.of(excluded));
        request.put("reason", "integration test");

        ResponseEntity<String> preview = send("/api/schedules/" + scheduleId + "/reschedule", HttpMethod.POST, request);
        assertEquals(HttpStatus.OK, preview.getStatusCode(), preview.getBody());
        JsonNode previewData = data(preview);
        assertFalse(previewData.get("applied").asBoolean());
        assertTrue(previewData.get("changes").size() > 0, "the excluded nurse works some of the shifts");

        // applying without the preview's revision is refused
        Map<String, Object> noRevision = new HashMap<>(request);
        noRevision.put("dryRun", false);
        assertEquals(HttpStatus.BAD_REQUEST, send("/api/schedules/" + scheduleId + "/reschedule", HttpMethod.POST, noRevision).getStatusCode());

        Map<String, Object> apply = new HashMap<>(request);
        apply.put("dryRun", false);
        apply.put("revision", previewData.get("revision").asText());
        apply.put("seed", previewData.get("seed").asLong());
        ResponseEntity<String> applied = send("/api/schedules/" + scheduleId + "/reschedule", HttpMethod.POST, apply);
        assertEquals(HttpStatus.OK, applied.getStatusCode(), applied.getBody());
        assertTrue(data(applied).get("applied").asBoolean());

        JsonNode after = data(http.getForEntity("/api/schedules/" + scheduleId, String.class));
        for (JsonNode s : after.get("shifts")) {
            if (s.get("date").asText().compareTo(from) >= 0) {
                s.get("assignedNurses").forEach(n -> assertNotEquals(excluded, n.asText(), "excluded nurse still on " + s.get("date")));
            }
        }

        // the schedule changed, so the same revision is stale now
        assertEquals(HttpStatus.CONFLICT, send("/api/schedules/" + scheduleId + "/reschedule", HttpMethod.POST, apply).getStatusCode());

        // the reschedule is in the audit log
        JsonNode log = data(http.getForEntity("/api/schedules/" + scheduleId + "/changes", String.class));
        boolean logged = false;
        for (JsonNode entry : log) {
            logged |= "RESCHEDULE".equals(entry.get("action").asText());
        }
        assertTrue(logged);
    }

    @Test
    void aNurseWithUpcomingShiftsCannotBeDeletedUntilRescheduled() throws Exception {
        generateSchedule();
        String worksAShift = shifts.findAll().stream()
                .flatMap(s -> s.getAssignedNurses().stream()).findFirst().orElseThrow();

        ResponseEntity<String> response = http.exchange("/api/nurses/" + worksAShift, HttpMethod.DELETE, HttpEntity.EMPTY, String.class);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode(), response.getBody());
        assertTrue(nurses.existsById(worksAShift));
    }

    @Test
    void theApiDocumentationListsEveryRouteTheFrontendCalls() {
        String docs = http.getForObject("/api-docs", String.class);
        for (String path : List.of(
                "/api/schedules", "/api/schedules/generate", "/api/schedules/{scheduleId}",
                "/api/schedules/{scheduleId}/conflicts", "/api/schedules/{scheduleId}/validate",
                "/api/schedules/{scheduleId}/status", "/api/schedules/{scheduleId}/reschedule",
                "/api/schedules/{scheduleId}/changes", "/api/schedules/shifts", "/api/schedules/shifts/{shiftId}",
                "/api/schedules/shifts/move", "/api/schedules/shifts/validate", "/api/schedules/swap-requests",
                "/api/schedules/swap-requests/{requestId}/approve", "/api/schedules/swap-requests/{requestId}/reject",
                "/api/schedules/swap-requests/{requestId}/cancel", "/api/schedules/swap-requests/{requestId}/check",
                "/api/schedules/workload", "/api/schedules/export")) {
            assertTrue(docs.contains("\"" + path + "\""), "missing in the API docs: " + path);
        }
    }
}
