import { NavLink, useLocation } from "react-router-dom";
import Logo from "@/assets/Logo-de-AirExpress-Distribution.svg";
import LogoMark from "@/assets/airexpress2.svg";
import { useEffect, useState, type JSX } from "react";
import ToolsPanel, { ColapsoToolsPanel, OperacionDiariaToolsPanel } from "./ToolsPanel";
import NavClock from "./NavClock";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Button } from "@/components/ui/button";
import { Clock3, SlidersHorizontal } from "lucide-react";

// 👇 nuevo: sheet para móvil
import { Sheet, SheetContent, SheetTrigger } from "@/components/ui/sheet";
import { ModeToggle } from "../ui/mode-toggle";
import { useRunSession } from "@/lib/runSession";
import { useRunSSE } from "@/hooks/useRunSSE";
import ClockSwitcher from "./ClockSwitcher";
import { handleApi, postJson } from "@/services/api";
import toast from "react-hot-toast";
import ToastCustom from "@/components/common/ToastCustom";
import type { CancelRunResponse } from "@/types/runs";

const tabs = [
  { to: "/registrar", label: "Registrar envío" },
  { to: "/operacion", label: "Operación Diaria" },
  { to: "/simulacion", label: "Simulación Semanal" },
  { to: "/colapso", label: "Colapso" },
];

const cx = (...classes: Array<string | false | null | undefined>) =>
  classes.filter(Boolean).join(" ");

const SHOW_BTN_PAGES = new Set(["/simulacion", "/operacion", "/colapso"]);

const CONTENT: Record<string, JSX.Element> = {
  "/simulacion": <ToolsPanel />,
  "/registrar": <p>Registrar Envío: Ingresa los datos de los envíos.</p>,
  "/operacion": <OperacionDiariaToolsPanel />,
  "/colapso": <ColapsoToolsPanel />,
};

