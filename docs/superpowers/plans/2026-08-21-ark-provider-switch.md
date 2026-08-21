# Ark provider switch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support switching the page-erasure VLM between DashScope Chat Completions and Volcengine Ark Responses through one active-provider setting.

**Architecture:** `VlmConfig` exposes the active provider kind. `VlmClient.create` selects the existing OpenAI-compatible client or a new Ark Responses client. Both clients keep the same four-role, parsed-result interface; only request/response transport differs.

**Tech Stack:** Java 8, Jackson, JUnit 4, HTTP URLConnection.

**Spec:** `docs/specs/2026-08-21-exam-page-erase-design.md`

## Global Constraints

- `active` is the only provider-selection switch.
- `roles` retains role prompt paths and human-readable descriptions only; do not add an unused provider field.
- Fail closed for unknown provider kinds, HTTP failures, malformed Ark output, and missing credentials.
- Do not send real API requests in unit tests.

### Task 1: Ark request contract

**Files:**
- Create: `java/src/test/java/com/xb/sgc/papererase/vlm/ArkResponsesRequestBuilderTest.java`
- Modify: `java/src/main/java/com/xb/sgc/papererase/vlm/VlmClient.java`

- [ ] Write a test that loads `VlmClient$ArkResponses` by name and asserts its request body uses `input[].content[]`, `input_text`, `input_image`, and a data URL.
- [ ] Run the test and confirm it fails because Ark client is absent.
- [ ] Implement the smallest Ark request builder and response-text extractor.
- [ ] Re-run the test and confirm it passes.

### Task 2: Provider selection and configuration cleanup

**Files:**
- Modify: `config/vlm-providers.json`
- Modify: `java/src/main/java/com/xb/sgc/papererase/vlm/VlmConfig.java`
- Modify: `java/src/main/java/com/xb/sgc/papererase/vlm/VlmClient.java`
- Modify: `java/src/main/java/com/xb/sgc/papererase/Main.java`
- Test: `java/src/test/java/com/xb/sgc/papererase/vlm/VlmConfigTest.java`

- [ ] Write a failing test for `config.getProviderKind()` and factory selection of `ark-responses`.
- [ ] Add provider kind parsing and fail-closed factory selection.
- [ ] Remove `roles.*.provider`; keep only role descriptions and prompt paths.
- [ ] Run VLM configuration/request tests and the three existing core safety tests.
- [ ] Commit the implementation.
