package bug_investigation_agent.prompt;

import bug_investigation_agent.model.request.InvestigationRequest;
import org.springframework.stereotype.Component;

@Component
public class InvestigationPromptBuilder {

    public String buildPrompt(InvestigationRequest request) {
        return buildPrompt(request, null);
    }

    public String buildPrompt(InvestigationRequest request, String historicalContext) {
        boolean hasImage = request != null
                && request.getFailureImage() != null
                && request.getFailureImage().trim().startsWith("data:image/");

        String imageSection = hasImage
                ? """
                Screenshot:
                AN IMAGE IS ATTACHED TO THIS REQUEST — you WILL receive it alongside this text prompt.
                Do NOT say "no screenshot provided" or "not provided" anywhere in your response — an image is present.
                Carefully examine the screenshot as primary visual evidence.
                Look for: error dialogs, toast messages, unexpected UI state, missing elements,
                wrong page/screen, incorrect data displayed, loading spinners still visible,
                network error banners, blank sections, or any visual anomaly.
                In "screenshotObservation", describe exactly what you see in the image (screen/page, visible text, elements,
                error banners, empty states, etc.) - this field must NEVER say a screenshot was not provided.
                Use the visual evidence to CONFIRM or REFUTE your root cause hypothesis.
                If the screenshot clearly shows an error/unexpected state, rootCauseType should be CONFIRMED.
                """
                : """
                Screenshot:
                                NOT PROVIDED - root cause type cannot be CONFIRMED without visual evidence.
                """;

                String historicalSection = (historicalContext == null || historicalContext.isBlank())
                                ? "NOT PROVIDED"
                                : historicalContext.trim();

        return """
                You are a Senior SDET specializing in Selenium, Appium, Cucumber, TestNG and mobile/web automation failure investigation.
                                Generate a specific, evidence-based technical root cause using ALL available evidence together
                                (test/scenario context, failed step, error, stack trace, locator hints, screenshot, and historical context when present).

                CONFIRMATION RULES:
                - CONFIRMED: screenshot + error/stack trace together prove the cause.
                - PROBABLE: error/stack trace alone strongly suggest the cause, no visual proof.
                                - POSSIBLE: evidence is limited or partially conflicting.

                RULES:
                - Use ONLY the evidence below. NEVER invent file/class/method names, selectors, endpoints or log types not present in it.
                - Read ALL steps for context; root cause may be in an earlier passed step, not just the failed one.
                                - TimeoutException/NoSuchElementException are SYMPTOMS, not root causes. Explain WHY the wait/lookup failed in this test.
                                - Do not auto-assume timeouts are application slowness. For locator-related failures, analyze locator stability,
                                    resource-id/content-desc usage, hierarchy changes, wrong screen/state, and whether the expected element is actually visible.
                                - Correlate stack trace call sites with screenshot state and the failed step. Root cause must reference this correlation.
                                - If evidence is insufficient, explicitly say "The evidence indicates..." / "The most likely cause is..." and use POSSIBLE.
                - Examine the screenshot for: error dialogs, toasts, wrong screen/state, missing/blank elements, incorrect data, spinners,
                  network banners, or any visual anomaly. Use it to confirm/refute the hypothesis.
                - Do not default to AUTOMATION_ISSUE. Simple locator-not-found with nothing else wrong = AUTOMATION_ISSUE. But if an
                  assertion compares an expected value against a blank/wrong result AFTER an action that succeeded earlier in the SAME
                  run (e.g. same navigation worked before, fails identically later), and the screenshot shows the app in a wrong/blank
                  state (not a test-framework crash), that is evidence of APPLICATION_ISSUE (state/navigation regression), not a broken
                  locator (broken locators fail consistently, not only on a later repeat of the same action).
                - Use only platform-correct terms: for native mobile (Appium/page objects like "...ui.pepsiConnect...HomePage.java"),
                  never mention CSS selectors/browser console/network HAR; use accessibility id / resource-id / XCUIElementType / page
                  object method instead.

                OUTPUT FIELDS (JSON only, no markdown, no extra text):
                {
                  "classification": "AUTOMATION_ISSUE|APPLICATION_ISSUE|API_ISSUE|DATA_ISSUE|ENVIRONMENT_ISSUE|NETWORK_ISSUE|UNKNOWN",
                                    "rootCauseType": "CONFIRMED|PROBABLE|POSSIBLE",
                                    "rootCause": "1-3 concise sentences: specific technical cause + concrete evidence correlation (error/stack/screenshot/step)",
                  "confidence": 0,
                  "evidence": ["specific evidence strings: quoted error, step refs, screenshot content, stack trace lines"],
                  "missingEvidence": ["info that would help, appropriate to the platform actually used (mobile: device logs, Appium session log, backend logs; web: browser console, network HAR)"],
                  "severity": "LOW|MEDIUM|HIGH|CRITICAL",
                  "recommendedAction": "specific action referencing the actual class/method/line visible in the stack trace; if APPLICATION_ISSUE, tell dev team to check the real app screen/navigation logic and reproduce manually; if AUTOMATION_ISSUE, reference the real page object/step method and suggest a concrete platform-appropriate wait/locator fix. Never empty or generic, never fabricated.",
                  "suggestedFix": "concrete code-level fix grounded ONLY in the evidence, platform-appropriate (Appium vs Selenium); if APPLICATION_ISSUE describe precisely what app behavior is wrong and should be logged as a defect",
                  "stepsToReproduce": ["ordered concrete manual steps with real data values, starting from app launch/login"],
                  "preventionTips": ["2-4 specific actionable automation best practices for this exact failure type"],
                  "similarPatterns": "1-2 look-alike failure patterns and how they differ from this one",
                  "screenshotObservation": "exact screen/state/errors/elements seen in the screenshot, or 'No screenshot provided - cannot visually confirm root cause' if none"
                }
                                Confidence guide: CONFIRMED+screenshot 85-95, CONFIRMED from trace only 75-85, PROBABLE 60-75, POSSIBLE 35-60.
                Never leave recommendedAction/suggestedFix empty or generic.
                                Never output a generic rootCause that just restates the exception.

                ==================== FAILURE EVIDENCE ====================
                Test Case ID   : %s
                Report Type    : %s
                Feature        : %s
                Scenario       : %s

                --- ALL SCENARIO STEPS (in execution order) ---
                %s

                --- FAILED STEP ---
                %s

                --- ERROR MESSAGE / EXCEPTION ---
                %s

                --- STACK TRACE (additional frames beyond the error message above, if any) ---
                %s

                --- CONSOLE LOGS ---
                %s

                --- VIDEO EVIDENCE ---
                %s

                --- HISTORICAL CONTEXT (supporting only; may be stale/incomplete) ---
                %s

                --- %s
                ================== END FAILURE EVIDENCE ==================

                Analyze this failure now. Cross-reference the screenshot with the error and step history to reach the most CONFIRMED
                conclusion possible. Pay special attention to whether the SAME action/step succeeded earlier in this scenario and only
                failed on a LATER occurrence — that signals APPLICATION_ISSUE (state/navigation regression), not a broken locator.
                Be specific and actionable. Do not fabricate file names, selectors or log types not implied by the evidence above.
                """.formatted(
                safe(request == null ? null : request.getTestName()),
                safe(request == null ? null : request.getReportType()),
                safe(request == null ? null : request.getFeature()),
                safe(request == null ? null : request.getScenario()),
                formatSteps(request),
                safe(request == null ? null : request.getFailedStep()),
                limit(sanitizeStackNoise(request == null ? null : request.getError()), 2500),
                buildStackTraceSection(request),
                limit(request == null ? null : request.getConsoleLogs(), 1200),
                safe(request == null ? null : request.getVideoUrl()),
                historicalSection,
                imageSection
        );
    }