export default function TopNav() {
  const [showContent, setShowContent] = useState(false);
  const { pathname: currentPage } = useLocation();

  const showButton = SHOW_BTN_PAGES.has(currentPage);
  const currentContent = CONTENT[currentPage] ?? <p>Selecciona una opción del menú</p>;

  //Esto es para la conexión SSE de la solución

  //Traemos el contexto
  const { runId, status, end, setSimNow, setWindow, reset, setAutoReconnect } = useRunSession();

  //Para el reloj
  const running = status === "running" && !!runId;
  
  //Acá expone connect(url, handlers) -> () => void
  const { simNowUtc, windows, finished, wallStartUtc, disconnect } = useRunSSE(
    status === "running" && runId ? runId : undefined
  );

  //Propagamos los TICKs al contexto
  useEffect(() => {
    if (simNowUtc) setSimNow(simNowUtc);
  }, [simNowUtc, setSimNow]);

  //Propagamos la última WINDOW al contexto
  useEffect(() => {
    if (windows.length > 0) {
      const w = windows[windows.length - 1];
      setWindow({ 
        index: w.index, 
        startUtc: w.startUtc, 
        endUtc: w.endUtc,
        vuelos: w.vuelos,
        pedidos: w.pedidos
      });
    }
  }, [windows, setWindow]);

  useEffect(() => {
    if (finished) end("finished");
  }, [finished, end]);

  //Para finalizar/cancelar el run:
  const handleCancelRun = async () => {
    if (!runId) return;

    //const base = import.meta.env.VITE_API_BASE_URL
    //const base = import.meta.env.prod.VITE_API_BASE_URL

    const path = `runs/${runId}/cancel`;

    const [data, error] = await handleApi(
      postJson<CancelRunResponse>(path)
    )

    if (error) {
        // aquí tu toast o UI de error
        console.error("❌ [TopNav] Error al finalizar la simulación:", error);
        //alert(`Error al iniciar simulación: ${error.message}`);
        toast.custom((t) => (
          <ToastCustom
            t={t}
            message={error+"❗"}
            type="error"
          />),
        { duration: 5000});
    }
    else if (data) {
      if (data.cancelled){
        console.log("✅ [TopNav] Simulación finalizada exitosamente:", data);
        toast.custom((t) => (
          <ToastCustom
            t={t}
            message={"¡Simulación finalizada exitosamente!"+"✅"}
            type="success"
          />),
        { duration: 5000});

        /*
        //Cortamos el SSE
        disconnect();
          */

        //Para que no dispare el evento de reconexión
        setAutoReconnect(false);

        /*
        //Limpiamos el contexto de la simulación
        reset();
          */
      }
      else{
        console.error("❌ [ToolsPanel] Error al finalizar la simulación:", error);
        //alert(`Error al iniciar simulación: ${error.message}`);
        toast.custom((t) => (
          <ToastCustom
            t={t}
            message={error+"❗"}
            type="error"
          />),
        { duration: 5000});
      }
    }

  }  


  return (
    <header className="fixed top-0 left-0 z-50 w-full h-16">
      <div> {/*className={cx(showContent && "bg-card/20 shadow-lg ring-1 ring-border transition-all")} */}
        {/* Logo */}
        <a
          href="/"
          aria-label="AirExpress"
          className="fixed top-1 left-2 z-[60] flex items-center gap-2 pointer-events-auto"
        >
          <img src={LogoMark} alt="AirExpress" className="block md:hidden h-9 w-auto" />
          <img src={Logo} alt="AirExpress" className="hidden md:block h-15 w-auto" />
          <span className="sr-only">AirExpress</span>
        </a>

        {/* NAV */}
        <div className="mx-auto max-w-8xl h-full flex items-center justify-center w-full">
          <nav className="flex justify-center w-full">
            {/* ======= MÓVIL ======= */}
            <div
              className={cx(
                "relative w-full md:hidden",
                "[mask-image:linear-gradient(to_right,transparent_0,black_24px,black_calc(100%-24px),transparent_100%)]",
                "pl-14 pr-3"
              )}
            >
              <div
                className={cx(
                  "w-full overflow-x-auto no-scrollbar",
                  "touch-pan-x overscroll-x-contain",
                  "relative z-[1]"
                )}
              >
                <ul
                  className={cx(
                    "flex w-max gap-2 py-1",
                    "whitespace-nowrap",
                    "snap-x snap-mandatory"
                  )}
                >
                  {tabs.map((t) => (
                    <li key={t.to} className="snap-center flex-none">
                      <NavLink
                        to={t.to}
                        end
                        className={({ isActive }) =>
                          cx(
                            "rounded-full inline-flex items-center justify-center",
                            "px-4 py-2 text-base transition-colors",
                            "ring-1 ring-border",
                            isActive
                              ? "bg-primary text-primary-foreground"
                              : "text-foreground/80 bg-accent/30 hover:bg-accent/40"
                          )
                        }
                      >
                        {t.label}
                      </NavLink>
                    </li>
                  ))}
                </ul>
              </div>
            </div>

            {/* ======= DESKTOP ======= */}
            <ul className="hidden md:flex rounded-full gap-1 overflow-hidden bg-card/40 shadow-lg ring-1 ring-border backdrop-blur-md backdrop-saturate-150 mt-2">
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
                          ? "bg-primary text-primary-foreground"
                          : "text-foreground/80 hover:bg-accent/40",
                        index === 0 && isActive && "rounded-l-full",
                        index === tabs.length - 1 && isActive && "rounded-r-full"
                      )
                    }
                  >
                    {t.label}
                  </NavLink>
                </li>
              ))}
            </ul>
          </nav>
          <ModeToggle/>
        </div>
        {/* Reloj + “Ver más” */}
        {currentPage !== "/registrar" && (
          <>
            {/* Desktop: reloj fijo */}
            <div className="hidden md:block">
              <ClockSwitcher
                running={running}
                finished={!!finished}
                runNow={simNowUtc ? new Date(simNowUtc) : null}
                runStart={wallStartUtc ? new Date(wallStartUtc) : null}
                onCancel={handleCancelRun}
              />
            </div>

            {/* ===== MÓVIL: FAB reloj (izquierda) ===== */}
            <MobileClockFab className="md:hidden fixed left-4 bottom-18 z-[60]" />

            {/* ===== MÓVIL: Botón “Ver más” (sheet) ===== */}
            {showButton && (
              <MobileToolsSheet>
                {/* Trigger flotante, derecha inferior */}
                <SheetTrigger asChild>
                  <button
                    className="md:hidden fixed left-4 bottom-32 z-[60]
                               inline-flex items-center gap-2 px-4 py-4 rounded-full
                               bg-card/30 ring-1 ring-border shadow-lg
                               text-foreground active:scale-95 backdrop-blur"
                    aria-label="Ver más"
                  >
                    <SlidersHorizontal className="h-4 w-4" />
                  </button>
                </SheetTrigger>

                {/* Contenido del sheet */}
                <SheetContent
                  side="bottom"
                  className="
                    h-[85vh] p-0 rounded-t-2xl border-t border-border
                    bg-popover/80 supports-[backdrop-filter]:bg-popover/60
                    backdrop-blur-2xl backdrop-saturate-150
                  "
                >
                  <div className="h-full overflow-y-auto p-1">
                    {CONTENT[currentPage] ?? <p>Selecciona una opción del menú</p>}
                  </div>
                </SheetContent>
              </MobileToolsSheet>
            )}
          </>
        )}

        {/* Ver más inline (solo desktop) */}
        <div className="hidden md:block text-center mt-2">
          {showButton && !showContent && (
            <button
              onClick={() => setShowContent(true)}
              className="text-foreground hover:text-foreground/70 font-medium focus:outline-none ring-border"
            >
              Ver más ↓
            </button>
          )}

          {showContent && ( 
            <>
              <div className="mt-1 p-2 rounded-md text-foreground">
                {currentContent}
              </div>
              <div className="mt-1">
                <button
                  onClick={() => setShowContent(false)}
                  className="text-foreground hover:text-foreground/70 font-medium focus:outline-none ring-border"
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

function MobileClockFab({ className = "" }: { className?: string }) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          size="icon"
          variant="secondary"
          className={cx(
            "h-12 w-12 rounded-full shadow-lg ring-1 ring-border bg-card/30 backdrop-blur",
            "active:scale-100",
            className
          )}
          aria-label="Mostrar reloj"
        >
          <Clock3 className="text-foreground" />
        </Button>
      </PopoverTrigger>
      <PopoverContent
        side="left"
        align="end"
        className="p-0 w-auto bg-transparent border-0 shadow-none outline-none ring-0 rounded-none backdrop-blur-0"
      >
        <div className="px-1">
          <NavClock className="ml-auto flex-none shrink-0 whitespace-nowrap" />
        </div>
      </PopoverContent>
    </Popover>
  );
}

/** Wrapper para evitar “Sheet must be used inside its Root” y mantener JSX limpio */
function MobileToolsSheet({ children }: { children: React.ReactNode }) {
  return <Sheet>{children}</Sheet>;
}
