/** Offline regression tests for version preparation and artifact promotion. */
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, statSync, unlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { APK, MANIFEST, MAX_CODE, bumpVersion, checkManifest, isCandidate, metadata, readVersion, selectCiRun, updateMetadata } from "./release.mjs";

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), "minimpos-release-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const version = join(root, "version.properties");
  const artifact = join(root, "artifact");
  mkdirSync(artifact);
  writeFileSync(version, "# retained\nversionName=0.6.2\nversionCode=14\n");
  writeFileSync(join(artifact, APK), "unsigned APK fixture");
  const args = [version, artifact, "a".repeat(40), "123", "1"];
  return {
    root,
    version,
    artifact,
    args,
    save: () => writeFileSync(join(artifact, MANIFEST), JSON.stringify(metadata(...args))),
  };
}

test("patch, minor and major raise both values and preserve comments", (t) => {
  const { version } = fixture(t);
  for (const [bump, name] of [["patch", "0.6.3"], ["minor", "0.7.0"], ["major", "1.0.0"]]) {
    writeFileSync(version, "# retained\nversionName=0.6.2\nversionCode=14\n");
    assert.deepEqual(bumpVersion(version, bump), { name, code: 15 });
    assert.deepEqual(readVersion(version), { name, code: 15 });
    assert.ok(readFileSync(version, "utf8").startsWith("# retained\n"));
  }
});

test("invalid or exhausted versions do not get bumped", (t) => {
  const { version } = fixture(t);
  for (const text of [
    "versionName=0.6\nversionCode=14\n",
    "versionName=0.6.2\nversionCode=0\n",
    "versionName=0.6.2\nversionName=0.6.3\nversionCode=14\n",
    "versionName=0.6.2\nversionName=invalid\nversionCode=14\n",
    "versionName=0.6.2\nversionCode=14\nversionCode=15\n",
    `versionName=0.6.2\nversionCode=${MAX_CODE}\n`,
  ]) {
    writeFileSync(version, text);
    assert.throws(() => bumpVersion(version, "patch"));
    assert.equal(readFileSync(version, "utf8"), text);
  }
});

test("resume accepts only the version-only release commit", () => {
  assert.ok(isCandidate("Release 0.6.2", "version.properties", "0.6.2"));
  for (const [subject, files] of [
    ["Release 0.6.1", "version.properties"],
    ["Other change", "version.properties"],
    ["Release 0.6.2", "version.properties\napp/build.gradle.kts"],
    ["Release 0.6.2", ""],
  ]) {
    assert.equal(isCandidate(subject, files, "0.6.2"), false);
  }
});

test("CI selection rejects stale runs, other commits, branches and PRs", () => {
  const commit = "a".repeat(40);
  const since = "2026-10-05T00:00:00Z";
  const run = { head_sha: commit, head_branch: "main", event: "workflow_dispatch", created_at: since };
  assert.deepEqual(selectCiRun([run], commit, since), run);
  assert.equal(selectCiRun([], commit, since), null);
  for (const change of [
    { head_sha: "b".repeat(40) },
    { head_branch: "feature" },
    { event: "pull_request" },
    { created_at: "2026-10-04T23:59:59Z" },
  ]) {
    assert.equal(selectCiRun([{ ...run, ...change }], commit, since), null);
  }
  // Return a pending new run, not an older successful run for the same commit.
  const pending = { ...run, status: "queued", conclusion: null };
  const stale = { ...run, created_at: "2026-10-04T00:00:00Z", status: "completed", conclusion: "success" };
  assert.deepEqual(selectCiRun([pending, stale], commit, since), pending);
});

test("matching artifact passes regardless of manifest key order", (t) => {
  const f = fixture(t);
  const data = Object.fromEntries(Object.entries(metadata(...f.args)).reverse());
  writeFileSync(join(f.artifact, MANIFEST), JSON.stringify(data));
  checkManifest(...f.args);
});

