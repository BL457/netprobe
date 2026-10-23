# Changelog

Release notes for every NetProbe version, newest first. The section for a version is what the in-app updater shows under "What's new".

## 0.4.1
- Update tools from GitHub: the Tools menu and Settings, Tools can pull every tool from the shared catalog in the NetProbe repository, including updates to tools you already have. One review sheet shows what is new, what changed and what failed before anything is installed.
- First catalog tool: Wake on LAN. Pick a sleeping device and its MAC address is filled in for you.
- Scripts can declare broadcast: true to send to your network's broadcast address, and device tools now receive the device's MAC as inputs.mac. The guide covers both.
- Directory brute-force can recurse into the folders it finds, down to a depth you choose.
- Identify service and Identify all services now try every known hello on a manual run instead of stopping at six, with no time limit. A new Settings switch, Include unsafe Hellos on service identification (off by default), adds the hellos that can upset fragile devices.

## 0.4.0
- The Tools tab is now a grid of tiles grouped by category. Pick a tool, then fill in only the inputs that tool needs.
- Each tool has its own page: device and port pickers where they apply, a Run button that stays at the bottom, live progress, results as they arrive, and previous runs of that tool.
- Make your own tools in JavaScript and import them from a file or an https link. A review sheet shows the tool's name, inputs, network scope and source before anything is saved.
- Three example scripts are included: HTTP headers, banner grab and DNS lookup. The scripting guide is in docs/tools-scripting.md.
- Manage imported tools under Settings, Tools: see version and scope, remove, or long-press a tile for About and Copy source.
- Tools remember the inputs you last used. Secret inputs such as SNMP communities are never stored.
- The dir-brute options sheet is gone; its settings live on the tool page.

## 0.3.5
- The card shown once after an update is now titled "In this version" and lists what was just added, so you do not have to leave the app to read it.

## 0.3.4
- After Check for updates finds a new version, the update appears on the Settings screen itself, with the same Update button as Home.

## 0.3.3
- Every update now ships with release notes, taken from this file.
- After you update, the Home screen shows a "What's new" card with the notes for the version you just installed. Tap "Got it" to dismiss it.
- Settings has a new "Release notes" row, above About, that opens all versions on GitHub.

## 0.3.2
- Settings shows when updates were last checked.
- The first open after an update checks for updates straight away.

## 0.3.1
- First update delivered through the in-app updater.
- No functional changes beyond the version bump.

## 0.3.0
- First signed release.
- Passive scans, with on-demand service identification for each host.
- Device type icons on the host list.
- Offline vendor database for MAC address lookups.
- Long-press a host or value to copy it.
- Ko-fi support link in Settings and About.
- In-app updater: checks GitHub for a newer version and installs it with one tap.
