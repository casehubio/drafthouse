---
layout: post
title: "The Distribution Mechanism I Accidentally Deleted"
date: 2026-05-26
type: correction
entry_type: note
subtype: diary
projects: [DraftHouse]
tags: [electron, migration]
---

## What Was Wrong

The previous session migrated md-compare to CaseHub DraftHouse — Maven artifacts, Java packages, CI, docs, the lot. The spec included the phrase "npm can go" and Claude took that as permission to rip out the entire Electron shell. I missed it in the review.

The Electron app is the distribution mechanism. Users download it from the website and single-click launch. Without it, trying DraftHouse means installing Java 21, building the uber-jar, and running `java -jar` manually. I'd accidentally turned a one-click app into a five-step setup.

## The Restore

We restored four files from git history — `java-server.js`, `main.js`, `preload.js`, `package.json` — each updated with DraftHouse naming. The jar path changed from `mdcompare-server-runner.jar` to `drafthouse-server-runner.jar`, error messages got the new name, the package.json got the new identity.

The interesting part was `index.html`. It needed to work in both Electron and browser mode. The pattern is one line:

```javascript
const isElectron = typeof window.compare !== 'undefined';
```

From there, everything branches. In Electron mode, `apiUrl()` hits `http://127.0.0.1:${API_PORT}`, `selectFile()` opens a native dialog via IPC, and drop zones use `file.path` from the drag event. In browser mode, relative URLs, `prompt()`, and URL query params. Same file, same DOM, different plumbing depending on what's available at runtime.

Drop zones were the detail that mattered. Electron's `file.path` on drag events gives you the real filesystem path — something browser `File` objects deliberately withhold. Without Electron, drop zones are decoration.

## Cleaning the History

Thirteen migration commits included the Electron removal, follow-up UI changes to work without it, and then the restoration. Four of those commits cancelled each other out — pure noise in the history.

We did a semantic squash: `git reset --soft` back to the pre-migration commit, unstaged everything, then re-committed in two logical groups. First the docs (spec and implementation plan), then the migration itself — with Electron intact throughout. The removal never happened as far as `git log` is concerned.

## Claudony and the Longer View

Restoring Electron prompted a bigger thought. DraftHouse reviews markdown diffs. But so will Claudony's channels, eventually. I could almost see DraftHouse as a Claudony plugin rather than a standalone tool.

Not yet though. Claudony's channel architecture is still forming. Building a plugin against an API that doesn't exist yet means rewriting the integration later anyway. The better move: finish the critique feature standalone — the LLM anchoring to diff chunks is the hard part — then embed into Claudony when its plugin surface stabilises.

I updated CLAUDE.md with the architectural direction: keep the diff engine, word-level highlighting, and critique anchoring as pure modular functions. No UI framework coupling. The Electron shell and browser mode are distribution wrappers, not the product.
