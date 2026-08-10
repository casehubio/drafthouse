# Quarkus Backend + Playwright Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace md-compare's Electron IPC file layer with a Quarkus HTTP server and add Playwright E2E tests covering the happy path and key regressions.

**Architecture:** A Quarkus 3.34 uber-JAR is spawned as a subprocess by Electron's `main.js` (same pattern as Sparge's `java-server.js`). The UI at `index.html` is served as a static file by Quarkus so there are no `file://` CORS issues; all file reads and SSE file-watch streams go through the server. Native file dialogs stay as Electron IPC. Playwright tests launch the full Electron app (which spawns the real Quarkus server) and drive the UI via DOM assertions.

**Tech Stack:** Quarkus 3.34, quarkus-rest-jackson, quarkus-smallrye-health, Mutiny Multi (SSE), JUnit 5 + REST-assured (Java tests), Playwright + @playwright/test (E2E), Java 21 virtual threads for WatchService.

---

## File Map

### New files — Quarkus server
| File | Purpose |
|---|---|
| `server/pom.xml` | Maven project, Quarkus BOM, dependencies |
| `server/src/main/resources/application.properties` | Port, CORS, uber-jar config |
| `server/src/main/java/io/mdcompare/server/PingResource.java` | `GET /api/ping` — health poll target |
| `server/src/main/java/io/mdcompare/server/FileResource.java` | `GET /api/file?path=` — read file content |
| `server/src/main/java/io/mdcompare/server/WatchResource.java` | `GET /api/watch?path=` — SSE file-change stream |
| `server/src/main/java/io/mdcompare/server/UiResource.java` | `GET /` and `GET /{path}` — serve static UI files |
| `server/src/main/java/io/mdcompare/server/CritiqueResource.java` | `POST /api/critique` — Phase 2 stub (501) |
| `server/src/test/java/io/mdcompare/server/PingResourceTest.java` | Verifies `/api/ping` returns 200 |
| `server/src/test/java/io/mdcompare/server/FileResourceTest.java` | Verifies file read and 404 on missing |
| `server/src/test/java/io/mdcompare/server/WatchResourceTest.java` | Verifies SSE fires on file change |

### New files — Electron
| File | Purpose |
|---|---|
| `java-server.js` | Process manager adapted from Sparge — spawn JAR, health poll, crash recovery |

### Modified files — Electron
| File | Changes |
|---|---|
| `main.js` | Spawn Quarkus via `java-server.js`, load `http://localhost:PORT/`, send `init:config` IPC |
| `preload.js` | Remove `readFile/watchFile/unwatchFile/onFileChanged`, add `onInitConfig` |
| `index.html` | Replace IPC file calls with `fetch` + `EventSource`; receive port via `onInitConfig` |
| `package.json` | Add `@playwright/test`, `playwright` devDeps; add `test:e2e` and `build:server` scripts |

### New files — Tests
| File | Purpose |
|---|---|
| `playwright.config.js` | Playwright config: testDir, timeout, Electron |
| `electron-tests/e2e/global-setup.js` | Write temp test `.md` fixtures, set env vars |
| `electron-tests/e2e/helpers.js` | `launchApp(fileA, fileB)` helper |
| `electron-tests/e2e/happy-path.spec.js` | App launches, panels render, diff markers visible |
| `electron-tests/e2e/regression.spec.js` | Sync scroll, click-to-scroll, file watch reload |

---

## Task 1: Quarkus Maven scaffold

