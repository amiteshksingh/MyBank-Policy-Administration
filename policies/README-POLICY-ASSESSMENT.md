# Existing OPA policy assessment for phases 12-14

No existing Rego file is replaced in these phases. The current decision endpoints are retained as PolicyTarget metadata:

- `pbac.prompt/decision` -> PROMPT
- `pbac.tool/result` -> TOOL
- `pbac.rag/decision` -> DATA
- `pbac.output/decision` -> OUTPUT
- `pbac.authorize/decision` -> GLOBAL
- `pbac.document_access/result` should later be classified as OTHER with subtype `DOCUMENT_ACCESS` if it remains a separate checkpoint.

Observed cleanup items reserved for the next integration phase:

1. Decision rule names are inconsistent (`decision` vs `result`).
2. Banking and hospitality rules are combined in the same modules.
3. Roles, SPIFFE IDs, purposes and tool names are hard-coded inside Rego.
4. `common.rego` depends on `data.pbac.config`, while the other modules duplicate parts of that logic.
5. Current modules do not read `data.mybank.runtime.*`; phases 12-14 only prove structured storage.

Changing these now would mix storage verification with evaluation changes. The next phase should add the smallest possible runtime lookup, beginning with TOOL.
