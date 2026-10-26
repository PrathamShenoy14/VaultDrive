// At most five attempts per lifetime, one outstanding request, no catch-up burst.
// Hidden documents cancel the request; resuming waits a full interval.
export function boundedPoll(
  run: (signal: AbortSignal) => Promise<void>,
  changed: () => void,
  interval = 60000,
  maximum = 5,
) {
  let stopped = false,
    attempts = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let active: AbortController | undefined;
  const schedule = () => {
    if (stopped) return;
    if (attempts < maximum && document.visibilityState !== "hidden")
      timer = setTimeout(() => void tick(), interval);
    else if (attempts >= maximum) changed();
  };
  const tick = async () => {
    if (
      stopped ||
      active ||
      document.visibilityState === "hidden" ||
      attempts >= maximum
    )
      return;
    attempts++;
    const controller = new AbortController();
    active = controller;
    try {
      await run(controller.signal);
    } catch {
      if (!controller.signal.aborted) {
        stopped = true;
        changed();
      }
    } finally {
      if (active === controller) active = undefined;
      schedule();
    }
  };
  const visible = () => {
    clearTimeout(timer);
    if (document.visibilityState === "hidden") active?.abort();
    else if (!active) schedule();
  };
  document.addEventListener("visibilitychange", visible);
  void tick();
  return () => {
    stopped = true;
    clearTimeout(timer);
    active?.abort();
    document.removeEventListener("visibilitychange", visible);
  };
}
