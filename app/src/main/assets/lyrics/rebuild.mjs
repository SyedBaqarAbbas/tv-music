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
const sha256 = bytes => createHash("sha256").update(bytes).digest("hex");
// The app reports intentional remote input directly, so it must bypass the upstream
// budget that filters scroll events caused by the renderer itself. False/default
// keeps the upstream behavior unchanged. Exact matches make upgrades fail closed.
const forceManualScrollPatch = [
  {
    file: "engine/userScroll.js",
    changes: [
      ["export function noteUserScroll(engine, lyricsAreUnsynced) {",
        "export function noteUserScroll(engine, lyricsAreUnsynced, force = false) {"],
      ["    else if (engine.skipScrolls > 0) {",
        "    else if (!force && engine.skipScrolls > 0) {"],
    ],
  },
  {
    file: "renderer.js",
    changes: [
      ["        noteUserScroll() {", "        noteUserScroll(force = false) {"],
      ["            noteEngineUserScroll(engine, hasUnsyncedLyrics(engine));",
        "            noteEngineUserScroll(engine, hasUnsyncedLyrics(engine), force);"],
    ],
  },
  {
    file: "types.d.ts",
    changes: [["    noteUserScroll(): void;", "    noteUserScroll(force?: boolean): void;"]],
  },
];
const sourcePatches = [
  {
    id: "force-explicit-manual-scroll-v1",
    api: "renderer.noteUserScroll(force = false)",
    behavior: "When force is true, intentional manual input bypasses the upstream engine-scroll event budget; omitted or false preserves upstream behavior.",
    files: forceManualScrollPatch,
  },
  {
    id: "skip-drift-sampling-for-legacy-animation-effects-v1",
    api: "Internal drift sampling compatibility",
    behavior: "Skip optional animation drift samples when a website animation polyfill supplies an effect without getComputedTiming; native effects retain upstream timing behavior.",
    files: [{
      file: "engine/animationTiming.js",
      changes: [[
        '    if (!track || track.skipDriftSample || animation.playState === "idle")',
        '    if (!track || track.skipDriftSample || animation.playState === "idle" || typeof animation.effect?.getComputedTiming !== "function")',
      ]],
    }],
  },
];
try {
  await cp(join(assets, "build-src"), temporary, { recursive: true });
  const install = spawnSync("npm", ["ci", "--ignore-scripts", "--no-audit", "--no-fund"], {
    cwd: temporary, stdio: "inherit",
  });
  if (install.status !== 0) throw new Error("Pinned lyrics dependency installation failed");
  const appliedPatches = [];
  for (const sourcePatch of sourcePatches) {
    const patchFiles = [];
    for (const patch of sourcePatch.files) {
      const path = join(temporary, "node_modules/@braccato/core/dist", patch.file);
      const original = await readFile(path, "utf8");
      let patched = original;
      for (const [before, after] of patch.changes) {
        if (patched.split(before).length !== 2) {
          throw new Error(`Pinned ${sourcePatch.id} patch expected exactly one match in ${patch.file}`);
        }
        patched = patched.replace(before, after);
      }
      await writeFile(path, patched);
      patchFiles.push({ path: patch.file, upstreamSha256: sha256(original), patchedSha256: sha256(patched) });
    }
    appliedPatches.push({
      id: sourcePatch.id, api: sourcePatch.api, behavior: sourcePatch.behavior,
      application: "Exact source snippets in rebuild.mjs; rebuild fails unless every snippet occurs exactly once.",
      files: patchFiles,
    });
  }
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
    files[name] = { bytes: bytes.length, sha256: sha256(bytes) };
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
    modifications: "Local built-in shims, conservative TV theme, explicit manual-scroll override and legacy website animation-effect compatibility patches",
    patches: appliedPatches,
    files,
  };
  await writeFile(join(assets, "PROVENANCE.json"), JSON.stringify(provenance, null, 2) + "\n");
  console.log(JSON.stringify(files, null, 2));
} finally {
  await rm(temporary, { recursive: true, force: true });
}
