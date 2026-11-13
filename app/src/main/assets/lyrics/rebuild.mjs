#!/usr/bin/env node
// Rebuild in a temporary directory. No node_modules are stored in the Android project.
import { cp, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";

const assets = dirname(fileURLToPath(import.meta.url));
const temporary = await mkdtemp(join(tmpdir(), "yt-music-tv-lyrics-"));
try {
  await cp(join(assets, "build-src"), temporary, { recursive: true });
  const install = spawnSync("npm", ["ci", "--ignore-scripts", "--no-audit", "--no-fund"], {
    cwd: temporary, stdio: "inherit",
  });
  if (install.status !== 0) throw new Error("Pinned lyrics dependency installation failed");
  const esbuild = await import(pathToFileURL(join(temporary, "node_modules/esbuild/lib/main.js")));
  await esbuild.build({
    absWorkingDir: temporary, entryPoints: ["entry.js"], bundle: true,
    format: "iife", globalName: "BraccatoTV", target: "chrome80", minify: true,
    legalComments: "none", loader: { ".css": "text" }, outfile: join(assets, "core.js"),
    banner: { js: "/*! @braccato/core 1.21.5 | MIT | See LICENSE and PROVENANCE.json. */" },
    // Android evaluates this bundle inside its injection function, so a top-level var is not global.
    footer: { js: "globalThis.BraccatoTV = BraccatoTV;" },
  });
  await esbuild.build({
    absWorkingDir: temporary, entryPoints: ["core.css"], bundle: true,
    target: "chrome80", minify: true, legalComments: "none", outfile: join(assets, "core.css"),
    banner: { css: "/*! @braccato/core 1.21.5 | MIT | See LICENSE and PROVENANCE.json. */" },
  });
  await cp(join(temporary, "node_modules/@braccato/core/dist/LICENSE"), join(assets, "LICENSE"));
  const files = {};
  for (const name of ["core.js", "core.css", "LICENSE"]) {
    const bytes = await readFile(join(assets, name));
    files[name] = { bytes: bytes.length, sha256: createHash("sha256").update(bytes).digest("hex") };
  }
  const packageLock = JSON.parse(await readFile(join(temporary, "package-lock.json"), "utf8"));
  const packageCore = packageLock.packages["node_modules/@braccato/core"];
  const provenance = {
    package: "@braccato/core", version: packageCore.version, license: "MIT",
    repository: "https://github.com/better-lyrics/braccato",
    sourceCommit: "48b6d470ac7fd413932cd7b08287505070a89f68",
    commitEvidence: "https://registry.npmjs.org/-/npm/v1/attestations/@braccato%2fcore@1.21.5",
    registryTarball: packageCore.resolved, registryIntegrity: packageCore.integrity,
    buildTool: "esbuild 0.25.12", javascriptSyntaxTarget: "Chrome 80",
    source: "Published npm package, verified by package-lock.json integrity during npm ci",
    modifications: "Unmodified renderer plus local built-in shims and conservative TV theme",
    files,
  };
  await writeFile(join(assets, "PROVENANCE.json"), JSON.stringify(provenance, null, 2) + "\n");
  console.log(JSON.stringify(files, null, 2));
} finally {
  await rm(temporary, { recursive: true, force: true });
}
