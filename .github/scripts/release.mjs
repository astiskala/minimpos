/** Version preparation and integrity checks for CI-built release APKs. */
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";

export const APK = "app-release-unsigned.apk";
export const MANIFEST = "release.json";
export const UPDATE_ASSET = "update.json";
export const MAX_CODE = 2_100_000_000;

/** Read the current Android version, rejecting ambiguous properties. */
export function readVersion(path) {
  const text = readFileSync(path, "utf8");
  const names = [...text.matchAll(/^versionName=(.*)$/gm)].map((match) => match[1]);
  const codes = [...text.matchAll(/^versionCode=(.*)$/gm)].map((match) => match[1]);
  const code = Number(codes[0]);
  if (
    names.length !== 1 ||
    !/^\d+\.\d+\.\d+$/.test(names[0]) ||
    codes.length !== 1 ||
    !/^\d+$/.test(codes[0]) ||
    !Number.isSafeInteger(code) ||
    code < 1 ||
    code > MAX_CODE
  ) {
    throw new Error("Invalid version.properties");
  }
  return { name: names[0], code };
}

/** Raise the requested component and Android code, preserving other text. */
export function bumpVersion(path, bump) {
  const { name, code } = readVersion(path);
  const index = ["major", "minor", "patch"].indexOf(bump);
  if (index < 0 || code === MAX_CODE) {
    throw new Error("Invalid version bump or exhausted Android versionCode");
  }
  const parts = name.split(".").map(BigInt);
  parts[index] += 1n;
  parts.fill(0n, index + 1);
  const next = parts.join(".");
  const text = readFileSync(path, "utf8")
    .replace(/^versionName=.*$/m, `versionName=${next}`)
    .replace(/^versionCode=.*$/m, `versionCode=${code + 1}`);
  writeFileSync(path, text);
  return { name: next, code: code + 1 };
}

/** A resumable candidate's version came from a version-only release commit. */
export function isCandidate(subject, files, name) {
  return subject === `Release ${name}` && files === "version.properties";
}

/** Only a new main CI run for this exact version commit may supply an artifact. */
export function selectCiRun(runs, commit, notBefore) {
  return runs.find((run) =>
    run.head_sha === commit &&
    run.head_branch === "main" &&
    run.event === "workflow_dispatch" &&
    run.created_at >= notBefore
  ) ?? null;
}

/** Bind the APK bytes to the checked commit, version and CI run attempt. */
export function metadata(version, artifact, commit, runId, attempt) {
  if (!/^[0-9a-f]{40}$/.test(commit)) {
    throw new Error("Expected a full commit SHA");
  }
  if (!/^[1-9]\d*$/.test(runId) || !/^[1-9]\d*$/.test(attempt)) {
    throw new Error("Expected a CI run ID and attempt");
  }
  const { name, code } = readVersion(version);
  return {
    commit,
    runId,
    runAttempt: attempt,
    versionName: name,
    versionCode: code,
    apkSha256: createHash("sha256").update(readFileSync(join(artifact, APK))).digest("hex"),
  };
}

/** The release's update metadata asset, which the app reads to compare version codes; one current format. */
export function updateMetadata(version) {
  const { name, code } = readVersion(version);
  return { versionName: name, versionCode: code, apk: `minimpos-${name}.apk` };
}

/** Reject missing, corrupt, wrong-commit or wrong-attempt artifacts. */
export function checkManifest(version, artifact, commit, runId, attempt) {
  const actual = JSON.parse(readFileSync(join(artifact, MANIFEST), "utf8"));
  const expected = metadata(version, artifact, commit, runId, attempt);
  if (
    actual === null ||
    Object.keys(actual).length !== Object.keys(expected).length ||
    !Object.entries(expected).every(([key, value]) => actual[key] === value)
  ) {
    throw new Error("Release artifact does not match the verified CI run, version or APK");
  }
}

function main() {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: Object.fromEntries(
      ["version", "artifact", "commit", "run-id", "run-attempt", "subject", "files", "runs", "not-before", "out"].map((key) => [
        key,
        { type: "string" },
      ]),
    ),
  });
  const version = values.version ?? "version.properties";
  const [command, component] = positionals;
  if (command === "bump") {
    bumpVersion(version, component);
  } else if (command === "candidate") {
    if (!isCandidate(values.subject, values.files, readVersion(version).name)) {
      throw new Error("Resume requires an unpublished version-only release commit");
    }
  } else if (command === "manifest" || command === "verify") {
    const args = [version, values.artifact, values.commit, values["run-id"], values["run-attempt"]];
    if (command === "manifest") {
      writeFileSync(join(values.artifact, MANIFEST), `${JSON.stringify(metadata(...args))}\n`);
    } else {
      checkManifest(...args);
    }
  } else if (command === "ci") {
    const runs = JSON.parse(readFileSync(values.runs, "utf8")).workflow_runs;
    console.log(JSON.stringify(selectCiRun(runs, values.commit, values["not-before"])));
  } else if (command === "update") {
    writeFileSync(values.out ?? UPDATE_ASSET, `${JSON.stringify(updateMetadata(version))}\n`);
  } else {
    throw new Error("Expected bump, candidate, manifest, verify, ci or update");
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
