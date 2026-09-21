/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

import { motion } from "motion/react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { BentoGrid, BentoGridItem } from "@/components/ui/bento-grid"

/**
 * Toolchain smoke test — proves Vite + Tailwind v4 + shadcn/ui + the
 * Aceternity registry + Motion all compile and render together in one tree.
 * Replace with real dashboard pages once you start building.
 */
function App() {
  return (
    <div className="min-h-svh bg-background p-10 text-foreground">
      <motion.h1
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4, ease: [0.16, 1, 0.3, 1] }}
        className="mb-2 text-3xl font-bold tracking-tight"
      >
        arbitrator-web toolchain check
      </motion.h1>
      <p className="mb-8 text-muted-foreground">
        Vite + React + Tailwind v4 + shadcn/ui + Aceternity UI + Motion — all wired up.
      </p>

      <motion.div
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4, delay: 0.1, ease: [0.16, 1, 0.3, 1] }}
      >
        <Card className="mb-8 max-w-md">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              shadcn/ui <Badge>working</Badge>
            </CardTitle>
          </CardHeader>
          <CardContent>
            <Button>A shadcn Button</Button>
          </CardContent>
        </Card>
      </motion.div>

      <BentoGrid>
        <BentoGridItem
          title="Aceternity UI"
          description="Registry-installed component, rendering fine."
        />
        <BentoGridItem
          title="Motion"
          description="motion/react driving the entrance animations above."
        />
        <BentoGridItem
          title="shadcn/ui"
          description="Card, Button and Badge above are shadcn primitives."
        />
      </BentoGrid>
    </div>
  )
}

export default App
