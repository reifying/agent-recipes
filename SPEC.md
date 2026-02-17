# Agent Recipes - Specification

A CLI tool that orchestrates multi-step, structured workflows ("recipes") for AI coding agents. Recipes are state machines that guide an agent through a series of steps, each producing a typed outcome that determines the next step.

---

## 1. Core Data Model

### Recipe

A recipe is a finite state machine.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string (kebab-case) | yes | Unique identifier |
| `label` | string | yes | Human-readable display name |
| `description` | string | yes | One-line summary of what the recipe does |
| `initialStep` | string | yes | Name of the first step to execute. Must exist in `steps`. |
| `steps` | map<string, Step> | yes | All steps in the recipe |
| `guardrails` | Guardrails | yes | Safety limits to prevent runaway execution |
| `model` | string | no | Default model tier for all steps in this recipe |

### Step

A step represents a single unit of work for the agent to perform.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `prompt` | string | yes | The full instruction text sent to the agent |
| `outcomes` | set<string> | yes | All valid outcome values for this step |
| `onOutcome` | map<string, Transition> | yes | What to do for each outcome. Keys must be members of `outcomes`. |
| `model` | string | no | Override the model tier for this step only |

### Transition

A transition determines what happens after a step completes with a given outcome. Exactly one of three forms:

| Form | Fields | Description |
|------|--------|-------------|
| **Next Step** | `{ "nextStep": "<step-name>" }` | Advance to another step in the recipe. Target must exist in `steps`. |
| **Exit** | `{ "action": "exit", "reason": "<string>" }` | Terminate the recipe with a reason. |
| **Restart New Session** | `{ "action": "restart-new-session", "recipeId": "<recipe-id>" }` | Terminate the current agent session, start a fresh session running the specified recipe. |

### Guardrails

Safety limits to prevent infinite loops or runaway execution.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `maxStepVisits` | int | 3 | Maximum times any single step can be visited before forced exit |
| `maxTotalSteps` | int | 100 | Maximum total step transitions before forced exit |
| `exitOnOther` | boolean | true | Whether selecting "other" outcome should exit the recipe |

### Model Tiers

Abstract model tiers that each backend maps to its own concrete model identifiers:

| Tier | Intent | Usage |
|------|--------|-------|
| `haiku` | Fast, cheap | Commit messages, summaries, simple routing |
| `sonnet` | Balanced | Default for most steps |
| `opus` | Most capable | Complex reasoning, implementation, code review |

---

## 2. Orchestration Engine

### 2.1 Execution Loop

```
1.  Look up current step definition from recipe.steps[currentStep]
2.  Build final prompt:
      a. Start with step.prompt (the base instruction text)
      b. Append outcome format block (see §2.2)
3.  Resolve model: step.model ?? recipe.model ?? backend default
4.  Send prompt to agent backend, receive full response text
5.  Extract JSON outcome from response (see §2.3)
6.  If extraction fails AND retryCount for this step == 0:
      a. Increment retryCount
      b. Build reminder prompt (see §2.4)
      c. Send reminder prompt to same session, goto 5
7.  If extraction fails AND retryCount > 0:
      a. Exit recipe with reason "orchestration-error"
8.  Validate outcome is in step.outcomes
9.  Look up transition = step.onOutcome[outcome]
10. Check guardrails (see §2.5)
11. Execute transition:
      - next-step:            update state, goto 1
      - exit:                 terminate with reason
      - restart-new-session:  terminate session, start fresh session, goto 1
```

**Key invariant:** The orchestrator drives all transitions. The agent never decides what step to execute next — it only reports outcomes.

### 2.2 Prompt Construction

The prompt sent to the agent is constructed by concatenation:

```
[step.prompt]

End your response with one of these JSON blocks on the last line:

{"outcome": "outcome-a"}
{"outcome": "outcome-b"}
{"outcome": "other", "otherDescription": "<brief description>"}
```

**Rules for the outcome format block:**
- Non-"other" outcomes are sorted alphabetically and listed as concrete JSON examples
- The "other" outcome (if present in step.outcomes) is listed last with the `otherDescription` template
- Two newlines separate the base prompt from the format block

**No global preamble or system prompt is prepended.** The step prompt is the entire instruction. Context files (project standards, CLAUDE.md, etc.) are referenced by name *within* step prompts — the agent is expected to find and read them autonomously.

**Optional system prompt override:** Backends that support `--append-system-prompt` or equivalent can accept an optional system prompt. This is a CLI flag, not embedded in the recipe definition.

**Prompt delivery:** The reference implementation passes the prompt as the final CLI argument to the agent process. This works for recipe prompts (typically 1-5KB) but could theoretically hit OS `ARG_MAX` limits (~1MB on macOS, ~2MB on Linux) for extremely large prompts. If a backend needs to support larger prompts, write the prompt to a temp file and pass via stdin or a `--prompt-file` flag instead. The reference implementation opens stdin as a pipe but immediately closes it (`.close(.getOutputStream(process))`) — stdin is not used for prompt delivery.

### 2.3 Outcome Extraction

The agent must end its response with a JSON block. Extraction logic:

1. **Scan the tail**: Split the full response into lines. Check the **last 5 lines** for one whose trimmed form starts with `{` and ends with `}`. Iterate from most-recent to oldest; return the first match. If none found → extraction failure.
2. **Strip fences from the matched line**: If the matched line (or surrounding context) contains markdown code fences (```` ```json ````...```` ``` ````), remove them. This operates on the **extracted candidate**, not the full response text.
3. **Parse JSON**: Parse the cleaned candidate string as JSON.
4. **Validate `outcome` field**: Must exist, must be a string, must be a member of `step.outcomes` (convert string to keyword/enum for comparison).
5. **Validate `otherDescription`**: Required when `outcome == "other"`, ignored otherwise.

**Important:** Steps 1-2 are ordered deliberately. First find a `{...}` line, then clean it. Do NOT pre-process the entire response to strip fences before scanning — that would corrupt multi-line code blocks the agent may have included in its response.

**Return value on success:**
```json
{ "success": true, "outcome": "no-issues", "description": null }
```

**Return value on failure:**
```json
{ "success": false, "error": "No JSON block found in response", "malformedJson": "{bad" }
```

The `malformedJson` field is populated when JSON was found but couldn't be parsed or validated — useful for debugging.

### 2.4 Retry Mechanism

When outcome extraction fails on the first attempt, a **reminder prompt** is sent to the same session:

