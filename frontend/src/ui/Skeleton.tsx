type SkeletonProps = {
  width?: string;
  height?: string;
  radius?: string;
};

// Decorative placeholder. aria-hidden so screen readers skip it; wrap the real
// region in aria-busy while loading. Shimmer is dropped under reduced motion.
export function Skeleton({ width, height, radius }: SkeletonProps) {
  return (
    <span
      className="skeleton"
      aria-hidden="true"
      style={{ width, height, borderRadius: radius }}
    />
  );
}
