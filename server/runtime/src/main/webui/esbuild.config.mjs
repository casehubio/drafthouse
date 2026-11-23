import { build, context } from "esbuild";
import { copyFileSync, mkdirSync, existsSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const isWatch = process.argv.includes("--watch");

const mosaicRoot = resolve(__dirname, "../../../../../../casehub-mosaic");
const pagesRoot = resolve(__dirname, "../../../../../../pages");

function resolveEntry(baseDir, subpath) {
  for (const sub of ["dist", "src"]) {
    const dir = resolve(baseDir, sub);
    if (!existsSync(dir)) continue;
    if (!subpath) {
      for (const idx of ["index.js", "index.ts"]) {
        const entry = resolve(dir, idx);
        if (existsSync(entry)) return entry;
      }
    } else {
      for (const candidate of [
        resolve(dir, subpath + ".ts"),
        resolve(dir, subpath, "index.ts"),
        resolve(dir, subpath + ".js"),
        resolve(dir, subpath, "index.js"),
      ]) {
        if (existsSync(candidate)) return candidate;
      }
    }
  }
  return null;
}

const mosaicResolvePlugin = {
  name: "mosaic-resolve",
  setup(bld) {
    bld.onResolve({ filter: /^@casehubio\/mosaic-/ }, (args) => {
      const parts = args.path.replace("@casehubio/", "").split("/");
      const pkgName = parts[0];
      let subpath = parts.slice(1).join("/");
      if (subpath.startsWith("dist/")) subpath = subpath.slice(5);
      subpath = subpath.replace(/\.js$/, "");
      const names = [pkgName];
      if (pkgName.startsWith("mosaic-")) names.push(pkgName.slice(7));
      for (const dir of ["packages", "components"]) {
        for (const name of names) {
          const entry = resolveEntry(resolve(mosaicRoot, dir, name), subpath);
          if (entry) return { path: entry };
        }
      }
      return undefined;
    });
  },
};

const pagesSourcePlugin = {
  name: "pages-source-resolve",
  setup(bld) {
    bld.onResolve({ filter: /^@casehubio\/(?!mosaic-)/ }, (args) => {
      const parts = args.path.replace("@casehubio/", "").split("/");
      const pkgName = parts[0];
      let subpath = parts.slice(1).join("/");
      if (subpath.startsWith("dist/")) subpath = subpath.slice(5);
      subpath = subpath.replace(/\.js$/, "");
      for (const dir of ["packages", "components"]) {
        const srcDir = resolve(pagesRoot, dir, pkgName, "src");
        if (existsSync(srcDir)) {
          if (!subpath) {
            const entry = resolve(srcDir, "index.ts");
            if (existsSync(entry)) return { path: entry };
          } else {
            for (const candidate of [
              resolve(srcDir, subpath + ".ts"),
              resolve(srcDir, subpath, "index.ts"),
              resolve(srcDir, subpath + ".js"),
              resolve(srcDir, subpath, "index.js"),
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
  nodePaths: [resolve(__dirname, "node_modules"), resolve(pagesRoot, "node_modules")],
  plugins: [mosaicResolvePlugin, ...(usePlugin ? [pagesSourcePlugin] : [])],
};

if (isWatch) {
  const ctx = await context(options);
  await ctx.watch();
  console.log("Watching for changes...");
} else {
  await build(options);
}