**Files:**
- Create: `server/pom.xml`
- Create: `server/src/main/resources/application.properties`
- Create: `server/src/main/java/io/mdcompare/server/PingResource.java`
- Create: `server/src/test/java/io/mdcompare/server/PingResourceTest.java`

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/io/mdcompare/server/PingResourceTest.java`:

```java
package io.mdcompare.server;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class PingResourceTest {
    @Test
    void pingReturnsOk() {
        given().when().get("/api/ping")
               .then().statusCode(200).body("ok", is(true));
    }
}
```

- [ ] **Step 2: Create `server/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>io.mdcompare</groupId>
  <artifactId>mdcompare-server</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <properties>
    <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
    <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>
    <quarkus.platform.version>3.34.3</quarkus.platform.version>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>${quarkus.platform.group-id}</groupId>
        <artifactId>${quarkus.platform.artifact-id}</artifactId>
        <version>${quarkus.platform.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-rest-jackson</artifactId>
    </dependency>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-smallrye-health</artifactId>
    </dependency>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-arc</artifactId>
    </dependency>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-junit5</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>io.rest-assured</groupId>
      <artifactId>rest-assured</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>${quarkus.platform.group-id}</groupId>
        <artifactId>quarkus-maven-plugin</artifactId>
        <version>${quarkus.platform.version}</version>
        <extensions>true</extensions>
        <executions>
          <execution>
            <goals>
              <goal>build</goal>
              <goal>generate-code</goal>
              <goal>generate-code-tests</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.3.1</version>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Create `server/src/main/resources/application.properties`**

```properties
quarkus.http.port=9001
quarkus.package.jar.type=uber-jar
quarkus.package.output-name=mdcompare-server
quarkus.http.cors=true
quarkus.http.cors.origins=*
quarkus.http.cors.methods=GET,POST,OPTIONS

%dev.quarkus.log.level=INFO
%test.quarkus.http.port=9002
```

- [ ] **Step 4: Create `server/src/main/java/io/mdcompare/server/PingResource.java`**

```java
package io.mdcompare.server;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/api/ping")
public class PingResource {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> ping() {
        return Map.of("ok", true);
    }
}
```

- [ ] **Step 5: Run the test**

```bash
cd server && /opt/homebrew/bin/mvn test -pl . -Dtest=PingResourceTest
```

Expected: `BUILD SUCCESS`, 1 test passing.

- [ ] **Step 6: Commit**

```bash
git add server/
git commit -m "feat: scaffold Quarkus server with ping endpoint

Refs #<issue>"
```

---

## Task 2: FileResource — read file content

**Files:**
- Create: `server/src/main/java/io/mdcompare/server/FileResource.java`
- Create: `server/src/test/java/io/mdcompare/server/FileResourceTest.java`

- [ ] **Step 1: Write the failing tests**

Create `server/src/test/java/io/mdcompare/server/FileResourceTest.java`:

```java
package io.mdcompare.server;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class FileResourceTest {

    @Test
    void readsExistingFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("test.md");
        Files.writeString(file, "# Hello\n\nWorld\n");

        given()
            .queryParam("path", file.toString())
        .when()
            .get("/api/file")
        .then()
            .statusCode(200)
            .contentType(containsString("text/plain"))
            .body(equalTo("# Hello\n\nWorld\n"));
    }

    @Test
    void returns404ForMissingFile() {
        given()
            .queryParam("path", "/tmp/mdcompare-does-not-exist-xyz.md")
        .when()
            .get("/api/file")
        .then()
            .statusCode(404);
    }

    @Test
    void returns400WhenPathMissing() {
        given()
        .when()
            .get("/api/file")
        .then()
            .statusCode(400);
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd server && /opt/homebrew/bin/mvn test -Dtest=FileResourceTest
```

Expected: FAIL — `FileResource` not found.

- [ ] **Step 3: Create `server/src/main/java/io/mdcompare/server/FileResource.java`**

```java
package io.mdcompare.server;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Path("/api/file")
public class FileResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response readFile(@QueryParam("path") String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Response.status(400).entity("path parameter required").build();
        }
        try {
            String content = Files.readString(Path.of(filePath));
            return Response.ok(content).build();
        } catch (IOException e) {
            return Response.status(404).entity(e.getMessage()).build();
        }
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
cd server && /opt/homebrew/bin/mvn test -Dtest=FileResourceTest
```

Expected: `BUILD SUCCESS`, 3 tests passing.

- [ ] **Step 5: Commit**

```bash
git add server/src/
git commit -m "feat: add FileResource — GET /api/file

Refs #<issue>"
```

---

## Task 3: WatchResource — SSE file-change stream

**Files:**
- Create: `server/src/main/java/io/mdcompare/server/WatchResource.java`
- Create: `server/src/test/java/io/mdcompare/server/WatchResourceTest.java`

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/io/mdcompare/server/WatchResourceTest.java`:

```java
package io.mdcompare.server;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WatchResourceTest {

    @Test
    void fileChangeTriggersSseEvent(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("watch-test.md");
        Files.writeString(file, "initial content");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        // Open SSE connection in background thread
        String url = "http://localhost:9002/api/watch?path=" +
                     java.net.URLEncoder.encode(file.toString(), "UTF-8");

        Thread sseThread = Thread.ofVirtual().start(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.connect();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            received.set(line.trim());
                            latch.countDown();
                            return;
                        }
                    }
                }
            } catch (IOException e) {
                // expected when connection closes after test
            }
        });

        // Give SSE connection time to establish
        Thread.sleep(300);

        // Modify the file
        Files.writeString(file, "modified content");

        // Wait for SSE event (max 5 seconds)
        assertTrue(latch.await(5, TimeUnit.SECONDS),
                   "SSE event was not received within 5 seconds");
        assertEquals("data: changed", received.get());

        sseThread.interrupt();
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd server && /opt/homebrew/bin/mvn test -Dtest=WatchResourceTest
```

Expected: FAIL — `WatchResource` not found.

- [ ] **Step 3: Create `server/src/main/java/io/mdcompare/server/WatchResource.java`**

```java
package io.mdcompare.server;

import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;

@Path("/api/watch")
public class WatchResource {

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @jakarta.ws.rs.sse.SseElementType(MediaType.TEXT_PLAIN)
    public Multi<String> watch(@QueryParam("path") String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Multi.createFrom().failure(new BadRequestException("path required"));
        }

        return Multi.createFrom().emitter(em -> {
            Path target = Path.of(filePath);
            Path dir    = target.getParent();
            String name = target.getFileName().toString();

            WatchService ws;
            try {
                ws = FileSystems.getDefault().newWatchService();
                dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY);
            } catch (IOException e) {
                em.fail(e);
                return;
            }

            em.onTermination(() -> {
                try { ws.close(); } catch (IOException ignored) {}
            });

            // Virtual thread — cheap and blocks cleanly on ws.poll()
            Thread.ofVirtual().start(() -> {
                try {
                    while (!em.isCancelled()) {
                        WatchKey key = ws.poll(200, TimeUnit.MILLISECONDS);
                        if (key == null) continue;
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (name.equals(event.context().toString())) {
                                em.emit("changed");
                            }
                        }
                        if (!key.reset()) break;
                    }
                } catch (InterruptedException | ClosedWatchServiceException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    em.complete();
                }
            });
        });
    }
}
```

- [ ] **Step 4: Run test to confirm it passes**

```bash
cd server && /opt/homebrew/bin/mvn test -Dtest=WatchResourceTest
```

Expected: `BUILD SUCCESS`, 1 test passing.

- [ ] **Step 5: Commit**

```bash
git add server/src/
git commit -m "feat: add WatchResource — SSE file-change stream

Refs #<issue>"
```

---

## Task 4: UiResource, CritiqueResource stub, and build the JAR

**Files:**
- Create: `server/src/main/java/io/mdcompare/server/UiResource.java`
- Create: `server/src/main/java/io/mdcompare/server/CritiqueResource.java`

The UI files (`index.html`, `styles.css`) stay at the project root. Quarkus receives their directory via the `-Dui.dir=<path>` JVM arg and serves them as static files. This is the same pattern as Sparge and avoids `file://` CORS issues.

- [ ] **Step 1: Create `server/src/main/java/io/mdcompare/server/UiResource.java`**

```java
package io.mdcompare.server;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Path("/")
public class UiResource {

    @ConfigProperty(name = "ui.dir", defaultValue = ".")
    String uiDir;

    @GET
    public Response index() throws IOException {
        return serveFile("index.html");
    }

    @GET
    @Path("{file}")
    public Response file(@PathParam("file") String file) throws IOException {
        return serveFile(file);
    }

    private Response serveFile(String fileName) throws IOException {
        Path resolved = Path.of(uiDir).resolve(fileName).normalize();
        if (!Files.exists(resolved)) {
            return Response.status(404).build();
        }
        String mediaType = fileName.endsWith(".css") ? "text/css" : MediaType.TEXT_HTML;
        return Response.ok(Files.readString(resolved), mediaType).build();
    }
}
```