```
Your previous response did not include the required JSON outcome block.
Please respond now with ONLY the JSON outcome on a single line.

Error: <error-details>

Valid responses:

{"outcome": "outcome-a"}
{"outcome": "outcome-b"}
{"outcome": "other", "otherDescription": "<brief description>"}

Respond with ONLY the JSON block, nothing else.
```

**Rules:**
- Maximum 1 retry per step execution (not per step visit — each visit resets)
- The reminder is sent as a follow-up in the same session (preserves conversation context)
- If the retry also fails, the recipe exits with reason `"orchestration-error"`

### 2.5 State Tracking

The orchestration engine maintains mutable state per recipe execution:

| Field | Type | Description |
|-------|------|-------------|
| `recipeId` | string | Which recipe is running |
| `currentStep` | string | Name of the step currently executing |
| `stepCount` | int | Total steps executed so far (starts at 1, incremented on each transition) |
| `stepVisitCounts` | map<string, int> | How many times each step has been visited |
| `stepRetryCounts` | map<string, int> | Outcome extraction retry count per step (resets on new visit) |
| `startTime` | long (epoch ms) | Timestamp when the recipe execution started |
| `sessionCreated` | boolean | Whether the current agent session has been created (affects new-session vs resume flags) |

**State updates on step transition:**
```
currentStep       = transition.nextStep
stepCount         = stepCount + 1
stepVisitCounts[nextStep] = stepVisitCounts[nextStep] + 1  (default 0)
```

### 2.6 Guardrail Enforcement

Checked **before** executing a transition to a next step (not on exit/restart transitions):

1. **Per-step limit**: If `stepVisitCounts[nextStep] >= guardrails.maxStepVisits`
   → exit with reason `"max-step-visits-exceeded:<step-name>"`

2. **Total limit**: If `stepCount >= guardrails.maxTotalSteps`
   → exit with reason `"max-total-steps"`

These are hard limits — no override by the agent. They can be overridden by CLI flags.

---

## 3. Session Management

### 3.1 Session Lifecycle

A "session" is a single conversation context with the agent backend. Sessions have these states:

```
[not created] → [active] → [complete]
                    ↑            │
                    └── resume ──┘
```

**New session:** First invocation uses a "create" flag (e.g., `--session-id <uuid>` for Claude Code). The backend allocates a new conversation context.

**Resume session:** Subsequent invocations in the same recipe execution use a "resume" flag (e.g., `--resume <uuid>`). The backend appends to the existing conversation, preserving full history.

**Session tracking flag:** The orchestrator tracks `sessionCreated`:
- Starts as `false`
- Set to `true` after first successful agent invocation
- When `false`: use "new session" flag
- When `true`: use "resume session" flag

This means the first step of a recipe creates the session, and all subsequent steps within the same recipe execution resume it.

### 3.2 Restart-New-Session Transition

When a step produces a `restart-new-session` transition:

1. Exit the current recipe state (release any locks)
2. Generate a new session ID (UUID)
3. Create fresh orchestration state for the target recipe
4. Set `sessionCreated = false` (next invocation will create a new session)
5. Begin executing the target recipe from its `initialStep`

**Purpose:** Prevents context window exhaustion. When a recipe like `implement-and-review-all` processes many tasks, each task gets a fresh context window. The agent starts each task without accumulated history from previous tasks.

**Restart loop bound:** There is no hard limit on the number of restart-new-session cycles. The loop terminates naturally when the `implement` step reports `no-tasks` (no more work available). If tasks are being added externally faster than they're completed, the loop could run indefinitely. For safety, the CLI should support `--max-restarts <n>` to cap the number of session restarts (default: unlimited).

### 3.3 Concurrency and Locking

Only one prompt can be in-flight per session at a time. The orchestrator must enforce this:

- Acquire a session lock before invoking the agent
- Hold the lock for the entire recipe execution (including recursive step transitions)
- Release the lock in a `finally` block to guarantee release on error
- On restart-new-session: release old session lock, acquire new session lock

---

## 4. Backend Abstraction

### 4.1 Backend Interface

Each backend must implement:

```java
record AgentResponse(
    boolean success,
    String responseText,      // The agent's full text response (for outcome extraction)
    String error,             // Error message if success==false
    String sessionId,         // Session ID returned by backend (may differ from input)
    Long inputTokens,         // Token usage, null if backend doesn't report
    Long outputTokens,
    Double costUsd            // Cost in USD, null if backend doesn't report
) {}

interface AgentBackend {
    /**
     * Send a prompt to the agent and return a structured response.
     *
     * @param prompt       The full prompt text (includes outcome format block)
     * @param sessionId    Session identifier for conversation continuity
     * @param isNewSession true = create new session, false = resume existing
     * @param workingDir   Working directory for the agent process
     * @param model        Resolved model identifier (backend-specific), or null for default
     * @param envVars      Additional environment variables to pass to agent process
     * @return             Structured response with text, usage, and cost
     */
    AgentResponse sendPrompt(String prompt, String sessionId, boolean isNewSession,
                             Path workingDir, String model, Map<String, String> envVars);

    /** Get backend display name */
    String getName();

    /**
     * Map abstract model tier to backend-specific model identifier.
     * @param tier  One of "haiku", "sonnet", "opus"
     * @return      Backend-specific model ID, or null for backend default
     */
    String resolveModel(String tier);
}
```

The `AgentResponse` record captures both the text (for outcome extraction) and metadata (for cost tracking and observability). Backends that don't report usage/cost return null for those fields.

### 4.2 Backend: Claude Code (`claude`)

**Invocation:**
```bash
claude \
  --print \
  --output-format json \
  --dangerously-skip-permissions \
  [--session-id <uuid>]        # new session
  [--resume <uuid>]            # resume session
  [--model <model>]            # optional model override
  [--append-system-prompt "…"] # optional system prompt
  "<prompt text>"
```

**Response format:** JSON array containing a result object:
```json
[{"type": "result", "session_id": "…", "result": "…text…", "usage": {…}, "total_cost_usd": 0.05}]
```

The `result` field contains the agent's text response for outcome extraction.

**Environment:**
- Strip `CLAUDE_CODE_ENTRYPOINT` and `CLAUDECODE` from parent environment (prevents nested session errors)
- Pass through all other environment variables
- Communication: prompt passed as final CLI argument, response read from stdout via temp file redirection

**Session storage:** `~/.claude/projects/<project-hash>/<session-id>.jsonl`

**Model mapping:**

| Tier | Claude Code Model |
|------|------------------|
| `haiku` | `haiku` (passed as `--model haiku`) |
| `sonnet` | `sonnet` (or omit for default) |
| `opus` | `opus` |