    /**
     * Avoids sending the stack trace verbatim when it is identical (or near-identical) to the
     * error message already shown above, since Selenium/Appium clients frequently duplicate the
     * full exception text in both fields - wasting the token budget twice on the same content.
     */
    private String buildStackTraceSection(InvestigationRequest request) {
        String error = request == null ? null : request.getError();
        String stackTrace = request == null ? null : request.getStackTrace();
        if (stackTrace == null || stackTrace.isBlank()) {
            return "NOT PROVIDED";
        }
        String sanitizedStack = sanitizeStackNoise(stackTrace);
        String sanitizedError = sanitizeStackNoise(error);
        if (sanitizedError != null && sanitizedStack != null
                && sanitizedStack.trim().equals(sanitizedError.trim())) {
            return "Same content as the ERROR MESSAGE / EXCEPTION section above.";
        }
        return limit(sanitizedStack, 2000);
    }

    /**
     * Selenium/Appium exceptions embed a huge, repetitive block of pure noise (full desired
     * capabilities dump, build/system/driver info, session id) that consumes a large share of the
     * token budget without adding any diagnostic value - and this block is often duplicated
     * verbatim in both the "error" and "stackTrace" fields of the same request. Stripping it (and
     * capping the number of stack frames) frees up that budget for the actual scenario steps and
     * screenshot, which is what previously caused the model to run out of context and claim no
     * evidence was provided at all.
     */
    private String sanitizeStackNoise(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return stackTrace;
        }
        String cleaned = stackTrace;
        // Remove the Capabilities{...} dump (can be 1500+ chars of desired-capability noise).
        cleaned = cleaned.replaceAll("(?s)Capabilities \\{.*?\\}\\n", "");
        // Remove Build info / System info / Driver info / Session ID boilerplate lines.
        cleaned = cleaned.replaceAll("(?m)^(Build info|System info|Driver info|Session ID):.*$\\n?", "");
        // Cap the number of "at ..." stack frame lines kept, since only the first few frames
        // (closest to the actual failing test code) are useful for root-causing.
        String[] lines = cleaned.split("\\r?\\n");
        java.util.List<String> kept = new java.util.ArrayList<>();
        int frameCount = 0;
        int maxFrames = 8;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("at ")) {
                frameCount++;
                if (frameCount > maxFrames) {
                    continue;
                }
            }
            kept.add(line);
        }
        return String.join("\n", kept).trim();
    }

    /** Keeps the prompt bounded while still giving the model enough scenario context. */
    private static final int MAX_STEPS_SENT = 25;

    private String formatSteps(InvestigationRequest request) {
        if (request == null) return "NOT PROVIDED";
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (request.getAllStepsDetail() != null && !request.getAllStepsDetail().isEmpty()) {
            int index = 1;
            for (Object step : request.getAllStepsDetail()) {
                lines.add(index++ + ". " + formatStepValue(step));
            }
        } else if (request.getAllSteps() != null && !request.getAllSteps().isBlank()) {
            for (String line : request.getAllSteps().split("\\r?\\n")) {
                if (!line.isBlank()) lines.add(line);
            }
        }
        if (lines.isEmpty()) return "NOT PROVIDED";

        if (lines.size() <= MAX_STEPS_SENT) {
            return String.join("\n", lines);
        }
        int omitted = lines.size() - MAX_STEPS_SENT;
        java.util.List<String> trimmed = lines.subList(lines.size() - MAX_STEPS_SENT, lines.size());
        return "[... " + omitted + " earlier step(s) omitted for brevity ...]\n" + String.join("\n", trimmed);
    }

    private String formatStepValue(Object step) {
        if (step == null) return "";
        if (step instanceof String s) return s;
        if (step instanceof java.util.Map<?, ?> map) {
            Object text = map.get("text");
            if (text != null) return String.valueOf(text);
            Object raw = map.get("raw");
            if (raw != null) return String.valueOf(raw);
        }
        return String.valueOf(step);
    }

    private String limit(String value, int max) {
        if (value == null || value.isBlank()) return "NOT PROVIDED";
        String v = value.trim();
        return v.length() <= max ? v : v.substring(0, max) + "\n[TRUNCATED]";
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "NOT PROVIDED" : value.trim();
    }
}