- [ ] **Step 2: Create `server/src/main/java/io/mdcompare/server/CritiqueResource.java`**

```java
package io.mdcompare.server;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/critique")
public class CritiqueResource {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response critique(String body) {
        // Phase 2: LLM critique via LangChain4j — not yet implemented
        return Response.status(501).entity("Critique not yet implemented").build();
    }
}
```

- [ ] **Step 3: Build the uber-JAR**

```bash
cd server && /opt/homebrew/bin/mvn package -DskipTests
```

Expected: `BUILD SUCCESS`. JAR at `server/target/mdcompare-server-runner.jar`.

- [ ] **Step 4: Smoke-test the JAR manually**

```bash
java -Dquarkus.http.port=9001 -Dui.dir=/Users/mdproctor/claude/md-compare -jar server/target/mdcompare-server-runner.jar &
sleep 3
curl -s http://localhost:9001/api/ping
# Expected: {"ok":true}
curl -o /dev/null -w "%{http_code}" "http://localhost:9001/api/file?path=/Users/mdproctor/claude/md-compare/sample-a.md"
# Expected: 200
kill %1
```

- [ ] **Step 5: Commit**

```bash
git add server/src/
git commit -m "feat: add UiResource, CritiqueResource stub, build JAR

Refs #<issue>"
```

---

## Task 5: java-server.js — Quarkus process manager

**Files:**
- Create: `java-server.js`

This is a direct adaptation of Sparge's `java-server.js`. The state machine (idle → starting → healthy → crashed → restarting → fatal) is unchanged. Two differences: the poll URL is `/api/ping` and the JAR name is `mdcompare-server-runner.jar`.

- [ ] **Step 1: Create `java-server.js`**

```javascript
// java-server.js — Quarkus process manager for md-compare
// Adapted from Sparge's java-server.js (same state machine).
'use strict';
const { spawn }        = require('child_process');
const http             = require('http');
const net              = require('net');
const path             = require('path');
const { EventEmitter } = require('events');

const MAX_RESTARTS       = 3;
const STABILITY_RESET_MS = 60_000;
const BACKOFF_MS         = [1000, 2000, 4000];
const LOG_BUFFER_SIZE    = 200;

function findFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
    server.on('error', reject);
  });
}

function pollUntilReady(port, { intervalMs = 200, timeoutMs = 20000 } = {}) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMs;
    const attempt = () => {
      if (Date.now() >= deadline) {
        reject(new Error(`Server did not start within ${timeoutMs}ms`));
        return;
      }
      const req = http.get(`http://127.0.0.1:${port}/api/ping`, (res) => {
        res.resume();
        if (res.statusCode === 200) resolve();
        else setTimeout(attempt, intervalMs);
      });
      req.on('error', () => setTimeout(attempt, intervalMs));
      req.setTimeout(intervalMs, () => { req.destroy(); });
    };
    attempt();
  });
}

function getJarPath(isPackaged, resourcesPath) {
  if (isPackaged) return path.join(resourcesPath, 'mdcompare-server-runner.jar');
  return path.join(__dirname, 'server', 'target', 'mdcompare-server-runner.jar');
}

function getUiDir(isPackaged, resourcesPath) {
  if (isPackaged) return path.join(resourcesPath, 'ui');
  return __dirname;
}

class JavaServer extends EventEmitter {
  constructor({ isPackaged = false, resourcesPath = '' } = {}) {
    super();
    this._isPackaged    = isPackaged;
    this._resourcesPath = resourcesPath;
    this._port          = null;
    this._process       = null;
    this._state         = 'idle';
    this._logs          = [];
    this._crashCount    = 0;
    this._stabilityTimer = null;
    this._pollFn        = pollUntilReady;
  }

  getPort()  { return this._port; }
  getLogs()  { return [...this._logs]; }

  async spawnServer(port) {
    this._port  = port;
    this._state = 'starting';
    this._doSpawn();
    await this._pollFn(port);
    this._state = 'healthy';
    this._resetStabilityTimer();
  }

  _doSpawn() {
    const jarPath = getJarPath(this._isPackaged, this._resourcesPath);
    const uiDir   = getUiDir(this._isPackaged, this._resourcesPath);
    const jvmArgs = [
      `-Dquarkus.http.port=${this._port}`,
      `-Dui.dir=${uiDir}`,
      '-jar', jarPath,
    ];
    this._process = spawn('java', jvmArgs, { env: { ...process.env } });
    this._process.stdout.on('data', d => this._appendLog(d.toString()));
    this._process.stderr.on('data', d => this._appendLog(d.toString()));
    this._process.on('exit', (code, signal) => this._onExit(code, signal));
  }

  _appendLog(text) {
    const lines = text.split('\n').filter(l => l.length > 0);
    this._logs.push(...lines);
    if (this._logs.length > LOG_BUFFER_SIZE) this._logs = this._logs.slice(-LOG_BUFFER_SIZE);
  }

  _resetStabilityTimer() {
    if (this._stabilityTimer) clearTimeout(this._stabilityTimer);
    this._stabilityTimer = setTimeout(() => { this._crashCount = 0; }, STABILITY_RESET_MS);
  }

  _onExit(code, signal) {
    if (this._state === 'idle') return;
    this._state = 'crashed';
    this.emit('crashed', { code, signal });
    this._crashCount++;
    if (this._crashCount > MAX_RESTARTS) {
      this._state = 'fatal';
      this.emit('fatal', { logs: this.getLogs() });
      return;
    }
    const delay = BACKOFF_MS[Math.min(this._crashCount - 1, BACKOFF_MS.length - 1)];
    setTimeout(() => this._restart(), delay);
  }

