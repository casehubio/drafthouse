---
id: PP-20260610-073663
title: "Infrastructure provenance messages in debate channels must be intercepted by string check before EntryType.valueOf()"
type: rule
scope: application
applies_to: "DebateChannelProjection.apply(); any code that dispatches on entryType values in the debate channel"
severity: important
refs:
  - server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java
  - server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java
  - server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java
violation_hint: "Adding RESTART_CONTEXT (or any infrastructure provenance type) as a constant to EntryType.java — this causes SummaryRenderer's exhaustive switch on ThreadEntry.type() to fail to compile, and pollutes the domain enum with infrastructure concerns"
garden_ref: "GE-20260609-0e178e"
created: 2026-06-10
---

When a Qhorus debate channel carries infrastructure provenance messages (e.g. `RESTART_CONTEXT` posted at session branch time), those message types must NOT be added to `EntryType` — the domain enum whose values appear in `SummaryRenderer`'s exhaustive switch. Instead, `DebateChannelProjection.apply()` must intercept the provenance type as a string literal immediately before calling `EntryType.valueOf()`:

```java
if ("RESTART_CONTEXT".equals(entryTypeStr)) return state;  // provenance — skip silently
EntryType entryType = EntryType.valueOf(entryTypeStr);       // only for domain types
```

This keeps domain semantics (RAISE, AGREE, DISPUTE, …) cleanly separated from infrastructure provenance, avoids exhaustive-switch breakage, and suppresses the WARNING log that the catch block emits for unknown types. The DHMETA structure is preserved — the marker is still queryable via `contentPattern("originChannelId=")` — but the projection treats it as transparent.
