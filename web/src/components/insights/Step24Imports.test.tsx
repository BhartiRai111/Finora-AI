// Component tests with a mocked fetch (no real backend, no real browser). The backend's own behaviour for these cases is covered by the Java end-to-end suite.
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FinanceProvider } from "@/lib/FinanceContext";
import type { Account } from "@/lib/finance";
import { mockApi, problem, renderWithSession, USER } from "@/test-utils";
import { ImportsPanel } from "./ImportsPanel";

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

const me = { "GET /v1/me": () => ({ status: 200, body: USER }) };
const wrap = (ui: React.ReactElement) => renderWithSession(<FinanceProvider space="p1">{ui}</FinanceProvider>);
const ACCOUNT: Account = { id: "a1", name: "Main", type: "BANK", currency: "INR", opening_balance: "0.00", opening_date: "2026-01-01", balance: "0.00", liability: false, archived: false, version: 0 };
const LIST = (items: unknown[] = [], total = items.length) => ({ items, total, limit: 20, offset: 0 });
const JOB = {
  id: "j1", kind: "TRANSACTIONS", status: "NEEDS_MAPPING", filename: "bank.csv", account_id: "a1", currency: "INR", total_rows: 3, valid_rows: 0, invalid_rows: 0, duplicate_rows: 0, possible_duplicate_rows: 0,
  committed_rows: 0, skipped_rows: 0, rejected_rows: 0, possible_duplicate_policy: null, headers: ["Txn Date", "Amount", "Narration"], mapping: {}, options: {}, required_fields: ["date", "amount"],
  optional_fields: ["description"], suggested_mapping: { date: "Txn Date", amount: "Amount", description: "Narration" }, sample: [["2026-03-01", "-10.00", "Tea"]], created_at: "2026-03-15T10:00:00Z", completed_at: null,
};
const csv = (name = "bank.csv") => new File(["Txn Date,Amount,Narration\n2026-03-01,-10.00,Tea\n"], name, { type: "text/csv" });
const history = { "GET /v1/spaces/p1/finance-imports?limit=20&offset=0": () => ({ status: 200, body: LIST() }) };

