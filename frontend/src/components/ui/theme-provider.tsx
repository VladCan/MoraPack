// src/components/ui/theme-provider.tsx

import { createContext, useContext, useEffect, useMemo, useState } from "react"

type Theme = "dark" | "light" | "system"

type ThemeProviderProps = {
  children: React.ReactNode
  defaultTheme?: Theme
  storageKey?: string
}

type ThemeProviderState = {
  theme: Theme
  /** El tema efectivo que ve el usuario (si theme === "system", resuelve a "dark" | "light") */
  resolvedTheme: Exclude<Theme, "system">
  setTheme: (theme: Theme) => void
}

const initialState: ThemeProviderState = {
  theme: "system",
  resolvedTheme: "light",
  setTheme: () => null,
}

const ThemeProviderContext = createContext<ThemeProviderState>(initialState)

export function ThemeProvider({
  children,
  defaultTheme = "system",
  storageKey = "vite-ui-theme",
  ...props
}: ThemeProviderProps) {
  const [theme, setTheme] = useState<Theme>(() => {
    if (typeof window === "undefined") return defaultTheme
    return (localStorage.getItem(storageKey) as Theme) || defaultTheme
  })

  // detecta tema del sistema
  const [systemIsDark, setSystemIsDark] = useState<boolean>(() => {
    if (typeof window === "undefined") return false
    return window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false
  })

  // escucha cambios del sistema
  useEffect(() => {
    if (typeof window === "undefined") return
    const mql = window.matchMedia("(prefers-color-scheme: dark)")
    const handler = (e: MediaQueryListEvent) => setSystemIsDark(e.matches)
    // compat moderno
    mql.addEventListener?.("change", handler)
    return () => {
      mql.removeEventListener?.("change", handler)
    }
  }, [])

  // resuelve el tema efectivo
  const resolvedTheme: "dark" | "light" = useMemo(() => {
    if (theme === "system") return systemIsDark ? "dark" : "light"
    return theme
  }, [theme, systemIsDark])

  // aplica clases a <html>
  useEffect(() => {
    if (typeof document === "undefined") return
    const root = document.documentElement
    root.classList.remove("light", "dark")
    root.classList.add(resolvedTheme)
  }, [resolvedTheme])

  const value: ThemeProviderState = {
    theme,
    resolvedTheme,
    setTheme: (t: Theme) => {
      if (typeof window !== "undefined") {
        localStorage.setItem(storageKey, t)
      }
      setTheme(t)
    },
  }

  return (
    <ThemeProviderContext.Provider {...props} value={value}>
      {children}
    </ThemeProviderContext.Provider>
  )
}

export const useTheme = () => {
  const context = useContext(ThemeProviderContext)
  if (context === undefined) {
    throw new Error("useTheme must be used within a ThemeProvider")
  }
  return context
}
