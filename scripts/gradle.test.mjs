/** Offline checks of output filtering, complete logs, argument forwarding and failure/signal status. */
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { runGradle } from "./gradle.mjs";

function fixture(t, body) {
  const root = mkdtempSync(join(tmpdir(), "minimpos-gradle-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  if (body != null) writeFileSync(join(root, "gradlew"), `#!/usr/bin/env node\n${body}\n`, { mode: 0o700 });
  return root;
}

async function run(root, args = []) {
  let text = "";
  const result = await runGradle(args, { root, output: { write: (chunk) => { text += chunk; } } });
  return { ...result, text };
}

test("success drops known progress, preserves warnings and complete private logs", async (t) => {
  const lines = [
    "",
    "> Task :core:compileKotlin",
    "> Task :core:test UP-TO-DATE",
    "> Task :app:testDebugUnitTest FROM-CACHE",
    "> Task :app:empty NO-SOURCE",
    "> Task :app:skip SKIPPED",
    "Reusing configuration cache.",
    "Configuration cache entry reused.",
    "Configuration cache entry stored.",
    "12 actionable tasks: 2 executed, 3 from cache, 7 up-to-date",
    "✔ passing test (1.234ms)",
    "ℹ tests 1",
    "ℹ fail 0",
    "BUILD SUCCESSFUL in 1s",
  ];
  const warnings = [
    "Deprecated Gradle features were used in this build.",
    "    Warning continuation with context",
    "warning: compiler diagnostic",
    "> Task :custom:output unexpected message",
    "Unrecognized tool output must survive",
  ];
  const root = fixture(t, `console.log(${JSON.stringify(lines.join("\n"))}); console.error(${JSON.stringify(warnings.join("\n"))});`);
  const result = await run(root);
  assert.equal(result.code, 0);
  for (const line of lines.slice(1, 11)) assert.ok(!result.text.includes(line), line);
  for (const line of [...lines.slice(11), ...warnings]) assert.ok(result.text.includes(line), line);
  const log = readFileSync(result.log, "utf8");
  assert.ok(log.includes(`${lines.join("\n")}\n`));
  assert.ok(log.includes(`${warnings.join("\n")}\n`));
  assert.equal(statSync(result.log).mode & 0o777, 0o600);
});

test("failures preserve exit status, failed tasks, assertions and stack traces without truncation", async (t) => {
  const failure = [
    "> Task :core:test FAILED",
    "ExampleTest > regression FAILED",
    "    java.lang.AssertionError: expected true",
    "        at ExampleTest.regression(ExampleTest.kt:42)",
    ...Array.from({ length: 300 }, (_, i) => `Failure context line ${i}`),
    "BUILD FAILED in 1s",
  ].join("\n");
  const root = fixture(t, `console.error(${JSON.stringify(failure)}); process.exitCode = 37;`);
  const result = await run(root);
  assert.equal(result.code, 37);
  assert.ok(result.text.includes(failure));
  assert.equal(readFileSync(result.log, "utf8"), `${failure}\n`);
});

test("arguments stay separate, plain output is enforced, concurrent logs never overwrite", async (t) => {
  const root = fixture(t, `console.log(JSON.stringify(process.argv.slice(2))); console.log(process.cwd());`);
  const args = [":core:test", "--tests", "Example name; not a shell command", "--warning-mode=all"];
  const results = await Promise.all([run(root, [...args, "--console=rich"]), run(root, ["--console", "rich", ...args])]);
  assert.notEqual(results[0].log, results[1].log);
  for (const result of results) {
    assert.equal(result.code, 0);
    assert.ok(result.text.includes(JSON.stringify(["--console=plain", ...args])));
    assert.ok(result.text.includes(root));
  }
});

test("console handling does not consume task options after the end-of-options marker", async (t) => {
  const root = fixture(t, `console.log(JSON.stringify(process.argv.slice(2)));`);
  const args = [":example", "--", "--console=task-option"];
  const result = await run(root, ["--console=rich", ...args]);
  assert.equal(result.code, 0);
  assert.ok(result.text.includes(JSON.stringify(["--console=plain", ...args])));
});

test("missing wrapper fails with a diagnostic and saved log", async (t) => {
  const result = await run(fixture(t));
  assert.notEqual(result.code, 0);
  assert.match(result.text, /Cannot run Gradle: .*ENOENT/);
  assert.match(readFileSync(result.log, "utf8"), /ENOENT/);
});

test("interruption reaches the wrapper and remains nonzero even if it exits successfully", { timeout: 10000 }, async (t) => {
  const root = fixture(t, `process.on("SIGTERM", () => { console.log("interrupted"); process.exit(0); }); console.log("READY"); setInterval(() => {}, 1000);`);
  const launcher = join(root, "launch.mjs");
  writeFileSync(launcher, `import { runGradle } from ${JSON.stringify(new URL("./gradle.mjs", import.meta.url).href)};\nprocess.exitCode = (await runGradle([], { root: ${JSON.stringify(root)} })).code;\n`);
  const child = spawn(process.execPath, [launcher], { stdio: ["ignore", "pipe", "pipe"] });
  t.after(() => child.kill("SIGKILL"));
  let output = "";
  let signaled = false;
  child.stdout.on("data", (chunk) => {
    output += chunk;
    if (!signaled && output.includes("READY")) {
      signaled = true;
      child.kill("SIGTERM");
    }
  });
  const code = await new Promise((done, reject) => {
    child.on("error", reject);
    child.on("close", done);
  });
  assert.equal(code, 143);
  assert.ok(output.includes("interrupted"));
});