  async _restart() {
    this._state = 'restarting';
    this._doSpawn();
    try {
      await this._pollFn(this._port);
      this._state = 'healthy';
      this.emit('restarted');
      this._resetStabilityTimer();
    } catch (_) { /* _onExit handles next failure */ }
  }

  async killServer() {
    this._state = 'idle';
    if (this._stabilityTimer) clearTimeout(this._stabilityTimer);
    if (!this._process) return;
    return new Promise((resolve) => {
      const timer = setTimeout(() => { this._process.kill('SIGKILL'); resolve(); }, 5000);
      this._process.once('exit', () => { clearTimeout(timer); resolve(); });
      this._process.kill('SIGTERM');
    });
  }
}

module.exports = { JavaServer, findFreePort };
```

- [ ] **Step 2: Verify the server can be spawned manually**

```bash
node -e "
const { JavaServer, findFreePort } = require('./java-server');
findFreePort().then(async port => {
  const s = new JavaServer();
  await s.spawnServer(port);
  console.log('healthy on port', port);
  await s.killServer();
  console.log('killed');
});
"
```

Expected: prints `healthy on port NNNNN` then `killed` within 10 seconds.

- [ ] **Step 3: Commit**

```bash
git add java-server.js
git commit -m "feat: add java-server.js process manager for Quarkus

Refs #<issue>"
```

---

## Task 6: Wire Electron to Quarkus — update main.js, preload.js, index.html

**Files:**
- Modify: `main.js`
- Modify: `preload.js`
- Modify: `index.html`
- Modify: `package.json`

After this task the app works end-to-end: Electron spawns Quarkus, loads `http://localhost:PORT/`, and file reads/watches go over HTTP. The native file dialog stays as IPC.

- [ ] **Step 1: Replace `main.js`**

```javascript
// main.js
'use strict';
const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const { JavaServer, findFreePort } = require('./java-server');

let mainWindow = null;
const server = new JavaServer({ isPackaged: app.isPackaged, resourcesPath: process.resourcesPath });

// Extra CLI args: electron <app> [fileA] [fileB]
const initFiles = process.argv.slice(2).filter(a => !a.startsWith('--'));

function showErrorWindow(message) {
  const escape = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const win  = new BrowserWindow({ width: 700, height: 500, show: false });
  const logs = escape(server.getLogs().join('\n'));
  const html = `<!DOCTYPE html><html><body style="font-family:monospace;padding:20px;background:#1a1a1a;color:#eee">
    <h2 style="color:#f87171">md-compare failed to start</h2>
    <p>${escape(message)}</p>
    <pre style="overflow:auto;background:#111;padding:10px;max-height:350px">${logs}</pre>
    </body></html>`;
  win.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`);
  win.show();
}

async function createMainWindow(port) {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  await mainWindow.loadURL(`http://127.0.0.1:${port}/`);
  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
    mainWindow.webContents.send('init:config', { port });
    if (initFiles.length > 0) {
      mainWindow.webContents.send('init:files', initFiles[0] || null, initFiles[1] || null);
    }
  });
}

app.whenReady().then(async () => {
  server.on('fatal', () => showErrorWindow('The md-compare server crashed and could not restart.'));
  try {
    const port = await findFreePort();
    await server.spawnServer(port);
    await createMainWindow(port);
  } catch (err) {
    showErrorWindow(err.message);
  }
});

app.on('before-quit', async (event) => {
  event.preventDefault();
  await server.killServer();
  app.exit(0);
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

// Native file dialog — stays as IPC (can't do this over HTTP)
ipcMain.handle('dialog:selectFile', async () => {
  const { canceled, filePaths } = await dialog.showOpenDialog(mainWindow, {
    properties: ['openFile'],
    filters: [{ name: 'Markdown / Text', extensions: ['md', 'markdown', 'txt'] }],
  });
  return canceled ? null : filePaths[0];
});
```

- [ ] **Step 2: Replace `preload.js`**

```javascript
// preload.js
'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('compare', {
  selectFile:   () => ipcRenderer.invoke('dialog:selectFile'),
  onInitConfig: (cb) => ipcRenderer.on('init:config', (_event, cfg) => cb(cfg)),
  onInitFiles:  (cb) => ipcRenderer.on('init:files',  (_event, a, b) => cb(a, b)),
});
```

- [ ] **Step 3: Update `index.html` — replace IPC file calls with fetch + EventSource**

Replace the entire `<script>` block (everything between `<script>` and `</script>` in the body) with:

```javascript
'use strict';
const $ = id => document.getElementById(id);

// ── State ────────────────────────────────────────────────────────────
const filePaths = { a: null, b: null };
const contents  = { a: null, b: null };
const watchers  = {};                    // path → EventSource
let API_PORT    = null;
let syncEnabled = true, syncing = false, dragging = false;
let lastChunks = [], lastTotalA = 0, lastTotalB = 0;

// ── API helpers ──────────────────────────────────────────────────────
function apiUrl(path) {
  return `http://127.0.0.1:${API_PORT}${path}`;
}

async function fetchFile(filePath) {
  const res = await fetch(apiUrl(`/api/file?path=${encodeURIComponent(filePath)}`));
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.text();
}

function watchFile(filePath) {
  if (watchers[filePath]) watchers[filePath].close();
  const es = new EventSource(apiUrl(`/api/watch?path=${encodeURIComponent(filePath)}`));
  es.onmessage = async () => {
    for (const panel of ['a', 'b']) {
      if (filePaths[panel] === filePath) {
        try {
          const content = await fetchFile(filePath);
          renderMarkdown(panel, content);
        } catch (_) {}
      }
    }
  };
  watchers[filePath] = es;
}

function unwatchFile(filePath) {
  if (watchers[filePath]) { watchers[filePath].close(); delete watchers[filePath]; }
}

// ── Helpers ──────────────────────────────────────────────────────────
function scrollPercent(el) {
  const max = el.scrollHeight - el.clientHeight;
  return max <= 0 ? 0 : Math.min(1, el.scrollTop / max);
}
function applyPercent(el, pct) {
  const max = el.scrollHeight - el.clientHeight;
  if (max > 0) el.scrollTop = pct * max;
}

