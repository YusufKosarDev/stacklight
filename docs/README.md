# docs

The main [README](../README.md) is the documentation. This directory holds what
that file points at, plus one record it does not.

| | |
|---|---|
| `media/` | The two clips at the top of the main README, each as a gif for inline rendering and a webm for anyone who would rather watch it properly. Produced by [`tools/media`](../tools/media). |
| `screenshots/` | Stills of the overview and a group page. |
| `design/` | The spec and the implementation plan for the dashboard redesign. |

## On `design/`

Those two files are a record of one round of work rather than current
documentation, and they are kept for what they show rather than for what they
say: the spec argues for a change, the plan breaks it into tasks with a
verification loop, and both were written before any of it was built.

They have dated filenames and they are not maintained. Some of what they describe
has since moved — the front page's chart, for one, now takes its window from the
URL. **Where they and the code disagree, the code is right and the main README
is the current account.**
