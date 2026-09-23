import { build, context } from "esbuild";
import { copyFileSync, mkdirSync, existsSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const isWatch = process.argv.includes("--watch");

const blocksUiPath = resolve(__dirname, "../../../../../../blocks-ui/components/document-workbench/src");
const blocksUiCorePath = resolve(__dirname, "../../../../../../blocks-ui/packages/blocks-ui-core/src");
const pagesRoot = resolve(__dirname, "../../../../../../pages");

const pagesSourcePlugin = {
  name: "pages-source-resolve",
  setup(bld) {
    bld.onResolve({ filter: /^@casehubio\// }, (args) => {
      if (args.path.startsWith("@casehubio/blocks-ui")) return undefined;
      const parts = args.path.replace("@casehubio/", "").split("/");
      const pkgName = parts[0];
      const subpath = parts.slice(1).join("/");
      const dirs = ["packages", "components"];
      for (const dir of dirs) {
        const srcDir = resolve(pagesRoot, dir, pkgName, "src");
        if (existsSync(srcDir)) {
          if (!subpath) {
            const entry = resolve(srcDir, "index.ts");
            if (existsSync(entry)) return { path: entry };
          } else {
            for (const candidate of [
              resolve(srcDir, subpath, "index.ts"),
              resolve(srcDir, subpath + ".ts"),
              resolve(srcDir, subpath, "index.js"),
              resolve(srcDir, subpath + ".js"),
            ]) {
              if (existsSync(candidate)) return { path: candidate };
            }
          }
        }
      }
      return undefined;
    });
  },
};

const usePlugin = !existsSync(resolve(__dirname, "node_modules/@casehubio/pages-data/dist/index.js"));

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
  nodePaths: [resolve(__dirname, "node_modules")],
  plugins: usePlugin ? [pagesSourcePlugin] : [],
  alias: {
    "@casehubio/blocks-ui-document-workbench": blocksUiPath,
    "@casehubio/blocks-ui-core": blocksUiCorePath,
  },
};

if (isWatch) {
  const ctx = await context(options);
  await ctx.watch();
  console.log("Watching for changes...");
} else {
  await build(options);
}