// ── File loading ─────────────────────────────────────────────────────
async function selectFile(panel) {
  const path = await window.compare.selectFile();
  if (path) await loadFile(panel, path);
}

async function loadFile(panel, path) {
  const prev = filePaths[panel];
  filePaths[panel] = path;
  $(`path-${panel}`).textContent = path;
  $(`path-${panel}`).classList.add('loaded');
  if (prev && prev !== path) unwatchFile(prev);
  try {
    const content = await fetchFile(path);
    renderMarkdown(panel, content);
  } catch (err) {
    $(`render-${panel}`).innerHTML =
      `<p style="color:var(--error);padding:24px">Could not read file: ${err.message}</p>`;
  }
  watchFile(path);
}

function renderMarkdown(panel, content) {
  contents[panel] = content;
  $(`render-${panel}`).innerHTML = marked.parse(content);
  $(`empty-${panel}`).classList.add('hidden');
  updateDiffMap();
}

// ── Scroll sync ──────────────────────────────────────────────────────
function setupScrollSync() {
  const bodyA = $('body-a'), bodyB = $('body-b');
  bodyA.addEventListener('scroll', () => {
    if (!syncEnabled || syncing) return;
    syncing = true;
    applyPercent(bodyB, scrollPercent(bodyA));
    requestAnimationFrame(() => requestAnimationFrame(() => { syncing = false; }));
  }, { passive: true });
  bodyB.addEventListener('scroll', () => {
    if (!syncEnabled || syncing) return;
    syncing = true;
    applyPercent(bodyA, scrollPercent(bodyB));
    requestAnimationFrame(() => requestAnimationFrame(() => { syncing = false; }));
  }, { passive: true });
}

function toggleSync() {
  syncEnabled = !syncEnabled;
  $('btn-sync').classList.toggle('active', syncEnabled);
  if (syncEnabled) applyPercent($('body-b'), scrollPercent($('body-a')));
}

// ── Drag divider ─────────────────────────────────────────────────────
$('divider').addEventListener('mousedown', () => { dragging = true; });
document.addEventListener('mousemove', e => {
  if (!dragging) return;
  const r   = $('panels').getBoundingClientRect();
  const pct = Math.max(20, Math.min(80, (e.clientX - r.left) / r.width * 100));
  const pa = $('panel-a'), pb = $('panel-b');
  pa.style.flex = 'none'; pa.style.width = pct + '%';
  pb.style.flex = '1';    pb.style.width = '';
});
document.addEventListener('mouseup', () => { dragging = false; });

// ── Drop zones ───────────────────────────────────────────────────────
document.addEventListener('dragover', e => e.preventDefault());
document.addEventListener('drop',     e => e.preventDefault());

function setupDropZone(panel) {
  const body = $(`body-${panel}`);
  body.addEventListener('dragover', e => {
    e.preventDefault(); e.stopPropagation(); body.classList.add('drag-over');
  });
  body.addEventListener('dragleave', () => body.classList.remove('drag-over'));
  body.addEventListener('drop', e => {
    e.preventDefault(); e.stopPropagation();
    body.classList.remove('drag-over');
    const file = e.dataTransfer.files[0];
    if (file && file.path) loadFile(panel, file.path);
  });
}

// ── Diff minimap (LCS line diff) ─────────────────────────────────────
function lineDiff(textA, textB) {
  const a = textA.split('\n');
  const b = textB.split('\n');
  const m = a.length, n = b.length;
  const dp = Array.from({ length: m + 1 }, () => new Uint32Array(n + 1));
  for (let i = m - 1; i >= 0; i--)
    for (let j = n - 1; j >= 0; j--)
      dp[i][j] = a[i] === b[j] ? dp[i+1][j+1] + 1 : Math.max(dp[i+1][j], dp[i][j+1]);

  const raw = [];
  let i = 0, j = 0;
  while (i < m || j < n) {
    const last = raw[raw.length - 1];
    if (i < m && j < n && a[i] === b[j]) {
      if (last?.op === 'eq') { last.aEnd++; last.bEnd++; }
      else raw.push({ op: 'eq', aStart: i, aEnd: i+1, bStart: j, bEnd: j+1 });
      i++; j++;
    } else if (i < m && (j >= n || dp[i+1][j] >= dp[i][j+1])) {
      if (last?.op === 'del') { last.aEnd++; }
      else raw.push({ op: 'del', aStart: i, aEnd: i+1, bStart: j, bEnd: j });
      i++;
    } else {
      if (last?.op === 'ins') { last.bEnd++; }
      else raw.push({ op: 'ins', aStart: i, aEnd: i, bStart: j, bEnd: j+1 });
      j++;
    }
  }
  const chunks = [];
  for (let k = 0; k < raw.length; k++) {
    if (raw[k].op === 'del' && raw[k+1]?.op === 'ins') {
      chunks.push({ op: 'mod', aStart: raw[k].aStart, aEnd: raw[k].aEnd,
                               bStart: raw[k+1].bStart, bEnd: raw[k+1].bEnd });
      k++;
    } else { chunks.push(raw[k]); }
  }
  return { a, b, chunks };
}

function drawDiffMap(totalA, totalB, chunks) {
  const canvas  = $('diff-map');
  const divider = $('divider');
  const h = divider.clientHeight, w = divider.clientWidth;
  canvas.width = w; canvas.height = h;
  const ctx = canvas.getContext('2d');
  const mid = Math.floor(w / 2);
  ctx.fillStyle = '#ede7d9'; ctx.fillRect(0, 0, w, h);
  for (const c of chunks) {
    if (c.op === 'eq') continue;
    if (c.op === 'del' || c.op === 'mod') {
      const y1 = Math.floor((c.aStart / totalA) * h);
      const y2 = Math.ceil( (c.aEnd   / totalA) * h);
      ctx.fillStyle = '#ef4444';
      ctx.fillRect(1, y1, mid - 2, Math.max(2, y2 - y1));
    }
    if (c.op === 'ins' || c.op === 'mod') {
      const y1 = Math.floor((c.bStart / totalB) * h);
      const y2 = Math.ceil( (c.bEnd   / totalB) * h);
      ctx.fillStyle = '#22c55e';
      ctx.fillRect(mid + 1, y1, w - mid - 2, Math.max(2, y2 - y1));
    }
  }
  ctx.fillStyle = '#c8baa0'; ctx.fillRect(mid, 0, 1, h);
}

