/** Concise Gradle output for local/agent runs; the complete output stays in an ignored, private log. */
import { spawn } from "node:child_process";
import { closeSync, mkdirSync, mkdtempSync, openSync, writeSync } from "node:fs";
import { constants } from "node:os";
import { join, resolve } from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";

const repository = fileURLToPath(new URL("../", import.meta.url));

// Only known progress and passing-test lines are noise. Never filter by severity or truncate diagnostics.
const noise = [
  /^\s*$/,
  /^> Task :[\w:.-]+(?: (?:UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED))?$/,
  /^\d+ actionable tasks?: \d+ (?:executed|up-to-date)(?:, \d+ (?:executed|from cache|up-to-date))*$/,
  /^(?:Reusing configuration cache\.|Configuration cache entry (?:stored|reused)\.)$/,
  /^\s*✔ .+ \(\d+(?:\.\d+)?ms\)$/u,
];

/**
 * Runs the repository wrapper with plain output and otherwise unchanged arguments. Returns its exit code and log path;
 * signals return 128 + signal number. Root/output overrides let offline tests use a fake wrapper.
 */
export function runGradle(args, { root = repository, output = process.stdout } = {}) {
  const logs = join(root, "build", "gradle-logs");
  mkdirSync(logs, { recursive: true, mode: 0o700 });
  const log = join(mkdtempSync(join(logs, "run-")), "output.log");
  const fd = openSync(log, "wx", 0o600);
  output.write(`Gradle running. Full log: ${log}\n`);
  const command = ["--console=plain"];
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--") {
      command.push(...args.slice(i));
      break;
    }
    if (args[i] === "--console") {
      i++;
    } else if (!args[i].startsWith("--console=")) {
      command.push(args[i]);
    }
  }
  const child = spawn(join(root, "gradlew"), command, {
    cwd: root,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let interrupted;
  const forward = (signal) => {
    interrupted = signal;
    child.kill(signal);
  };
  const onInterrupt = () => forward("SIGINT");
  const onTerminate = () => forward("SIGTERM");
  process.on("SIGINT", onInterrupt);
  process.on("SIGTERM", onTerminate);
  for (const stream of [child.stdout, child.stderr]) {
    stream.on("data", (chunk) => writeSync(fd, chunk));
    createInterface({ input: stream, crlfDelay: Infinity }).on("line", (line) => {
      if (!noise.some((pattern) => pattern.test(line))) output.write(`${line}\n`);
    });
  }
  child.on("error", (error) => {
    const message = `Cannot run Gradle: ${error.message}\n`;
    writeSync(fd, message);
    output.write(message);
  });
  return new Promise((done) => {
    child.on("close", (code, signal) => {
      closeSync(fd);
      process.off("SIGINT", onInterrupt);
      process.off("SIGTERM", onTerminate);
      const stopped = interrupted ?? signal;
      const exitCode = stopped ? 128 + constants.signals[stopped] : (code ?? 1);
      output.write(`Gradle exit ${exitCode}. Full log: ${log}\n`);
      done({ code: exitCode, log });
    });
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = (await runGradle(process.argv.slice(2))).code;
  } catch (error) {
    console.error(`Cannot run Gradle: ${error.message}`);
    process.exitCode = 1;
  }
}