### 4.3 Backend: GitHub Copilot CLI (`gh copilot`)

| Tier | Mapping |
|------|---------|
| `haiku` | TBD — may not support model selection |
| `sonnet` | TBD |
| `opus` | TBD |

### 4.4 Backend: Cursor Agent CLI (`cursor`)

| Tier | Mapping |
|------|---------|
| `haiku` | TBD |
| `sonnet` | TBD |
| `opus` | TBD |

### 4.5 Backend: OpenCode (`opencode`)

| Tier | Mapping |
|------|---------|
| `haiku` | TBD |
| `sonnet` | TBD |
| `opus` | TBD |

---

## 5. Recipe Catalog

### 5.1 Review & Commit

**ID:** `review-and-commit`
**Purpose:** Review existing uncommitted changes, fix issues found, commit and push.
**Initial step:** `code-review`

```
code-review ──no-issues──→ commit ──committed──→ EXIT("changes-committed")
     │                        ├─ nothing-to-commit → EXIT("no-changes-to-commit")
     │ issues-found           └─ other → EXIT
     ↓
    fix ──complete──→ code-review
     └─ other → EXIT
```

| Step | Outcomes | Model | Transitions |
|------|----------|-------|-------------|
| `code-review` | `no-issues`, `issues-found`, `other` | — | no-issues → commit; issues-found → fix; other → EXIT |
| `fix` | `complete`, `other` | — | complete → code-review; other → EXIT |
| `commit` | `committed`, `nothing-to-commit`, `other` | `haiku` | all → EXIT |

**code-review prompt requirements:**
- Run `git diff` to see changes
- Read each modified file for context
- Evaluate against checklist: correctness, code quality, testing, security/performance, design alignment
- List files read and what was checked
- Report findings without making changes

**fix prompt requirements:**
- Address issues found in code review
- Run tests after fixing
- Verify fix doesn't introduce new issues

**commit prompt requirements:**
- Write clear commit message
- Push to remote after committing

---

### 5.2 Document Design

**ID:** `document-design`
**Purpose:** Create a detailed design document with code examples and verification steps.
**Initial step:** `document`

```
document ──complete──→ review ──no-issues──→ commit → EXIT
    │                    │
    │ needs-input→EXIT   │ issues-found
    └ other→EXIT         ↓
                        fix ──complete──→ review
```

| Step | Outcomes | Model | Transitions |
|------|----------|-------|-------------|
| `document` | `complete`, `needs-input`, `other` | — | complete → review; needs-input → EXIT; other → EXIT |
| `review` | `no-issues`, `issues-found`, `other` | — | no-issues → commit; issues-found → fix; other → EXIT |
| `fix` | `complete`, `other` | — | complete → review; other → EXIT |
| `commit` | `committed`, `nothing-to-commit`, `other` | `haiku` | all → EXIT |

**Required document structure (enforced in prompt):**
1. Overview — problem statement, goals, non-goals
2. Background & Context — current state, why now, related work
3. Detailed Design — data model, API design, code examples, component interactions
4. Verification Strategy — testing approach, test examples, acceptance criteria
5. Alternatives Considered — other approaches, why this one was chosen
6. Risks & Mitigations — what could go wrong, detection, rollback

**Quality checklist embedded in prompt:**
- Code examples syntactically correct
- Examples match codebase style
- Verification steps specific and actionable
- Cross-references use `@filename.md` format
- No placeholder text remains

---

### 5.3 Break Down Tasks

**ID:** `break-down-tasks`
**Purpose:** Analyze a design document and create structured implementation tasks.
**Initial step:** `analyze`

```
analyze ──complete──→ create-epic ──complete──→ create-tasks ──complete──→ review-tasks ──no-issues──→ commit → EXIT
   │                                                                          │
   │ design-missing→EXIT                                                      │ issues-found
   │ needs-input→EXIT                                                         ↓
   └ other→EXIT                                                           fix-tasks ──complete──→ review-tasks
```

| Step | Outcomes | Model | Transitions |
|------|----------|-------|-------------|
| `analyze` | `complete`, `design-missing`, `needs-input`, `other` | — | complete → create-epic; rest → EXIT |
| `create-epic` | `complete`, `other` | — | complete → create-tasks; other → EXIT |
| `create-tasks` | `complete`, `other` | — | complete → review-tasks; other → EXIT |
| `review-tasks` | `no-issues`, `issues-found`, `other` | — | no-issues → commit; issues-found → fix-tasks; other → EXIT |
| `fix-tasks` | `complete`, `other` | — | complete → review-tasks; other → EXIT |
| `commit` | `committed`, `nothing-to-commit`, `other` | `haiku` | all → EXIT |

**Task creation requirements (enforced in create-tasks prompt):**
- Each task must be: atomic, testable, independent, small (completable in one session)
- Required task sections: design reference, context, requirements, technical approach, verification, acceptance criteria
- Creation order: foundation → core logic → integration → UI → docs
- Parallelizable tasks marked explicitly
- Dependency links established between tasks after creation

**Analyze step expectations:**
- Identify all components to create/modify
- Map acceptance criteria to implementation work
- Identify dependency graph
- Report ambiguities or gaps

---

### 5.4 Implement & Review

**ID:** `implement-and-review`
**Purpose:** Implement a single task, review the code, fix issues iteratively, then commit.
**Initial step:** `implement`

```
implement ──complete──→ code-review ──no-issues──→ commit → EXIT("changes-committed")
    │                       │
    │ no-tasks→EXIT         │ issues-found
    │ blocked→EXIT          ↓
    └ other→EXIT           fix ──complete──→ code-review
```

| Step | Outcomes | Model | Transitions |
|------|----------|-------|-------------|
| `implement` | `complete`, `no-tasks`, `blocked`, `other` | — | complete → code-review; no-tasks → EXIT; blocked → EXIT; other → EXIT |
| `code-review` | `no-issues`, `issues-found`, `other` | — | no-issues → commit; issues-found → fix; other → EXIT |
| `fix` | `complete`, `other` | — | complete → code-review; other → EXIT |
| `commit` | `committed`, `nothing-to-commit`, `other` | `haiku` | all → EXIT |

**Implement step prompt requirements:**
- Check for available tasks (gracefully exit if none)
- Read design document referenced in task
- Review code standards
- Write tests alongside implementation (not after)
- Run tests to verify
- Do NOT commit — code review happens next

