// The curve between two stages, in the console's edge colour. Decorative: the order is the list's.
export function Edge({ reached }: { reached: boolean }) {
  return (
    <svg className={`ac-edge edp-edge${reached ? " edp-edge-reached" : ""}`} viewBox="0 0 40 48" preserveAspectRatio="none" aria-hidden="true" focusable="false">
      <path d="M0 24 C 14 24, 14 14, 20 14 S 26 24, 40 24" />
    </svg>
  );
}
