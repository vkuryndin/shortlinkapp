# ShortLinkApp CLI (Java)
## Badges

![Build Status](https://github.com/vkuryndin/MyFinanceApp/actions/workflows/ci.yml/badge.svg)

![Coverage](./badges/jacoco.svg)
![Branches](./badges/branches.svg)

---
# Description
A multi-user console application for creating and managing short links entirely on local JSON storage.  
It supports link creation with TTL and click limits, opening (with auto-redirect), filters/sorting, statistics, JSON export, user switching, and an event/notification log.

---

## Features

### Short-Link Management
- Create short links for `http`/`https` URLs
- TTL (hours) and click-limit per link (including “unlimited”)
- List, search, filter by status (`ACTIVE`, `EXPIRED`, `LIMIT_REACHED`, `DELETED`)
- View detailed link info
- Edit click limit (owner-only; respects config flag)
- Delete link (owner-only)

### Open / Redirect
- Open by short code or full `baseUrl + code`
- Desktop auto-open via default browser (falls back to printing URL)
- Accurate handling of:
    - Expired links → mark as `EXPIRED` and block
    - Limit reached → mark as `LIMIT_REACHED` and block
    - Deleted links → block

### Users
- Local multi-user support
- Auto-generated per-machine default user UUID (`.local/user.uuid`)
- List known users (created/last seen)
- Switch current user by UUID
- Create a new user and switch
- Persist “current default” user choice

### Events / Notifications
- Toggleable event logging (config)
- Types: `INFO`, `EXPIRED`, `LIMIT_REACHED`, `ERROR`
- Per-user notifications (recent N)
- Global event log (recent N)

### Maintenance & Validation
- Cleanup expired links (soft mark or hard delete per config)
- Cleanup `LIMIT_REACHED`
- JSON integrity validation:
    - Duplicate or missing `shortCode`
    - Unknown owners
    - URL validity, dates consistency
    - Counters/limits coherence
- Statistics:
    - Totals by status
    - Total clicks
    - Top-N by clicks (mine/global)

### JSON Export
- Export all of *my* links to `data/export_<uuid>_<timestamp>.json`

### Console Interface
- Safe, trimmed input
- Clear multi-level menus for links, open, maintenance, help, settings, users
- Consistent table output (padding/truncation helpers)

---

## Architecture Overview

    src/
    └── main/java/org/example/shortlinkapp/
    app/
      Main.java                     # Entry point: load config, ensure user UUID, run ConsoleMenu

    cli/
      ConsoleMenu.java              # All menus and actions (links, open, maintenance, users, help)
      InputUtils.java               # Safe console input (trimmed, null on EOF)
      ConsoleMenuTest.java
      InputUtilsTest.java      
    
    model/
      ShortLink.java                # Link entity: id/owner/urls/timestamps/limits/status
      Status.java                   # ACTIVE | EXPIRED | LIMIT_REACHED | DELETED
      EventLog.java                 # Event entry (ts/type/owner/shortCode/message)
      EventType.java                # INFO | EXPIRED | LIMIT_REACHED | ERROR
      User.java                     # UUID, createdAt, lastSeenAt

    service/
      ShortLinkService.java         # Core business logic: create/open/edit/delete, stats, export,
                                    # auto-cleanups, validation; emits events via EventService
      EventService.java             # Emits/list events (per owner and global)
      UserService.java              # Manages current user, list, create/switch/upsert
      EventServiceTest.java
      ShortLinkServiceTest.java        

    storage/
      ConfigJson.java               # Load or create default config (data/config.json)
      DataPaths.java                # Common paths like data/links.json, users.json, events.json
      JsonRepository.java           # Resilient JSON read/write with atomic replace
      LinksRepository.java          # In-memory cache + JSON persistence for links
      UsersRepository.java          # In-memory cache + JSON persistence for users
      EventsRepository.java         # In-memory cache + JSON persistence for events
      LocalUuid.java                # Persistent current user UUID in .local/user.uuid
      StorageJson.java              # Generic atomic writer for lists (kept for compatibility)
      ConfigJsonTest.java
      EventRepositoryTest.java
      LinksRepositoryTest.java
      LinksREpositoryCleanupTest.java      
      UsersRepositoryTest.java
      StorageJsonTest.java    
      
    util/
      JsonUtils.java                # Gson with LocalDateTime adapters (ISO)
      TimeUtils.java                # Expiration check helpers
      UrlValidator.java             # Strict http/https + host check

> **Data files (created automatically on first run):**
>
> - `data/config.json` – configuration
> - `data/links.json` – all short links
> - `data/users.json` – known users
> - `data/events.json` – event log
> - `.local/user.uuid` – default user UUID

---

## Configuration

`data/config.json` contains:

| Key                    | Type           | Description |
|------------------------|----------------|-------------|
| baseUrl                | string         | Prefix for printed links (e.g. `cli://`) |
| shortCodeLength        | number         | Base62 code length |
| defaultTtlHours        | number         | TTL in hours |
| defaultClickLimit      | number or null | Default click limit; null → unlimited |
| maxUrlLength           | number         | Max allowed long URL size |
| cleanupOnEachOp        | boolean        | Auto-cleanup on every operation |
| allowOwnerEditLimit    | boolean        | Allow editing link limits |
| hardDeleteExpired      | boolean        | Hard delete expired or limit-reached |
| eventsLogEnabled       | boolean        | Enable notification logging |
| clockSkewToleranceSec  | number         | Reserved |

Reload config at runtime via **Settings → Reload Config**.

---

## Typical Flows

### Create a short link
1. `Main Menu → My Links → Create Short Link`
2. Enter long URL (must be valid http/https)
3. Enter click limit or leave empty

### Open a short link
1. `Main Menu → Open Short Link`
2. Enter the code or full URL
3. Browser opens (when supported)

### Maintenance
- Cleanup expired or limit reached
- Validate JSON
- Statistics (mine/global)
- View global event log

### Users
- Show current user
- List all users
- Switch by UUID
- Create a new user and switch
- Save as default user

---

## Used Libraries

### Runtime
- **Gson** – JSON serialization
- **AWT Desktop API** – browser opening

---

## Running Tests and Coverage

### Run all tests

    ./gradlew test

### Generate coverage report

    ./gradlew jacocoTestReport

Reports can be found in:

    - build/reports/jacoco/
    - build/reports/tests/test/

### Run spotbugs 
    ./gradlew spotbugstest spotbugsMain 
---

## GitHub CI/CD Pipeline

GitHub Actions are fully configured to run on every push and pull request.

### The pipeline performs:
- Java 17 setup
- Gradle caching
- Full project build, including:
    - Compilation
    - Tests
    - Jacoco coverage
    - SpotBugs
    - Checkstyle

### Artifact Publishing
The pipeline uploads:
- JAR files
- JUnit test reports
- Jacoco HTML reports
- SpotBugs reports
- Checkstyle reports

### Coverage Badges
- Total and branch coverage badges are generated automatically
- Generated using jacoco-badge-generator
- Badges are committed back to the repository
- Badge generation disabled for pull requests

### Artifact Naming
- Branch names are sanitized (slashes replaced with dashes)

### Reliability
- Artifacts are stored for 7 days
- Reports are uploaded even if the build fails

---
## Static Analysis (GitHub Actions) - additional static analysis workflow

This repository includes a dedicated **Static Analysis** workflow that runs **Spotless**, **Checkstyle**, and **SpotBugs** on every change to Java and build/config files.

**When it runs**
- On `push` to: `main`, `master`, `develop`, and any `feature/**` branch
- On all `pull_request`s
- Only triggers if relevant files change: `**/*.java`, Gradle files, `config/checkstyle/**`, or the workflow itself

**What it does**
- Executes:
    - `spotlessCheck`
    - `checkstyleMain` and `checkstyleTest`
    - `spotbugsMain` and `spotbugsTest`
- Uses JDK 17 and Gradle cache for faster runs
- Concurrency prevents overlapping runs per ref

**Outputs & reports**
- **Spotless**: uploads `.diff` files (if formatting violations exist)
- **Checkstyle**: HTML reports under `build/reports/checkstyle`
- **SpotBugs**: HTML/XML reports uploaded as artifacts:
    - `build/reports/spotbugs/main.html` / `test.html`
    - `build/reports/spotbugs/main.xml` / `test.xml`
- A short summary is added to the job’s “Summary” tab

**How to fix failures locally**

# Format code automatically
    ./gradlew spotlessApply

# Re-run checks
    ./gradlew spotlessCheck checkstyleMain checkstyleTest spotbugsMain spotbugsTest