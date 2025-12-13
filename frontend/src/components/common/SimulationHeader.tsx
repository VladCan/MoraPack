import { useLocation } from "react-router-dom";
import { useEffect, type JSX } from "react";
import ToolsPanel, { ColapsoToolsPanel, OperacionDiariaToolsPanel } from "./ToolsPanel";
import NavClock from "./NavClock";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Button } from "@/components/ui/button";
import { Clock3, SlidersHorizontal, ChevronDown, ChevronUp } from "lucide-react";
import { Sheet, SheetContent, SheetTrigger } from "@/components/ui/sheet";
import { useRunSession } from "@/lib/runSession";
import { useRunSSE } from "@/hooks/useRunSSE";
import ClockSwitcher from "./ClockSwitcher";
import { handleApi, postJson } from "@/services/api";
import toast from "react-hot-toast";
import ToastCustom from "@/components/common/ToastCustom";
import type { CancelRunResponse } from "@/types/runs";

// Mapeo de contenido según la ruta
const CONTENT: Record<string, JSX.Element> = {
  "/simulacion": <ToolsPanel />,
  "/operacion": <OperacionDiariaToolsPanel />,
  "/colapso": <ColapsoToolsPanel />,
};

const cx = (...classes: Array<string | false | null | undefined>) =>
  classes.filter(Boolean).join(" ");

export default function SimulationHeader() {
  const { pathname: currentPage } = useLocation();
  const currentContent = CONTENT[currentPage];

  // Contexto de sesión (Esto fallaría si no estuviera dentro de RunSessionProvider)
  const { 
    runId, status, end, setSimNow, setWindow, setAutoReconnect,
    toolsPanelOpen, setToolsPanelOpen 
  } = useRunSession();

  const running = status === "running" && !!runId;

  // Lógica SSE
  const { simNowUtc, windows, finishedReason, wallStartUtc } = useRunSSE(
    status === "running" && runId ? runId : undefined
  );

  // Sincronización de Contexto
  useEffect(() => {
    if (simNowUtc) setSimNow(simNowUtc);
  }, [simNowUtc, setSimNow]);

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
    if (finishedReason) end("finished");
  }, [finishedReason, end]);

  // Manejador de cancelación
  const handleCancelRun = async () => {
    if (!runId) return;
    const path = `runs/${runId}/cancel`;
    const [data, error] = await handleApi(postJson<CancelRunResponse>(path));

    if (error) {
      console.error("❌ [SimulationHeader] Error al finalizar:", error);
      toast.custom((t) => <ToastCustom t={t} message={error + "❗"} type="error" />, { duration: 5000 });
    } else if (data) {
      if (data.cancelled) {
        toast.custom((t) => <ToastCustom t={t} message={"¡Simulación finalizada!"} type="success" />, { duration: 5000 });
        setAutoReconnect(false);
      } else {
        toast.custom((t) => <ToastCustom t={t} message={error + "❗"} type="error" />, { duration: 5000 });
      }
    }
  };

  // Si no hay contenido definido para esta página (ej. registrar), no mostramos nada
  // Aunque idealmente este componente no debería montarse en registrar.
  if (!currentContent) return null;

  return (
    <>
      {/* Este div actúa como "spacer" y contenedor de controles.
        Se posiciona "fixed" pero debajo del Navbar (top-16).
      */}
      <div className="fixed top-16 left-0 w-full z-40 pointer-events-none">
        
        {/* === CONTROLES DESKTOP (Reloj + Toggle Panel) === */}
        <div className="hidden md:flex justify-between items-start px-4 pt-4 max-w-8xl mx-auto pointer-events-auto">
            {/* Espacio vacío izquierda para balancear o poner info extra */}
            <div className="w-1/3"></div>

            {/* Panel Central: Reloj */}
            <div className="flex justify-center">
               <div className="bg-card/80 backdrop-blur shadow-sm border border-border rounded-xl p-1">
                  <ClockSwitcher
                    running={running}
                    finished={!!finishedReason}
                    runNow={simNowUtc ? new Date(simNowUtc) : null}
                    runStart={wallStartUtc ? new Date(wallStartUtc) : null}
                    onCancel={handleCancelRun}
                  />
               </div>
            </div>

            {/* Derecha: Toggle Tools Panel */}
            <div className="w-1/3 flex justify-end">
                <Button 
                    variant="outline" 
                    size="sm" 
                    className="bg-card/80 backdrop-blur shadow-sm gap-2"
                    onClick={() => setToolsPanelOpen(!toolsPanelOpen)}
                >
                    {toolsPanelOpen ? "Ocultar Filtros" : "Ver Filtros"}
                    {toolsPanelOpen ? <ChevronUp className="h-4 w-4"/> : <ChevronDown className="h-4 w-4"/>}
                </Button>
            </div>
        </div>

        {/* === PANEL DE HERRAMIENTAS (DESKTOP) === */}
        {/* Se despliega debajo de los controles */}
        {toolsPanelOpen && (
            <div className="hidden md:block pointer-events-auto mt-2 animate-in slide-in-from-top-2 fade-in duration-200">
                {currentContent}
            </div>
        )}

        {/* === CONTROLES MÓVIL (FABs) === */}
        <div className="md:hidden pointer-events-auto">
            {/* FAB Reloj (Izquierda Abajo) */}
            <div className="fixed left-4 bottom-20 z-[60]">
               <MobileClockFab />
            </div>

            {/* FAB Herramientas (Derecha Abajo) - Sheet */}
            <div className="fixed right-4 bottom-20 z-[60]">
                <MobileToolsSheet>
                    <SheetTrigger asChild>
                        <Button size="icon" className="h-12 w-12 rounded-full shadow-lg">
                            <SlidersHorizontal className="h-5 w-5" />
                        </Button>
                    </SheetTrigger>
                    <SheetContent side="bottom" className="h-[85vh] rounded-t-2xl">
                        <div className="h-full overflow-y-auto pt-4">
                            {currentContent}
                        </div>
                    </SheetContent>
                </MobileToolsSheet>
            </div>
        </div>
      </div>
    </>
  );
}

// --- Subcomponentes Móviles ---

function MobileClockFab({ className = "" }: { className?: string }) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          size="icon"
          variant="secondary"
          className={cx(
            "h-12 w-12 rounded-full shadow-lg ring-1 ring-border bg-card/80 backdrop-blur",
            className
          )}
        >
          <Clock3 className="text-foreground" />
        </Button>
      </PopoverTrigger>
      <PopoverContent side="top" align="start" className="w-auto p-2">
         <NavClock className="whitespace-nowrap font-mono" />
      </PopoverContent>
    </Popover>
  );
}

function MobileToolsSheet({ children }: { children: React.ReactNode }) {
  return <Sheet>{children}</Sheet>;
}