**Note:** `code-review`, `fix`, and `commit` steps are shared with review-and-commit recipe (identical prompts and transitions).

---

### 5.5 Implement & Review All

**ID:** `implement-and-review-all`
**Purpose:** Implement all available tasks, one per session, restarting after each commit.
**Initial step:** `implement`

Identical to Implement & Review (§5.4) except for the `commit` step:

| Step | Outcome | Transition |
|------|---------|------------|
| `commit` | `committed` | **restart-new-session** → `implement-and-review-all` |
| `commit` | `nothing-to-commit` | EXIT |
| `commit` | `other` | EXIT |

**Behavior:** After successfully committing one task, the orchestrator:
1. Terminates the current agent session
2. Creates a fresh session (new context window)
3. Starts the recipe again from `implement`
4. The `implement` step checks for more tasks
5. If no tasks remain, exits with `no-tasks`

This creates a **task processing loop** bounded by the availability of tasks, with each task getting an isolated context window.

---

### 5.6 Rebase

**ID:** `rebase`
**Purpose:** Rebase the current branch on local main with careful conflict resolution.
**Initial step:** `rebase`

```
rebase ──complete──→ review ──no-issues──→ complete → EXIT("rebase-complete")
  │                    │
  │ ask-questions→EXIT │ issues-found
  │ conflicts→EXIT     ↓
  └ other→EXIT        fix ──complete──→ review
```

| Step | Outcomes | Model | Transitions |
|------|----------|-------|-------------|
| `rebase` | `complete`, `ask-questions`, `conflicts-unresolvable`, `other` | — | complete → review; rest → EXIT |
| `review` | `no-issues`, `issues-found`, `other` | — | no-issues → complete; issues-found → fix; other → EXIT |
| `fix` | `complete`, `other` | — | complete → review; other → EXIT |
| `complete` | `done`, `other` | `haiku` | all → EXIT |

**Rebase step prompt requirements:**
- Ensure working directory is clean
- Fetch latest changes
- Rebase on **local** main (not remote)
- Preserve intent of both branches during conflict resolution
- Read both versions carefully, understand WHY each change was made
- If resolution unclear, select `ask-questions` outcome

**Review step requirements:**
- Use subagent/parallel analysis for conflicted files
- Verify resolution preserves intent from both branches
- Check for accidentally deleted or duplicated code
- Run tests

---

### 5.7 Retrospective

**ID:** `retrospective`
**Purpose:** Reflect on the session, identify friction points. Read-only.
**Initial step:** `reflect`

```
reflect ──complete──→ EXIT("retrospective-complete")
```

| Step | Outcomes | Model | Transitions |
|------|----------|-------|-------------|
| `reflect` | `complete`, `other` | — | all → EXIT |

**Constraints (enforced in prompt):**
- Do NOT make any changes to files
- Do NOT run any commands or tests
- Purely investigative and reflective
- Be concise — bullet points preferred

**Focus areas (friction only — no positives):**
- Tool issues (failures, unexpected results, missing tools)
- Development friction (slowdowns, unclear requirements, backtracking)
- Testing friction (test problems, unclear feedback, unreliable infrastructure)
- Process friction (workflow inefficiencies, missing documentation)

---

### 5.8 Refine Design

**ID:** `refine-design`
**Purpose:** Iteratively improve an existing design document through 6 focused review passes.
**Initial step:** `locate-design`
**Guardrails:** `maxStepVisits: 5`, `maxTotalSteps: 150` (elevated due to many passes)

```
locate-design → review-completeness ⇄ fix-completeness
                        ↓ (no-issues)
               review-breadth ⇄ fix-breadth
                        ↓
               review-simplicity ⇄ fix-simplicity
                        ↓
               review-consistency ⇄ fix-consistency
                        ↓
               review-polish ⇄ fix-polish
                        ↓
               final-review ⇄ fix-final
                        ↓
                      commit → EXIT
```

| Step | Outcomes | Model | Transitions |
|------|----------|-------|-------------|
| `locate-design` | `found`, `not-found`, `other` | `haiku` | found → review-completeness; not-found → EXIT |
| `review-completeness` | `no-issues`, `issues-found`, `other` | — | no-issues → review-breadth; issues-found → fix-completeness |
| `fix-completeness` | `complete`, `other` | — | complete → review-completeness |
| `review-breadth` | `no-issues`, `issues-found`, `other` | — | no-issues → review-simplicity; issues-found → fix-breadth |
| `fix-breadth` | `complete`, `other` | — | complete → review-breadth |
| `review-simplicity` | `no-issues`, `issues-found`, `other` | — | no-issues → review-consistency; issues-found → fix-simplicity |
| `fix-simplicity` | `complete`, `other` | — | complete → review-simplicity |
| `review-consistency` | `no-issues`, `issues-found`, `other` | — | no-issues → review-polish; issues-found → fix-consistency |
| `fix-consistency` | `complete`, `other` | — | complete → review-consistency |
| `review-polish` | `no-issues`, `issues-found`, `other` | — | no-issues → final-review; issues-found → fix-polish |
| `fix-polish` | `complete`, `other` | — | complete → review-polish |
| `final-review` | `no-issues`, `issues-found`, `other` | — | no-issues → commit; issues-found → fix-final |
| `fix-final` | `complete`, `other` | — | complete → final-review |
| `commit` | `committed`, `nothing-to-commit`, `other` | `haiku` | all → EXIT |

**Review passes in order:**

1. **Completeness & depth** — problem statement, goals, all components described, data models specified, API contracts complete, code examples provided, error handling documented, testing strategy outlined. Technical decisions justified, code examples idiomatic, edge cases addressed.

2. **Breadth & coverage** — failure modes, recovery strategies, backward compatibility, migration path, performance implications, security implications, observability needs, dependency failure modes, upstream/downstream consumers, deployment considerations.

3. **Simplicity (over-engineering check)** — abstractions without multiple uses, configurable things that could be hardcoded, extensibility for hypothetical needs, generic where specific would suffice, multiple indirection layers, complex state machines where conditionals work. YAGNI check: anything built "for future use"?

4. **Consistency & alignment** — terminology consistent throughout, code examples match described approach, data models agree across sections, no contradictions, naming follows project conventions, patterns match existing codebase, referenced files/modules exist.

5. **Polish** — writing concise and direct, no ambiguous statements, headers create logical hierarchy, code blocks properly formatted, no typos, no placeholder text, no TODO comments.

6. **Final sanity check** — does the design solve the stated problem? Anything obviously wrong or missing? Comfortable implementing from this design?

---

