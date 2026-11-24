# <img src="docs/icon.png" width="36" height="36" align="absmiddle" alt="" /> Agent Threads

A Wear OS wrist app and Cloudflare Workers relay for continuing ongoing AI coding agent conversations (**T3 Code**, **Claude Code**, **Codex**, and **Antigravity**) on the go.

Pairs with the Mac bridge in [agent-hud](https://github.com/TheHubOfGit/agent-hud) (`mac-agent-bridge` / `Agent HUD.app`).

## Structure

| Folder | What it is |
|---|---|
| `watch-threads/` | **Agent Threads** Wear OS app (Kotlin, Jetpack Compose): browse threads by project, read full-width high-contrast strips, answer approvals/questions, and reply by voice, keyboard, or quick replies. |
| `relay/` | Cloudflare Worker + Durable Object that lets the watch reach your Mac bridge from any network (Bluetooth proxy, LTE, or away Wi-Fi) without opening ports on your home network. |

## Build & Install (Wear OS)

```bash
cd watch-threads
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" # if default JDK > 21
./gradlew assembleRelease
adb -s <watch-serial> install -r app/build/outputs/apk/release/app-release.apk
```

The build bakes in your Mac bridge token from `~/.agenthud-bridge-token`. Open the app once at home to automatically learn the relay URL from your Mac.

## Deploy Relay (Cloudflare Workers)

```bash
cd relay
npm install
npx wrangler deploy
printf %s "$(tr -d '\n' < ~/.agenthud-bridge-token)" | shasum -a 256 | cut -d' ' -f1 | tr -d '\n' | npx wrangler secret put TOKEN_SHA256
echo https://agent-threads-relay.<your-subdomain>.workers.dev > ~/.agenthud-relay-url
```
