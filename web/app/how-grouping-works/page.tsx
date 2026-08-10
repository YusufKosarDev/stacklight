import { Shell } from "@/app/components/shell/shell";

export const metadata = {
  title: "How grouping works — Stacklight",
};

function Step({
  number,
  title,
  children,
}: {
  number: string;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <li className="rounded-xl border border-edge bg-surface-1 p-5">
      <h3 className="mb-2 text-sm font-medium text-ink-hi">
        <span className="mr-2 font-mono text-ink-low">{number}</span>
        {title}
      </h3>
      <div className="space-y-3 text-sm leading-relaxed text-ink-low">
        {children}
      </div>
    </li>
  );
}

export default function Page() {
  return (
    <Shell current="grouping">
      {/*
        A readable measure rather than the full dashboard column. This page is
        continuous prose, and the main column is far too wide for it.
      */}
      <article className="max-w-xl">
        <h1 className="text-xl font-semibold tracking-tight text-ink-hi sm:text-2xl">
          How grouping works
        </h1>
        <p className="mt-2 text-sm leading-relaxed text-ink-low">
          Ten thousand events are not ten thousand problems. Grouping turns a
          stream of events into a list of distinct faults, and the only useful
          version of that is one you can predict: the same error must always
          land in the same group, and you have to be able to see why it did.
        </p>
        <p className="mt-3 text-sm leading-relaxed text-ink-low">
          Every step below is a pure function of the event. Nothing here is
          statistical, nothing is trained, and nothing changes its mind between
          two runs. Any group page shows its own worked example.
        </p>

        <ol className="mt-8 space-y-4">
          <Step number="01" title="Detect the platform">
            <p>
              Java and V8 stack traces have nothing in common past the leading{" "}
              <code className="font-mono text-ink">at</code>. Each has its own
              parser, picked from the platform the client declared or, when it
              declared none, from whichever parser recognises the text with the
              highest confidence. A trailing{" "}
              <code className="font-mono text-ink">:line:column</code> pair is
              decisive: the JVM never emits one.
            </p>
          </Step>

          <Step
            number="02"
            title="Separate application frames from vendor frames"
          >
            <p>
              A frame belongs to the application unless it comes from the
              runtime, a framework or a dependency &mdash;{" "}
              <code className="font-mono text-ink">java.</code>,{" "}
              <code className="font-mono text-ink">org.springframework.</code>,{" "}
              <code className="font-mono text-ink">node_modules/</code>,{" "}
              <code className="font-mono text-ink">node:</code>.
            </p>
            <p>
              This matters more than it looks. One bug in your code is reached
              through a different framework path depending on which request hit
              it. Grouping on those paths splits one fault into a dozen groups.
            </p>
          </Step>

          <Step number="03" title="Drop what changes between occurrences">
            <p>
              Line numbers are excluded. Adding a line above a throw site shifts
              every number below it, and a fingerprint that moved on every such
              edit would open a new group for an error that never changed.
            </p>
            <p>
              Paths are cut at the last recognisable source root, so{" "}
              <code className="font-mono text-ink">/app/src/cart.js</code> in a
              container and{" "}
              <code className="font-mono text-ink">
                /home/dev/checkout/src/cart.js
              </code>{" "}
              on a laptop are the same frame.
            </p>
            <p>
              Inside messages, identifiers, addresses, timestamps, paths and
              plain numbers become placeholders. The rules run from most
              specific to least: a UUID contains digits, so replacing digits
              first would shred it, and two messages differing only by
              identifier would stop matching.
            </p>
          </Step>

          <Step number="04" title="Leave the message out when there are frames">
            <p>
              Messages carry values and runtimes reword them between releases.
              Where a stack trace exists, the frames say what happened more
              reliably, so the fingerprint is built from the type and the in-app
              frames alone.
            </p>
            <p>
              When there is no trace, the normalized message is all there is and
              it is used &mdash; the group says so on its page.
            </p>
          </Step>

          <Step number="05" title="Hash, and record the version">
            <p>
              The assembled text is hashed with SHA-256 and truncated to 128
              bits. A group is keyed by that hash <em>and</em> the algorithm
              version that produced it.
            </p>
            <p>
              When the algorithm changes, old groups are frozen rather than
              recomputed. A group carries observed facts &mdash; when it was
              first seen, how often it happened &mdash; and a new version
              re-partitions events instead of relabelling them: groups can merge
              and split at once, so there is no correct answer for which
              first-seen survives. The honest cost is that a still-occurring
              error opens a new group after a version bump, which is why the
              version changes rarely and on purpose.
            </p>
          </Step>
        </ol>

        <section className="mt-10">
          <h2 className="text-sm font-medium uppercase tracking-wider text-ink-low">
            What grouping deliberately does not do
          </h2>
          <div className="mt-3 space-y-3 text-sm leading-relaxed text-ink-low">
            <p>
              <strong className="text-ink-hi">Minified frames.</strong> Minified
              names are reassigned on every build, so grouping on them opens a
              fresh group per deploy &mdash; worse than not grouping. They are
              detected and the message is used instead. Resolving them properly
              needs source maps, which this project does not accept yet.
            </p>
            <p>
              <strong className="text-ink-hi">Guessing.</strong> Similar groups
              are surfaced with Postgres trigram similarity over an index, and
              shown as suggestions on a group page. They never merge anything on
              their own.
            </p>
          </div>
        </section>
      </article>
    </Shell>
  );
}