## 6. Shared Step Patterns

### 6.1 Review-Commit Steps

Shared by recipes: review-and-commit, implement-and-review, implement-and-review-all.

| Step | Reused across |
|------|---------------|
| `code-review` | All three recipes use identical prompt and transitions |
| `fix` | All three recipes use identical prompt and transitions |
| `commit` | review-and-commit and implement-and-review share identical commit; implement-and-review-all overrides the `committed` transition |

### 6.2 Review-Fix Loop Pattern

A recurring pattern throughout the recipe catalog:

```
review ──no-issues──→ [next phase]
  │
  │ issues-found
  ↓
 fix ──complete──→ review
```

This loop is bounded by `maxStepVisits` — typically 3 visits per step, meaning at most 3 review-fix cycles before forced exit.

### 6.3 The "Other" Escape Hatch

Every step includes `other` as a valid outcome. Convention:
- Transition: `{ "action": "exit", "reason": "user-provided-other" }`
- Agent must provide `otherDescription` field explaining why
- Used when the agent encounters an unexpected situation that doesn't fit the defined outcomes
- Allows graceful exit with diagnostic information

---

## 7. Prompt Engineering Patterns

### 7.1 Prompt Structure Conventions

Step prompts follow consistent structural patterns:

**Action steps** (implement, fix, commit):
```
[What to do]

## Prerequisites / Before Starting
[Setup steps, commands to run first]

## Requirements / Guidelines
[Specific requirements with checkboxes]

## Verification Checklist
Before marking complete:
- [ ] Requirement 1
- [ ] Requirement 2

[Constraint: what NOT to do]
```

**Review steps** (code-review, review-completeness, etc.):
```
[What to review and focus area]

## Review Checklist / Focus
- [ ] Check 1
- [ ] Check 2

## Important Constraints
- Do not make changes yet - this is review only
- [Scope boundaries]

Report [specific things]. If [all clear], report no issues.
```

### 7.2 Prompt Self-Containment Principle

Each prompt includes everything the agent needs:
- Specific commands to run (e.g., `git diff`, `bd ready --limit 1`)
- Checklists with evaluation criteria
- Examples of expected output format
- Explicit constraints (what NOT to do)
- References to project files the agent should read (e.g., `@STANDARDS.md`, `@CLAUDE.md`)

The agent is expected to autonomously find and read referenced files — the orchestrator does not inject file contents.

### 7.3 Model-Appropriate Complexity

Steps using `haiku` model have intentionally simpler prompts:
- Commit steps: straightforward "commit and push" instructions
- Summary/routing steps: simple "report what you found" instructions
- No complex reasoning or multi-step analysis required

Steps using default/opus models get the complex work:
- Code review with multi-dimensional checklists
- Implementation with verification requirements
- Design analysis with coverage matrices

---

## 8. Recipe Validation

All recipes must pass structural validation at startup, before any execution.

### Validation Rules

1. `initialStep` must reference a step that exists in `steps`
2. Every key in `onOutcome` must be a member of the step's `outcomes` set
3. Every `nextStep` value must reference a step that exists in `steps`
4. Every `exit` transition must have a non-empty `reason` string
5. Every `other` outcome transition must have a `reason` string
6. If `model` is specified (recipe-level or step-level), it must be one of `{"haiku", "sonnet", "opus"}`
7. Every step must have `prompt` (string), `outcomes` (set), and `onOutcome` (map) fields

### Validation Result

- Valid recipe: return null/empty (no errors)
- Invalid recipe: return list of error objects with descriptive messages:
  ```json
  [
    {"error": "Initial step not found in steps: nonexistent"},
    {"error": "Invalid model 'gpt-4' at step :commit. Valid models: {haiku, sonnet, opus}"},
    {"error": "Next step :nonexistent-step not found in steps"}
  ]
  ```

---

## 9. CLI Interface

```
agent-recipes [options] <recipe-id>
```

### Options

| Flag | Description |
|------|-------------|
| `--backend <name>` | Agent backend: `claude-code`, `github-copilot`, `cursor`, `opencode` (default: `claude-code`) |
| `--list` | List all available recipes with descriptions |
| `--dry-run` | Print recipe structure (steps, transitions, guardrails) without executing |
| `--verbose` | Log orchestration state transitions: step entered, outcome extracted, transition taken |
| `--model <tier>` | Override the default model tier for all steps |
| `--max-steps <n>` | Override `maxTotalSteps` guardrail |
| `--max-visits <n>` | Override `maxStepVisits` guardrail |
| `--working-dir <path>` | Working directory for agent (default: current directory) |
| `--system-prompt <text>` | Optional system prompt appended to backend's default (backend-dependent support) |
| `--max-restarts <n>` | Cap restart-new-session cycles (default: unlimited). Safety net for recipes like `implement-and-review-all`. |

### Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Recipe completed successfully (exit transition reached) |
| 1 | Recipe validation failed |
| 2 | Orchestration error (outcome extraction failed after retry) |
| 3 | Guardrail triggered (max steps/visits exceeded) |
| 4 | Backend error (agent process failed) |
| 5 | Configuration error (unknown backend, missing CLI tool) |

### Output

**Normal mode:** Print the agent's response for each step to stdout. Print the final exit reason on completion.

**Verbose mode:** Additionally print to stderr:
```
[orchestration] Starting recipe: implement-and-review
[orchestration] Step: implement (visit 1/3, total 1/100)
[orchestration] Sending prompt (2847 chars) to claude-code [opus]
[orchestration] Outcome extracted: complete
[orchestration] Transition: implement → code-review
[orchestration] Step: code-review (visit 1/3, total 2/100)
...
[orchestration] Exit: changes-committed
```

---

## 10. Recipe Definition Format

Recipes are defined in JSON files stored in a `recipes/` directory.