function annotateRendered(panel, content, chunks) {
  const render   = $(`render-${panel}`);
  const elements = [...render.children];
  const startKey = panel === 'a' ? 'aStart' : 'bStart';
  const endKey   = panel === 'a' ? 'aEnd'   : 'bEnd';
  elements.forEach(el => {
    el.removeAttribute('data-diff-chunk');
    el.classList.remove('diff-del', 'diff-ins');
  });
  const tokens = marked.lexer(content);
  let line = 0, elIdx = 0;
  for (const token of tokens) {
    const rawLines = token.raw.split('\n').length - 1;
    const tokenEnd = line + rawLines;
    if (token.type !== 'space') {
      const el = elements[elIdx++];
      if (el) {
        const ci = chunks.findIndex(c =>
          c.op !== 'eq' && c[startKey] < tokenEnd && c[endKey] > line);
        if (ci >= 0) {
          el.dataset.diffChunk = ci;
          el.classList.add(panel === 'a' ? 'diff-del' : 'diff-ins');
        }
      }
    }
    line = tokenEnd;
  }
}

function updateDiffMap() {
  if (!contents.a || !contents.b) return;
  const { a, b, chunks } = lineDiff(contents.a, contents.b);
  lastChunks = chunks; lastTotalA = a.length; lastTotalB = b.length;
  drawDiffMap(a.length, b.length, chunks);
  annotateRendered('a', contents.a, chunks);
  annotateRendered('b', contents.b, chunks);
}

// ── Critique panel stub ──────────────────────────────────────────────
function toggleCritique() {
  $('critique-panel').classList.toggle('hidden');
}

// ── Keyboard shortcuts ───────────────────────────────────────────────
document.addEventListener('keydown', e => {
  if (e.target.tagName === 'INPUT') return;
  if (e.key === 's' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); toggleSync(); }
});

// ── Init ─────────────────────────────────────────────────────────────
window.compare.onInitConfig(({ port }) => { API_PORT = port; });

window.compare.onInitFiles(async (pathA, pathB) => {
  if (pathA) await loadFile('a', pathA);
  if (pathB) await loadFile('b', pathB);
});

document.addEventListener('DOMContentLoaded', () => {
  setupScrollSync();
  setupDropZone('a');
  setupDropZone('b');
  new ResizeObserver(updateDiffMap).observe($('divider'));

  $('diff-map').addEventListener('click', e => {
    if (!lastChunks.length) return;
    const canvas   = $('diff-map');
    const yFrac    = e.offsetY / canvas.height;
    const leftSide = e.offsetX < canvas.width / 2;
    const total    = leftSide ? lastTotalA : lastTotalB;
    const startKey = leftSide ? 'aStart' : 'bStart';
    const endKey   = leftSide ? 'aEnd'   : 'bEnd';
    const clickLine = Math.floor(yFrac * total);
    const ci = lastChunks.findIndex(c =>
      c.op !== 'eq' && c[startKey] <= clickLine && c[endKey] > clickLine);
    if (ci < 0) return;
    for (const p of ['a', 'b']) {
      const el = $(`render-${p}`).querySelector(`[data-diff-chunk="${ci}"]`);
      if (el) {
        const body = $(`body-${p}`);
        const relTop = el.getBoundingClientRect().top - body.getBoundingClientRect().top;
        body.scrollBy({ top: relTop - 24, behavior: 'smooth' });
        break;
      }
    }
  });
});
```

- [ ] **Step 4: Update `package.json`**

Add to `devDependencies` and add scripts:

```json
{
  "name": "md-compare",
  "version": "1.0.0",
  "description": "Side-by-side rendered markdown comparison viewer",
  "main": "main.js",
  "scripts": {
    "start": "electron .",
    "build:server": "cd server && mvn package -DskipTests",
    "test:java": "cd server && mvn test",
    "test:e2e": "playwright test"
  },
  "devDependencies": {
    "electron": "^33.4.0",
    "@playwright/test": "^1.44.0",
    "playwright": "^1.44.0"
  }
}
```

- [ ] **Step 5: Manual smoke test — launch the full app**

```bash
ELECTRON=/Users/mdproctor/claude/sparge/node_modules/electron/dist/Electron.app/Contents/MacOS/Electron
"$ELECTRON" /Users/mdproctor/claude/md-compare \
  /Users/mdproctor/claude/md-compare/sample-a.md \
  /Users/mdproctor/claude/md-compare/sample-b.md
```

Verify: window opens at `http://localhost:PORT/`, both panels load, diff minimap shows markers.

- [ ] **Step 6: Commit**

```bash
git add main.js preload.js index.html package.json
git commit -m "feat: wire Electron to Quarkus server — HTTP file ops replace IPC

Refs #<issue>"
```

---

## Task 7: Playwright config and test harness

**Files:**
- Create: `playwright.config.js`
- Create: `electron-tests/e2e/global-setup.js`
- Create: `electron-tests/e2e/helpers.js`

- [ ] **Step 1: Install Playwright**

```bash
# Use Sparge's node_modules as Electron source; install Playwright in md-compare
# Since npm isn't on this shell's PATH, the user runs this in their terminal:
# cd ~/claude/md-compare && npm install
# Then: npx playwright install chromium
```

Note: Task 7 onwards requires npm to be available in an interactive shell. Run `npm install` and `npx playwright install` once manually, then the plan scripts work.

- [ ] **Step 2: Create `playwright.config.js`**

```javascript
// playwright.config.js
const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir:      './electron-tests/e2e',
  globalSetup:  './electron-tests/e2e/global-setup.js',
  timeout:      60_000,
  retries:      0,
  use: { headless: false },
});
```

- [ ] **Step 3: Create `electron-tests/e2e/global-setup.js`**

