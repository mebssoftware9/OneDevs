import { describe, type Verified, wellFormed } from "./credential.ts";

function assert(ok: boolean, what: string) {
  if (!ok) throw new Error(what);
}

const issued: Verified = {
  key: "ship-to-play",
  pathway: "ship-to-play",
  holder: "Ada",
  scope: { competencies: ["closed-testing", "signing"], app: "Pocket Ledger", testers: 13, days: 15 },
  rubric: 1,
  version: 1,
  issued_at: "2026-10-23T10:00:00Z",
  revoked_at: null,
  valid: true,
};

Deno.test("only a 32-character hex token is looked up", () => {
  assert(wellFormed("0123456789abcdef0123456789abcdef"), "a real token");
  assert(!wellFormed(null), "no token");
  assert(!wellFormed("0123456789ABCDEF0123456789ABCDEF"), "upper case is not what the server issues");
  assert(!wellFormed("' or 1=1 --"), "anything else");
});

Deno.test("a valid credential says who, what, when and from what", () => {
  const text = describe(issued);
  assert(text.includes("OneDevs credential: Ship to Play\n"), "name");
  assert(text.includes("Holder: Ada"), "holder");
  assert(text.includes("Issued: 2026-10-23"), "date");
  assert(text.includes("Status: valid"), "status");
  assert(text.includes("closed-testing, signing"), "competencies");
  assert(text.includes("Pocket Ledger, used by 13 testers on 15 different days"), "capstone");
});

Deno.test("a revoked credential says so, and an unknown link says nothing about anyone", () => {
  assert(
    describe({ ...issued, valid: false, revoked_at: "2026-11-01T00:00:00Z" }).includes("revoked on 2026-11-01"),
    "revoked",
  );
  assert(!describe(null).includes("Holder"), "unknown");
});
