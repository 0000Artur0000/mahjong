import { useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "@/motion/motion";
import { Button, Tile } from "@/ui";
import type { TileId } from "@/ui";

const DEAL: TileId[] = ["1z", "5m", "0p", "7s", "back"];

/**
 * Layer 3 proof: the motion library animates a tile deal with spring physics
 * and stagger. This module is imported only by the lazy Styleguide route, so
 * the library never lands in the main chunk (scripts/motion-check.mjs).
 */
export function MotionDemo() {
  const [dealt, setDealt] = useState(false);
  const reduce = useReducedMotion();

  return (
    <div className="motion-demo">
      <div className="motion-demo__table fx-felt">
        <AnimatePresence>
          {dealt &&
            DEAL.map((id, i) => (
              <motion.div
                key={id}
                initial={reduce ? false : { opacity: 0, x: -64, rotate: -10 }}
                animate={{ opacity: 1, x: 0, rotate: 0 }}
                exit={reduce ? { opacity: 0 } : { opacity: 0, x: 48, rotate: 6 }}
                transition={{
                  type: "spring",
                  stiffness: 320,
                  damping: 24,
                  delay: reduce ? 0 : i * 0.07,
                }}
              >
                <Tile tile={id} width={64} />
              </motion.div>
            ))}
        </AnimatePresence>
      </div>
      <div className="cluster">
        <Button variant="primary" onClick={() => setDealt((v) => !v)}>
          {dealt ? "Собрать обратно" : "Раздать тайлы"}
        </Button>
        <p className="demo-label">spring + stagger · motion/react · lazy chunk</p>
      </div>
    </div>
  );
}
