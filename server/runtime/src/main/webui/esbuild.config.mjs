import { build, context } from "esbuild";
import { copyFileSync, existsSync, mkdirSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const isWatch = process.argv.includes("--watch");

const BLOCKS_UI_COMPONENT = "components/document-workbench/src";
const siblingPath = resolve(__dirname, "../../../../../../blocks-ui", BLOCKS_UI_COMPONENT);
const inRepoPath = resolve(__dirname, "../../../../../blocks-ui", BLOCKS_UI_COMPONENT);
const blocksUiPath = existsSync(siblingPath) ? siblingPath : inRepoPath;

mkdirSync("dist", { recursive: true });
copyFileSync("public/index.html", "dist/index.html");

const options = {
  entryPoints: ["src/index.ts"],
  bundle: true,
  outfile: "dist/app.js",
  format: "esm",
  target: "es2022",
  minify: false,
  sourcemap: true,
  alias: {
    "@casehubio/blocks-ui-document-workbench": blocksUiPath,
  },
};

if (isWatch) {
  const ctx = await context(options);
  await ctx.watch();
  console.log("Watching for changes...");
} else {
  await build(options);
}
