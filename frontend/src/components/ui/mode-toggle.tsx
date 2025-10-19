import { Moon, Sun } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { useTheme } from "@/components/ui/theme-provider"
import { cn } from "@/lib/utils"

export function ModeToggle() {
  const { setTheme } = useTheme()

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          className={cn(
            // contenedor del botón con glassmorphism
            "relative overflow-hidden rounded-2xl",
            "border border-white/10 dark:border-white/10",
            "bg-white/10 dark:bg-neutral-800/10",
            "backdrop-blur-xl supports-[backdrop-filter]:bg-white/10",
            "shadow-[0_10px_30px_rgba(0,0,0,0.15)]",
            // interacción
            "hover:bg-white/35 dark:hover:bg-neutral-800/35",
            "focus-visible:ring-2 focus-visible:ring-white/50 focus-visible:outline-none"
          )}
          aria-label="Toggle theme"
        >
          <Sun className="h-[1.2rem] w-[1.2rem] scale-100 rotate-0 transition-all dark:scale-0 dark:-rotate-90" />
          <Moon className="absolute h-[1.2rem] w-[1.2rem] scale-0 rotate-90 transition-all dark:scale-100 dark:rotate-0" />
          <span className="sr-only">Toggle theme</span>

          {/* brillo suave en hover */}
          <span className="pointer-events-none absolute inset-0 opacity-0 hover:opacity-100 transition-opacity">
            <span className="absolute -inset-8 bg-white/20 blur-2xl" />
          </span>
        </Button>
      </DropdownMenuTrigger>

      <DropdownMenuContent
        align="end"
        className={cn(
          // panel con glassmorphism
          "rounded-2xl border",
          "border-white/30 dark:border-white/10",
          "bg-white/30 dark:bg-neutral-900/30",
          "backdrop-blur-2xl supports-[backdrop-filter]:bg-white/15",
          "shadow-2xl",
          // animaciones/espaciado
          "p-1.5"
        )}
      >
        <DropdownMenuItem
          onClick={() => setTheme("light")}
          className="rounded-xl text-neutral-900 hover:bg-white/50 focus:bg-white/50 dark:text-neutral-100 dark:hover:bg-white/10 dark:focus:bg-white/10"
        >
          Light
        </DropdownMenuItem>
        <DropdownMenuItem
          onClick={() => setTheme("dark")}
          className="rounded-xl text-neutral-900 hover:bg-white/50 focus:bg-white/50 dark:text-neutral-100 dark:hover:bg-white/10 dark:focus:bg-white/10"
        >
          Dark
        </DropdownMenuItem>
        <DropdownMenuItem
          onClick={() => setTheme("system")}
          className="rounded-xl text-neutral-900 hover:bg-white/50 focus:bg-white/50 dark:text-neutral-100 dark:hover:bg-white/10 dark:focus:bg-white/10"
        >
          System
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}