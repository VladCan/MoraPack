import { NavLink } from "react-router-dom";
import Logo from "@/assets/Logo-de-AirExpress-Distribution.svg";

const tabs = [
  { to: "/registrar",  label: "Registrar envío" },
  { to: "/operacion",  label: "Operación Diaria" },
  { to: "/simulacion", label: "Simulación Semanal" },
  { to: "/colapso",    label: "Colapso" },
];

export default function TopNav() {
  return (
    <header className="fixed top-0 left-0 z-50 w-full h-16">
      {/* LOGO totalmente fijo en la esquina superior izquierda */}
      <a
        href="/"
        aria-label="AirExpress"
        className="fixed top-1 left-2 z-[60] flex items-center gap-2 pointer-events-auto"
      >
        <img src={Logo} alt="AirExpress" className="h-15 w-auto md:h-15" />
        <span className="sr-only">AirExpress</span>
      </a>

      {/* Contenedor para centrar el nav sin que el logo lo desplace */}
      <div className="mx-auto max-w-8xl h-full flex items-center justify-center">
        <nav className="flex justify-center">
          <ul
            className={[
              "flex items-center gap-0.5 rounded-full px-4 py-3",
              "backdrop-blur-md bg-white/10 border border-white/10 shadow-lg",
              "ring-1 ring-black/5",
            ].join(" ")}
          >
            {tabs.map((t) => (
              <li key={t.to}>
                <NavLink
                  to={t.to}
                  end
                  className={({ isActive }) =>
                    [
                      "px-8 py-3 rounded-full text-[20px] whitespace-nowrap transition-colors",
                      isActive
                        ? "bg-primary text-white shadow"
                        : "text-primary hover:bg-blue-50/60",
                    ].join(" ")
                  }
                >
                  {t.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </div>
    </header>
  );
}