```json
{
  "id": "review-and-commit",
  "label": "Review & Commit",
  "description": "Review existing changes, fix issues, and commit",
  "initialStep": "code-review",
  "guardrails": {
    "maxStepVisits": 3,
    "maxTotalSteps": 100,
    "exitOnOther": true
  },
  "steps": {
    "code-review": {
      "prompt": "Perform a thorough code review on the changes.\n\n## Review Process\n\n1. Run `git diff` to see exactly what changed\n2. Read each modified file to understand the changes in context\n...",
      "outcomes": ["no-issues", "issues-found", "other"],
      "onOutcome": {
        "no-issues": { "nextStep": "commit" },
        "issues-found": { "nextStep": "fix" },
        "other": { "action": "exit", "reason": "user-provided-other" }
      }
    },
    "fix": {
      "prompt": "Address the issues found in the code review.\n\nAfter fixing:\n- Run tests to ensure they still pass\n- Verify the fix doesn't introduce new issues",
      "outcomes": ["complete", "other"],
      "onOutcome": {
        "complete": { "nextStep": "code-review" },
        "other": { "action": "exit", "reason": "user-provided-other" }
      }
    },
    "commit": {
      "prompt": "Commit and push the changes.\n\n- Write a clear commit message describing what was implemented\n- Push to the remote repository after committing",
      "model": "haiku",
      "outcomes": ["committed", "nothing-to-commit", "other"],
      "onOutcome": {
        "committed": { "action": "exit", "reason": "changes-committed" },
        "nothing-to-commit": { "action": "exit", "reason": "no-changes-to-commit" },
        "other": { "action": "exit", "reason": "user-provided-other" }
      }
    }
  }
}
```

### Shared Step References

To support DRY step definitions, recipes can reference shared step libraries:

```json
{
  "id": "implement-and-review",
  "initialStep": "implement",
  "steps": {
    "implement": { "...": "inline definition" },
    "$include": "shared/review-commit-steps.json"
  }
}
```

Or steps can be composed programmatically at load time — the exact mechanism is an implementation detail.

---

## 11. Error Handling Summary

| Error Scenario | Behavior |
|----------------|----------|
| Agent produces no JSON outcome | Retry once with reminder prompt; exit on second failure |
| Agent produces invalid JSON | Retry once; exit on second failure |
| Agent selects outcome not in `outcomes` set | Retry once; exit on second failure |
| Agent selects "other" without `otherDescription` | Retry once; exit on second failure |
| Guardrail: max step visits exceeded | Exit with `"max-step-visits-exceeded:<step>"` |
| Guardrail: max total steps exceeded | Exit with `"max-total-steps"` |
| Agent process crashes / times out | Exit with backend error |
| Recipe validation fails at startup | Exit before execution with validation errors |
| Session lock conflict | Reject with "session locked" (only relevant for concurrent access) |

---

## 12. Key Design Principles

1. **Orchestrator drives, agent reports** — The agent never decides the next step. It reports an outcome via JSON; the orchestrator determines the transition.

2. **Every step has an escape hatch** — The `other` outcome provides a graceful exit when the agent encounters unexpected situations.

3. **Review-fix loops are bounded** — Guardrails prevent infinite correction cycles. Default: 3 visits per step.

4. **Cheap models for simple steps** — Commit messages and summaries use `haiku` tier to minimize cost and latency.

5. **Prompts are self-contained** — Each step prompt includes complete instructions. No hidden context injection.

6. **Restart-new-session prevents context exhaustion** — Multi-task recipes get fresh context for each task.

7. **Shared steps promote consistency** — The same review/fix/commit cycle is reused across recipes, tested once.

8. **Fail fast on bad definitions** — Recipe validation catches structural errors before any agent invocation.

9. **One retry, then stop** — Outcome extraction gets exactly one retry. No infinite retry loops.

10. **Backend agnostic** — Recipe definitions contain no backend-specific details. The same recipe JSON works with any supported backend.

---

## 13. Process Lifecycle

### 13.1 Agent Process Spawning

Each step invocation spawns an OS process for the agent CLI tool. The orchestrator manages the full lifecycle:

```
[build args] → [spawn process] → [wait for exit] → [read output] → [parse response]
                     │                                    │
                     └── [track in active-processes map] ──┘── [cleanup temp files]
```

**CRITICAL: Stdout/stderr capture via temp file redirection (not stream reading):**

Do NOT use `Process.getInputStream()` / `Process.getErrorStream()` to read agent output. Agent CLI responses can be very large (hundreds of KB), and Java's `Process` stream handling will deadlock or silently truncate when the OS pipe buffer fills. The reference implementation hit this bug and solved it with file redirection:

1. **Before spawning:** Create secure temp files with restrictive permissions (`rw-------`):
   ```java
   Path stdoutFile = Files.createTempFile("agent-stdout-", ".json",
       PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
   Path stderrFile = Files.createTempFile("agent-stderr-", ".txt",
       PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
   ```

2. **Redirect process output to files** via `ProcessBuilder.Redirect`:
   ```java
   processBuilder.redirectOutput(ProcessBuilder.Redirect.to(stdoutFile.toFile()));
   processBuilder.redirectError(ProcessBuilder.Redirect.to(stderrFile.toFile()));
   ```

3. **After process exit:** Read the files atomically, then parse:
   ```java
   String stdout = Files.readString(stdoutFile);
   String stderr = Files.readString(stderrFile);
   ```

4. **Cleanup in `finally`:** Delete both temp files regardless of success/failure:
   ```java
   finally {
       Files.deleteIfExists(stdoutFile);
       Files.deleteIfExists(stderrFile);
   }
   ```

This pattern is mandatory for all backends, not just Claude Code. Any agent CLI can produce large output.

**Process tracking:**
- Active processes stored in a map: `sessionId → Process`
- Enables external kill (e.g., user cancels recipe mid-step)
- Graceful shutdown: `Process.destroy()` with 200ms grace, then `Process.destroyForcibly()`

### 13.2 Timeout Handling

| Context | Default Timeout | Behavior on Timeout |
|---------|----------------|---------------------|
| Recipe step execution | 24 hours (86400000ms) | Force-kill process, exit recipe with error |
| Regular prompt (non-recipe) | 1 hour (3600000ms) | Force-kill process, return error |

The orchestrator uses `async/alts!` (or equivalent) with a timeout channel:
- If response arrives before timeout: proceed normally
- If timeout fires first: force-kill the process, invoke error callback

### 13.3 CLI Path Resolution

The backend resolves the CLI executable path with a fallback chain:
1. Check `$CLAUDE_CLI_PATH` environment variable
2. Fall back to `~/.claude/local/claude` if file exists
3. Fail with configuration error if not found

For the Java CLI, each backend should resolve its tool path similarly.

---

## 14. Environment & Working Directory

### 14.1 Working Directory Semantics

The working directory is the project root where the agent executes. It determines:
- Which files the agent can see and modify
- Which git repository context applies
- Which CLAUDE.md / project standards apply
- Which beads database is used (via BEADS_DB)

**Determination:**
- CLI flag `--working-dir` (default: current directory)
- Passed to the agent process as the process working directory
- Immutable for the duration of a recipe execution (even across restart-new-session)

### 14.2 Environment Variable Injection

