# AGENTS.md
This project contains an MCP server, ACP server and Web UI. The MCP server has a JavaScript tool with a lot of modules.

## Setup commands
- Install deps: Give the user deps-hints and ask the user to edit `pom.xml`.
- Build & test: ask the user to run the Maven build (mvn package — it includes the tests). For quick pre-checks you may use the javascript_tool (e.g. a syntax check) before asking.

## Code style
- Java 17
- Javadoc in English.
- Logging: SLF4J
- Use explicit single-type imports; no wildcard imports, no fully qualified names in code.
- Do a proper exception-handling: Catch the checked exceptions instead of `Exception`, log them if there are no reasons against.
- Use flat-style try-with-resources — one declaration per resource — instead of nesting constructors in a single declaration.
- Prefer `java.util.concurrent` primitives (locks, atomics, concurrent collections) over `synchronized` (and `volatile`).
- Validate configuration properties strictly: an invalid value aborts with the property name and value quoted — never fall back to a default silently (otherwise it takes too long until anyone notices).
- JSON: Jackson3
- Documentation in `docs/` and `docs/js/`.
