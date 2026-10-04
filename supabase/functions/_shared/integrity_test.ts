import { digestAsGoogleReports, judge, type Payload, sessionOf, sha256Hex } from "./integrity.ts";

function assert(ok: boolean, what: string) {
  if (!ok) throw new Error(what);
}

const now = 1_800_000_000_000;
const expect = { packageName: "com.devbangs.onedevs", requestHash: "abc", certDigests: [] as string[], now };
const genuine = (): Payload => ({
  requestDetails: { requestPackageName: "com.devbangs.onedevs", requestHash: "abc", timestampMillis: String(now - 1000) },
  appIntegrity: { appRecognitionVerdict: "PLAY_RECOGNIZED", certificateSha256Digest: ["sig"] },
  deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_BASIC_INTEGRITY", "MEETS_DEVICE_INTEGRITY"] },
  accountDetails: { appLicensingVerdict: "LICENSED" },
});

Deno.test("the real app on a genuine phone passes", () => {
  const v = judge(genuine(), expect);
  assert(v.passed && v.reason === null, JSON.stringify(v));
});

Deno.test("everything else fails, each for its own reason", () => {
  const cases: [string, (p: Payload) => void, Partial<typeof expect>?][] = [
    ["wrong_package", (p) => (p.requestDetails!.requestPackageName = "com.evil")],
    ["wrong_request", (p) => (p.requestDetails!.requestHash = "another session")],
    ["wrong_request", (p) => delete p.requestDetails!.requestHash],
    ["stale", (p) => (p.requestDetails!.timestampMillis = String(now - 10 * 60_000))],
    ["stale", (p) => delete p.requestDetails!.timestampMillis],
    ["app_not_recognized", (p) => (p.appIntegrity!.appRecognitionVerdict = "UNRECOGNIZED_VERSION")],
    ["app_not_recognized", (p) => delete p.appIntegrity],
    ["device_not_trusted", (p) => (p.deviceIntegrity!.deviceRecognitionVerdict = ["MEETS_BASIC_INTEGRITY"])],
    ["device_not_trusted", (p) => delete p.deviceIntegrity],
    ["wrong_signature", () => {}, { certDigests: ["someone-else"] }],
  ];
  for (const [reason, spoil, over] of cases) {
    const p = genuine();
    spoil(p);
    const v = judge(p, { ...expect, ...over });
    assert(!v.passed && v.reason === reason, `${reason}: ${JSON.stringify(v)}`);
  }
});

Deno.test("the request hash is the SHA-256 of the access token, in hex", async () => {
  assert((await sha256Hex("abc")) === "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "sha256");
});

Deno.test("the session id is read from the token, and nothing else passes for one", () => {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  const id = "0b7e4c4a-3f1e-4a7c-9b4e-2f6a1c0d9e11";
  assert(sessionOf(`h.${b64({ session_id: id })}.s`) === id, "reads it");
  assert(sessionOf(`h.${b64({ session_id: "x'; drop" })}.s`) === null, "refuses a non-id");
  assert(sessionOf("not a token") === null, "refuses junk");
});

Deno.test("a digest pasted from Play Console matches Google's base64url form", () => {
  const hex = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89";
  const google = digestAsGoogleReports(hex);
  if (google !== "q83vASNFZ4mrze8BI0VniavN7wEjRWeJq83vASNFZ4k") throw new Error(google);
  if (digestAsGoogleReports(google) !== google) throw new Error("base64url should pass through");
});