The orchestrator can inject environment variables into the agent process:

| Variable | When Set | Purpose |
|----------|----------|---------|
| `BEADS_DB` | When working dir is a git worktree with `.beads-local/local.db` | Isolates task database per worktree |

**Environment stripping (Claude Code backend):**
- Remove `CLAUDE_CODE_ENTRYPOINT` and `CLAUDECODE` from inherited environment
- These prevent Claude Code from detecting it's running inside another Claude Code session

### 14.3 Git Worktree Integration

Git worktrees enable parallel isolated work on the same repository:

```
/project/               ← main repo
/project-feature-a/     ← worktree (sibling directory)
/project-feature-b/     ← worktree (sibling directory)
```

**Detection:** Run `git rev-parse --git-dir` — if the result contains `/worktrees/`, it's a worktree.

**Impact on recipes:**
- Each worktree gets its own `.beads-local/local.db` (via BEADS_DB)
- Recipes running in different worktrees are fully isolated
- `implement-and-review-all` can process different task sets per worktree

---

## 15. Prompt External Dependencies

### 15.1 Commands Referenced in Prompts

Prompts tell the agent to run specific CLI commands. The agent must have these tools available:

**Git commands (required by most recipes):**

| Command | Recipes | Purpose |
|---------|---------|---------|
| `git diff` | review-and-commit, implement-and-review | View uncommitted changes |
| `git status` | rebase, review-and-commit | Check working directory state |
| `git fetch origin` | rebase | Sync remote before rebase |
| `git rebase main` | rebase | Core rebase operation |
| `git commit --amend` | rebase | Fix commits during conflict resolution |
| `git push` | all that commit | Push changes to remote |

**Task management commands (recipes that use task tracking):**

| Command | Recipes | Purpose |
|---------|---------|---------|
| `bd ready --limit 1` | implement-and-review | Get next available task |
| `bd add` | break-down-tasks | Create epics and tasks |
| `bd close <id>` | implement-and-review, review-and-commit | Mark task complete |
| `bd update <id>` | review-and-commit | Update task status |
| `bd dep add` | break-down-tasks | Create task dependencies |
| `bd list` | break-down-tasks | List all tasks |
| `bd blocked` | break-down-tasks | View blocked tasks |
| `bd show <id>` | break-down-tasks | View task details |
| `bd edit <id>` | break-down-tasks | Update task description |
| `bd delete <id>` | break-down-tasks | Remove task |

### 15.2 File Convention References

Prompts reference project files the agent should read autonomously:

| Reference | Recipes | Purpose |
|-----------|---------|---------|
| `@STANDARDS.md` | implement-and-review | Coding conventions and standards |
| `@CLAUDE.md` | implement-and-review | Project-specific agent instructions |
| `@path/to/design.md` | break-down-tasks, implement-and-review | Design document for implementation guidance |
| `beads/` directory | break-down-tasks | Task data storage |

### 15.3 Agent Capabilities Required

| Capability | Recipes | How Used |
|-----------|---------|---------|
| Read files | All | Review source, read standards, read design docs |
| Write/edit files | document-design, refine-design, implement-and-review | Create docs, implement code |
| Execute shell commands | All except retrospective | Git, bd, tests, builds |
| Spawn subagents | rebase | Delegate conflict review to parallel subagent |
| Make subjective decisions | All | "Report issues", "evaluate quality", "decide if complete" |

### 15.4 Generic vs. Project-Specific Recipes

| Recipe | Portability | Dependencies |
|--------|-------------|--------------|
| `review-and-commit` | **Generic** | Git only |
| `rebase` | **Generic** | Git only (hardcodes `main` branch) |
| `retrospective` | **Generic** | None (read-only) |
| `document-design` | **Mostly generic** | Git + project conventions for doc location |
| `refine-design` | **Mostly generic** | Git + project conventions for alignment check |
| `break-down-tasks` | **Project-specific** | Requires `bd` (beads) task management tool |
| `implement-and-review` | **Project-specific** | Requires `bd`, @STANDARDS.md, @CLAUDE.md |
| `implement-and-review-all` | **Project-specific** | Same as implement-and-review + session restart |

**For agent-recipes CLI:** The generic recipes should work out-of-the-box with any codebase. Project-specific recipes need either:
1. The external tools installed (bd), or
2. The prompts rewritten to remove/replace those dependencies

---

## 16. WebSocket Protocol (Reference Architecture)

The voice-code system uses a WebSocket protocol between an iOS client and the backend server. While agent-recipes is a CLI (not a WebSocket server), this protocol documents the events a UI could consume:

### 16.1 Client → Server Messages

| Message Type | Fields | Purpose |
|-------------|--------|---------|
| `start_recipe` | `recipe_id`, `session_id`, `working_directory` | Begin recipe execution |
| `exit_recipe` | `session_id` | Cancel in-progress recipe |
| `get_available_recipes` | — | List all recipes with metadata |

### 16.2 Server → Client Messages

| Message Type | Fields | Purpose |
|-------------|--------|---------|
| `recipe_started` | `recipe_id`, `recipe_label`, `session_id`, `current_step`, `step_count` | Recipe execution began |
| `recipe_step_started` | `session_id`, `step`, `step_count` | New step beginning |
| `recipe_step_transition` | `session_id`, `from_step`, `to_step` | Step transition occurred |
| `orchestration_retry` | `session_id`, `step`, `error` | Outcome parse failed, retrying |
| `recipe_exited` | `session_id`, `reason`, `error` | Recipe completed or failed |
| `turn_complete` | `session_id` | Recipe finished, session idle |
| `session_locked` | `session_id` | Session busy, cannot accept prompt |

### 16.3 CLI Equivalent Events

For the CLI tool, these events map to structured log output (verbose mode):

```
[recipe:started] implement-and-review (session: abc-123)
[step:started]   implement (visit 1/3, total 1/100)
[step:outcome]   complete
[step:transition] implement → code-review
[step:started]   code-review (visit 1/3, total 2/100)
[step:outcome]   no-issues
[step:transition] code-review → commit
[step:started]   commit (visit 1/3, total 3/100) [model: haiku]
[step:outcome]   committed
[recipe:exited]  changes-committed
```

---

## 17. Backend Process Protocol (Claude Code Reference Implementation)

### 17.1 Full Invocation Example

