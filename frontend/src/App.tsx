
import { Toaster } from 'react-hot-toast';
import { Routes, Route, Navigate } from "react-router-dom";
import TopNav from "@/components/common/TopNav";
import Simulacion from "@/pages/Simulacion";
import Operacion from "@/pages/Operacion";
import Colapso from "./pages/Colapso";
import Registrar from "./pages/Registrar";
import { RunSessionProvider } from './lib/runSession';
export default function App() {

  return (
    <div className="min-h-screen bg-transparent">
      <RunSessionProvider>
        <TopNav />
        <main>
          <Routes>
            <Route path="/" element={<Navigate to="/operacion" replace />} />
            <Route path="/registrar" element={<Registrar />} />
            <Route path="/operacion" element={<Operacion />} />
            <Route path="/simulacion" element={<Simulacion />} />
            <Route path="/colapso" element={<Colapso />} />
            <Route path="*" element={<Navigate to="/operacion" replace />} />
          </Routes>
          <Toaster 
            position='top-right'
            toastOptions={{
              duration: 3000,  // Duración del toast
              style: {
                background: "rgba(255, 255, 255, 0.8)", // Fondo semitransparente
                color: "rgba(0, 0, 0, 0.85)",            // Texto oscuro
                fontSize: "14px",                        // Tamaño del texto
                borderRadius: "10px",                    // Bordes redondeados
                padding: "10px 16px",                    // Espaciado
                boxShadow: "0 4px 10px rgba(0, 0, 0, 0.1)", // Sombra
              },
            }}
            />
        </main>
      </RunSessionProvider>
    </div>
  );
}