```javascript
// electron-tests/e2e/global-setup.js
'use strict';
const fs   = require('fs');
const path = require('path');
const os   = require('os');

const TMP = os.tmpdir();

// Write stable fixture files that tests can reference by known path
module.exports = async function globalSetup() {
  const fileA = path.join(TMP, 'mdcompare-test-a.md');
  const fileB = path.join(TMP, 'mdcompare-test-b.md');

  fs.writeFileSync(fileA, [
    '# Rule Engines',
    '',
    'A rule engine evaluates business rules against a set of facts.',
    '',
    '## How It Works',
    '',
    'Rules have a condition and an action.',
    '',
    '```java',
    'rule "Large order"',
    'when Order(total > 1000)',
    'then flag(order);',
    'end',
    '```',
    '',
    '## Limitations',
    '',
    'Rule engines add operational complexity and require careful tuning.',
  ].join('\n'));

  fs.writeFileSync(fileB, [
    '# Rule Engines',
    '',
    'A rule engine runs your business rules so developers do not have to hard-code them.',
    '',
    '## How It Works',
    '',
    'Each rule has a condition and an action. When the condition matches, the action fires.',
    '',
    '```java',
    'rule "Large order"',
    'when Order(total > 1000)',
    'then flag(order);',
    'end',
    '```',
    '',
    '## When Not to Use One',
    '',
    'Do not reach for a rule engine to replace five if/else statements.',
  ].join('\n'));

  // Make paths available to tests via env vars
  process.env.TEST_FILE_A = fileA;
  process.env.TEST_FILE_B = fileB;
};
```

- [ ] **Step 4: Create `electron-tests/e2e/helpers.js`**

```javascript
// electron-tests/e2e/helpers.js
'use strict';
const { _electron: electron } = require('playwright');
const path = require('path');

const ELECTRON_BIN = process.env.ELECTRON_BIN ||
  path.join(__dirname, '..', '..', 'node_modules', '.bin', 'electron');

const APP_PATH = path.join(__dirname, '..', '..');

async function launchApp(fileA, fileB) {
  const args = [APP_PATH];
  if (fileA) args.push(fileA);
  if (fileB) args.push(fileB);
  const app    = await electron.launch({ executablePath: ELECTRON_BIN, args });
  const window = await app.firstWindow();
  // Wait for both panels to be ready (server started, files loaded)
  await window.waitForSelector('#render-a .md-wrap h1, #render-a p', { timeout: 30_000 });
  return { app, window };
}

module.exports = { launchApp };
```

- [ ] **Step 5: Commit**

```bash
git add playwright.config.js electron-tests/
git commit -m "test: add Playwright config and test harness

Refs #<issue>"
```

---

## Task 8: Happy path and diff marker tests

**Files:**
- Create: `electron-tests/e2e/happy-path.spec.js`

- [ ] **Step 1: Create `electron-tests/e2e/happy-path.spec.js`**

```javascript
// electron-tests/e2e/happy-path.spec.js
'use strict';
const { test, expect } = require('@playwright/test');
const { launchApp }    = require('./helpers');

let app, window;

test.beforeAll(async () => {
  ({ app, window } = await launchApp(
    process.env.TEST_FILE_A,
    process.env.TEST_FILE_B
  ));
});

test.afterAll(async () => { if (app) await app.close(); });

test('app launches — window visible, no JS errors', async () => {
  const errors = [];
  window.on('pageerror', e => errors.push(e.message));
  await expect(window.locator('#topbar')).toBeVisible();
  await expect(window.locator('#logo')).toContainText('md-compare');
  expect(errors).toHaveLength(0);
});

test('panel A renders the heading from file A', async () => {
  await expect(window.locator('#render-a h1')).toContainText('Rule Engines');
});

test('panel B renders the heading from file B', async () => {
  await expect(window.locator('#render-b h1')).toContainText('Rule Engines');
});

test('both panels render code blocks', async () => {
  await expect(window.locator('#render-a pre')).toBeVisible();
  await expect(window.locator('#render-b pre')).toBeVisible();
});

test('diff markers appear in panel A for changed sections', async () => {
  // At least one element in panel A should have the diff-del class
  const count = await window.locator('#render-a .diff-del').count();
  expect(count).toBeGreaterThan(0);
});

test('diff markers appear in panel B for changed sections', async () => {
  const count = await window.locator('#render-b .diff-ins').count();
  expect(count).toBeGreaterThan(0);
});

test('diff minimap canvas has coloured segments', async () => {
  // Sample the canvas: a coloured pixel means the diff map rendered something
  const hasColor = await window.evaluate(() => {
    const canvas = document.getElementById('diff-map');
    const ctx    = canvas.getContext('2d');
    const w = canvas.width, h = canvas.height;
    for (let y = 0; y < h; y += 4) {
      // Left half — should contain red pixels (#ef4444 = 239,68,68)
      const px = ctx.getImageData(2, y, 1, 1).data;
      if (px[0] > 200 && px[1] < 100) return true;
    }
    return false;
  });
  expect(hasColor).toBe(true);
});

test('sync toggle button is active by default', async () => {
  await expect(window.locator('#btn-sync')).toHaveClass(/active/);
});
```

- [ ] **Step 2: Run the happy path tests**

```bash
npx playwright test electron-tests/e2e/happy-path.spec.js --reporter=list
```

Expected: 7 tests passing.

- [ ] **Step 3: Commit**

```bash
git add electron-tests/e2e/happy-path.spec.js
git commit -m "test: add happy-path Playwright E2E tests

Refs #<issue>"
```

---

## Task 9: Regression tests — sync scroll, click-to-scroll, file watch

**Files:**
- Create: `electron-tests/e2e/regression.spec.js`

- [ ] **Step 1: Create `electron-tests/e2e/regression.spec.js`**

```javascript
// electron-tests/e2e/regression.spec.js
'use strict';
const { test, expect } = require('@playwright/test');
const { launchApp }    = require('./helpers');
const fs = require('fs');
const os = require('os');
const path = require('path');

let app, window;

test.beforeAll(async () => {
  ({ app, window } = await launchApp(
    process.env.TEST_FILE_A,
    process.env.TEST_FILE_B
  ));
});