```bash
# First step (new session):
claude \
  --dangerously-skip-permissions \
  --print \
  --output-format json \
  --model opus \
  --session-id "550e8400-e29b-41d4-a716-446655440000" \
  "Implement the current task from beads.\n\n## Prerequisites\n..."

# Subsequent step (resume session):
claude \
  --dangerously-skip-permissions \
  --print \
  --output-format json \
  --model sonnet \
  --resume "550e8400-e29b-41d4-a716-446655440000" \
  "Perform a thorough code review on the changes.\n\n## Review Process\n..."

# Retry (resume same session, reminder prompt):
claude \
  --dangerously-skip-permissions \
  --print \
  --output-format json \
  --resume "550e8400-e29b-41d4-a716-446655440000" \
  "Your previous response did not include the required JSON outcome block..."
```

### 17.2 Response Parsing

```json
[
  {
    "type": "result",
    "session_id": "550e8400-e29b-41d4-a716-446655440000",
    "is_error": false,
    "result": "I reviewed the code changes...\n\n{\"outcome\": \"no-issues\"}",
    "usage": {"input_tokens": 5200, "output_tokens": 1800},
    "total_cost_usd": 0.042
  }
]
```

**Parsing steps:**
1. Parse stdout as JSON array
2. Filter for objects where `type == "result"`
3. Check `is_error == false`
4. Extract `result` field as the agent's text response
5. Pass text to outcome extraction (§2.3)

### 17.3 Session File Storage

Claude Code stores session history in JSONL files:
```
~/.claude/projects/<project-hash>/<session-id>.jsonl
```

Each line is a JSON object representing a message in the conversation. The orchestrator does not read these directly — it only interacts via the CLI.

---

## 18. Recipe Composition & Step Reuse

### 18.1 Step Inheritance Model

The reference implementation uses three composition patterns:

**1. Shared step maps:** Define a map of steps once, reuse across recipes.
```
review-commit-steps = {code-review, fix, commit}

review-and-commit.steps     = review-commit-steps
implement-and-review.steps  = review-commit-steps + {implement}
```

**2. Step override:** A recipe can replace a single step while inheriting the rest.
```
implement-and-review-all.steps = review-commit-steps + {implement}
                                 but override commit.onOutcome.committed → restart-new-session
```

**3. Standalone steps:** Some recipes define all steps inline with no sharing.
```
rebase.steps        = {rebase, review, fix, complete}  (no sharing)
retrospective.steps = {reflect}                         (no sharing)
```

### 18.2 JSON Implementation

For JSON-based recipes, composition can use either:

**Approach A: `$ref` references to shared step files**
```json
{
  "steps": {
    "implement": { "prompt": "...", "outcomes": [...], "onOutcome": {...} },
    "code-review": { "$ref": "shared/review-commit-steps.json#/code-review" },
    "fix": { "$ref": "shared/review-commit-steps.json#/fix" },
    "commit": { "$ref": "shared/review-commit-steps.json#/commit" }
  }
}
```

**Approach B: Merge at load time (programmatic)**
```java
Map<String, Step> steps = new HashMap<>();
steps.putAll(loadSharedSteps("shared/review-commit-steps.json"));
steps.put("implement", loadStep("implement.json"));
recipe.setSteps(steps);
```

**Approach C: All inline (simplest, some duplication)**
Each recipe file is fully self-contained. Step reuse is a copy-paste concern managed by tooling or tests.

---

## 19. Observability & Debugging

### 19.1 Structured Logging Fields

Every orchestration event should include:

| Field | Description |
|-------|-------------|
| `sessionId` | Agent session UUID |
| `recipeId` | Recipe being executed |
| `step` | Current step name |
| `stepCount` | Total steps so far |
| `visitCount` | Times this step has been visited |
| `outcome` | Extracted outcome (on success) |
| `error` | Error message (on failure) |
| `model` | Resolved model used for this step |
| `promptLength` | Character count of the full prompt sent |
| `responseLength` | Character count of the agent's response |
| `durationMs` | Wall-clock time for the step |
| `costUsd` | Cost reported by backend (if available) |

### 19.2 Cost Tracking

The Claude Code backend reports per-invocation cost:
```json
{"usage": {"input_tokens": 5200, "output_tokens": 1800}, "total_cost_usd": 0.042}
```

The orchestrator should accumulate:
- Total cost across all steps in the recipe
- Per-step cost breakdown
- Report total cost on recipe exit

### 19.3 Dry-Run Mode

`--dry-run` renders the recipe as a state machine visualization without executing:

```
Recipe: implement-and-review
  Label: Implement & Review
  Description: Implement task, review code, fix issues, and commit
  Guardrails: maxStepVisits=3, maxTotalSteps=100, exitOnOther=true

  Steps:
    implement (initial)
      Outcomes: complete → code-review, no-tasks → EXIT, blocked → EXIT, other → EXIT
      Model: (default)
    code-review
      Outcomes: no-issues → commit, issues-found → fix, other → EXIT
      Model: (default)
    fix
      Outcomes: complete → code-review, other → EXIT
      Model: (default)
    commit
      Outcomes: committed → EXIT, nothing-to-commit → EXIT, other → EXIT
      Model: haiku
```

---

## 20. Testing Strategy

### 20.1 Recipe Definition Tests

For each recipe, verify:

1. **Structural validity** — `validate-recipe` returns no errors
2. **Initial step exists** — `initialStep` is in `steps` map
3. **All transitions valid** — every `nextStep` target exists, every `exit` has `reason`
4. **Outcome coverage** — every outcome in `outcomes` has a corresponding `onOutcome` entry
5. **Model validity** — all `model` values are in `{haiku, sonnet, opus}`
6. **Guardrails present** — `maxStepVisits` and `maxTotalSteps` are positive integers

### 20.2 Orchestration Engine Tests

1. **State initialization** — creates correct initial state (step=initial, count=1, visits={initial:1})
2. **Transition determination** — given step + outcome, returns correct next action
3. **Guardrail enforcement** — exits when max visits or max total exceeded
4. **Outcome extraction** — parses valid JSON, rejects invalid, handles fences
5. **Retry logic** — retries once, exits on second failure
6. **Restart-new-session** — creates fresh state with new session ID

### 20.3 Shared Step Tests

1. **DRY verification** — steps shared between recipes are identical (same object, not just equal)
2. **Override verification** — implement-and-review-all's commit step differs only in the `committed` transition

### 20.4 Integration Tests

1. **Mock backend** — simulate agent responses with embedded JSON outcomes
2. **Full recipe execution** — run a recipe end-to-end with deterministic mock responses
3. **Error paths** — simulate JSON parse failures, process timeouts, guardrail hits
4. **Restart-new-session** — verify session ID changes and state resets
