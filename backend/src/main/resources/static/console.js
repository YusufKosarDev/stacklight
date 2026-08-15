/*
  Triage console.

  One rule runs through this file and it is not a style preference: every value that
  reaches the page goes in through textContent, and nothing here builds markup from a
  string. Group titles are derived from the error messages applications send to
  /api/events, so their text is chosen by whoever holds the ingest key rather than by
  anyone reading this page. Assigning one to innerHTML would turn a reported error into
  script running in the operator's browser, with the operator's console key in the same
  tab. A test asserts this file contains no innerHTML, so the rule cannot quietly lapse.

  The key lives in sessionStorage: it survives a reload, which is what makes the page
  usable, and goes when the tab does, which bounds how long it sits anywhere.
*/

"use strict";

const STORAGE_KEY = "stacklight.console.key";
const HEADER = "X-Stacklight-Console-Key";
const STATUSES = ["open", "resolved", "ignored"];

const keyForm = document.getElementById("key-form");
const keyInput = document.getElementById("key-input");
const forgetButton = document.getElementById("forget");
const filter = document.getElementById("filter");
const reloadButton = document.getElementById("reload");
const message = document.getElementById("message");
const rows = document.getElementById("rows");

function consoleKey() {
  return sessionStorage.getItem(STORAGE_KEY) || "";
}

function say(text, isError) {
  message.textContent = text;
  message.classList.toggle("error", Boolean(isError));
}

function request(path, options) {
  const key = consoleKey();
  if (!key) {
    return Promise.reject(new Error("No key yet. Paste the console key above."));
  }

  const headers = { [HEADER]: key };
  if (options && options.body) {
    headers["Content-Type"] = "application/json";
  }

  return fetch(path, { ...options, headers, cache: "no-store" }).then((response) => {
    if (response.status === 401) {
      throw new Error("The server did not accept that key.");
    }
    if (response.status === 404) {
      throw new Error("No group with that id. It may have been reassigned by a version bump.");
    }
    if (!response.ok) {
      throw new Error("The server answered " + response.status + ".");
    }
    return response.status === 204 ? null : response.json();
  });
}

/* Renders one cell. Text only, always. */
function cell(row, text, className) {
  const td = document.createElement("td");
  td.textContent = text;
  if (className) {
    td.className = className;
  }
  row.appendChild(td);
  return td;
}

function moveTo(group, status, release) {
  say("Moving " + group.id + " to " + status + "…");

  request("api/groups/" + group.id, {
    method: "PATCH",
    body: JSON.stringify({ status, release: release || null }),
  })
    .then(() => {
      say("Group " + group.id + " is " + status + ".");
      return load();
    })
    .catch((error) => say(error.message, true));
}

function actions(row, group) {
  const td = document.createElement("td");
  const wrap = document.createElement("div");
  wrap.className = "actions";

  const release = document.createElement("input");
  release.type = "text";
  release.placeholder = "release";
  release.setAttribute("aria-label", "Release for group " + group.id);
  release.spellcheck = false;

  STATUSES.forEach((status) => {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = status;
    button.disabled = group.status === status;
    button.addEventListener("click", () =>
      moveTo(group, status, status === "resolved" ? release.value.trim() : ""),
    );
    wrap.appendChild(button);
  });

  /*
    Only meaningful when resolving: the column records the build a fix went out in, so a
    regression can later be read against it. Left blank, the server falls back to the last
    release the group was seen in.
  */
  wrap.appendChild(release);

  td.appendChild(wrap);
  row.appendChild(td);
}

function render(groups) {
  rows.replaceChildren();

  if (groups.length === 0) {
    const row = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = 7;
    td.className = "empty";
    td.textContent = "Nothing in this status.";
    row.appendChild(td);
    rows.appendChild(row);
    return;
  }

  groups.forEach((group) => {
    const row = document.createElement("tr");
    cell(row, String(group.id), "num");
    cell(row, group.service);
    cell(row, group.title, "title");
    cell(row, group.status, "status status-" + group.status);
    cell(row, String(group.eventCount), "num");
    cell(row, new Date(group.lastSeen).toLocaleString());
    actions(row, group);
    rows.appendChild(row);
  });
}

function load() {
  if (!consoleKey()) {
    rows.replaceChildren();
    say("Paste the console key to list groups.");
    return Promise.resolve();
  }

  say("Loading…");

  const status = filter.value;
  const path = status ? "api/groups?status=" + encodeURIComponent(status) : "api/groups";

  return request(path)
    .then((groups) => {
      render(groups);
      say(groups.length + (groups.length === 1 ? " group." : " groups."));
    })
    .catch((error) => {
      rows.replaceChildren();
      say(error.message, true);
    });
}

keyForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const value = keyInput.value.trim();
  if (!value) {
    return;
  }
  sessionStorage.setItem(STORAGE_KEY, value);
  keyInput.value = "";
  load();
});

forgetButton.addEventListener("click", () => {
  sessionStorage.removeItem(STORAGE_KEY);
  keyInput.value = "";
  rows.replaceChildren();
  say("Key forgotten.");
});

filter.addEventListener("change", load);
reloadButton.addEventListener("click", load);

load();
