/**
 * Motion layer 3: the `motion` (framer-motion) library.
 *
 * Every import of the library flows through this module so the bundle impact
 * stays visible and scripts/motion-check.mjs can keep the main chunk free of
 * it: only lazy routes (src/demo/**, src/showcase/**) and this directory may
 * touch motion/react. Wow-scenes only — never base interactions.
 */
export {
  AnimatePresence,
  motion,
  useInView,
  useReducedMotion,
} from "motion/react";
