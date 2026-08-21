# tools

Two things that are not the product and not tests: one that produced the data the
detectors were judged on, and one that produced the clips at the top of the main
README.

## `traffic/`

A generated scenario, played against the wall clock.

It exists because the detector scorecard cannot compare three detectors on a
deployment nobody uses: with three judged buckets they all sat at 100% and the
choice of active detector rested on an argument rather than a measurement. It has
since done its job — the run finished, the scorecard filled, and **the active
detector changed on the strength of it**.

The schedule in `scenario.mjs` is pure data, and the tests over it are about what
it must not do: exceed the collector's hourly cap, outgrow the storage budget, or
keep running after its thirty hours are up. `generate.mjs` reads what the current
hour already holds and sends only the difference, so a tick that finds the hour
complete makes no request at all.

```bash
node --test tools/traffic/*.test.mjs
```

Rollup buckets are written as `date_trunc('hour', now())`, so history cannot be
back-filled — re-running means moving `START_ISO` and playing it forward again.

> The traffic is **written, not reported**, and the dashboard says so on every
> page. [Why, and what it is for](../README.md#-the-traffic-behind-those-numbers-is-generated-not-real).

## `media/`

`record.mjs` drives a browser to record the two clips; `to-gif.sh` turns a
recording into the gif the README shows inline.

The gif settings are a compromise found by measuring rather than by taste, and
the script explains each one — the width in particular, which is where the
caption strip stops being legible if it goes lower.
