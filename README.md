<!-- Published from `extensions/linear` in the Agents Deck host repository. Edit it there. -->

> **Install it from [the latest release](https://github.com/alenkazoloto/agents-deck-linear/releases/latest)** — or from Agents Deck's
> Settings › Connections › Extensions › **+**, which installs this repository by name.
>
> Building from this checkout needs the host plugin's distribution zip, which is not published
> here: pass it as `./gradlew buildPlugin -PagentsDeckPluginZip=/path/to/agents-deck-<v>.zip`.
> The paths in the build section below are the host repository's own.

# Agents Deck: Linear

A first-party extension for [Agents Deck](https://github.com/alenkazoloto). It puts a Linear workspace behind
the chat's `#` completion, links `ENG-123` in agent output, and reports which key is in force.

It is also the first consumer of the host's **`issueProvider`** extension point — the widest
api surface the host publishes, and the only one that had no cross-classloader consumer until
this existed.

## What it contributes

| Extension point | What you see |
|---|---|
| `issueProvider` | `#` completion rows, chips, hover cards, and the issue's title and description travelling with the prompt |
| `transcriptLinker` | `ENG-123` in agent output opens the issue |
| `settingsSection` | Settings › Connections › Integrations › **Linear** — who the key belongs to |

## Configuring it

Create a **personal API key** at [linear.app/settings/api](https://linear.app/settings/api) and
put it in your environment as `LINEAR_API_KEY`:

```bash
export LINEAR_API_KEY=lin_api_…
```

…or paste it into **Agents Deck › Settings › Connections › Extensions**, on this extension's own
page, which keeps it in the IDE's password safe.

The host resolves it with its own precedence: the IDE's process environment first, then the
`env` block of a configured stdio MCP server, then the saved key. **This extension ships no field
and no store of its own** — one paste form in the host is what keeps four extensions from making
four storage decisions, and nothing typed there reaches plugin state in plaintext. With no key
set it contributes nothing at all and the chat is exactly the chat Agents Deck ships.

## Design notes

- **Personal API key, not OAuth.** `Authorization: <key>`, no `Bearer` — which is what removes
  the browser redirect, the password store and the token refresh from this extension entirely.
- **Search filters titles.** `issue(id: "ENG-123")` taking the human identifier is documented;
  a full-text field is not, and secondary sources disagree on its name. Guessing wrong would
  produce an empty popup indistinguishable from "no results", so search is structured
  filtering — `issues(filter: { title: { containsIgnoreCase: … } })` — and a typed identifier
  is looked up directly instead.
- **A link needs a team key, not just a shape.** `UTF-8`, `SHA-256` and `RFC-822` all match an
  identifier pattern. The linker resolves a token only when its team key is one the workspace
  actually has, which it learns from a single `viewer` probe on first use.
- **Nothing is probed at IDE start.** The startup activity resolves whether a key exists (a
  file read); the network call happens the first time you use `#` or open the settings section.

## Building it

```bash
# in the host repository root
./gradlew buildPlugin
# here
./gradlew test build
```

`./gradlew verifyExtensions` in the host builds this against `buildPlugin`'s own zip; it
discovers `extensions/*` rather than listing them, so nothing had to be registered for this
extension to be covered.
