import { createContext, useContext, useState } from "react";

type Nivel = "disponible" | "limitado" | "saturado";

type FiltersState = {
  disponible: boolean;
  limitado: boolean;
  saturado: boolean;
  toggle: (n: Nivel) => void;
  setAll: (vals: { disponible: boolean; limitado: boolean; saturado: boolean }) => void;
};

const CapacityFiltersContext = createContext<FiltersState | null>(null);

export function CapacityFiltersProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState({
    disponible: true,
    limitado: false,
    saturado: false,
  });

  const toggle = (n: Nivel) => {
    setState((prev) => ({ ...prev, [n]: !prev[n] }));
  };

  const setAll = (vals: { disponible: boolean; limitado: boolean; saturado: boolean }) => {
    setState(vals);
  };

  return (
    <CapacityFiltersContext.Provider value={{ ...state, toggle, setAll }}>
      {children}
    </CapacityFiltersContext.Provider>
  );
}

export function useCapacityFilters() {
  const ctx = useContext(CapacityFiltersContext);
  if (!ctx) throw new Error("useCapacityFilters must be inside provider");
  return ctx;
}
