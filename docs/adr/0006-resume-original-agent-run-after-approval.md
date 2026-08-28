# Resume the original Agent Run after human approval

When a Harness tool call requires human approval, the product keeps the existing Agent Run in a waiting state and resumes that same run with an AgentScope confirmation message. It does not model approval as an ordinary chat message or create a replacement run, because both alternatives lose the pending tool-call identity, break idempotency, and split one user-visible execution across unrelated business records; the cost is an explicit persisted approval state machine and restart-safe recovery from AgentState.
