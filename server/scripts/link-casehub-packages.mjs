#!/usr/bin/env node
/**
 * After npm install, symlink all .casehub-packages into node_modules/@casehubio/
 * under their actual package names. This makes transitive @casehubio/* imports
 * resolvable by esbuild without listing every package as a direct dependency.
 */
import { readdirSync, readFileSync, mkdirSync, symlinkSync, existsSync, lstatSync } from 'fs';
import { join, resolve, relative, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const webui = resolve(__dirname, '..', 'app', 'src', 'main', 'webui');
const packagesDir = join(webui, '.casehub-packages', 'packages');
const nodeModules = join(webui, 'node_modules', '@casehubio');

if (!existsSync(packagesDir)) {
  console.log('No .casehub-packages/packages — skipping');
  process.exit(0);
}

mkdirSync(nodeModules, { recursive: true });

let linked = 0;
for (const dir of readdirSync(packagesDir)) {
  const pkgJsonPath = join(packagesDir, dir, 'package.json');
  if (!existsSync(pkgJsonPath)) continue;

  const pkg = JSON.parse(readFileSync(pkgJsonPath, 'utf8'));
  const name = pkg.name;
  if (!name || !name.startsWith('@casehubio/')) continue;

  const shortName = name.replace('@casehubio/', '');
  const targetDir = join(nodeModules, shortName);

  if (existsSync(targetDir)) {
    const stat = lstatSync(targetDir);
    if (stat.isSymbolicLink() || stat.isDirectory()) continue;
  }

  const relPath = relative(nodeModules, join(packagesDir, dir));
  symlinkSync(relPath, targetDir);
  linked++;
}

console.log(`Linked ${linked} @casehubio packages into node_modules`);
