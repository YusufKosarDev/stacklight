/**
 * Renders a server component tree to HTML.
 *
 * `renderToStaticMarkup` is synchronous, and these pages are not: a page awaits its
 * query and then returns `<Shell>`, which awaits its own. React refuses to render that
 * synchronously -- "a component suspended while responding to synchronous input" -- and
 * the streaming renderers do not help either, because resolving async server components
 * is the job of a React Server Components runtime rather than of the DOM renderer.
 *
 * Pulling that runtime in would mean a dependency. Instead this walks the tree first and
 * awaits every component in it, which is what such a runtime does before handing markup
 * to the renderer. What comes out the far side is a plain element tree of host tags,
 * which `renderToStaticMarkup` renders happily.
 *
 * It is not a reimplementation of React and does not try to be. There is no Suspense
 * here, no streaming, no client boundary and no context -- this dashboard has none of
 * those, and the day it does, this file should stop being the way to test it.
 */
import { cloneElement, isValidElement, type ReactElement, type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";

/** Depth-first, awaiting every component so what is left is only host elements. */
async function settle(node: ReactNode): Promise<ReactNode> {
  if (Array.isArray(node)) {
    return Promise.all(node.map((child) => settle(child)));
  }

  if (!isValidElement(node)) {
    return node;
  }

  const element = node as ReactElement<{ children?: ReactNode }>;

  // A component -- call it, await whatever it gives back, and settle that in turn.
  // Sync components return an element and `await` passes it straight through, so this
  // one branch covers both kinds.
  if (typeof element.type === "function") {
    const component = element.type as (props: unknown) => ReactNode | Promise<ReactNode>;
    return settle(await component(element.props));
  }

  // A host tag or a fragment: nothing to call, but its children may still hold
  // components. Cloned rather than mutated, and with no props passed, so the key and
  // everything else the element carries survives.
  const { children } = element.props;
  if (children === undefined) {
    return element;
  }

  const settled = await settle(children);

  // Spread rather than handed over as one array. A list passed as a single child is a
  // dynamic list to React and it asks for keys; JSX siblings are not, and they arrive
  // here as an array only because this walk rebuilt them. Passing the array whole made
  // the harness print missing-key warnings about markup that never had a list in it,
  // which is worse than useless -- it invents a defect in the thing under test.
  //
  // The cost is that a genuine missing key inside a mapped list is quiet here too. That
  // belongs to `next build` and the browser, which both still say so, and not to a
  // renderer written to check what the page says.
  return Array.isArray(settled)
    ? cloneElement(element, undefined, ...settled)
    : cloneElement(element, undefined, settled);
}

/** The HTML a browser would be sent for this page. */
export async function render(node: ReactNode): Promise<string> {
  return renderToStaticMarkup((await settle(node)) as ReactElement);
}

/** The same, stripped to what a reader actually sees. */
export async function text(node: ReactNode): Promise<string> {
  const html = await render(node);
  return html
    .replace(/<[^>]+>/g, " ")
    .replace(/&#x27;|&apos;/g, "'")
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&middot;/g, "·")
    .replace(/&nbsp;/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}
