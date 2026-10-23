// What a credential's verification link says, as plain text.
//
// Plain text rather than a page: Supabase serves function responses from its
// own domain as text, and a verifier needs the facts, not a layout. Kept apart
// from the function so it can be tested without a database.

export type Verified = {
  key: string;
  pathway: string;
  holder: string;
  scope: { competencies?: string[]; app?: string; testers?: number; days?: number };
  rubric: number;
  version: number;
  issued_at: string;
  revoked_at: string | null;
  valid: boolean;
};

const NAMES: Record<string, string> = {
  "foundations": "Ship to Play: Foundations",
  "capstone": "Ship to Play: Closed test run",
  "ship-to-play": "Ship to Play",
};

const day = (iso: string) => iso.slice(0, 10);

/** A token is 32 lowercase hex characters; anything else names nothing. */
export const wellFormed = (token: string | null): token is string =>
  token !== null && /^[0-9a-f]{32}$/.test(token);

export function describe(c: Verified | null): string {
  if (!c) {
    return "OneDevs could not find a credential for this link.\n";
  }
  const lines = [
    `OneDevs credential: ${NAMES[c.key] ?? c.key}`,
    `Holder: ${c.holder}`,
    `Issued: ${day(c.issued_at)}`,
    c.valid
      ? "Status: valid"
      : `Status: revoked on ${c.revoked_at ? day(c.revoked_at) : "an unknown date"}`,
  ];
  if (c.scope.competencies?.length) {
    lines.push(`Competencies verified: ${c.scope.competencies.join(", ")}`);
  }
  if (c.scope.app) {
    lines.push(
      `Closed test: ${c.scope.app}, used by ${c.scope.testers} testers on ${c.scope.days} different days`,
    );
  }
  lines.push(`Rubric ${c.rubric}, content version ${c.version}.`);
  lines.push("");
  lines.push(
    "Competencies are verified from answers given without hints and, where",
    "marked, from a release build the holder's own Lab checked. The closed",
    "test is counted from OneDevs' own records of who used the app.",
  );
  return lines.join("\n") + "\n";
}