test("changed APK, commit, run, attempt and version are rejected", (t) => {
  const f = fixture(t);
  f.save();
  for (const [index, value] of [[2, "b".repeat(40)], [3, "124"], [4, "2"]]) {
    const args = [...f.args];
    args[index] = value;
    assert.throws(() => checkManifest(...args));
  }
  bumpVersion(f.version, "patch");
  assert.throws(() => checkManifest(...f.args));
  f.save();
  writeFileSync(join(f.artifact, APK), "tampered");
  assert.throws(() => checkManifest(...f.args));
});

test("missing, malformed and extra manifest data fail closed", (t) => {
  const f = fixture(t);
  assert.throws(() => checkManifest(...f.args));
  for (const text of ["{", "null", "[]", JSON.stringify({ ...metadata(...f.args), extra: true })]) {
    writeFileSync(join(f.artifact, MANIFEST), text);
    assert.throws(() => checkManifest(...f.args));
  }
  f.save();
  unlinkSync(join(f.artifact, APK));
  assert.throws(() => checkManifest(...f.args));
});

test("invalid provenance is rejected", (t) => {
  const f = fixture(t);
  for (const [commit, run, attempt] of [["main", "123", "1"], ["a".repeat(40), "0", "1"], ["a".repeat(40), "123", "x"]]) {
    assert.throws(() => metadata(f.version, f.artifact, commit, run, attempt));
  }
});

test("update metadata matches the version and names the APK asset", (t) => {
  const { version } = fixture(t);
  assert.deepEqual(updateMetadata(version), { versionName: "0.6.2", versionCode: 14, apk: "minimpos-0.6.2.apk" });
});

test("the CLI writes and verifies manifests and prepares versions", (t) => {
  const f = fixture(t);
  const script = fileURLToPath(new URL("./release.mjs", import.meta.url));
  const run = (...args) => spawnSync(process.execPath, [script, "--version", f.version, ...args]);
  assert.equal(run("bump", "patch").status, 0);
  assert.deepEqual(readVersion(f.version), { name: "0.6.3", code: 15 });
  const args = ["--artifact", f.artifact, "--commit", f.args[2], "--run-id", "123", "--run-attempt", "1"];
  assert.equal(run("manifest", ...args).status, 0);
  assert.equal(run("verify", ...args).status, 0);
  assert.equal(run("candidate", "--subject", "Release 0.6.3", "--files", "version.properties").status, 0);
  assert.notEqual(run("candidate", "--subject", "Other", "--files", "version.properties").status, 0);
  assert.equal(run("update", "--out", join(f.root, "update.json")).status, 0);
  assert.equal(
    readFileSync(join(f.root, "update.json"), "utf8"),
    '{"versionName":"0.6.3","versionCode":15,"apk":"minimpos-0.6.3.apk"}\n',
  );
});

test("signing uses Java properties without logging values", (t) => {
  const { root } = fixture(t);
  writeFileSync(join(root, "signing.properties"), "# comment\nstorePassword = leading\\ space\\u0021 \nkeyAlias:alias\nkeyPassword=second\\\n  half\n");
  const script = fileURLToPath(new URL("./SigningProperties.java", import.meta.url));
  const result = spawnSync("java", [script, root]);
  assert.equal(result.status, 0, result.stderr.toString());
  assert.equal(result.stdout.toString(), "");
  assert.equal(result.stderr.toString(), "");
  assert.equal(readFileSync(join(root, "storePassword"), "utf8"), "leading space! \n");
  assert.equal(readFileSync(join(root, "keyPassword"), "utf8"), "secondhalf\n");
  assert.equal(readFileSync(join(root, "keyAlias"), "utf8"), "alias\n");
  assert.equal(statSync(join(root, "storePassword")).mode & 0o777, 0o600);
});

test("missing signing properties fail without printing values", (t) => {
  const { root } = fixture(t);
  writeFileSync(join(root, "signing.properties"), "storePassword=fixture-not-for-output\nkeyAlias=alias\n");
  const script = fileURLToPath(new URL("./SigningProperties.java", import.meta.url));
  const result = spawnSync("java", [script, root]);
  assert.notEqual(result.status, 0);
  assert.ok(!`${result.stdout}${result.stderr}`.includes("fixture-not-for-output"));
});
