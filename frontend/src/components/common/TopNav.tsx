import { NavLink, useLocation } from "react-router-dom";
import Logo from "@/assets/Logo-de-AirExpress-Distribution.svg";
import { useState, type JSX } from "react";
import ToolsPanel, { ColapsoToolsPanel, OperacionDiariaToolsPanel } from "./ToolsPanel";
import NavClock from "./NavClock";

const tabs = [
  { to: "/registrar", label: "Registrar envío" },
  { to: "/operacion", label: "Operación Diaria" },
  { to: "/simulacion", label: "Simulación Semanal" },
  { to: "/colapso", label: "Colapso" },
];

// Helper para clases
const cx = (...classes: Array<string | false | null | undefined>) =>
  classes.filter(Boolean).join(" ");

// Páginas donde se muestra el botón
const SHOW_BTN_PAGES = new Set(["/simulacion", "/operacion", "/colapso"]);

// Contenidos por ruta
const CONTENT: Record<string, JSX.Element> = {
  "/simulacion": <ToolsPanel />,         // inicio + fin
  "/registrar":  <p>Registrar Envío: Ingresa los datos de los envíos.</p>,
  "/operacion":  <OperacionDiariaToolsPanel />,    // sin inicio ni fin
  "/colapso":    <ColapsoToolsPanel />,            // solo inicio
};

export default function TopNav() {
  const [showContent, setShowContent] = useState(false);
  const { pathname: currentPage } = useLocation();

  const showButton = SHOW_BTN_PAGES.has(currentPage);
  const currentContent = CONTENT[currentPage] ?? (
    <p>Selecciona una opción del menú</p>
  );

  return (
    <header className="fixed top-0 left-0 z-50 w-full h-16">
      {/* 👇 Estilos solo cuando showContent es true */}
      <div className={cx(showContent && "bg-amber-50/10 shadow-lg ring-1 ring-black/5 transition-all")}>
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
            <ul className="flex rounded-full gap-1 overflow-hidden bg-white/10 shadow-lg ring-1 ring-black/5">
              {tabs.map((t, index) => (
                <li key={t.to} className="flex-1">
                  <NavLink
                    to={t.to}
                    end
                    className={({ isActive }) =>
                      cx(
                        "h-full w-full rounded-full flex items-center justify-center text-[20px] px-4 py-2 transition-colors",
                        "whitespace-nowrap",
                        isActive
                          ? "bg-primary text-white shadow-lg ring-1 ring-black/2"
                          : "text-primary hover:bg-blue-50/60",
                        index === 0 && isActive && "rounded-l-full",
                        index === tabs.length - 1 &&
                          isActive &&
                          "rounded-r-full"
                      )
                    }
                  >
                    {t.label}
                  </NavLink>
                </li>
              ))}
            </ul>
          </nav>
        </div>
        <NavClock className="fixed top-2 right-3 z-[60]" />

        <div className="text-center">
          {showButton && !showContent && (
            <button
              onClick={() => setShowContent(true)}
              className="text-primary hover:text-black font-medium focus:outline-none ring-black/5"
            >
              Ver más ↓
            </button>
          )}

          {showContent && (
            <>
              <div className="mt-4 p-4 rounded-md text-white opacity-90 transform transition-transform duration-300 translate-y-2">
                {currentContent}
              </div>
              <div className="mt-3">
                <button
                  onClick={() => setShowContent(false)}
                  className="text-primary hover:text-black font-medium focus:outline-none ring-black/5"
                >
                  Ocultar ↑
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </header>
  );
}
