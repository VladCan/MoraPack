import { NavLink } from "react-router-dom";
import Logo from "@/assets/Logo-de-AirExpress-Distribution.svg";
import LogoMark from "@/assets/airexpress2.svg";
import { ModeToggle } from "../ui/mode-toggle";

const tabs = [
  { to: "/registrar", label: "Registrar envío" },
  { to: "/operacion", label: "Operación Diaria" },
  { to: "/simulacion", label: "Simulación Semanal" },
  { to: "/colapso", label: "Colapso" },
];

const cx = (...classes: Array<string | false | null | undefined>) =>
  classes.filter(Boolean).join(" ");

export default function Navbar() {
  return (
    <nav className="fixed top-0 left-0 z-50 w-full h-16 bg-background/80 backdrop-blur-md border-b border-border">
      <div className="w-full h-full px-4 flex items-center justify-between">
        
        {/* --- LOGO --- */}
        <a
          href="/"
          aria-label="AirExpress"
          className="flex items-center gap-2 z-[60]"
        >
          <img src={LogoMark} alt="AirExpress" className="block md:hidden h-9 w-auto" />
          <img src={Logo} alt="AirExpress" className="hidden md:block h-10 w-auto" />
        </a>

        {/* --- NAVEGACIÓN CENTRAL --- */}
        <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 hidden md:block">
          <ul className="flex items-center gap-1 bg-muted/50 p-1 rounded-full border border-border">
            {tabs.map((t) => (
              <li key={t.to}>
                <NavLink
                  to={t.to}
                  className={({ isActive }) =>
                    cx(
                      "px-4 py-1.5 rounded-full text-sm font-medium transition-all",
                      isActive
                        ? "bg-background text-foreground shadow-sm"
                        : "text-muted-foreground hover:text-foreground hover:bg-muted"
                    )
                  }
                >
                  {t.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </div>

        {/* --- MOVIL NAV (Horizontal Scroll) --- */}
        <div className="md:hidden absolute left-0 bottom-0 translate-y-full w-full bg-background/95 border-b border-border overflow-x-auto no-scrollbar">
           <ul className="flex items-center p-2 gap-2 w-max">
            {tabs.map((t) => (
              <li key={t.to}>
                <NavLink
                  to={t.to}
                  className={({ isActive }) =>
                    cx(
                      "px-3 py-1.5 rounded-full text-xs font-medium whitespace-nowrap border transition-colors",
                      isActive
                        ? "bg-primary text-primary-foreground border-primary"
                        : "bg-card text-muted-foreground border-border"
                    )
                  }
                >
                  {t.label}
                </NavLink>
              </li>
            ))}
           </ul>
        </div>

        {/* --- DERECHA (THEME) --- */}
        <div className="flex items-center gap-2 z-[60]">
          <ModeToggle />
        </div>
      </div>
    </nav>
  );
}