test.afterAll(async () => { if (app) await app.close(); });

// ── Sync scroll ──────────────────────────────────────────────────────

test('sync on: scrolling panel A moves panel B', async () => {
  // Ensure sync is on
  const btnActive = await window.locator('#btn-sync').getAttribute('class');
  if (!btnActive.includes('active')) await window.locator('#btn-sync').click();

  // Scroll panel A to 50%
  await window.evaluate(() => {
    const body = document.getElementById('body-a');
    const max  = body.scrollHeight - body.clientHeight;
    body.scrollTop = max * 0.5;
    body.dispatchEvent(new Event('scroll'));
  });

  // Wait a frame for sync to propagate
  await window.waitForTimeout(100);

  const [scrollA, scrollB] = await window.evaluate(() => {
    const a = document.getElementById('body-a');
    const b = document.getElementById('body-b');
    const maxA = a.scrollHeight - a.clientHeight;
    const maxB = b.scrollHeight - b.clientHeight;
    return [a.scrollTop / maxA, b.scrollTop / maxB];
  });

  // Both panels should be at roughly the same percentage (within 5%)
  expect(Math.abs(scrollA - scrollB)).toBeLessThan(0.05);
});

test('sync off: scrolling panel A does not move panel B', async () => {
  // Turn sync off
  const btnActive = await window.locator('#btn-sync').getAttribute('class');
  if (btnActive.includes('active')) await window.locator('#btn-sync').click();

  // Reset B to top
  await window.evaluate(() => {
    document.getElementById('body-b').scrollTop = 0;
  });

  // Scroll A to 60%
  await window.evaluate(() => {
    const body = document.getElementById('body-a');
    const max  = body.scrollHeight - body.clientHeight;
    body.scrollTop = max * 0.6;
    body.dispatchEvent(new Event('scroll'));
  });

  await window.waitForTimeout(100);

  const scrollB = await window.evaluate(() => {
    const b = document.getElementById('body-b');
    return b.scrollTop;
  });

  // Panel B should still be at top (scrollTop near 0)
  expect(scrollB).toBeLessThan(10);

  // Restore sync
  await window.locator('#btn-sync').click();
});

// ── Click-to-scroll ──────────────────────────────────────────────────

test('clicking diff minimap scrolls to the changed section', async () => {
  // Scroll both panels to top first
  await window.evaluate(() => {
    document.getElementById('body-a').scrollTop = 0;
    document.getElementById('body-b').scrollTop = 0;
  });

  // Find the first red segment in the diff map canvas and click it
  const clicked = await window.evaluate(() => {
    const canvas = document.getElementById('diff-map');
    const ctx    = canvas.getContext('2d');
    const h = canvas.height;
    const mid = Math.floor(canvas.width / 2);
    for (let y = 10; y < h; y += 3) {
      const px = ctx.getImageData(2, y, 1, 1).data; // left half
      if (px[0] > 200 && px[1] < 100 && px[3] > 0) {
        // Found a red pixel — simulate click at this position
        canvas.dispatchEvent(new MouseEvent('click', {
          bubbles: true, clientX: canvas.getBoundingClientRect().left + 2,
          offsetX: 2, offsetY: y
        }));
        return y;
      }
    }
    return null;
  });

  expect(clicked).not.toBeNull();

  // Wait for smooth scroll animation
  await window.waitForTimeout(600);

  // At least one panel should have scrolled away from top
  const [scrollA, scrollB] = await window.evaluate(() => [
    document.getElementById('body-a').scrollTop,
    document.getElementById('body-b').scrollTop,
  ]);

  expect(scrollA + scrollB).toBeGreaterThan(0);
});

// ── File watch ───────────────────────────────────────────────────────

test('file watch: updating file B re-renders panel B', async () => {
  const fileB = process.env.TEST_FILE_B;
  expect(fileB).toBeTruthy();

  // Verify original content is rendered
  await expect(window.locator('#render-b h2').first()).toBeVisible();
  const originalHeading = await window.locator('#render-b h2').first().textContent();

  // Write new content with a distinctive heading
  fs.writeFileSync(fileB, [
    '# Rule Engines',
    '',
    'Updated by file watch test.',
    '',
    '## Completely New Section',
    '',
    'This heading was not in the original file.',
  ].join('\n'));

  // Wait for SSE event and re-render (up to 5s)
  await expect(window.locator('#render-b h2')).toContainText('Completely New Section', { timeout: 5000 });

  // Restore original content for subsequent test runs
  fs.writeFileSync(fileB, fs.readFileSync(
    path.join(os.tmpdir(), 'mdcompare-test-b.md'), 'utf8'
  ));
});
```

Note: The file watch test modifies `TEST_FILE_B` in place and restores it. `global-setup.js` re-creates the fixtures on every run, so a failed restore doesn't break subsequent runs.

- [ ] **Step 2: Run all E2E tests**

```bash
npx playwright test --reporter=list
```

Expected: all tests passing across `happy-path.spec.js` and `regression.spec.js`.

- [ ] **Step 3: Commit**

```bash
git add electron-tests/e2e/regression.spec.js
git commit -m "test: add regression Playwright E2E tests — sync, click-scroll, file watch

Refs #<issue>"
```

---

## Self-Review

**Spec coverage:**
- ✅ Quarkus server with FileResource, WatchResource (SSE), UiResource, CritiqueResource stub
- ✅ java-server.js process manager (crash recovery, poll)
- ✅ Electron wires to Quarkus — IPC removed for file ops, dialog stays
- ✅ Playwright happy path — launch, panels render, diff markers visible, minimap coloured
- ✅ Playwright regression — sync on/off, click-to-scroll, file watch reload

**Placeholder scan:** No TBD, no "implement later", no "similar to Task N" references. All code shown in full.

**Type consistency:**
- `JavaServer` in `java-server.js` matches usage in `main.js`
- `API_PORT` used consistently throughout `index.html` script
- `data-diff-chunk` attribute name consistent between `annotateRendered` (write) and click handler (read)
- `TEST_FILE_A` / `TEST_FILE_B` env vars set in `global-setup.js` and read in both spec files