async function pick(file: File, applyAccept = true) {
  await userEvent.selectOptions(await screen.findByLabelText("Account"), "a1");
  await userEvent.upload(screen.getByLabelText(/^CSV file \(UTF-8/), file, { applyAccept });
}

describe("import file validation (mocked fetch)", () => {
  it.each([["statement.pdf", "document"], ["photo.png", "image"], ["data.json", "json"]])("refuses %s as not CSV before anything is sent", async (name) => {
    const { fn } = mockApi({ ...me, ...history });
    wrap(<ImportsPanel domain="PERSONAL" canWrite accounts={[ACCOUNT]} />);
    await pick(new File(["x"], name), false);
    await userEvent.click(screen.getByRole("button", { name: "Upload and continue" }));
    expect(await screen.findByText(/Only CSV files are supported/)).toBeInTheDocument();
    expect(fn.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST")).toBe(false);
  });

  it("refuses an empty file", async () => {
    mockApi({ ...me, ...history });
    wrap(<ImportsPanel domain="PERSONAL" canWrite accounts={[ACCOUNT]} />);
    await pick(new File([], "empty.csv", { type: "text/csv" }));
    await userEvent.click(screen.getByRole("button", { name: "Upload and continue" }));
    expect(await screen.findByText("The file is empty.")).toBeInTheDocument();
  });

  it("shows the server's own words for a file it refuses (too large, too many rows, not readable) and creates no import", async () => {
    const answers = [
      problem(413, "PAYLOAD_TOO_LARGE", "The file is larger than 2 MB"),
      problem(400, "VALIDATION_FAILED", "The file has more than 5000 rows"),
      problem(400, "VALIDATION_FAILED", "The file is not valid UTF-8 text"),
      problem(415, "UNSUPPORTED_MEDIA_TYPE"),
    ];
    let i = 0;
    mockApi({ ...me, ...history, "POST /v1/spaces/p1/finance-imports?kind=TRANSACTIONS&filename=bank.csv&account_id=a1": () => answers[i++]! });
    wrap(<ImportsPanel domain="PERSONAL" canWrite accounts={[ACCOUNT]} />);
    await pick(csv());
    for (const expected of [/larger than 2 MB/, /more than 5000 rows/, /not valid UTF-8/, /kind of file is not supported/]) {
      await userEvent.click(screen.getByRole("button", { name: "Upload and continue" }));
      expect(await screen.findByRole("alert")).toHaveTextContent(expected);
    }
    expect(screen.queryByRole("form", { name: "Column mapping" })).toBeNull();
    expect(screen.getByRole("button", { name: "Upload and continue" })).toBeEnabled();
  });
});

describe("import upload is safe to repeat (mocked fetch)", () => {
  it("keeps one idempotency key for the same file across a failed attempt, and uses a new one for the next file", async () => {
    const keys: string[] = [];
    let fail = true;
    mockApi({
      ...me, ...history,
      "POST /v1/spaces/p1/finance-imports?kind=TRANSACTIONS&filename=bank.csv&account_id=a1": ({ headers }) => { keys.push(headers["Idempotency-Key"] ?? ""); return fail ? problem(503, "DOWNSTREAM_UNAVAILABLE") : { status: 201, body: JOB }; },
      "POST /v1/spaces/p1/finance-imports?kind=TRANSACTIONS&filename=next.csv&account_id=a1": ({ headers }) => { keys.push(headers["Idempotency-Key"] ?? ""); return { status: 201, body: { ...JOB, id: "j2", filename: "next.csv" } }; },
      "GET /v1/spaces/p1/finance-imports/j1": () => ({ status: 200, body: JOB }),
      "GET /v1/spaces/p1/finance-imports/j2": () => ({ status: 200, body: { ...JOB, id: "j2" } }),
    });
    wrap(<ImportsPanel domain="PERSONAL" canWrite accounts={[ACCOUNT]} />);
    await pick(csv());
    await userEvent.click(screen.getByRole("button", { name: "Upload and continue" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("temporarily unavailable");
    fail = false;
    await userEvent.click(screen.getByRole("button", { name: "Upload and continue" }));
    expect(await screen.findByRole("form", { name: "Column mapping" })).toBeInTheDocument();
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
    await userEvent.upload(screen.getByLabelText(/^CSV file \(UTF-8/), csv("next.csv"));
    await userEvent.click(screen.getByRole("button", { name: "Upload and continue" }));
    await waitFor(() => expect(keys).toHaveLength(3));
    expect(keys[2]).not.toBe(keys[0]);
  });

  it("sends one upload for a double click and shows the busy state", async () => {
    let release: () => void = () => undefined;
    let posts = 0;
    mockApi({
      ...me, ...history,
      "POST /v1/spaces/p1/finance-imports?kind=TRANSACTIONS&filename=bank.csv&account_id=a1": () => { posts += 1; return new Promise((r) => { release = () => r({ status: 201, body: JOB }); }); },
      "GET /v1/spaces/p1/finance-imports/j1": () => ({ status: 200, body: JOB }),
    });
    wrap(<ImportsPanel domain="PERSONAL" canWrite accounts={[ACCOUNT]} />);
    await pick(csv());
    const button = screen.getByRole("button", { name: "Upload and continue" });
    await userEvent.click(button);
    expect(await screen.findByRole("button", { name: "Uploading…" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Uploading…" }));
    expect(posts).toBe(1);
    await act(async () => release());
    expect(await screen.findByRole("form", { name: "Column mapping" })).toBeInTheDocument();
  });

  it("tells the person when a file with the same name is already in the history, without blocking it", async () => {
    mockApi({ ...me, "GET /v1/spaces/p1/finance-imports?limit=20&offset=0": () => ({ status: 200, body: LIST([{ ...JOB, status: "COMPLETED", committed_rows: 3 }]) }) });
    wrap(<ImportsPanel domain="PERSONAL" canWrite accounts={[ACCOUNT]} />);
    await screen.findByRole("table");
    await pick(csv("bank.csv"));
    expect(await screen.findByText(/already in the history below/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Upload and continue" })).toBeEnabled();
  });
});

describe("import preview, history and progress (mocked fetch)", () => {
  it("shows the first rows of the file beside the column mapping before anything is imported", async () => {
    mockApi({ ...me, "GET /v1/spaces/p1/finance-imports?limit=20&offset=0": () => ({ status: 200, body: LIST([JOB]) }), "GET /v1/spaces/p1/finance-imports/j1": () => ({ status: 200, body: JOB }) });
    wrap(<ImportsPanel domain="PERSONAL" canWrite accounts={[ACCOUNT]} />);
    await userEvent.click(await screen.findByRole("button", { name: "Open bank.csv" }));
    const sample = await screen.findByRole("table", { name: /first rows of your file/ });
    expect(within(sample).getByText("Tea")).toBeInTheDocument();
    expect(within(sample).getByText("-10.00")).toBeInTheDocument();
  });

  it("pages through the import history using the server's offset", async () => {
    const offsets: string[] = [];
    const page = (n: number, name: string) => ({ status: 200, body: { items: [{ ...JOB, id: `j${n}`, filename: name }], total: 25, limit: 20, offset: n === 1 ? 0 : 20 } });
    mockApi({
      ...me,
      "GET /v1/spaces/p1/finance-imports?limit=20&offset=0": () => { offsets.push("0"); return page(1, "first.csv"); },
      "GET /v1/spaces/p1/finance-imports?limit=20&offset=20": () => { offsets.push("20"); return page(2, "second.csv"); },
    });
    wrap(<ImportsPanel domain="PERSONAL" canWrite accounts={[ACCOUNT]} />);
    expect(await screen.findByText("first.csv")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("second.csv")).toBeInTheDocument();
    expect(offsets).toEqual(["0", "20"]);
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
  });

  it("pages through the rows of an import and keeps the status filter", async () => {
    const done = { ...JOB, status: "FAILED", total_rows: 120, rejected_rows: 120 };
    const asked: string[] = [];
    const rows = (from: number) => ({ status: 200, body: { items: [{ row_number: from, status: "REJECTED", errors: [{ field: "date", message: "bad date" }], raw: ["x"], normalized: {}, possible_duplicate_of: null }], total: 120, limit: 50, offset: from - 2 } });
    mockApi({
      ...me,
      "GET /v1/spaces/p1/finance-imports?limit=20&offset=0": () => ({ status: 200, body: LIST([done]) }),
      "GET /v1/spaces/p1/finance-imports/j1": () => ({ status: 200, body: done }),
      "GET /v1/spaces/p1/finance-imports/j1/rows?limit=50&offset=0": () => { asked.push("0"); return rows(2); },
      "GET /v1/spaces/p1/finance-imports/j1/rows?limit=50&offset=50": () => { asked.push("50"); return rows(52); },
      "GET /v1/spaces/p1/finance-imports/j1/rows?limit=50&offset=0&status=REJECTED": () => { asked.push("filter"); return rows(2); },
    });
    wrap(<ImportsPanel domain="PERSONAL" canWrite accounts={[ACCOUNT]} />);
    await userEvent.click(await screen.findByRole("button", { name: "Open bank.csv" }));
    await userEvent.click(await screen.findByRole("button", { name: "Next rows" }));
    await waitFor(() => expect(asked).toContain("50"));
    await userEvent.selectOptions(screen.getByLabelText("Show rows"), "REJECTED");
    await waitFor(() => expect(asked).toContain("filter"));
    expect(screen.getByText(/Nothing was imported: 120 rows were rejected/)).toBeInTheDocument();
  });

  it("keeps checking an import that is still running and shows it as finished only when the server says so", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    let committed = false;
    const running = { ...JOB, status: "COMMITTING", valid_rows: 3, mapping: JOB.suggested_mapping };
    const finished = { ...running, status: "COMPLETED", committed_rows: 3 };
    let polls = 0;
    mockApi({
      ...me,
      "GET /v1/spaces/p1/finance-imports?limit=20&offset=0": () => ({ status: 200, body: LIST([running]) }),
      "GET /v1/spaces/p1/finance-imports/j1": () => { polls += 1; return { status: 200, body: committed ? finished : running }; },
      "GET /v1/spaces/p1/finance-imports/j1/rows?limit=50&offset=0": () => ({ status: 200, body: { items: [], total: 0, limit: 50, offset: 0 } }),
    });
    wrap(<ImportsPanel domain="PERSONAL" canWrite accounts={[ACCOUNT]} />);
    await userEvent.setup({ advanceTimers: vi.advanceTimersByTime }).click(await screen.findByRole("button", { name: "Open bank.csv" }));
    expect(await screen.findByText(/The import is running/)).toBeInTheDocument();
    expect(screen.queryByText(/Import finished/)).toBeNull();
    const before = polls;
    committed = true;
    await act(async () => { vi.advanceTimersByTime(3100); });
    expect(await screen.findByText(/Import finished: 3 records created/)).toBeInTheDocument();
    expect(polls).toBeGreaterThan(before);
  });

  it("shows a read-only role the history and details but no upload, mapping or confirm controls", async () => {
    const ready = { ...JOB, status: "READY", valid_rows: 1, mapping: JOB.suggested_mapping };
    mockApi({
      ...me,
      "GET /v1/spaces/p1/finance-imports?limit=20&offset=0": () => ({ status: 200, body: LIST([ready]) }),
      "GET /v1/spaces/p1/finance-imports/j1": () => ({ status: 200, body: ready }),
      "GET /v1/spaces/p1/finance-imports/j1/rows?limit=50&offset=0": () => ({ status: 200, body: { items: [], total: 0, limit: 50, offset: 0 } }),
    });
    wrap(<ImportsPanel domain="PERSONAL" canWrite={false} accounts={[ACCOUNT]} />);
    await userEvent.click(await screen.findByRole("button", { name: "Open bank.csv" }));
    await screen.findByText(/Ready to import/, { selector: "strong" });
    expect(screen.queryByRole("button", { name: "Upload and continue" })).toBeNull();
    expect(screen.queryByRole("group", { name: "Confirm the import" })).toBeNull();
    expect(screen.queryByRole("form", { name: "Column mapping" })).toBeNull();
  });

  it("fetches the rows again after the file is checked, so the preview never shows rows from before the mapping", async () => {
    let checked = false;
    const ready = { ...JOB, status: "READY", valid_rows: 1, invalid_rows: 1, mapping: JOB.suggested_mapping, options: { date_format: "ISO", decimal_separator: "." }, suggested_mapping: null };
    mockApi({
      ...me,
      "GET /v1/spaces/p1/finance-imports?limit=20&offset=0": () => ({ status: 200, body: LIST([JOB]) }),
      "GET /v1/spaces/p1/finance-imports/j1": () => ({ status: 200, body: checked ? ready : JOB }),
      "PUT /v1/spaces/p1/finance-imports/j1/mapping": () => { checked = true; return { status: 200, body: ready }; },
      "GET /v1/spaces/p1/finance-imports/j1/rows?limit=50&offset=0": () => ({ status: 200, body: { items: [checked
        ? { row_number: 1, status: "INVALID", errors: [{ field: "date", message: "bad date" }], raw: ["x"], normalized: {}, possible_duplicate_of: null }
        : { row_number: 1, status: "PARSED", errors: [], raw: ["x"], normalized: {}, possible_duplicate_of: null }], total: 1, limit: 50, offset: 0 } }),
    });
    wrap(<ImportsPanel domain="PERSONAL" canWrite accounts={[ACCOUNT]} />);
    await userEvent.click(await screen.findByRole("button", { name: "Open bank.csv" }));
    await userEvent.click(await screen.findByRole("button", { name: "Check the file" }));
    expect(await screen.findByText("bad date")).toBeInTheDocument();
  });
});
