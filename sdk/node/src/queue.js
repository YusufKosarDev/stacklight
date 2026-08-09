"use strict";

/**
 * A bounded hand-off between the code that fails and the loop that reports.
 *
 * When full, the oldest event is discarded. The queue only fills when the collector cannot
 * be reached, which on this deployment means it is waking up; when it comes back, what is
 * happening now is worth more than what was happening two minutes ago.
 *
 * The cost is that the first occurrence of an incident is the first to go. It is accepted
 * because the collector groups by fingerprint, so a later event of the same fault opens the
 * same group -- losing the earliest copy costs a count, while dropping the newest would
 * mean nothing recent gets through during a sustained burst.
 *
 * Every discard is counted. A reporter that quietly loses things is worse than one that
 * says how many.
 */
class EventQueue {
  constructor(capacity) {
    this.capacity = capacity;
    this.items = [];
    this.acceptedCount = 0;
    this.droppedCount = 0;
  }

  /** Never throws and never blocks: at capacity it makes room. */
  offer(event) {
    while (this.items.length >= this.capacity) {
      this.items.shift();
      this.droppedCount += 1;
    }
    this.items.push(event);
    this.acceptedCount += 1;
  }

  /** Takes up to `max` events from the front. */
  drain(max) {
    return this.items.splice(0, max);
  }

  /**
   * Returns a failed batch to the front, keeping its order.
   *
   * A batch that could not be delivered has not been reported, so it goes back where it
   * was. If the queue filled while it was in flight, the usual rule applies from the other
   * end.
   */
  requeueFront(batch) {
    this.items.unshift(...batch);
    while (this.items.length > this.capacity) {
      this.items.pop();
      this.droppedCount += 1;
    }
  }

  get size() {
    return this.items.length;
  }

  get accepted() {
    return this.acceptedCount;
  }

  get dropped() {
    return this.droppedCount;
  }
}

module.exports = { EventQueue };
